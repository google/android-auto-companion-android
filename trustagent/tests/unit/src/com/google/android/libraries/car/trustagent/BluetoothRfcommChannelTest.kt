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
import com.google.android.companionprotos.outOfBandAssociationToken
import com.google.android.libraries.car.trustagent.blemessagestream.SppManager
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.nhaarman.mockitokotlin2.any
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
  private val testOobProtoData =
    outOfBandAssociationToken {
        encryptionKey =
          ByteString.copyFrom(ByteArray(TOKEN_SIZE).apply { SecureRandom().nextBytes(this) })
        ihuIv = ByteString.copyFrom(ByteArray(TOKEN_SIZE).apply { SecureRandom().nextBytes(this) })
        mobileIv =
          ByteString.copyFrom(ByteArray(TOKEN_SIZE).apply { SecureRandom().nextBytes(this) })
      }
      .toByteArray()

  private val testOobRawBytes =
    ByteArray(DATA_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }

  private lateinit var bluetoothRfcommChannel: BluetoothRfcommChannel

  @Before
  fun setUp() {
    // Duration of 1 second is arbitrary.
    bluetoothRfcommChannel =
      BluetoothRfcommChannel(Duration.ofSeconds(1), true, testDispatcher).apply {
        callback = mockCallback
      }
    shadowOf(BluetoothAdapter.getDefaultAdapter())
      .setBondedDevices(setOf(ShadowBluetoothDevice.newInstance("00:11:22:AA:BB:CC")))
  }

  @Test
  fun startOobDiscoveryAndDataExchange_oobProto_success() {
    startOobExchangeAndDiscovery(bluetoothRfcommChannel, testOobProtoData)
    verify(mockCallback).onSuccess(any())
  }

  @Test
  fun startOobDiscoveryAndDataExchange_rawBytes_success() {
    val bluetoothRfcommChannel =
      BluetoothRfcommChannel(Duration.ofSeconds(1), false, testDispatcher).apply {
        callback = mockCallback
      }
    startOobExchangeAndDiscovery(bluetoothRfcommChannel, testOobRawBytes)
    verify(mockCallback).onSuccess(any())
  }

  @Test
  fun startOobDiscoveryAndDataExchange_stopConnectionDuringAccept() {
    val oobDiscovery =
      CoroutineScope(backgroundContext).launch { bluetoothRfcommChannel.startOobDataExchange() }

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
      bluetoothRfcommChannel.startOobDataExchange()

      verify(mockCallback).onFailure()
    }
  }

  @Test
  fun startOobDiscoveryAndDataExchange_acceptSocketFails() {
    runBlocking {
      bluetoothRfcommChannel.startOobDataExchange()
      // There is no remote device to connect to, so bluetoothServerSocket.accept will time out.
      shadowOf(Looper.getMainLooper()).runToEndOfTasks()

      verify(mockCallback).onFailure()
    }
  }

  private fun startOobExchangeAndDiscovery(channel: BluetoothRfcommChannel, oobData: ByteArray) {
    // startOobDataExchange blocks, so run it as a background job.
    val oobDiscovery = CoroutineScope(backgroundContext).launch { channel.startOobDataExchange() }

    // bluetoothServerSocket not being null means the background thread is about to accept sockets.
    while (channel.bluetoothServerSocket == null) {
      Thread.sleep(Duration.ofMillis(100L).toMillis())
    }

    // Connect the socket.
    shadowOf(channel.bluetoothServerSocket)?.let {
      val bluetoothDevice = ShadowBluetoothDevice.newInstance("00:11:22:AA:BB:CC")
      shadowOf(bluetoothDevice).setName("bluetoothDevice")
      it.deviceConnected(bluetoothDevice)
    }

    // Write OOB data.
    while (channel.bluetoothSocket == null) {
      Thread.sleep(Duration.ofMillis(100L).toMillis())
    }

    shadowOf(channel.bluetoothSocket)?.let {
      val outputStream = it.getInputStreamFeeder()
      outputStream.write(
        ByteBuffer.allocate(SppManager.LENGTH_BYTES_SIZE)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putInt(oobData.size)
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

    // The actual length of these bytes doesn't matter to this class.
    private const val TOKEN_SIZE = 5
  }
}
