// Copyright 2023 Google LLC
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
import com.google.android.companionprotos.PeriodicPingProto.PeriodicPingMessage
import com.google.android.companionprotos.PeriodicPingProto.PeriodicPingMessage.MessageType
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class PeriodicPingManagerTest {
  private lateinit var manager: PeriodicPingManager
  private lateinit var car: Car

  @Before
  fun setUp() {
    car = createMockCar()

    manager = PeriodicPingManager().apply { notifyCarConnected(car) }
  }

  @Test
  fun testOnCarConnected_populateConnectedCar() {
    manager.onCarConnected(DEVICE_ID_1)

    assertEquals(DEVICE_ID_1, manager.connectedCar)
  }

  @Test
  fun testOnCarDisconnected_sameCarDisconnected_removeConnectedCar() {
    manager.onCarConnected(DEVICE_ID_1)
    manager.onCarDisconnected(DEVICE_ID_1)

    assertThat(manager.connectedCar).isNull()
  }

  @Test
  fun testOnCarDisconnected_differentCarDisconnected_doNothing() {
    manager.onCarConnected(DEVICE_ID_1)
    manager.onCarDisconnected(DEVICE_ID_2)

    assertEquals(DEVICE_ID_1, manager.connectedCar)
  }

  @Test
  fun testOnMessageReceived_notPingMessage_ignore() {
    val spiedManager =
      spy(PeriodicPingManager()).apply {
        notifyCarConnected(car)
        onCarConnected(DEVICE_ID_1)
        onMessageReceived(createUnknownMessage(), DEVICE_ID_1)
      }

    verify(spiedManager, never()).sendMessage(any(), any())
  }

  @Test
  fun testOnMessageReceived_pingMessage_sendAck() {
    val spiedManager =
      spy(PeriodicPingManager()).apply {
        notifyCarConnected(car)
        onCarConnected(DEVICE_ID_1)
        onMessageReceived(createPingMessage(), DEVICE_ID_1)
      }
    val captor = argumentCaptor<ByteArray>()

    verify(spiedManager).sendMessage(captor.capture(), eq(DEVICE_ID_1))
    val message = PeriodicPingMessage.parseFrom(captor.firstValue)
    assertThat(message.messageType).isEqualTo(MessageType.ACK)
  }

  /** Creates a mock [Car] with a random [UUID]. */
  private fun createMockCar(
    mockMessageId: Int = MESSAGE_ID,
    mockDeviceId: UUID = DEVICE_ID_1
  ): Car = mock {
    on { deviceId } doReturn mockDeviceId
    on { sendMessage(any(), eq(PeriodicPingManager.FEATURE_ID)) } doReturn mockMessageId
  }

  private fun createPingMessage() =
    PeriodicPingMessage.newBuilder().setMessageType(MessageType.PING).build().toByteArray()

  private fun createUnknownMessage() =
    PeriodicPingMessage.newBuilder().setMessageType(MessageType.UNKNOWN).build().toByteArray()

  companion object {
    private val DEVICE_ID_1 = UUID.randomUUID()
    private val DEVICE_ID_2 = UUID.randomUUID()
    private const val MESSAGE_ID = 1
  }
}
