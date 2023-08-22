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
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.companionprotos.outOfBandAssociationToken
import com.google.android.libraries.car.trustagent.blemessagestream.SppManager
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class BluetoothRfcommChannelTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testBluetoothDevice =
    context
      .getSystemService(BluetoothManager::class.java)
      .adapter
      .getRemoteDevice("00:11:22:33:AA:BB")
  private val testDispatcher = UnconfinedTestDispatcher()
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
    bluetoothRfcommChannel =
      BluetoothRfcommChannel(true, testDispatcher).apply { callback = mockCallback }
    shadowOf(BluetoothAdapter.getDefaultAdapter()).setBondedDevices(setOf(testBluetoothDevice))
  }

  @Test
  fun startOobDiscoveryAndDataExchange_oobProto_success() {
    startOobExchangeAndDiscovery(bluetoothRfcommChannel, testOobProtoData)
    verify(mockCallback).onSuccess(any())
  }

  @Test
  fun startOobDiscoveryAndDataExchange_rawBytes_success() {
    val bluetoothRfcommChannel =
      BluetoothRfcommChannel(false, testDispatcher).apply { callback = mockCallback }
    startOobExchangeAndDiscovery(bluetoothRfcommChannel, testOobRawBytes)
    verify(mockCallback).onSuccess(any())
  }

  @Test
  fun startOobDiscoveryAndDataExchange_stopOobExchangeDuringConnection() {
    val oobDiscovery =
      CoroutineScope(backgroundContext).launch {
        // startOobDataExchange blocks, so run it as a background job.
        bluetoothRfcommChannel.startOobDataExchange(testBluetoothDevice)
      }

    // bluetoothSocket not being null means the background thread is about to establish connection.
    while (bluetoothRfcommChannel.bluetoothSocket == null) {
      Thread.sleep(Duration.ofMillis(100L).toMillis())
    }
    shadowOf(bluetoothRfcommChannel.bluetoothSocket)?.getInputStreamFeeder()?.close()
    bluetoothRfcommChannel.stopOobDataExchange()

    assertThat(bluetoothRfcommChannel.bluetoothSocket).isNull()

    runBlocking {
      oobDiscovery.join()
      verifyNoMoreInteractions(mockCallback)
    }
  }

  @Test
  fun startOobDiscoveryAndDattaExchange_noBondedDevices() {
    shadowOf(BluetoothAdapter.getDefaultAdapter()).setBondedDevices(emptySet())
    runBlocking {
      bluetoothRfcommChannel.startOobDataExchange(testBluetoothDevice)

      verify(mockCallback).onFailure()
    }
  }

  @Test
  fun startOobDiscoveryAndDataExchange_readOobDataFails() {
    val oobDiscovery =
      CoroutineScope(backgroundContext).launch {
        // startOobDataExchange blocks, so run it as a background job.
        bluetoothRfcommChannel.startOobDataExchange(testBluetoothDevice)
      }

    // bluetoothSocket not being null means the background thread is about to establish connection.
    while (bluetoothRfcommChannel.bluetoothSocket == null) {
      Thread.sleep(Duration.ofMillis(100L).toMillis())
    }

    shadowOf(bluetoothRfcommChannel.bluetoothSocket)?.getInputStreamFeeder()?.close()

    runBlocking {
      oobDiscovery.join()
      verify(mockCallback).onFailure()
    }
  }

  private fun startOobExchangeAndDiscovery(channel: BluetoothRfcommChannel, oobData: ByteArray) {
    // startOobDataExchange blocks, so run it as a background job.
    val oobDiscovery =
      CoroutineScope(backgroundContext).launch { channel.startOobDataExchange(testBluetoothDevice) }

    // bluetoothSocket not being null means the background thread is about to establish connection.
    while (channel.bluetoothSocket == null) {
      Thread.sleep(Duration.ofMillis(100L).toMillis())
    }

    // Write OOB data.
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
    // The actual length of these bytes doesn't matter to this class.
    private const val TOKEN_SIZE = 5
  }
}
