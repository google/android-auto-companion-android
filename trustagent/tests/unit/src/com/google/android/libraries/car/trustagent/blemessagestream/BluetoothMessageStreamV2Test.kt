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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.companionprotos.PacketProto.Packet
import com.google.android.libraries.car.trustagent.blemessagestream.version2.BluetoothMessageStreamV2
import com.google.android.libraries.car.trustagent.blemessagestream.version2.MessageIdGenerator
import com.google.android.libraries.car.trustagent.blemessagestream.version2.getTotalPacketNumber
import com.google.android.libraries.car.trustagent.blemessagestream.version2.makePackets
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test the receive and send message functions of [BleMessageStream] */
@RunWith(AndroidJUnit4::class)
class BluetoothMessageStreamV2Test {

  private val mockBluetothManager: BluetoothConnectionManager = mock {
    on { sendMessage(any()) } doReturn true
  }
  private lateinit var managerCallbacks: MutableList<BluetoothConnectionManager.MessageCallback>
  private lateinit var stream: BluetoothMessageStreamV2
  private val mockMessageIdGenerator: MessageIdGenerator = mock {
    on { next() } doReturn MESSAGE_ID
  }

  @Before
  fun setUp() {
    managerCallbacks = mutableListOf()
    stream =
      BluetoothMessageStreamV2(mockBluetothManager, isCompressionEnabled = false).apply {
        messageIdGenerator = mockMessageIdGenerator
      }

    // BleStream registers a gatt callback during initialization. Capture it here for delivering
    // messages to BleStream.
    argumentCaptor<BluetoothConnectionManager.MessageCallback>().apply {
      verify(mockBluetothManager).registerMessageCallback(capture())
      managerCallbacks.add(firstValue)
    }
  }

  @Test
  fun writeMessage_noChunkingRequired_sendsCorrectMessage() {
    // Sufficient large value to hold message in single packet.
    val maxSize = 512
    val message = "message".toByteArray()
    val expected = makePackets(createStreamMessage(message), maxSize, MESSAGE_ID).last()

    stream.maxSize = maxSize
    sendMessage(message)

    argumentCaptor<ByteArray>().apply {
      verify(mockBluetothManager).sendMessage(capture())
      val actual = Packet.parseFrom(firstValue)
      assertThat(actual).isEqualTo(expected)
      for (callback in managerCallbacks) {
        callback.onMessageSent(firstValue)
      }
    }
    assertThat(stream.messageQueue).hasSize(0)
  }

  @Test
  fun writeMessage_ChunkingRequired_sendsCorrectMessage() {
    val maxSize = 20
    val payloadSize = 100
    val payload = makePayload(payloadSize)
    val expectedWrites = getTotalPacketNumber(MESSAGE_ID, payloadSize, maxSize)
    // NOTE: in the implementation, each response directly triggers next write.
    //  In the test, the response is invoked in the same thread so next sendMessage() is stacked.
    //  Namely if we don't limit the number of response, the test will Stack Overflow™.
    //  Only respond expected times because it is triggering "writing next message".
    var responseCount = expectedWrites
    whenever(mockBluetothManager.sendMessage(any())).thenAnswer {
      if (responseCount > 0) {
        responseCount--
        // For every sendMessage, notify callback to cue the next message.
        for (callback in managerCallbacks) {
          callback.onMessageSent(it.getArgument(0))
        }
      }
      // Return `true` to indicate writing was initiated successfully.
      true
    }

    stream.maxSize = maxSize
    sendMessage(payload)

    verify(mockBluetothManager, times(expectedWrites)).sendMessage(any())
    assertThat(stream.messageQueue).hasSize(0)
  }

  @Test
  fun writeMessage_noChunkingRequired_notifyCallback() {
    val semaphore = Semaphore(0)
    val callbackSpy = spy(StreamCallback(semaphore))
    stream.registerMessageEventCallback(callbackSpy)

    // Sufficient large value to hold message in single packet.
    val maxSize = 512
    val message = "message".toByteArray()
    whenever(mockBluetothManager.sendMessage(any())).thenAnswer {
      // Return `true` to indicate writing was initiated successfully.
      true
    }
    stream.maxSize = maxSize
    sendMessage(message)

    argumentCaptor<ByteArray>().apply {
      verify(mockBluetothManager).sendMessage(capture())
      for (callback in managerCallbacks) {
        callback.onMessageSent(firstValue)
      }
    }

    assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue()
    verify(callbackSpy).onMessageSent(MESSAGE_ID)
  }

  @Test
  fun writeMessage_ChunkingRequired_notifyCallback() {
    val semaphore = Semaphore(0)
    val callbackSpy = spy(StreamCallback(semaphore))
    stream.registerMessageEventCallback(callbackSpy)

    val maxSize = 20
    val payloadSize = 100
    val payload = makePayload(payloadSize)
    val expectedWrites = getTotalPacketNumber(MESSAGE_ID, payloadSize, maxSize)
    var responseCount = expectedWrites - 1
    whenever(mockBluetothManager.sendMessage(any())).thenAnswer {
      if (responseCount > 0) {
        responseCount--
        for (callback in managerCallbacks) {
          callback.onMessageSent(it.getArgument(0))
        }
        assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isFalse()
      }
      // Return `true` to indicate writing was initiated successfully.
      true
    }

    stream.maxSize = maxSize
    val messageID = sendMessage(payload)

    argumentCaptor<ByteArray>().apply {
      verify(mockBluetothManager, times(expectedWrites)).sendMessage(capture())
      for (callback in managerCallbacks) {
        callback.onMessageSent(lastValue)
      }
    }
    assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue()
    verify(callbackSpy).onMessageSent(messageID)
  }

