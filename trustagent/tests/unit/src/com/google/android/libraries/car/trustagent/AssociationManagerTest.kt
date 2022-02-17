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

import android.Manifest.permission
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest as CdmAssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattManager
import com.google.android.libraries.car.trustagent.testutils.Base64CryptoHelper
import com.google.android.libraries.car.trustagent.testutils.FakeMessageStream
import com.google.android.libraries.car.trustagent.testutils.FakeSecretKey
import com.google.android.libraries.car.trustagent.testutils.createScanRecord
import com.google.android.libraries.car.trustagent.testutils.createScanResult
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.inOrder
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import java.util.UUID
import kotlin.random.Random
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothDevice

// Randomly generated UUID.
private val TEST_UUID = UUID.fromString("8a16e891-d4ad-455d-8194-cbc2dfbaebdf")
private val TEST_DEVICE_ID = UUID.randomUUID()
private val TEST_PREFIX = "TestPrefix"
private val TEST_MACADDRESS = "00:11:22:AA:BB:CC"
private val TEST_BLUETOOTH_DEVICE =
  BluetoothAdapter.getDefaultAdapter().getRemoteDevice(TEST_MACADDRESS)
private val TEST_IDENTIFICATION_KEY = FakeSecretKey()
private val TEST_STREAM = FakeMessageStream()
private val SHORT_LOCAL_NAME = "name"
private val SERVICE_DATA = Random.nextBytes(ByteArray(2))

