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

import android.bluetooth.BluetoothAdapter
import android.os.Looper
import com.google.android.libraries.car.trustagent.blemessagestream.SppManager
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyZeroInteractions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class BluetoothRfcommChannelTest {

  private val testDispatcher = TestCoroutineDispatcher()
  private val backgroundContext: CoroutineDispatcher =
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  private val mockCallback: OobChannel.Callback = mock()

  private lateinit var bluetoothRfcommChannel: BluetoothRfcommChannel

  @Before
  fun setUp() {
    // Duration of 1 second is arbitrary.
    bluetoothRfcommChannel =
      BluetoothRfcommChannel(Duration.ofSeconds(1), testDispatcher, GATT_UUID, SPP_UUID)
    shadowOf(BluetoothAdapter.getDefaultAdapter())
      .setBondedDevices(setOf(ShadowBluetoothDevice.newInstance("00:11:22:AA:BB:CC")))
  }

  @Test
  fun startOobDiscoveryAndDataExchange_success() {
    startOobExchangeAndDiscovery()
    verify(mockCallback).onOobExchangeSuccess(any())
  }

  @Test
  fun startOobDiscoveryAndDataExchange_stopConnectionDuringAccept() {
    val oobDiscovery =
      CoroutineScope(backgroundContext).launch {
        bluetoothRfcommChannel.startOobDataExchange(OobConnectionManager(), mockCallback)
      }

    while (bluetoothRfcommChannel.bluetoothServerSocket == null) {
      Thread.sleep(Duration.ofMillis(100L).toMillis())
    }

    bluetoothRfcommChannel.stopOobDataExchange()

    runBlocking { oobDiscovery.join() }

    assertThat(bluetoothRfcommChannel.bluetoothServerSocket).isNull()
    assertThat(bluetoothRfcommChannel.bluetoothSocket).isNull()
    verifyZeroInteractions(mockCallback)
  }

  @Test
  fun startOobDiscoveryAndDattaExchange_noBondedDevices() {
    shadowOf(BluetoothAdapter.getDefaultAdapter()).setBondedDevices(emptySet())
    runBlocking {
      bluetoothRfcommChannel.startOobDataExchange(OobConnectionManager(), mockCallback)

      verify(mockCallback).onOobExchangeFailure()
    }
  }

  @Test
  fun startOobDiscoveryAndDataExchange_failToSetOobData() {
    startOobExchangeAndDiscovery(oobData = ByteArray(OobConnectionManager.DATA_LENGTH_BYTES))
    verify(mockCallback).onOobExchangeFailure()
  }

  @Test
  fun startOobDiscoveryAndDataExchange_acceptSocketFails() {
    runBlocking {
      bluetoothRfcommChannel.startOobDataExchange(OobConnectionManager(), mockCallback)
      // There is no remote device to connect to, so bluetoothServerSocket.accept will time out.
      shadowOf(Looper.getMainLooper()).runToEndOfTasks()

      verify(mockCallback).onOobExchangeFailure()
    }
  }

  @Test
  fun startOobDiscoveryAndDataExchange_tokenIsWrongLength() {
    startOobExchangeAndDiscovery(tokenLength = 13)
    verify(mockCallback).onOobExchangeFailure()
  }

  @Test
  fun startOobDiscoveryAndDataExchange_passesNonNullOobManager() {
    bluetoothRfcommChannel =
      BluetoothRfcommChannel(Duration.ofSeconds(1), testDispatcher, GATT_UUID, SPP_UUID)
    startOobExchangeAndDiscovery()

    argumentCaptor<DiscoveredCar>().apply {
      verify(mockCallback).onOobExchangeSuccess(capture())

      val discoveredCar = firstValue

      assertThat(discoveredCar.oobConnectionManager).isNotNull()
    }
  }

  private fun startOobExchangeAndDiscovery(
    tokenLength: Int = OobConnectionManager.DATA_LENGTH_BYTES,
    oobData: ByteArray = ByteArray(tokenLength).apply { SecureRandom().nextBytes(this) }
  ) {
    // startOobDataExchange blocks, so run it as a background job.
    val oobDiscovery =
      CoroutineScope(backgroundContext).launch {
        bluetoothRfcommChannel.startOobDataExchange(OobConnectionManager(), mockCallback)
      }

    // bluetoothServerSocket not being null means the background thread is about to accept sockets.
    while (bluetoothRfcommChannel.bluetoothServerSocket == null) {
      Thread.sleep(Duration.ofMillis(100L).toMillis())
    }

    // Connect the socket.
    shadowOf(bluetoothRfcommChannel.bluetoothServerSocket)?.let {
      val bluetoothDevice = ShadowBluetoothDevice.newInstance("00:11:22:AA:BB:CC")
      shadowOf(bluetoothDevice).setName("bluetoothDevice")
      it.deviceConnected(bluetoothDevice)
    }

    // Write OOB data.
    while (bluetoothRfcommChannel.bluetoothSocket == null) {
      Thread.sleep(Duration.ofMillis(100L).toMillis())
    }

    shadowOf(bluetoothRfcommChannel.bluetoothSocket)?.let {
      val outputStream = it.getInputStreamFeeder()
      outputStream.write(
        ByteBuffer.allocate(SppManager.LENGTH_BYTES_SIZE)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putInt(tokenLength)
          .array()
      )
      outputStream.write(oobData)
    }

    // Wait for background job to finish.
    runBlocking { oobDiscovery.join() }
  }

  companion object {
    // Randomly generated value.
    private val GATT_UUID = UUID.fromString("99dc5e5f-5dd1-49bb-8c28-5579ba43d1a9")
    private val SPP_UUID = UUID.fromString("7bf92b05-ff1b-47a2-8a2b-d2542537a499")
    private val TEST_KEY = KeyGenerator.getInstance("AES").generateKey()
  }
}
