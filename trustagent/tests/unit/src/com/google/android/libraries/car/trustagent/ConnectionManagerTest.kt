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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class ConnectionManagerTest {

  private lateinit var connectionManager: ConnectionManager

  private val context = ApplicationProvider.getApplicationContext<Context>()

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
      ConnectionManager(
          context,
          SERVICE_UUID,
          associatedCarManager,
          directExecutor(),
        )
        .apply { registerConnectionCallback(connectionCallback) }
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
        serviceData = emptyMap()
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
        serviceData = emptyMap()
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
          )
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
          )
      )
    val fakeAssociatedCar = AssociatedCar(DEVICE_ID, "NAME", "MAC_ADDRESS", FakeSecretKey())

    val fakeScanResult = createScanResult(fakeScanRecord)
    val associatedCars = listOf(fakeAssociatedCar)

    assertThat(connectionManager.shouldConnect(fakeScanResult, associatedCars)).isFalse()
  }

  @Test
  fun resolveVersion_bluetoothDisconnects_onConnectionFailed() {
    runBlocking {
      val mockBluetoothManager = mock<BluetoothConnectionManager>()

      connectionManager.resolveVersion(mockBluetoothManager)
      val bluetoothCallback =
        argumentCaptor<BluetoothConnectionManager.ConnectionCallback>().run {
          verify(mockBluetoothManager).registerConnectionCallback(capture())
          firstValue
        }
      bluetoothCallback.onConnectionFailed()

      verify(connectionCallback).onConnectionFailed(anyOrNull())
    }
  }

  @Test
  fun resolveVersion_bluetoothOnConnected_onConnectionFailed() {
    runBlocking {
      val mockBluetoothManager = mock<BluetoothConnectionManager>()

      connectionManager.resolveVersion(mockBluetoothManager)
      val bluetoothCallback =
        argumentCaptor<BluetoothConnectionManager.ConnectionCallback>().run {
          verify(mockBluetoothManager).registerConnectionCallback(capture())
          firstValue
        }
      bluetoothCallback.onConnected()

      verify(connectionCallback).onConnectionFailed(anyOrNull())
    }
  }

  @Test
  fun resolveVersion_bluetoothOnConnectionFailed_onConnectionFailed() {
    runBlocking {
      val mockBluetoothManager = mock<BluetoothConnectionManager>()

      connectionManager.resolveVersion(mockBluetoothManager)
      val bluetoothCallback =
        argumentCaptor<BluetoothConnectionManager.ConnectionCallback>().run {
          verify(mockBluetoothManager).registerConnectionCallback(capture())
          firstValue
        }
      bluetoothCallback.onConnectionFailed()

      verify(connectionCallback).onConnectionFailed(anyOrNull())
    }
  }

  companion object {
    private val SERVICE_UUID = UUID.fromString("8a16e891-d4ad-455d-8194-cbc2dfbaebdf")
    private val DEVICE_ID = UUID.fromString("a99d8e3c-77bc-427d-a4fa-744d6b84a4cd")
  }
}