/** Tests for [AssociationManager]. */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class AssociationManagerTest {

  @get:Rule
  val grantPermissionRule: GrantPermissionRule =
    GrantPermissionRule.grant(
      permission.ACCESS_FINE_LOCATION,
      permission.ACCESS_BACKGROUND_LOCATION,
      permission.BLUETOOTH_SCAN,
      permission.BLUETOOTH_CONNECT,
    )

  private val testDispatcher = TestCoroutineDispatcher()
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var database: ConnectedCarDatabase
  private lateinit var bleManager: FakeBleManager
  private lateinit var associationManager: AssociationManager
  private lateinit var associatedCarManager: AssociatedCarManager
  private lateinit var discoveryCallback: AssociationManager.DiscoveryCallback
  private lateinit var associationCallback: AssociationManager.AssociationCallback
  private lateinit var disassociationCallback: AssociationManager.DisassociationCallback
  private lateinit var car: Car
  private lateinit var bluetoothGattManager: BluetoothGattManager
  private lateinit var associationHandler: TestAssociationHandler

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

    bleManager = spy(FakeBleManager())
    discoveryCallback = mock()
    associationCallback = mock()
    disassociationCallback = mock()
    bluetoothGattManager = mock { on { bluetoothDevice } doReturn TEST_BLUETOOTH_DEVICE }

    car = Car(bluetoothGattManager, TEST_STREAM, TEST_IDENTIFICATION_KEY, TEST_DEVICE_ID)

    associationManager =
      AssociationManager(context, associatedCarManager, bleManager).apply {
        registerDiscoveryCallback(discoveryCallback)
        registerAssociationCallback(associationCallback)
        registerDisassociationCallback(disassociationCallback)
      }

    associationHandler =
      spy(TestAssociationHandler()).also { associationManager.associationHandler = it }
    associationManager.coroutineContext = testDispatcher
  }

  @After
  fun after() {
    database.close()
    testDispatcher.cleanupTestCoroutines()
  }

  @Test
  fun testStartDiscovery_notEnabled_doesNotStartScan() {
    bleManager.isEnabled = false
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isFalse()
    verify(bleManager, never()).startScan(any(), any(), any())
  }

  @Test
  fun testStartDiscovery_failedScan_returnsFalse() {
    bleManager.startScanSucceeds = false
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isFalse()
  }

  @Test
  fun testStartDiscovery_startsScanWithPassedUuid() {
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isTrue()

    argumentCaptor<List<ScanFilter>>().apply {
      verify(bleManager).startScan(capture(), any(), any())

      val filters = firstValue
      assertThat(filters).hasSize(1)

      val scanFilter = filters.first()
      assertThat(scanFilter.serviceUuid).isEqualTo(ParcelUuid(TEST_UUID))
    }
  }

  @Test
  fun testStartDiscovery_stopsPreviousScan() {
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isTrue()

    argumentCaptor<ScanCallback>().apply {
      verify(bleManager).startScan(any(), any(), capture())
      verify(bleManager).stopScan(capture())

      assertThat(firstValue).isEqualTo(secondValue)
    }
  }

  @Test
  fun onScanResult_returnsCorrectDiscoveredCar() {
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isTrue()

    val serviceData = hashMapOf(AssociationManager.DEVICE_NAME_DATA_UUID to SERVICE_DATA)

    val scanRecord =
      createScanRecord(name = null, serviceUuids = listOf(TEST_UUID), serviceData = serviceData)
    val scanResult = createScanResult(scanRecord)
    bleManager.triggerOnScanResult(scanResult)

    val expectedCar =
      DiscoveredCar(scanResult.device, "$TEST_PREFIX${SERVICE_DATA.toHexString()}", TEST_UUID, null)
    verify(discoveryCallback).onDiscovered(expectedCar)
  }

  @Test
  fun onScanResult_returnScanRecordName_ifServiceDataDoesNotHaveName() {
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isTrue()

    val scanRecord =
      createScanRecord(
        name = SHORT_LOCAL_NAME,
        serviceUuids = listOf(TEST_UUID),
        serviceData = emptyMap()
      )

    val scanResult = createScanResult(scanRecord)
    bleManager.triggerOnScanResult(scanResult)

    val expectedCar = DiscoveredCar(scanResult.device, SHORT_LOCAL_NAME, TEST_UUID, null)
    verify(discoveryCallback).onDiscovered(expectedCar)
  }

  @Test
  fun onScanResult_doesNotInvokeCallback_ifWrongServiceUuid() {
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isTrue()

    val invalidUuid = UUID.fromString("b8695c70-442d-4113-9893-7ef7565940a0")

    val serviceData =
      hashMapOf(AssociationManager.DEVICE_NAME_DATA_UUID to "deviceName".toByteArray())
    val scanRecord =
      createScanRecord(name = null, serviceUuids = listOf(invalidUuid), serviceData = serviceData)
    val scanResult = createScanResult(scanRecord)
    bleManager.triggerOnScanResult(scanResult)

    verify(discoveryCallback, never()).onDiscovered(any())
  }

  @Test
  fun onScanResult_doesNotInvokeCallback_ifMultipleServiceUuids() {
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isTrue()

    val invalidUuid = UUID.fromString("b8695c70-442d-4113-9893-7ef7565940a0")

    val serviceData =
      hashMapOf(AssociationManager.DEVICE_NAME_DATA_UUID to "deviceName".toByteArray())
    val scanRecord =
      createScanRecord(
        name = null,
        serviceUuids = listOf(TEST_UUID, invalidUuid),
        serviceData = serviceData
      )

    val scanResult = createScanResult(scanRecord)
    bleManager.triggerOnScanResult(scanResult)

    verify(discoveryCallback, never()).onDiscovered(any())
  }

  @Test
  fun onScanResult_doesNotInvokeCallback_ifNullScanResult() {
    assertThat(associationManager.startDiscovery(TEST_UUID, TEST_PREFIX)).isTrue()
    bleManager.triggerOnScanResult(null)
    verify(discoveryCallback, never()).onDiscovered(any())
  }

  @Test
  fun startSppDiscovery_bluetoothDisabled_returnsFalse() {
    bleManager.isEnabled = false

    assertThat(associationManager.startSppDiscovery()).isFalse()
    assertThat(associationManager.isSppDiscoveryStarted).isFalse()
  }

  @Test
  fun startSppDiscovery_startedSuccessfully_updateFlag() {
    bleManager.isEnabled = true

    assertThat(associationManager.startSppDiscovery()).isTrue()
    assertThat(associationManager.isSppDiscoveryStarted).isTrue()
  }

  @Test
  fun associate_onAssociationFailed() {
    val mockBluetoothManager =
      mock<BluetoothConnectionManager> { onBlocking { connectToDevice() } doReturn false }
    val mockDiscoveredCar =
      mock<DiscoveredCar> {
        on { toBluetoothConnectionManagers(any()) } doReturn listOf(mockBluetoothManager)
      }

    associationManager.associate(mockDiscoveredCar)

    verify(associationCallback).onAssociationFailed()
  }

  @Test
  fun associate_onAssociationStart() {
    val mockBluetoothManager =
      mock<BluetoothConnectionManager> { onBlocking { connectToDevice() } doReturn true }
    val mockDiscoveredCar =
      mock<DiscoveredCar> {
        on { toBluetoothConnectionManagers(any()) } doReturn listOf(mockBluetoothManager)
      }

    associationManager.associate(mockDiscoveredCar)

    verify(associationCallback).onAssociationStart()
  }

  @Test
  fun associate_stopSppDiscovery() {
    assertThat(associationManager.startSppDiscovery()).isTrue()

    associationManager.coroutineContext = testDispatcher
    val deviceName = SHORT_LOCAL_NAME
    val scanRecord =
      createScanRecord(
        name = deviceName,
        serviceUuids = listOf(TEST_UUID),
        serviceData = emptyMap()
      )

    val scanResult = createScanResult(scanRecord)
    // SPP UUID does not matter here.
    val testDiscoveredCar =
      DiscoveredCar(scanResult.device, deviceName, TEST_UUID, sppServiceUuid = null)

    associationManager.associate(testDiscoveredCar)

    assertThat(associationManager.isSppDiscoveryStarted).isFalse()
  }

  @Test
  fun clearCurrentAssociation_stopSppDiscovery() {
    assertThat(associationManager.startSppDiscovery()).isTrue()

    associationManager.clearCurrentAssociation()

    assertThat(associationManager.isSppDiscoveryStarted).isFalse()
  }

  @Test
  fun clearCurrentCdmAssociation_disassociateCdm() {
    val macAddress = "00:11:22:33:AA:BB"
    val bluetoothDevice = ShadowBluetoothDevice.newInstance(macAddress)
    val shadowBluetoothDevice = Shadow.extract<ShadowBluetoothDevice>(bluetoothDevice)
    shadowBluetoothDevice.setName("testName")
    val request =
      AssociationRequest.Builder(
          Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, bluetoothDevice)
        )
        .build()

    associationManager.associate(request)
    associationManager.clearCurrentCdmAssociation()

    verify(associationManager.associationHandler).disassociate(macAddress)
  }

  @Test
  fun clearCurrentCdmAssociation_associationCompleted_doNotClearDevice() {
    val macAddress = "00:11:22:33:AA:BB"
    val bluetoothDevice = ShadowBluetoothDevice.newInstance(macAddress)
    val shadowBluetoothDevice = Shadow.extract<ShadowBluetoothDevice>(bluetoothDevice)
    shadowBluetoothDevice.setName("testName")
    val request =
      AssociationRequest.Builder(
          Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, bluetoothDevice)
        )
        .build()

    associationManager.associate(request)
    associationManager.pendingCarCallback.onConnected(car)
    associationManager.clearCurrentCdmAssociation()

    verify(associationManager.associationHandler, never()).disassociate(macAddress)
  }

  @Test
  fun clearCdmAssociatedCar_disassociateCdm() {
    runBlocking {
      associatedCarManager.add(car)
      associationManager.clearCdmAssociatedCar(TEST_DEVICE_ID)
      verify(associationManager.associationHandler).disassociate(TEST_MACADDRESS)
    }
  }

  @Test
  fun clearAllAssociatedCars_disassociateCdm() {
    runBlocking {
      associationManager.clearAllCdmAssociatedCars()

      verify(associationManager.associationHandler).disassociate(TEST_MACADDRESS)
    }
  }

  @Test
  fun testStartCdmDiscovery_doesNotIncludeSppDeviceFilter_ifSppDisabled() {
    associationManager.isSppEnabled = false

    val discoveryRequest = DiscoveryRequest.Builder(Activity()).build()
    associationManager.startCdmDiscovery(discoveryRequest, mock())

    // `AssocationRequest` is a final object, so cannot use an ArgumentCaptor to capture it.
    val request =
      checkNotNull(associationHandler.request) {
        "Method `associate` was not called in startCdmDiscovery"
      }

    // `getDeviceFilters` is an @hide method.
    val getDeviceFiltersMethod = request.javaClass.getMethod("getDeviceFilters")
    val filters = getDeviceFiltersMethod.invoke(request)
    if (filters !is List<*>) {
      fail("Unexpected. `getDeviceFilters` returned wrong type. Did the method change?")
    }

    assertThat(filters).hasSize(1)
    assertThat(filters.first()).isInstanceOf(BluetoothLeDeviceFilter::class.java)
  }

  @Test
  fun testStartCdmDiscovery_doesIncludesSppDeviceFilter_ifSppEnabled() {
    associationManager.isSppEnabled = true

    val discoveryRequest = DiscoveryRequest.Builder(Activity()).build()
    associationManager.startCdmDiscovery(discoveryRequest, mock())

    // `AssocationRequest` is a final object, so cannot use an ArgumentCaptor to capture it.
    val request =
      checkNotNull(associationHandler.request) {
        "Method `associate` was not called in startCdmDiscovery"
      }

    // `getDeviceFilters` is an @hide method.
    val getDeviceFiltersMethod = request.javaClass.getMethod("getDeviceFilters")
    val filters = getDeviceFiltersMethod.invoke(request)
    if (filters !is List<*>) {
      fail("Unexpected. `getDeviceFilters` returned wrong type. Did the method change?")
    }

    assertThat(filters).hasSize(2)
    assertThat(filters.any { it is BluetoothDeviceFilter }).isTrue()
    assertThat(filters.any { it is BluetoothLeDeviceFilter }).isTrue()
  }

  @Test
  fun testDeviceIdReceived_notifiesCallback() {
    associationManager.pendingCarCallback.onDeviceIdReceived(TEST_DEVICE_ID)

    verify(disassociationCallback, never()).onCarDisassociated(TEST_DEVICE_ID)
    verify(associationCallback).onDeviceIdReceived(TEST_DEVICE_ID)
  }

  @Test
  fun testDeviceIdReceived_previouslyAssociated_issuesDisassocationCallback() {
    runBlocking {
      // Simulate that the car has already been associated.
      associatedCarManager.add(car)

      // Then, receiving the same device ID again.
      associationManager.pendingCarCallback.onDeviceIdReceived(TEST_DEVICE_ID)

      // The disassociation callback should be issued first.
      val order = inOrder(disassociationCallback, associationCallback)
      order.verify(disassociationCallback).onCarDisassociated(TEST_DEVICE_ID)
      order.verify(associationCallback).onDeviceIdReceived(TEST_DEVICE_ID)
    }
  }

  private fun ByteArray.toHexString(): String {
    return this.joinToString("") { String.format("%02X", it) }
  }

  open class TestAssociationHandler() : AssociationHandler {
    var request: CdmAssociationRequest? = null

    override val associations = mutableListOf(TEST_MACADDRESS)

    override fun associate(
      activity: Activity,
      request: CdmAssociationRequest,
      callback: CompanionDeviceManager.Callback
    ) {
      this.request = request
    }

    override fun disassociate(macAddress: String): Boolean {
      // No implementation
      return true
    }
  }
}
