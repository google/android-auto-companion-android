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

import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.DeviceMessageProto.Message
import com.google.android.companionprotos.PacketProto.Packet
import com.google.android.libraries.car.trustagent.blemessagestream.MAX_INT32_ENCODING_BYTES
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.blemessagestream.getEncodedSize
import com.google.android.libraries.car.trustagent.util.bytesToUuid
import com.google.android.libraries.car.trustagent.util.uuidToBytes
import com.google.protobuf.ByteString
import kotlin.math.ceil
import kotlin.math.min

private const val TAG = "PacketFactory"

/**
 * The size in bytes of a `fixed32` field in the proto.
 */
private const val FIXED_32_SIZE = 4

/**
 * The bytes needed to encode the field number in the proto.
 * Since the [Packet] only has 4 fields, it will only take 1 additional byte to encode.
 */
private const val FIELD_NUMBER_ENCODING_SIZE = 1

/**
 * The size in bytes of field `packet_number`. The proto field is a fixed32
 */
private const val PACKET_NUMBER_ENCODING_SIZE = FIXED_32_SIZE + FIELD_NUMBER_ENCODING_SIZE

/**
 * Splits data in [streamMessage] (if necessary) to fit within the given [maxSize].
 *
 * @param streamMessage Contains the data to potentially split across multiple [BlePacket]s.
 * @param maxSize The maximum size of each chunk.
 * @return A list of [BlePacket]s.
 */
fun makePackets(
  streamMessage: StreamMessage,
  maxSize: Int,
  messageId: Int
): List<Packet> {
  val bleDeviceMessage = with(Message.newBuilder()) {
    payload = ByteString.copyFrom(streamMessage.payload)

    operation = streamMessage.operation
    isPayloadEncrypted = streamMessage.isPayloadEncrypted
    streamMessage.recipient?.let {
      recipient = ByteString.copyFrom(uuidToBytes(it))
    }
    originalSize = streamMessage.originalMessageSize
    build()
  }
  val payload = bleDeviceMessage.toByteArray()
  val payloadSize = payload.size

  val blePackets = mutableListOf<Packet>()
  val totalPackets = getTotalPacketNumber(messageId, payloadSize, maxSize)
  val packetHeaderSize = getPacketHeaderSize(totalPackets, messageId, min(payloadSize, maxSize))
  val maxPayloadSize = maxSize - packetHeaderSize
  var start = 0
  var end = min(maxPayloadSize, payloadSize)
  for (packetNum in 1..totalPackets) {
    blePackets.add(
      Packet.newBuilder()
        .setPacketNumber(packetNum)
        .setTotalPackets(totalPackets)
        .setMessageId(messageId)
        .setPayload(ByteString.copyFrom(payload.copyOfRange(start, end)))
        .build()
    )
    start = end
    end = min(start + maxPayloadSize, payloadSize)
  }
  return blePackets
}

/**
 * Computes the header size of a [BlePacket] proto message in bytes.
 * This method assumes that the proto contains a payload.
 */
private fun getPacketHeaderSize(
  totalPackets: Int,
  messageId: Int,
  payloadSize: Int
): Int {
  return PACKET_NUMBER_ENCODING_SIZE +
    getEncodedSize(totalPackets) + FIELD_NUMBER_ENCODING_SIZE +
    getEncodedSize(messageId) + FIELD_NUMBER_ENCODING_SIZE +
    getEncodedSize(payloadSize) + FIELD_NUMBER_ENCODING_SIZE
}

/**
 * Computes the number of total packets.
 */
@VisibleForTesting
fun getTotalPacketNumber(messageId: Int, payloadSize: Int, maxSize: Int): Int {
  val headerSizeWithoutTotalPackets = PACKET_NUMBER_ENCODING_SIZE +
    getEncodedSize(messageId) + FIELD_NUMBER_ENCODING_SIZE +
    getEncodedSize(min(payloadSize, maxSize)) + FIELD_NUMBER_ENCODING_SIZE
  for (totalPacketsSize in 1..MAX_INT32_ENCODING_BYTES) {
    val packetHeaderSize = headerSizeWithoutTotalPackets +
      totalPacketsSize + FIELD_NUMBER_ENCODING_SIZE
    val totalPackets = ceil(payloadSize.toDouble() / (maxSize - packetHeaderSize)).toInt()
    if (getEncodedSize(totalPackets) == totalPacketsSize) {
      return totalPackets
    }
  }
  throw PayloadTooLargeException(
    """
    |Cannot get valid total packet number for payload.
    |payloadSize: $payloadSize, messageId: $messageId, maxSize: $maxSize.
    """.trimMargin()
  )
}

internal fun Message.toStreamMessage() = StreamMessage(
  payload.toByteArray(),
  operation,
  isPayloadEncrypted,
  originalSize,
  recipient = if (recipient.isEmpty) null else bytesToUuid(recipient.toByteArray())
)

class PayloadTooLargeException(message: String) : Exception(message)
