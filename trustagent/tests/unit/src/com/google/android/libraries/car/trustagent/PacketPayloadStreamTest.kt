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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.DeviceMessageProto.Message
import com.google.android.companionprotos.PacketProto.Packet
import com.google.android.libraries.car.trustagent.blemessagestream.version2.PacketPayloadStream
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

private val PAYLOAD = "payload".toByteArray(Charsets.UTF_8)
private val ANOTHER_PAYLOAD = "another_payload".toByteArray(Charsets.UTF_8)

@RunWith(AndroidJUnit4::class)
class PacketPayloadStreamTest {
  private lateinit var stream: PacketPayloadStream
  private lateinit var mockStreamListener: PacketPayloadStream.OnMessageCompletedListener

  @Before
  fun setUp() {
    mockStreamListener = mock()
    stream = PacketPayloadStream().apply { listener = mockStreamListener }
  }

  @Test
  fun testSinglePacket() {
    val packets = makePackets(messageId = 0, packetCount = 1)
    stream.write(packets[0])

    argumentCaptor<Message>().apply {
      verify(mockStreamListener).onMessageCompleted(capture())

      assertThat(firstValue.payload.toByteArray()).isEqualTo(PAYLOAD)
    }
  }

  @Test
  fun testCombiningPackets() {
    val packets = makePackets(messageId = 0, packetCount = 3)
    for (packet in packets) {
      stream.write(packet)
    }

    argumentCaptor<Message>().apply {
      verify(mockStreamListener).onMessageCompleted(capture())

      assertThat(firstValue.payload.toByteArray()).isEqualTo(PAYLOAD)
    }
  }

  @Test
  fun testIncompletePackets_NoCallback() {
    val packetCount = 3
    val packets = makePackets(messageId = 0, packetCount = packetCount)
    stream.write(packets[0])

    verify(mockStreamListener, never()).onMessageCompleted(any())
  }

  @Test
  fun testMultipleMessageIds() {
    val packetsId0 = makePackets(PAYLOAD, messageId = 0, packetCount = 3)
    for (packet in packetsId0) {
      stream.write(packet)
    }
    val packetsId1 = makePackets(ANOTHER_PAYLOAD, messageId = 1, packetCount = 1)
    stream.write(packetsId1[0])

    argumentCaptor<Message>().apply {
      verify(mockStreamListener, times(2)).onMessageCompleted(capture())

      assertThat(firstValue.payload.toByteArray()).isEqualTo(PAYLOAD)
      assertThat(secondValue.payload.toByteArray()).isEqualTo(ANOTHER_PAYLOAD)
    }
  }

  @Test
  fun testCombiningMultipleMessageIds() {
    val packetsId0 = makePackets(PAYLOAD, messageId = 0, packetCount = 3)
    for (packet in packetsId0) {
      stream.write(packet)
    }
    val packetsId1 = makePackets(ANOTHER_PAYLOAD, messageId = 1, packetCount = 1)
    for (packet in packetsId1) {
      stream.write(packet)
    }

    argumentCaptor<Message>().apply {
      verify(mockStreamListener, times(2)).onMessageCompleted(capture())

      assertThat(firstValue.payload.toByteArray()).isEqualTo(PAYLOAD)
      assertThat(secondValue.payload.toByteArray()).isEqualTo(ANOTHER_PAYLOAD)
    }
  }

  @Test
  fun testDuplicatePacket_Ignored() {
    val packets = makePackets(messageId = 0, packetCount = 3)
    for (packet in packets) {
      // Note: writing packet twice to simulate duplicate packet.
      stream.write(packet)
      stream.write(packet)
    }

    argumentCaptor<Message>().apply {
      verify(mockStreamListener).onMessageCompleted(capture())
      assertThat(firstValue.payload.toByteArray()).isEqualTo(PAYLOAD)
    }
  }

  @Test
  fun testOutOfOrderPacket_throwsError() {
    val packets = makePackets(messageId = 0, packetCount = 3)

    stream.write(packets[0])

    // Write the 3rd packet instead of 2nd.
    assertFailsWith<IllegalStateException> { stream.write(packets[2]) }
  }

  private fun makePackets(
    payload: ByteArray = PAYLOAD,
    messageId: Int,
    packetCount: Int
  ): List<Packet> {
    val bleDeviceMessage =
      Message.newBuilder().setPayload(ByteString.copyFrom(payload)).build().toByteArray()

    val packets = mutableListOf<Packet>()
    for (i in 1 until packetCount) {
      packets.add(
        Packet.newBuilder()
          // Each packet contains 1 byte.
          .setPayload(ByteString.copyFrom(bleDeviceMessage, i - 1, 1))
          .setPacketNumber(i)
          .setTotalPackets(packetCount)
          .setMessageId(messageId)
          .build()
      )
    }
    val lastPayload =
      ByteString.copyFrom(
        bleDeviceMessage,
        packetCount - 1,
        bleDeviceMessage.size - packetCount + 1
      )
    packets.add(
      Packet.newBuilder()
        // The last packet contains the remaining payload.
        .setPayload(lastPayload)
        .setPacketNumber(packetCount)
        .setTotalPackets(packetCount)
        .setMessageId(messageId)
        .build()
    )
    return packets
  }
}
