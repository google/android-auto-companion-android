// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.android.libraries.car.trustagent

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.encryptionrunner.EncryptionRunnerFactory
import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.storage.getDeviceId
import com.google.android.libraries.car.trustagent.util.bytesToUuid
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.toHexString
import com.google.android.libraries.car.trustagent.util.uuidToBytes
import java.lang.IllegalStateException
import java.lang.UnsupportedOperationException
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * Represents a car that is ready to be associated with.
 *
 * This class handles the association flow defined by security version V2.
 *
 * See detailed design in https://goto.google.com/aae-batmobile-hide-device-ids
 *
 * See also [PendingCarV2Reconnection].
 */
internal open class PendingCarV2Association
internal constructor(
  private val context: Context,
  @VisibleForTesting internal val messageStream: MessageStream,
  private val associatedCarManager: AssociatedCarManager,
  // External API exposes ScanResult so keep it for reporting error.
  override val device: BluetoothDevice,
  private val bluetoothManager: BluetoothConnectionManager,
  private val coroutineScope: CoroutineScope = MainScope()
) : PendingCar {
  override val isAssociating = true
  override var callback: PendingCar.Callback? = null

  protected var state = State.UNINITIATED
  protected enum class State {
    UNINITIATED,
    ENCRYPTION_HANDSHAKE,
    PENDING_OOB_CONFIRMATION,
    PENDING_CONFIRMATION,
    SENDING_DEVICE_ID_AND_SECRET
  }
  protected open val encryptionRunnerType = EncryptionRunnerFactory.EncryptionRunnerType.UKEY2
  protected var encryptionRunnerManager: EncryptionRunnerManager? = null

  // =======
  // This comment explains the peculiar points during association flow:
  // - During encryption handshake, we will blindly accept the verification string, which generates
  //   an encryption key;
  // - phone will receive the encrypted device ID as the verification confirmation; it decrypts the
  //   device ID, and notifies the UI;
  // - phone then generates an [identificationKey]; the message ID of sending [identificationKey]
  //   is kept as [deviceIdAndSecretKeyMessageId];
  // - when message of ID [deviceIdAndSecretKeyMessageId] is sent, association is completed.
  // =======
  @VisibleForTesting internal var deviceId: UUID? = null
  // Secret key for reconnection identification.
  // Generated as the next step after receiving [encryptedDeviceId].
  private var identificationKey: SecretKey? = null
  // The message ID of sending [identificationKey] and the device ID of this phone.
  private var deviceIdAndSecretKeyMessageId = INVALID_MESSAGE_ID

  private val gattConnectionCallback =
    object : BluetoothConnectionManager.ConnectionCallback {
      override fun onConnected() {
        loge(TAG, "Unexpected gatt callback: onConnected. Disconnecting.")
        disconnect()
        callback?.onConnectionFailed(this@PendingCarV2Association)
      }

      override fun onConnectionFailed() {
        loge(TAG, "Unexpected gatt callback: onConnectionFailed. Disconnecting.")
        disconnect()
        callback?.onConnectionFailed(this@PendingCarV2Association)
      }

      override fun onDisconnected() {
        cleanUp()
        loge(TAG, "Disconnected while attempting to establish connection.")
        callback?.onConnectionFailed(this@PendingCarV2Association)
      }
    }

  private val streamCallback =
    object : MessageStream.Callback {
      override fun onMessageReceived(streamMessage: StreamMessage) {
        if (streamMessage.operation != OperationType.ENCRYPTION_HANDSHAKE) {
          loge(TAG, "Received non-handshake message $streamMessage. Ignored.")
          return
        }

        when (state) {
          State.PENDING_CONFIRMATION -> {
            handleAuthStringConfirmation(streamMessage.payload)
          }
          State.PENDING_OOB_CONFIRMATION -> {
            handleOobVerificationMessage(streamMessage)
          }
          else -> logd(TAG, "Received message $streamMessage when state is $state. Ignored")
        }
      }

      override fun onMessageSent(messageId: Int) {
        if (state != State.SENDING_DEVICE_ID_AND_SECRET) {
          // ignored.
          return
        }
        if (messageId != deviceIdAndSecretKeyMessageId) {
          logi(TAG, "Expected $deviceIdAndSecretKeyMessageId but received $messageId. Ignored.")
          return
        }
        logi(TAG, "DeviceId and secret key has been delivered. Association completed.")
        callback?.onConnected(toCar())
      }
    }

  init {
    bluetoothManager.registerConnectionCallback(gattConnectionCallback)
    messageStream.registerMessageEventCallback(streamCallback)
  }

  override fun disconnect() {
    cleanUp()
    bluetoothManager.disconnect()
  }

  protected open fun cleanUp() {
    state = State.UNINITIATED
    bluetoothManager.unregisterConnectionCallback(gattConnectionCallback)
    messageStream.unregisterMessageEventCallback(streamCallback)
  }

  /**
   * Starts association with this car.
   *
   * [advertisedData] must be null.
   */
  override fun connect(advertisedData: ByteArray?) {
    require(advertisedData == null) {
      "Expected parameter advertisedData to be null; actual ${advertisedData?.toHexString()}."
    }
    state = State.ENCRYPTION_HANDSHAKE
    encryptionRunnerManager =
      EncryptionRunnerManager(
          EncryptionRunnerFactory.newRunner(encryptionRunnerType),
          messageStream
        )
        .apply {
          callback = encryptionCallback
          initEncryption()
        }
  }

  @VisibleForTesting
  internal val encryptionCallback =
    object : EncryptionRunnerManager.Callback {
      override fun onAuthStringAvailable(authString: String) {
        state = State.PENDING_CONFIRMATION
        logd(TAG, "Notifying callback auth string available: $authString.")
        callback?.onAuthStringAvailable(authString)

        // Internally we blindly accept the auth string so encryption key is ready
        // to decrypt the next message as IHU device ID.
        // This leads to onEncryptionEstablished() callback.
        encryptionRunnerManager?.notifyAuthStringConfirmed()
      }

      override fun onOobAuthTokenAvailable(oobToken: ByteArray) {
        handleOobAuthToken(oobToken)
      }

      override fun onEncryptionEstablished(key: Key) {
        messageStream.encryptionKey = key
      }

      override fun onEncryptionFailure(reason: EncryptionRunnerManager.FailureReason) {
        when (reason) {
          EncryptionRunnerManager.FailureReason.NO_VERIFICATION_CODE ->
            callback?.onConnectionFailed(this@PendingCarV2Association)
          else -> throw IllegalStateException("Unexpected failure reason $reason. Ignored.")
        }
      }
    }

  protected open fun handleOobAuthToken(token: ByteArray) {
    throw UnsupportedOperationException(
      "Out of band association is not supported. Use PendingCarV2OobAssociation instead."
    )
  }

  protected open fun handleOobVerificationMessage(streamMessage: StreamMessage) {
    throw UnsupportedOperationException(
      "Out of band association is not supported. Use PendingCarV2OobAssociation instead."
    )
  }

  private fun handleAuthStringConfirmation(message: ByteArray) {
    val deviceId = bytesToUuid(message).also { logi(TAG, "Received car device id: $this.") }

    this@PendingCarV2Association.deviceId = deviceId
    callback?.onDeviceIdReceived(deviceId)

    sendDeviceIdAndSecretKey()
  }

  private fun sendDeviceIdAndSecretKey() {
    val phoneDeviceId = getDeviceId(context).also { logi(TAG, "Sending device id $it") }
    val secretKey = associatedCarManager.generateKey()
    identificationKey = secretKey

    val payload = uuidToBytes(phoneDeviceId) + secretKey.encoded
    deviceIdAndSecretKeyMessageId =
      messageStream.sendMessage(
        StreamMessage(
          payload,
          operation = OperationType.ENCRYPTION_HANDSHAKE,
          isPayloadEncrypted = true,
          originalMessageSize = 0,
          recipient = null
        )
      )
    state = State.SENDING_DEVICE_ID_AND_SECRET
    logi(TAG, "Sent deviceId and secret key. Message Id: $deviceIdAndSecretKeyMessageId")
  }

  private fun toCar(): Car {
    cleanUp()
    // checkNotNull is safe because this method should only be called after the identificationKey
    // has been delivered, thus all nullable properties should have been set.
    return Car(
      bluetoothManager,
      messageStream,
      checkNotNull(identificationKey),
      checkNotNull(deviceId)
    )
  }

  companion object {
    private const val TAG = "PendingCarV2Association"

    private const val INVALID_MESSAGE_ID = -1
  }
}
