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
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
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
import java.lang.IllegalStateException
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Represents a car that is ready to be associated with.
 *
 * This class handles the association flow defined by security version V3.
 *
 * V3 supports out-of-band association.
 * - Phone-IHU exchange capabilities, mostly for supported OOB channels;
 * - if resolved OOB channels is not empty, i.e. both support RFCOMM, start those channels;
 * - IHU sends OOB data to the phone;
 * - phone instantiate UKEY2 encryption runner with OOB support;
 * - instead of UKEY2 PIN verification, use the OOB data to confirm the encryption.
 *
 * If resolved capabilities is empty, or OOB channel failed to receive OOB data, use the regular
 * UKEY2 encryption, i.e. PIN verification.
 */
@OptIn(kotlin.ExperimentalStdlibApi::class)
internal class PendingCarV3Association
internal constructor(
  private val context: Context,
  @get:VisibleForTesting internal val messageStream: MessageStream,
  private val associatedCarManager: AssociatedCarManager,
  // External API exposes ScanResult so keep it for reporting error.
  override val device: BluetoothDevice,
  private val bluetoothManager: BluetoothConnectionManager,
  private val resolvedOobChannelTypes: List<OobChannelType>,
  private val oobData: OobData?,
  private val oobChannelManagerFactory: OobChannelManagerFactory = OobChannelManagerFactoryImpl(),
) : PendingCar {
  override var callback: PendingCar.Callback? = null

  private var state = State.UNINITIATED
  private enum class State {
    UNINITIATED,
    ENCRYPTION_HANDSHAKE,
    PENDING_OOB_CONFIRMATION,
    PENDING_CONFIRMATION,
    SENDING_DEVICE_ID_AND_SECRET
  }
  private var encryptionRunnerManager: EncryptionRunnerManager? = null

  // oobConnectionManager and oobAuthToken will be set if the association uses OOB flow.
  private var oobConnectionManager: OobConnectionManager? = null
  private var oobAuthToken: ByteArray? = null

  // =======
  // This comment explains the peculiar points during association flow:
  // - During encryption handshake, the phone blindly accepts the verification string, which
  //   generates an encryption key;
  // - phone receives the encrypted device ID as the verification confirmation; it decrypts the
  //   device ID, and notifies the UI;
  // - phone sends the [identificationKey]; keeps the message ID as [deviceIdAndSecretKeyMessageId];
  // - when message of ID [deviceIdAndSecretKeyMessageId] is sent, association is completed.
  // =======
  @VisibleForTesting internal var deviceId: UUID? = null
  // Secret key for reconnection identification. Sent as the last step of association.
  private var identificationKey: SecretKey = associatedCarManager.generateKey()
  // The message ID of sending [identificationKey] and the device ID of this phone.
  private var deviceIdAndSecretKeyMessageId = INVALID_MESSAGE_ID

  private val bluetoothConnectionCallback =
    PendingCar.createBluetoothConnectionManagerCallback(this)

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

  @VisibleForTesting
  internal val encryptionCallback =
    object : EncryptionRunnerManager.Callback {
      override fun onAuthStringAvailable(authString: String, oobToken: ByteArray) {
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
            callback?.onConnectionFailed(this@PendingCarV3Association)
          else -> throw IllegalStateException("Unexpected failure reason $reason.")
        }
      }
    }

  init {
    bluetoothManager.registerConnectionCallback(bluetoothConnectionCallback)
    messageStream.registerMessageEventCallback(streamCallback)
  }

  override fun disconnect() {
    cleanUp()
    bluetoothManager.disconnect()
  }

  private fun cleanUp() {
    state = State.UNINITIATED
    bluetoothManager.unregisterConnectionCallback(bluetoothConnectionCallback)
    messageStream.unregisterMessageEventCallback(streamCallback)
  }

  /**
   * Starts association with this car.
   *
   * [advertisedData] must be null.
   */
  override suspend fun connect(advertisedData: ByteArray?) {
    require(advertisedData == null) {
      "Expected parameter advertisedData to be null; actual ${advertisedData?.toHexString()}."
    }
    logi(TAG, "Starting connection.")

    if (resolvedOobChannelTypes.isNotEmpty()) {
      readOobData(resolvedOobChannelTypes)
    } else {
      initEncryption(EncryptionRunnerFactory.EncryptionRunnerType.UKEY2)
    }
  }

  private suspend fun readOobData(oobChannelTypes: List<OobChannelType>) {
    // Start OOB exchange to receive an OobConnectionManager.
    val oobChannelManager =
      oobChannelManagerFactory.create(oobChannelTypes, oobData, securityVersion = 3)

    oobConnectionManager =
      oobChannelManager.readOobData(device)?.let { OobConnectionManager.create(it) }

    val encryptionRunnerType =
      if (oobConnectionManager == null) {
        EncryptionRunnerFactory.EncryptionRunnerType.UKEY2
      } else {
        EncryptionRunnerFactory.EncryptionRunnerType.OOB_UKEY2
      }
    logi(TAG, "OOB data is handled by $oobConnectionManager; using $encryptionRunnerType.")

    initEncryption(encryptionRunnerType)
  }

  private fun initEncryption(encryptionRunnerType: Int) {
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

  private fun handleOobAuthToken(token: ByteArray) {
    logi(TAG, "Received Oob auth string of ${token.size} bytes, sending oob data.")

    val manager = oobConnectionManager
    if (manager == null) {
      "Received onOobAuthTokenAvailable callback but OobConnectionManager is null."
      callback?.onConnectionFailed(this@PendingCarV3Association)
      return
    }

    val encryptedVerificationCode = manager.encryptVerificationCode(token)
    messageStream.sendMessage(
      StreamMessage(
        payload = encryptedVerificationCode,
        operation = OperationType.ENCRYPTION_HANDSHAKE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = null
      )
    )

    oobAuthToken = token
    state = State.PENDING_OOB_CONFIRMATION
  }

  private fun handleOobVerificationMessage(streamMessage: StreamMessage) {
    val token = oobAuthToken
    if (token == null) {
      loge(TAG, "Received OOB verification message but OOB auth token is null.")
      callback?.onConnectionFailed(this@PendingCarV3Association)
      return
    }

    val manager = oobConnectionManager
    if (manager == null) {
      "Received OOB verification message but OobConnectionManager is null."
      callback?.onConnectionFailed(this@PendingCarV3Association)
      return
    }

    val decryptedMessage = manager.decryptVerificationCode(streamMessage.payload)
    if (!decryptedMessage.contentEquals(token)) {
      loge(TAG, "OOB verification exchange failed: verification codes don't match.")
      callback?.onConnectionFailed(this@PendingCarV3Association)
      return
    }

    state = State.PENDING_CONFIRMATION
    // Internally we blindly accept the auth string so encryption key is ready
    // to decrypt the next message as IHU device ID.
    // This leads to onEncryptionEstablished() callback.
    encryptionRunnerManager?.notifyAuthStringConfirmed()
  }

  private fun handleAuthStringConfirmation(message: ByteArray) {
    val deviceId = bytesToUuid(message)
    logi(TAG, "Received car device id: $deviceId.")

    this@PendingCarV3Association.deviceId = deviceId
    callback?.onDeviceIdReceived(deviceId)

    val phoneDeviceId = getDeviceId(context)
    deviceIdAndSecretKeyMessageId = messageStream.send(phoneDeviceId, identificationKey)

    state = State.SENDING_DEVICE_ID_AND_SECRET
  }

  private fun toCar(): Car {
    cleanUp()
    // checkNotNull is safe because this method should only be called when association is completed,
    // namely the device ID has been received.
    return Car(bluetoothManager, messageStream, identificationKey, checkNotNull(deviceId))
  }

  companion object {
    private const val TAG = "PendingCarV3Association"

    private const val INVALID_MESSAGE_ID = -1
  }
}
