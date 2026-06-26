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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.testutils.Base64CryptoHelper
import com.google.android.libraries.car.trustagent.testutils.FakeSecretKey
import com.google.android.libraries.car.trustagent.testutils.createScanRecord
import com.google.android.libraries.car.trustagent.testutils.createScanResult
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class ConnectionManagerTest {

  private lateinit var connectionManager: ConnectionManager

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val bluetoothAdapter: BluetoothAdapter =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

  private lateinit var connectionCallback: ConnectionManager.ConnectionCallback

  private lateinit var database: ConnectedCarDatabase
  private lateinit var associatedCarManager: AssociatedCarManager

  @Before
  fun setUp() {
    // Using directExecutor to ensure that all operations happen on the main thread and allows for
    // tests to wait until the operations are done before continuing. Without this, operations can
    // leak and interfere between tests. See b/153095973 for details.
    database =
      Room.inMemoryDatabaseBuilder(context, ConnectedCarDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(directExecutor())
        .build()
    associatedCarManager = AssociatedCarManager(context, database, Base64CryptoHelper())

    connectionCallback = mock()

    connectionManager =
      ConnectionManager(context, SERVICE_UUID, associatedCarManager, directExecutor()).apply {
        registerConnectionCallback(connectionCallback)
      }
  }

  @After
  fun cleanUp() {
    database.close()
  }

  @Test
  fun shouldConnect_noAssociatedCars_shouldNotConnect() {
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData = emptyMap(),
      )

    val fakeScanResult = createScanResult(fakeScanRecord)
    val associatedCars = emptyList<AssociatedCar>()

    assertThat(connectionManager.shouldConnect(fakeScanResult, associatedCars)).isFalse()
  }

  @Test
  fun shouldConnect_noAdvertisedData_shouldConnect() {
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData = emptyMap(),
      )

    val fakeAssociatedCar = AssociatedCar(DEVICE_ID, "NAME", "MAC_ADDRESS", FakeSecretKey())

    val fakeScanResult = createScanResult(fakeScanRecord)
    val associatedCars = listOf(fakeAssociatedCar)

    assertThat(connectionManager.shouldConnect(fakeScanResult, associatedCars)).isTrue()
  }

  @Test
  fun shouldConnect_advertisedDataDoesNotMatch_shouldNotConnect() {
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData =
          mapOf(
            ConnectionManager.V2_DATA_UUID to
              ByteArray(PendingCarV2Reconnection.ADVERTISED_DATA_SIZE_BYTES)
          ),
      )
    val fakeAssociatedCar = AssociatedCar(DEVICE_ID, "NAME", "MAC_ADDRESS", FakeSecretKey())

    val fakeScanResult = createScanResult(fakeScanRecord)
    val associatedCars = listOf(fakeAssociatedCar)

    assertThat(connectionManager.shouldConnect(fakeScanResult, associatedCars)).isFalse()
  }

  @Test
  fun shouldConnect_advertisedDataLengthDoesNotMatchExpectation_shouldNotConnect() {
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData =
          mapOf(
            // Send advertised data of lenth (1) that doesn't match expected value.
            ConnectionManager.V2_DATA_UUID to ByteArray(1)
          ),
      )
    val fakeAssociatedCar = AssociatedCar(DEVICE_ID, "NAME", "MAC_ADDRESS", FakeSecretKey())

    val fakeScanResult = createScanResult(fakeScanRecord)
    val associatedCars = listOf(fakeAssociatedCar)

    assertThat(connectionManager.shouldConnect(fakeScanResult, associatedCars)).isFalse()
  }

  @Test
  fun resolveVersion_bluetoothDisconnects_returnsNull() {
    runBlocking {
      val mockBluetoothManager = mock<BluetoothConnectionManager>()
      val device = bluetoothAdapter.getRemoteDevice("00:11:22:33:44:55")
      whenever(mockBluetoothManager.bluetoothDevice).thenReturn(device)
      whenever(mockBluetoothManager.sendMessage(anyOrNull())).thenReturn(true)

      val deferredResult = async { connectionManager.resolveVersion(mockBluetoothManager) }

      yield()

      val connectionCallbackCaptor = argumentCaptor<BluetoothConnectionManager.ConnectionCallback>()
      verify(mockBluetoothManager).registerConnectionCallback(connectionCallbackCaptor.capture())
      val capturedConnectionCallback = connectionCallbackCaptor.firstValue

      val messageCallbackCaptor = argumentCaptor<BluetoothConnectionManager.MessageCallback>()
      verify(mockBluetoothManager).registerMessageCallback(messageCallbackCaptor.capture())
      val messageCallback = messageCallbackCaptor.firstValue

      capturedConnectionCallback.onDisconnected()
      messageCallback.onMessageReceived(ByteArray(0))

      val result = deferredResult.await()
      assertThat(result).isNull()
      verify(connectionCallback).onConnectionFailed(device, 0)
    }
  }

  @Test
  fun resolveVersion_bluetoothOnConnected_returnsNull() {
    runBlocking {
      val mockBluetoothManager = mock<BluetoothConnectionManager>()
      val device = bluetoothAdapter.getRemoteDevice("00:11:22:33:44:55")
      whenever(mockBluetoothManager.bluetoothDevice).thenReturn(device)
      whenever(mockBluetoothManager.sendMessage(anyOrNull())).thenReturn(true)

      val deferredResult = async { connectionManager.resolveVersion(mockBluetoothManager) }

      yield()

      val connectionCallbackCaptor = argumentCaptor<BluetoothConnectionManager.ConnectionCallback>()
      verify(mockBluetoothManager).registerConnectionCallback(connectionCallbackCaptor.capture())
      val capturedConnectionCallback = connectionCallbackCaptor.firstValue

      val messageCallbackCaptor = argumentCaptor<BluetoothConnectionManager.MessageCallback>()
      verify(mockBluetoothManager).registerMessageCallback(messageCallbackCaptor.capture())
      val messageCallback = messageCallbackCaptor.firstValue

      capturedConnectionCallback.onConnected()
      messageCallback.onMessageReceived(ByteArray(0))

      val result = deferredResult.await()
      assertThat(result).isNull()
      verify(connectionCallback).onConnectionFailed(device, 0)
    }
  }

  @Test
  fun resolveVersion_bluetoothOnConnectionFailed_returnsNull() {
    runBlocking {
      val mockBluetoothManager = mock<BluetoothConnectionManager>()
      val device = bluetoothAdapter.getRemoteDevice("00:11:22:33:44:55")
      whenever(mockBluetoothManager.bluetoothDevice).thenReturn(device)
      whenever(mockBluetoothManager.sendMessage(anyOrNull())).thenReturn(true)

      val deferredResult = async { connectionManager.resolveVersion(mockBluetoothManager) }

      yield()

      val connectionCallbackCaptor = argumentCaptor<BluetoothConnectionManager.ConnectionCallback>()
      verify(mockBluetoothManager).registerConnectionCallback(connectionCallbackCaptor.capture())
      val capturedConnectionCallback = connectionCallbackCaptor.firstValue

      val messageCallbackCaptor = argumentCaptor<BluetoothConnectionManager.MessageCallback>()
      verify(mockBluetoothManager).registerMessageCallback(messageCallbackCaptor.capture())
      val messageCallback = messageCallbackCaptor.firstValue

      capturedConnectionCallback.onConnectionFailed(5)
      messageCallback.onMessageReceived(ByteArray(0))

      val result = deferredResult.await()
      assertThat(result).isNull()
      verify(connectionCallback).onConnectionFailed(device, 5)
    }
  }

  @Test
  fun connect_timeout_callsDisconnectAndNotifiesCallback() =
    runTest(UnconfinedTestDispatcher()) {
      val mockBluetoothManager = mock<BluetoothConnectionManager>()
      val device = bluetoothAdapter.getRemoteDevice("00:11:22:33:44:55")
      whenever(mockBluetoothManager.bluetoothDevice).thenReturn(device)

      val job = launch { connectionManager.connect(mockBluetoothManager, null) }

      // Wait for the timeout to trigger and the job to complete
      job.join()

      verify(mockBluetoothManager).disconnect()
      verify(connectionCallback).onConnectionFailed(device, 0)
    }

  companion object {
    private val SERVICE_UUID = UUID.fromString("8a16e891-d4ad-455d-8194-cbc2dfbaebdf")
    private val DEVICE_ID = UUID.fromString("a99d8e3c-77bc-427d-a4fa-744d6b84a4cd")
  }
}
