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

package com.google.android.libraries.car.trustagent.blemessagestream

import android.bluetooth.BluetoothDevice
import androidx.annotation.IntRange
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.blemessagestream.version2.BluetoothMessageStreamV2
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logwtf
import java.util.Objects
import java.util.UUID
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Handles the streaming of messages to a specific [BluetoothDevice].
 *
 * This stream will handle if messages to a particular remote device need to be split into multiple
 * messages or if the messages can be sent all at once. Internally, it will have its own protocol
 * for how the split messages are structured.
 */
interface MessageStream {

  /** Encryption key used to encrypt/decrypt data over this stream. */
  var encryptionKey: Key?

  /**
   * Sends the given [streamMessage] to remote device and returns a message id generated for
   * [streamMessage].
   *
   * The given message will adhere to the size limit of delivery channel. If the wrapped message is
   * greater than the limit, then this stream should take the appropriate actions necessary to chunk
   * the message to the device so that no parts of the message is dropped. The message id that will
   * be return is an [Int] internally assigned to [streamMessage]. When [streamMessage] is sent out,
   * callback [Callback.onMessageSent] will be invoked with this message id. If the sending fails,
   * remote device will be disconnected. Message id will never be negative.
   */
  @IntRange(from = 0) fun sendMessage(streamMessage: StreamMessage): Int

  fun registerMessageEventCallback(callback: Callback)

  fun unregisterMessageEventCallback(callback: Callback)

  /** Encrypts the payload; throws exception if encrypted is not requested. */
  fun StreamMessage.toEncrypted(): StreamMessage {
    check(isPayloadEncrypted) { "StreamMessage should not be encrypted: $this" }

    val key = checkNotNull(encryptionKey) { "Could not encrypt $this; encryption key is null." }
    val encrypted = key.encryptData(payload)
    return copy(payload = encrypted)
  }

  /** Decrypts the payload; throws exception if it is not marked as encrypted. */
  fun StreamMessage.toDecrypted(): StreamMessage {
    check(isPayloadEncrypted) { "StreamMessage is not encrypted: $this" }

    val key = checkNotNull(encryptionKey) { "Could not decrypt $this; encryption key is null." }
    val decrypted = key.decryptData(payload)
    return copy(payload = decrypted, isPayloadEncrypted = false)
  }

  /**
   * Compresses the payload.
   *
   * Data should be compressed before it's encrypted.
   */
  fun StreamMessage.toCompressed(): StreamMessage {
    if (isCompressed) {
      loge(TAG, "StreamMessage compression: message is already compressed. Returning as is.")
      return this
    }

    val compressed = compressData(payload)
    if (compressed == null) {
      logi(TAG, "Compression did not result in positive net savings. Returning as is.")
      return this
    }

    var originalSize = payload.size
    val saving = Math.round(100L * (originalSize - compressed.size) / originalSize.toDouble())
    logi(TAG, "Compressed from $originalSize to ${compressed.size} bytes saved $saving%.")

    return copy(payload = compressed, originalMessageSize = originalSize)
  }

  /**
   * Decompresses the payload.
   *
   * Data should be decrypted before they are decompressed.
   */
  fun StreamMessage.toDecompressed(): StreamMessage {
    if (!isCompressed) {
      return this
    }

    val decompressed = decompressData(payload, originalMessageSize)
    if (decompressed == null) {
      logwtf(TAG, "Could not decompress $this. Returning as is.")
      return this
    }

    return copy(payload = decompressed, originalMessageSize = 0)
  }

  /** Callbacks that will be invoked for message events. */
  interface Callback {
    /** Invoked when the specified [streamMessage] has been received. */
    fun onMessageReceived(streamMessage: StreamMessage)

    /** Invoked when the specified [streamMessage] has been sent successfully. */
    fun onMessageSent(messageId: Int)
  }

  companion object {
    private const val TAG = "MessageStream"

    private val inflater = Inflater()
    private val deflater = Deflater(Deflater.BEST_COMPRESSION)

    /**
     * Compresses input and return the resulting [ByteArray].
     *
     * Returns `null` if compressed result is larger than the original input.
     */
    internal fun compressData(byteArray: ByteArray): ByteArray? {
      val buffer = ByteArray(byteArray.size)
      val compressedSize =
        deflater.run {
          reset()
          setInput(byteArray)
          finish()
          deflate(buffer)
        }
      // Unfinished deflater means compression generated data larger than the original input.
      // Return null to indicate it should not be compressed.
      if (!deflater.finished()) {
        return null
      }
      return buffer.take(compressedSize).toByteArray()
    }

    /**
     * Decompresses input and returns the resulting [ByteArray].
     *
     * @param originalSize The size of [byteArray] before compression.
     *
     * Returns `null` if [byteArray] could not be decompressed.
     */
    internal fun decompressData(
      byteArray: ByteArray,
      @IntRange(from = 0) originalSize: Int
    ): ByteArray? {
      if (originalSize == 0) {
        logi(TAG, "Decompression: input is not compressed because original size is 0.")
        return byteArray
      }

      val decompressedData = ByteArray(originalSize)
      try {
        inflater.run {
          reset()
          setInput(byteArray)
          inflate(decompressedData)
        }
      } catch (exception: DataFormatException) {
        loge(TAG, "An error occurred while decompressing the message.", exception)
        return null
      }
      if (!inflater.finished()) {
        loge(TAG, "Inflater is not finished after decompression.")
        return null
      }
      logi(TAG, "Decompressed message from ${byteArray.size} to $originalSize bytes.")
      return decompressedData
    }

    /**
     * Creates an implementation of [MessageStream] with [bluetoothManager], based on
     * [messageVersion].
     *
     * [messageVersion] must be no less than ConnectionResolver.MIN_MESSAGING_VERSION.
     */
    fun create(
      @IntRange(from = 2) messageVersion: Int,
      bluetoothManager: BluetoothConnectionManager
    ): MessageStream? {
      return when (messageVersion) {
        2 -> {
          logi(TAG, "Creating MessageStreamV2 without compression support")
          BluetoothMessageStreamV2(bluetoothManager, isCompressionEnabled = false)
        }
        3 -> {
          logi(TAG, "Creating MessageStreamV2 with compression support")
          BluetoothMessageStreamV2(bluetoothManager, isCompressionEnabled = true)
        }
        else -> {
          loge(TAG, "Unrecognized message version $messageVersion. No stream created.")
          return null
        }
      }
    }
  }
}

/**
 * The [payload] to be sent and its metadata.
 *
 * @property payload Bytes to be sent.
 * @property operation The [OperationType] of this message.
 * @property isPayloadEncrypted For a outgoing message, `true` if the payload should be encrypted;
 *           For an incoming message, `true` is the payload is encrypted.
 * @property originalMessageSize If payload is compressed, its original size.
 *           0 if payload is not compressed.
 * @property recipient Identifies the intended receiver of payload.
 */
data class StreamMessage(
  val payload: ByteArray,
  val operation: OperationType,
  val isPayloadEncrypted: Boolean,
  val originalMessageSize: Int,
  val recipient: UUID?
) {
  val isCompressed: Boolean = originalMessageSize > 0

  // Kotlin cannot properly generate these common functions for array property [payload].
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StreamMessage) return false

    return payload.contentEquals(other.payload) &&
      operation == other.operation &&
      isPayloadEncrypted == other.isPayloadEncrypted &&
      recipient == other.recipient &&
      originalMessageSize == other.originalMessageSize
  }

  override fun hashCode() =
    Objects.hash(
      payload.contentHashCode(),
      recipient,
      operation,
      isPayloadEncrypted,
      originalMessageSize
    )
}
