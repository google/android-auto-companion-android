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

package com.google.android.libraries.car.trustagent.blemessagestream.version2

import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.DeviceMessageProto.Message
import com.google.android.companionprotos.PacketProto.Packet
import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.protobuf.InvalidProtocolBufferException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Handles incoming and outgoing messages with [bluetoothManager].
 *
 * Incoming messages are expected to be [Packet]. They are combined based on message id to form a
 * [DeviceMessage] and delivered through [MessageStream.Callback].
 *
 * Outgoing messages are chunked into [Packet] per maximum size.
 *
 * Failure to send one message will disconnect with remote device.
 */
class BluetoothMessageStreamV2(
  private val bluetoothManager: BluetoothConnectionManager,
  private val isCompressionEnabled: Boolean
) : MessageStream {

  override var encryptionKey: Key? = null

  // Guards the entire process of queueing a message including encryption and enqueueing it.
  private val lock = ReentrantLock()
  // Ensures the next message will not be sent until the callback for sending current message has
  // been received.
  private val writeInProgress = AtomicBoolean(false)

  @GuardedBy("lock") @VisibleForTesting internal val messageQueue = ArrayDeque<Packet>()

  /** The maximum size of the data which can form a packet and be sent at once. */
  @VisibleForTesting internal var maxSize = bluetoothManager.maxWriteSize

  @GuardedBy("lock")
  @VisibleForTesting
  internal var messageIdGenerator: MessageIdGenerator = MessageIdGenerator()

  private val callbacks = CopyOnWriteArrayList<MessageStream.Callback>()
  private val payloadStreamListener =
    object : PacketPayloadStream.OnMessageCompletedListener {
      override fun onMessageCompleted(deviceMessage: Message) {
        var message = deviceMessage.toStreamMessage()
        if (message.isPayloadEncrypted) {
          message = message.toDecrypted()
        }

        if (isCompressionEnabled) {
          message = message.toDecompressed()
        }
        callbacks.forEach { it.onMessageReceived(message) }
      }
    }

  @GuardedBy("lock")
  private val blePayloadStream = PacketPayloadStream().apply { listener = payloadStreamListener }

  private val messageCallback =
    object : BluetoothConnectionManager.MessageCallback {
      override fun onMessageSent(message: ByteArray) {
        lock.withLock { messageQueue.remove() }

        // After removing the current message from the queue, the write is considered completed.
        writeInProgress.set(false)

        val blePacket =
          try {
            Packet.parseFrom(message)
          } catch (e: InvalidProtocolBufferException) {
            loge(TAG, "Can not parse BLE packet sent to client.", e)
            return
          }
        logi(TAG, "Packet ${blePacket.packetNumber} of message ${blePacket.messageId} was sent.")
        if (blePacket.packetNumber == blePacket.totalPackets) {
          callbacks.forEach { it.onMessageSent(blePacket.messageId) }
        }

        writeNextMessageInQueue()
      }

      override fun onMessageReceived(data: ByteArray) {
        val blePacket =
          try {
            Packet.parseFrom(data)
          } catch (e: InvalidProtocolBufferException) {
            loge(TAG, "Can not parse BLE message from client. Disconnecting.", e)
            bluetoothManager.disconnect()
            return
          }
        logi(
          TAG,
          "Received packet ${blePacket.packetNumber} of ${blePacket.totalPackets} for " +
            "message ${blePacket.messageId} containing ${blePacket.payload.size()} bytes."
        )
        lock.withLock {
          try {
            blePayloadStream.write(blePacket)
          } catch (e: Exception) {
            loge(TAG, "Could not process packet. Disconnecting.", e)
            bluetoothManager.disconnect()
          }
        }
      }
    }

  init {
    bluetoothManager.registerMessageCallback(messageCallback)
  }

  override fun sendMessage(streamMessage: StreamMessage): Int {
    var message = streamMessage

    if (isCompressionEnabled) {
      message = message.toCompressed()
    }

    val id =
      lock.withLock {
        // Encrypting the message and queueing must be atomic to ensure the messages are sent
        // in accordance with UKEY2 sequence number.
        if (message.isPayloadEncrypted) {
          message = message.toEncrypted()
        }

        val messageId = messageIdGenerator.next()
        val blePackets = makePackets(message, maxSize, messageId)
        logi(
          TAG,
          "Sending message $messageId to device, number of packets send: ${blePackets.size}"
        )

        // Messages are queued up for delivery.
        messageQueue.addAll(blePackets)

        messageId
      }

    writeNextMessageInQueue()
    return id
  }

  /** Write the next message in the [messageQueue]. */
  private fun writeNextMessageInQueue() {
    if (!writeInProgress.compareAndSet(/* expected= */ false, /* update= */ true)) {
      // If writeInProgress does not match expected value - false, (obviously) it is true.
      logw(
        TAG,
        "Request to write a message when writing is in progress. Waiting until write is complete."
      )
      return
    }

    val nextMessage = lock.withLock { messageQueue.peek() }
    if (nextMessage == null) {
      logi(TAG, "Queue is empty; no message to send.")
      writeInProgress.set(false)
      return
    }

    logi(
      TAG,
      "Sending packet ${nextMessage.packetNumber} of ${nextMessage.totalPackets} for " +
        "message ${nextMessage.messageId} containing ${nextMessage.payload.size()} bytes."
    )

    if (!bluetoothManager.sendMessage(nextMessage.toByteArray())) {
      // Handle failure to send message.
      loge(
        TAG,
        "Could not send message: $nextMessage. " +
          "Remaining ${messageQueue.size} messages in queue. Disconnecting."
      )
      writeInProgress.set(false)
      bluetoothManager.disconnect()
    }
  }

  override fun registerMessageEventCallback(callback: MessageStream.Callback) {
    check(callbacks.add(callback)) { "Could not add callback." }
  }

  override fun unregisterMessageEventCallback(callback: MessageStream.Callback) {
    if (!callbacks.remove(callback)) {
      loge(TAG, "Did not remove callback from existing ones.")
    }
  }
  companion object {
    private const val TAG = "BluetoothMessageStreamV2"
  }
}

/**
 * Generates the next available message ID to use.
 *
 * A message ID uniquely identifies a group of [BlePacket]s on the receiving end.
 *
 * This class is not thread-safe.
 */
open class MessageIdGenerator {
  private var messageId = 0
  open fun next() = messageId.also { messageId = (messageId + 1) % Int.MAX_VALUE }
}