  @Test
  fun receiveMessage_disconnectRequest_disconnect() {
    val packets =
      makePackets(
        createStreamMessage(payload = ByteArray(0), operationType = OperationType.DISCONNECT),
        maxSize = 100,
        MESSAGE_ID,
      )
    for (packet in packets) {
      for (callback in managerCallbacks) {
        callback.onMessageReceived(packet.toByteArray())
      }
    }

    verify(mockBluetothManager).disconnect()
  }

  @Test
  fun receiveMessage_ChunkingRequired_NotifyCallback() {
    val semaphore = Semaphore(0)
    val listenerSpy = spy(StreamCallback(semaphore))
    stream.registerMessageEventCallback(listenerSpy)

    val payloadSize = 100
    val maxSize = 20
    val payload = makePayload(payloadSize)
    val packets = makePackets(createStreamMessage(payload), maxSize, MESSAGE_ID)

    for (packet in packets) {
      for (callback in managerCallbacks) {
        callback.onMessageReceived(packet.toByteArray())
      }
    }

    assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue()
    verify(listenerSpy).onMessageReceived(createStreamMessage(payload))
  }

  @Test
  fun receiveMessage_noChunkingRequired_NotifyCallback() {
    // Sufficient large value to hold message in single packet.
    val maxSize = 512
    val message = "message".toByteArray()
    val semaphore = Semaphore(0)
    val listenerSpy = spy(StreamCallback(semaphore))
    stream.registerMessageEventCallback(listenerSpy)
    val incoming =
      makePackets(createStreamMessage(message), maxSize, MESSAGE_ID).last().toByteArray()

    for (callback in managerCallbacks) {
      callback.onMessageReceived(incoming)
    }

    assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue()
    verify(listenerSpy).onMessageReceived(createStreamMessage(message))
  }

  @Test
  fun receiveMessage_unableToParse_disconnect() {
    val message = "invalidMessage".toByteArray()
    for (callback in managerCallbacks) {
      callback.onMessageReceived(message)
    }
    verify(mockBluetothManager).disconnect()
  }

  @Test
  fun receiveMessage_duplicatePacket_notifiesCallback() {
    val semaphore = Semaphore(0)
    val listenerSpy = spy(StreamCallback(semaphore))
    stream.registerMessageEventCallback(listenerSpy)

    val payloadSize = 100
    val maxSize = 20
    val payload = makePayload(payloadSize)
    val packets = makePackets(createStreamMessage(payload), maxSize, MESSAGE_ID)

    for (packet in packets) {
      for (callback in managerCallbacks) {
        // Note: calling `onMessageReceived` twice to simulate a duplicate packet.
        callback.onMessageReceived(packet.toByteArray())
        callback.onMessageReceived(packet.toByteArray())
      }
    }

    assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue()
    verify(listenerSpy).onMessageReceived(createStreamMessage(payload))
  }

  @Test
  fun receiveMessage_outOfOrder_disconnects() {
    val payloadSize = 100
    val maxSize = 20
    val payload = makePayload(payloadSize)
    val packets = makePackets(createStreamMessage(payload), maxSize, MESSAGE_ID)

    // Sanity check to ensure that writing the first and last packet is considered out-of-order.
    assertThat(packets.size).isGreaterThan(2)

    for (callback in managerCallbacks) {
      callback.onMessageReceived(packets.first().toByteArray())
      callback.onMessageReceived(packets.last().toByteArray())
    }

    verify(mockBluetothManager).disconnect()
  }

  private fun sendMessage(message: ByteArray): Int {
    return stream.sendMessage(createStreamMessage(message))
  }

  /** Make a random message with a fixed length. */
  private fun makePayload(length: Int): ByteArray {
    val message = ByteArray(length)
    ThreadLocalRandom.current().nextBytes(message)
    return message
  }

  private fun createStreamMessage(
    payload: ByteArray,
    operationType: OperationType = OperationType.CLIENT_MESSAGE,
  ) =
    StreamMessage(
      payload,
      operationType,
      isPayloadEncrypted = false,
      originalMessageSize = 0,
      recipient = null,
    )

  /**
   * Add the thread control logic into [MessageStream.Callback]; only for spy purpose.
   *
   * Each invocation of callback releases the semaphore which should be held by a test thread,
   * effectively resuming the test to verify the effects of receiving a message.
   *
   * This is necessary because the code under test uses [notifyCallbacks] method, which starts a new
   * thread for callbacks.
   */
  open class StreamCallback(private val semaphore: Semaphore) : MessageStream.Callback {
    override fun onMessageReceived(streamMessage: StreamMessage) {
      semaphore.release()
    }

    override fun onMessageSent(messageId: Int) {
      semaphore.release()
    }
  }

  companion object {
    private const val MESSAGE_ID = 1
  }
}
