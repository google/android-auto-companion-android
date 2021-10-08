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
import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import java.lang.IllegalStateException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlin.collections.copyOfRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Represents a connection to an already associated car.
 *
 * This class handles the reconnection flow defined by security version V2. See detailed design in
 * https://goto.google.com/aae-batmobile-hide-device-ids
 *
 * See also [PendingCarV2Association].
 */
internal class PendingCarV2Reconnection
internal constructor(
  private val messageStream: MessageStream,
  private val associatedCarManager: AssociatedCarManager,
  override val device: BluetoothDevice,
  private val bluetoothManager: BluetoothConnectionManager,
  private val coroutineScope: CoroutineScope = MainScope()
) : PendingCar {
  override val isAssociating = false
  override var callback: PendingCar.Callback? = null

  private var deviceId: UUID? = null
  private var storedSession: ByteArray? = null
  private var identificationKey: SecretKey? = null

  private val gattConnectionCallback =
    object : BluetoothConnectionManager.ConnectionCallback {
      override fun onConnected() {
        loge(TAG, "Unexpected gatt callback: onConnected. Disconnecting.")
        bluetoothManager.disconnect()
        callback?.onConnectionFailed(this@PendingCarV2Reconnection)
      }

      override fun onConnectionFailed() {
        loge(TAG, "Unexpected gatt callback: onConnectionFailed. Disconnecting.")
        bluetoothManager.disconnect()
        callback?.onConnectionFailed(this@PendingCarV2Reconnection)
      }

      override fun onDisconnected() {
        cleanUp()
        loge(TAG, "Disconnected while attempting to establish connection.")
        callback?.onConnectionFailed(this@PendingCarV2Reconnection)
      }
    }

  init {
    bluetoothManager.registerConnectionCallback(gattConnectionCallback)
  }

  /**
   * [advertisedData] should contain [ADVERTISED_DATA_SIZE_BYTES] bytes that represent the salt and
   * its expected HMAC or is null if reconnection happen through SPP.
   */
  override fun connect(advertisedData: ByteArray?) {
    coroutineScope.launch { initConnection(advertisedData) }
  }

  override fun disconnect() {
    cleanUp()
    bluetoothManager.disconnect()
  }

  private fun cleanUp() {
    bluetoothManager.unregisterConnectionCallback(gattConnectionCallback)
  }

  /**
   * Initiate connection to available associated cars.
   *
   * In a BLE connection [advertisedData] will be used to find the remote device to be connected to.
   * In a SPP connection [advertisedData] will be null. Initiate connection to [device] directly.
   */
  private suspend fun initConnection(advertisedData: ByteArray?) {
    val associatedCars = associatedCarManager.retrieveAssociatedCars()
    var associatedCar: AssociatedCar? =
      if (advertisedData != null) {
        findMatch(advertisedData, associatedCars)
      } else {
        logi(TAG, "Advertised data is null, start Spp reconnection.")
        associatedCars.first { it.macAddress == device.address }
      }
    if (associatedCar == null) {
      loge(TAG, "No associated car could be found.")
      callback?.onConnectionFailed(this)
      return
    }

    deviceId = associatedCar.deviceId
    identificationKey = associatedCar.identificationKey
    storedSession = associatedCarManager.loadEncryptionKey(associatedCar.deviceId)

    logi(TAG, "Initiating connection to $deviceId.")
    if (advertisedData != null) {
      initDeviceVerification(advertisedData, associatedCar)
    } else {
      initEncryption(checkNotNull(storedSession))
    }
  }

  private fun initDeviceVerification(advertisedData: ByteArray, associatedCar: AssociatedCar) {
    // Solve the remote challenge.
    logi(TAG, "Responding to challenge.")
    val (paddedSalt, _) = advertisedData.toPaddedSaltAndTruncatedHmac()
    val hmac = computeHmac(paddedSalt, associatedCar.identificationKey)

    // Generate a challenge and store its expected response.
    val challenge = generateChallenge()

    val expectedHmac = computeHmac(challenge, associatedCar.identificationKey)

    with(messageStream) {
      registerMessageEventCallback(createStreamCallback(expectedHmac))
      val message =
        StreamMessage(
          payload = hmac + challenge,
          operation = OperationType.ENCRYPTION_HANDSHAKE,
          isPayloadEncrypted = false,
          originalMessageSize = 0,
          recipient = null
        )
      // After sending challenge, the created stream callback will be triggered.
      sendMessage(message)
    }
  }

  private fun createStreamCallback(expectedHmac: ByteArray) =
    object : MessageStream.Callback {
      override fun onMessageReceived(streamMessage: StreamMessage) {
        messageStream.unregisterMessageEventCallback(this)

        val hmac = streamMessage.payload

        if (!(hmac contentEquals expectedHmac)) {
          loge(TAG, "Received HMAC does not match expected. Disconnecting.")
          disconnect()
          callback?.onConnectionFailed(this@PendingCarV2Reconnection)
          return
        }

        logi(TAG, "Received HMAC matches expected value. Init encryption.")
        initEncryption(checkNotNull(storedSession))
      }

      override fun onMessageSent(messageId: Int) {}
    }

  private fun initEncryption(storedSession: ByteArray) {
    logi(TAG, "Initiating encryption")
    with(EncryptionRunnerManager(messageStream, storedSession)) {
      callback = encryptionCallback
      // After encryption handshake, encryptionCallback will be triggered.
      initEncryption()
    }
  }

  @VisibleForTesting
  val encryptionCallback =
    object : EncryptionRunnerManager.Callback {
      override fun onAuthStringAvailable(authString: String) {
        loge(TAG, "Received encryption callback onAuthStringAvailable. Ignored.")
      }

      override fun onOobAuthTokenAvailable(oobToken: ByteArray) {
        loge(TAG, "Received encryption callback onOobAuthTokenAvailable. Ignored.")
      }

      override fun onEncryptionFailure(reason: EncryptionRunnerManager.FailureReason) {
        when (reason) {
          EncryptionRunnerManager.FailureReason.UKEY2_KEY_MISMATCH -> {
            disconnect()
            callback?.onConnectionFailed(this@PendingCarV2Reconnection)
          }
          else -> throw IllegalStateException("Unexpected failure reason $reason. Ignored.")
        }
      }

      override fun onEncryptionEstablished(key: Key) {
        logd(TAG, "onEncryptionEstablished")
        messageStream.encryptionKey = key
        coroutineScope.launch { callback?.onConnected(toCar()) }
      }
    }

  private suspend fun toCar(): Car {
    cleanUp()
    // checkNotNull is safe because device ID is only set at [connect] and already checked.
    val deviceId = checkNotNull(this.deviceId)
    return Car(
      bluetoothManager,
      messageStream,
      checkNotNull(identificationKey),
      deviceId,
      associatedCarManager.loadName(deviceId)
    )
  }

  companion object {
    private const val TAG = "PendingCarV2Reconnection"

    /** Expected size of salt in advertised data during reconnection flow. */
    @VisibleForTesting const val SALT_SIZE_BYTES = 8
    /** Expected size of truncated expected value in advertised data during reconnection flow. */
    private const val TRUNCATED_SIZE_BYTES = 3
    /** Expected total size of advertised data during reconnection flow. */
    const val ADVERTISED_DATA_SIZE_BYTES = SALT_SIZE_BYTES + TRUNCATED_SIZE_BYTES

    @VisibleForTesting const val CHALLENGE_BLOCK_SIZE_BYTES = 16

    /**
     * Finds a car in [associatedCars] that is able to solve the [advertisedData].
     *
     * [advertisedData] should contain a salt and its expected HMAC.
     *
     * The salt will be zero-padded to [CHALLENGE_BLOCK_SIZE_BYTES], then passed to each
     * identification key in [associatedCars]. The generated HMAC will be truncated to compare
     * against the advertised expected HMAC.
     *
     * Returns the car with key that solves the advertised salt; `null` if none matches.
     */
    fun findMatch(advertisedData: ByteArray, associatedCars: List<AssociatedCar>): AssociatedCar? {
      val (paddedSalt, truncatedHmac) = advertisedData.toPaddedSaltAndTruncatedHmac()

      for (associatedCar in associatedCars) {
        val key = associatedCar.identificationKey
        val hmac = computeHmac(paddedSalt, key)

        // Truncate the HMAC to match the expected value.
        if (hmac.toTruncated() contentEquals truncatedHmac) {
          logi(TAG, "${associatedCar.deviceId} generated matching HMAC for advertised salt.")
          return associatedCar
        }
      }
      logi(TAG, "No associated car matched advertised data. Returning null.")
      return null
    }

    /**
     * Creates zero-padded salt and truncated HMAC out of the byte array.
     *
     * The byte array should contain [TRUNCATED_SIZE_BYTES] as truncated HMAC, and [SALT_SIZE_BYTES]
     * as salt. Salt will be zero padded to [CHALLENGE_BLOCK_SIZE_BYTES].
     */
    private fun ByteArray.toPaddedSaltAndTruncatedHmac(): PaddedSaltAndTruncatedHmac {
      require(size == ADVERTISED_DATA_SIZE_BYTES) {
        "Expect data size to be $ADVERTISED_DATA_SIZE_BYTES; actual $size."
      }

      val truncatedExpected = copyOfRange(0, TRUNCATED_SIZE_BYTES)
      val salt = copyOfRange(TRUNCATED_SIZE_BYTES, ADVERTISED_DATA_SIZE_BYTES)
      return PaddedSaltAndTruncatedHmac(
        // Zero-padded salt.
        salt.copyOf(CHALLENGE_BLOCK_SIZE_BYTES),
        truncatedExpected
      )
    }

    private data class PaddedSaltAndTruncatedHmac(
      val paddedSalt: ByteArray,
      val truncatedExpected: ByteArray
    )

    /** Hashes [input] with [secretKey]. */
    @VisibleForTesting
    fun computeHmac(input: ByteArray, secretKey: SecretKey): ByteArray {
      val mac = Mac.getInstance(AssociatedCarManager.IDENTIFICATION_KEY_ALGORITHM)
      return mac.run {
        init(secretKey)
        doFinal(input)
      }
    }

    @VisibleForTesting fun ByteArray.toTruncated(): ByteArray = copyOf(TRUNCATED_SIZE_BYTES)

    private fun generateChallenge(): ByteArray =
      ByteArray(CHALLENGE_BLOCK_SIZE_BYTES).apply { SecureRandom().nextBytes(this) }
  }
}
