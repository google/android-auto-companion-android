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
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow

@RunWith(AndroidJUnit4::class)
class SppManagerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val mockConnectionCallback: BluetoothConnectionManager.ConnectionCallback = mock()
  private val mockMessageCallback: BluetoothConnectionManager.MessageCallback = mock()
  private val shadowBluetoothSocket = Shadow.newInstanceOf(BluetoothSocket::class.java)

  private lateinit var sppManager: SppManager

  @Before
  fun setUp() {
    shadowBluetoothSocket.connect()
    sppManager =
      SppManager(context, Shadow.newInstanceOf(BluetoothDevice::class.java), SPP_UUID).apply {
        registerConnectionCallback(mockConnectionCallback)
        registerMessageCallback(mockMessageCallback)
      }
  }

  @Test
  fun testConnect_initConnectTask() {
    sppManager.connect()

    assertThat(sppManager.connectTask).isNotNull()
  }
  @Test
  fun testConnectTaskCallback_onConnectionSuccess_initReadMessageTask() {
    sppManager.connectTaskCallback.onConnectionSuccess(shadowBluetoothSocket)

    assertThat(sppManager.readMessageTask).isNotNull()
    verify(mockConnectionCallback).onConnected()
  }

  @Test
  fun testConnectTaskCallback_onConnectionAttemptFailed_informConnectionCallback() {
    sppManager.connectTaskCallback.onConnectionAttemptFailed()

    verify(mockConnectionCallback).onConnectionFailed()
  }

  @Test
  fun testReadMessageTaskCallback_onMessageReceived_callOnMessageReceivedCallback() {
    sppManager.readMessageTaskCallback.onMessageReceived(TEST_DATA)

    verify(mockMessageCallback).onMessageReceived(TEST_DATA)
  }

  @Test
  fun testReadMessageTaskCallback_onMessageReadError_informConnectionCallback() {
    sppManager.connectTaskCallback.onConnectionSuccess(shadowBluetoothSocket)
    sppManager.readMessageTaskCallback.onMessageReadError()

    assertThat(shadowBluetoothSocket.isConnected).isFalse()
  }

  @Test
  fun testWrite_informOnMessageSentCallback() {
    sppManager.writeExecutor = directExecutor()
    sppManager.connectTaskCallback.onConnectionSuccess(shadowBluetoothSocket)

    sppManager.sendMessage(TEST_DATA)

    verify(mockMessageCallback).onMessageSent(TEST_DATA)
  }

  companion object {
    private val TEST_DATA = "TestData".toByteArray()
    // Randomly generated value.
    private val SPP_UUID = UUID.fromString("24e5b135-6db4-4641-b1fa-81c2725399cd")
  }
}
