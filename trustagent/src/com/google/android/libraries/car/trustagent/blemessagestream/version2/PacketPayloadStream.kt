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

import com.google.android.companionprotos.DeviceMessageProto.Message
import com.google.android.companionprotos.PacketProto.Packet
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logw
import com.google.android.libraries.car.trustagent.util.toHexString
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Manages incoming [Packet]s by grouping them by [Packet.getMessageId] and notifying registered
 * listener when a complete message has been received.
 */
class PacketPayloadStream {
  /** A map of message IDs to data that are waiting to be formed into complete messages. */
  private val pendingData = mutableMapOf<Int, PendingMessage>()

  var listener: OnMessageCompletedListener? = null

  /**
   * Writes the given [packet] into message-id-based stream.
   *
   * If the write cannot be completed, an [IOException] will be thrown.
   *
   * An [IllegalStateException] can also be thrown if there is inconsistency with the packet that is
   * being written (e.g. if the given `packet` is written out-of-order with the last write of a
   * `packet` with the same message id). When this happens, the stream should not be reused.
   */
  fun write(packet: Packet) {
    val messageId = packet.messageId
    var pendingMessage =
      pendingData.get(messageId)?.apply {
        if (!shouldProcessPacket(packet, currentPendingMessage = this)) {
          return@write
        }

        write(packet)
      }

    if (pendingMessage == null) {
      // The first message must start at 1, but handle receiving the last packet as this could
      // represent a duplicate packet. All other cases will trigger an exception when the packet
      // is parsed into a BleDeviceMessage.
      if (packet.packetNumber != 1 && packet.packetNumber == packet.totalPackets) {
        logw(TAG, "Received a first message that is not the start of a packet. Ignoring.")
        return
      }

      pendingMessage = packet.toPendingMessage()
      pendingData[messageId] = pendingMessage
    }

    if (packet.packetNumber != packet.totalPackets) {
      return
    }

    val bleDeviceMessage =
      try {
        Message.parseFrom(pendingMessage.messageStream.toByteArray())
      } catch (e: IOException) {
        loge(TAG, "Could not parse BlePackets with message id $messageId as BleDeviceMessage.")
        throw e
      }

    pendingData.remove(messageId)
    listener?.onMessageCompleted(bleDeviceMessage)
  }

  /**
   * Validates the given [packet]'s metadata and returns `true` if the [packet] is valid for being
   * written into the message stream.
   */
  private fun shouldProcessPacket(packet: Packet, currentPendingMessage: PendingMessage): Boolean {
    if (currentPendingMessage.lastPacketNumber + 1 == packet.packetNumber) {
      return true
    }

    // A duplicate packet can just be ignored, while an out-of-order packet represents that the
    // stream should be closed.
    if (currentPendingMessage.lastPacketNumber == packet.packetNumber) {
      logw(
        TAG,
        "Received a duplicate packet (${packet.packetNumber}) for message ${packet.messageId}. " +
          "Ignoring."
      )
      return false
    }

    throw IllegalStateException(
      "Received out-of-order packet ${packet.packetNumber}. " +
        "Expecting ${currentPendingMessage.lastPacketNumber + 1}"
    )
  }

  private fun Packet.toPendingMessage(): PendingMessage {
    val payloadBytes = payload.toByteArray()
    val messageStream = ByteArrayOutputStream(payloadBytes.size).apply { write(payloadBytes) }

    return PendingMessage(messageStream, lastPacketNumber = packetNumber)
  }

  /** Invoked when [BlePacket]s through [write] can be combined into a [BleDeviceMessage]. */
  interface OnMessageCompletedListener {
    fun onMessageCompleted(deviceMessage: Message)
  }

  companion object {
    private const val TAG = "PacketPayloadStream"
  }
}

private data class PendingMessage(
  var messageStream: ByteArrayOutputStream,
  var lastPacketNumber: Int
) {
  /**
   * Writes the `payload` and `packetNumber` from the given [packet] to this `PendingMessage`.
   *
   * The `payload` is appended to the end of `messageStream`. If this write encounters an error,
   * then an [IOException] is thrown.
   */
  fun write(packet: Packet) {
    messageStream.write(packet.payload.toByteArray())
    lastPacketNumber = packet.packetNumber
  }

  /** Returns a hex representation of [messageStream]. */
  fun messageStreamToHex(): String = messageStream.toByteArray().toHexString()
}
