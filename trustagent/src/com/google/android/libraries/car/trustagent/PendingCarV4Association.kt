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
import com.google.android.companionprotos.VerificationCode
import com.google.android.companionprotos.VerificationCodeState
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
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import java.lang.IllegalStateException
import java.lang.UnsupportedOperationException
import java.time.Duration
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Represents a car that is ready to be associated with.
 *
 * This class handles the association flow defined by security version V4.
 *
 * V4 supports out-of-band association.
 * - phone asynchronously reads out-of-band data by starting all channels;
 * - phone-IHU perform UKEY2 encryption handshake;
 * - phone receives UKEY2 callback onAuthStringAvailable();
 * - phone checks whether it has received OOB data;
 * - if yes, phone sends encrypted OOB data; IHU responds with encrypted OOB data;
 * - if no, phone informs IHU to perform visual PIN verification;
 * - encryption is confirmed;
 * - IHU sends encrypted device ID; phone responds with device ID and secret key for reconnection.
 */
@OptIn(kotlin.ExperimentalStdlibApi::class)
internal class PendingCarV4Association
internal constructor(
  private val context: Context,
  @get:VisibleForTesting internal val messageStream: MessageStream,
  private val associatedCarManager: AssociatedCarManager,
  // External API exposes ScanResult so keep it for reporting error.
  override val device: BluetoothDevice,
  private val bluetoothManager: BluetoothConnectionManager,
  private val oobData: OobData?,
  private val oobChannelManagerFactory: OobChannelManagerFactory = OobChannelManagerFactoryImpl(),
  coroutineDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : PendingCar {
  override var callback: PendingCar.Callback? = null

  private val coroutineScope: CoroutineScope = CoroutineScope(coroutineDispatcher)

  private var state = State.UNINITIATED
  private enum class State {
    UNINITIATED,
    ENCRYPTION_HANDSHAKE,
    PENDING_CONFIRMATION,
    PENDING_OOB_CONFIRMATION,
    PENDING_ENCRYPTED_DEVICE_ID,
    SENDING_DEVICE_ID_AND_SECRET
  }

  private var deferredOobData: Deferred<OobData?>? = null
  private var oobToken: ByteArray? = null
  private var oobConnectionManager: OobConnectionManager? = null

  @VisibleForTesting
  internal val encryptionCallback =
    object : EncryptionRunnerManager.Callback {
      override fun onAuthStringAvailable(authString: String, oobToken: ByteArray) {
        this@PendingCarV4Association.oobToken = oobToken

        coroutineScope.launch {
          val oobData = deferredOobData?.await()
          logi(TAG, "OOB data is $oobData.")

          if (oobData == null) {
            handleVisualVerification(authString)
          } else {
            handleOobVerification(oobData, oobToken)
          }
        }
      }

      private fun handleVisualVerification(authString: String) {
        val visualVerificationMessage =
          VerificationCode.newBuilder().run {
            setState(VerificationCodeState.VISUAL_VERIFICATION)
            build()
          }

        logi(TAG, "Sending message for visual verification.")
        messageStream.sendMessage(
          StreamMessage(
            payload = visualVerificationMessage.toByteArray(),
            operation = OperationType.ENCRYPTION_HANDSHAKE,
            isPayloadEncrypted = false,
            originalMessageSize = 0,
            recipient = null
          )
        )

        logi(TAG, "Notifying callback auth string available: $authString.")
        callback?.onAuthStringAvailable(authString)

        state = State.PENDING_CONFIRMATION
      }

      private fun handleOobVerification(oobData: OobData, oobToken: ByteArray) {
        val oobConnectionManager =
          checkNotNull(OobConnectionManager.create(oobData)) {
            "Could not create OobConnectionManager with OobData."
          }
        this@PendingCarV4Association.oobConnectionManager = oobConnectionManager

        val encryptedVerificationCode = oobConnectionManager.encryptVerificationCode(oobToken)
        val oobVerificationMessage =
          VerificationCode.newBuilder().run {
            setState(VerificationCodeState.OOB_VERIFICATION)
            setPayload(ByteString.copyFrom(encryptedVerificationCode))
            build()
          }

        logi(TAG, "Sending message for out-of-band verification.")
        messageStream.sendMessage(
          StreamMessage(
            payload = oobVerificationMessage.toByteArray(),
            operation = OperationType.ENCRYPTION_HANDSHAKE,
            isPayloadEncrypted = false,
            originalMessageSize = 0,
            recipient = null
          )
        )

        state = State.PENDING_OOB_CONFIRMATION
      }

      override fun onOobAuthTokenAvailable(oobToken: ByteArray) {
        throw UnsupportedOperationException("Unexpected callback onOobAuthTokenAvailable.")
      }

      override fun onEncryptionEstablished(key: Key) {
        messageStream.encryptionKey = key
      }

      override fun onEncryptionFailure(reason: EncryptionRunnerManager.FailureReason) {
        when (reason) {
          EncryptionRunnerManager.FailureReason.NO_VERIFICATION_CODE ->
            callback?.onConnectionFailed(this@PendingCarV4Association)
          else -> throw IllegalStateException("Unexpected failure reason $reason.")
        }
      }
    }

  // Specify the type of encryptionRunnerManager because its callback refers to this variable.
  // Otherwise we'd get error "type checking has run into a recursive problem".
  private val encryptionRunnerManager: EncryptionRunnerManager =
    EncryptionRunnerManager(
        EncryptionRunnerFactory.newRunner(EncryptionRunnerFactory.EncryptionRunnerType.UKEY2),
        messageStream
      )
      .apply { callback = encryptionCallback }

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
          State.PENDING_ENCRYPTED_DEVICE_ID -> {
            handleEncryptedDeviceId(streamMessage.payload)
          }
          State.PENDING_OOB_CONFIRMATION -> {
            handleOobConfirmation(streamMessage.payload)
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

    // Start a coroutine to read OOB data asynchronously, in parallel with encryption handshake.
    // After the encryption finishes the handshake, it need a way to confirm the encryption.
    // If OOB data was read successfully, encryption will be confirmed by OOB data; otherwise
    // by user visual confirmation.
    deferredOobData =
      coroutineScope.async {
        withTimeoutOrNull<OobData?>(OOB_CHANNEL_TIMEOUT.toMillis()) {
          val oobChannelTypes = createOobChannelTypes()
          val oobChannelManager =
            oobChannelManagerFactory.create(oobChannelTypes, oobData, securityVersion = 4)
          oobChannelManager?.readOobData(device)
        }
      }

    state = State.ENCRYPTION_HANDSHAKE
    encryptionRunnerManager.initEncryption()
  }

  internal fun createOobChannelTypes(): List<OobChannelType> = buildList {
    add(OobChannelType.BT_RFCOMM)
    if (oobData != null) add(OobChannelType.PRE_ASSOCIATION)
  }

  private fun handleAuthStringConfirmation(message: ByteArray) {
    val verificationCode =
      try {
        VerificationCode.parseFrom(message)
      } catch (e: InvalidProtocolBufferException) {
        loge(TAG, "Could not parse auth string VerificationCode.", e)
        callback?.onConnectionFailed(this@PendingCarV4Association)
        return
      }

    if (verificationCode.state == VerificationCodeState.VISUAL_CONFIRMATION) {
      logi(TAG, "Confirming encryption based on visual confirmation.")
      encryptionRunnerManager.notifyAuthStringConfirmed()
      state = State.PENDING_ENCRYPTED_DEVICE_ID
    }
  }

  private fun handleOobConfirmation(message: ByteArray) {
    val verificationCode =
      try {
        VerificationCode.parseFrom(message)
      } catch (e: InvalidProtocolBufferException) {
        loge(TAG, "Could not parse OOB VerificationCode.", e)
        callback?.onConnectionFailed(this@PendingCarV4Association)
        return
      }

    val oobToken =
      checkNotNull(this.oobToken) {
        "Out-of-band token is null. It should be received at onAuthStringAvailable()."
      }
    val manager =
      checkNotNull(this.oobConnectionManager) {
        "OobConnectionManager is null. It should be set at onAuthStringAvailable()."
      }
    val decrypted = manager.decryptVerificationCode(verificationCode.payload.toByteArray())
    check(decrypted contentEquals oobToken) {
      "Received encrypted oob token does not match the cached value."
    }

    if (verificationCode.state == VerificationCodeState.OOB_VERIFICATION) {
      logi(TAG, "Confirming encryption based on out-of-band confirmation.")
      encryptionRunnerManager.notifyAuthStringConfirmed()
    }

    state = State.PENDING_ENCRYPTED_DEVICE_ID
  }

  private fun handleEncryptedDeviceId(message: ByteArray) {
    val deviceId = bytesToUuid(message)
    logi(TAG, "Received car device id: $deviceId.")

    this.deviceId = deviceId
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
    private const val TAG = "PendingCarV4Association"

    private const val INVALID_MESSAGE_ID = -1

    private val OOB_CHANNEL_TIMEOUT = Duration.ofMillis(500)
  }
}
