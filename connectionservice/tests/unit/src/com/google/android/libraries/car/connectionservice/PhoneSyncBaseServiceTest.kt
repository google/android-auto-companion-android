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

package com.google.android.libraries.car.connectionservice

import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.AssociatedCar
import com.google.android.libraries.car.trustagent.AssociationManager
import com.google.android.libraries.car.trustagent.Car
import com.google.android.libraries.car.trustagent.ConnectionManager
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.SystemFeatureManager
import com.google.android.libraries.car.trustagent.testutils.FakeSecretKey
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController

// Randomly generated UUID.
private val TEST_CAR_ID = UUID.fromString("8a16e892-d4ad-455d-8194-cbc2dfbaebdf")
private val FEATURE_ID = UUID.fromString("2ba1ce50-4811-41ee-a503-b51fb8e50f79")
private val TEST_SERVICE_RECIPIENT: UUID = UUID.fromString("11111111-635b-4560-bd8d-9cdf83f32ae7")

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class PhoneSyncBaseServiceTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testDispatcher = UnconfinedTestDispatcher()
  private lateinit var serviceController: ServiceController<FakeService>

  private val bluetoothDevice =
    BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:AA:BB")
  private val scanResult = ScanResult(bluetoothDevice, 0, 0, 0, 0, 0, 0, 0, null, 0)
  private lateinit var intentWithScanResult: Intent
  private lateinit var intentWithSppDevice: Intent

  private val mockCar: Car = mock {
    on { bluetoothDevice } doReturn bluetoothDevice
    on { deviceId } doReturn TEST_CAR_ID
    on { isSppDevice() } doReturn false
  }

  private val mockSppCar: Car = mock {
    on { bluetoothDevice } doReturn bluetoothDevice
    on { deviceId } doReturn TEST_CAR_ID
    on { isSppDevice() } doReturn true
  }

  private val mockConnectionManager: ConnectionManager = mock()
  private val mockAssociationManager: AssociationManager = mock()
  private val mockCallback: PhoneSyncBaseService.ConnectionStatusCallback = mock()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)

    AssociationManager.instance = mockAssociationManager
    ConnectionManager.instance = mockConnectionManager
    serviceController = Robolectric.buildService(FakeService::class.java)

    intentWithScanResult =
      Intent()
        .putParcelableArrayListExtra(
          PhoneSyncBaseService.EXTRA_SCAN_DEVICES,
          arrayListOf(scanResult)
        )

    intentWithSppDevice =
      Intent().putExtra(PhoneSyncBaseService.EXTRA_SPP_BLUETOOTH_DEVICE, bluetoothDevice)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun testFeatureManagers_addsOneSystemFeatureManager() {
    val service = serviceController.create().get()
    assertThat(service.featureManagers.count { it is SystemFeatureManager }).isEqualTo(1)
  }

  @Test
  fun testAddConnectedDevice_registerCarDisconnectedCallback() {
    val service = serviceController.create().get()

    service.connectionCallback.onConnected(mockCar)

    verify(mockCar).setCallback(any(), eq(TEST_SERVICE_RECIPIENT))
  }

  @Test
  fun testAddConnectedDevice_updateConnectedCarList() {
    val service = serviceController.create().get()

    assertThat(service.connectedCars).hasSize(0)
    service.connectionCallback.onConnected(mockCar)

    assertThat(service.connectedCars).hasSize(1)
  }

  @Test
  fun testAddConnectedDevice_startsFeatures() {
    val service = serviceController.create().get()

    service.connectionCallback.onConnected(mockCar)
    verify(service.mockFeatureManager).onCarConnected(TEST_CAR_ID)
  }

  @Test
  fun testOnCreate_setOnConnectedCallback() {
    serviceController = Robolectric.buildService(FakeService::class.java, intentWithScanResult)
    serviceController.create()

    verify(mockConnectionManager).registerConnectionCallback(any())
  }

  @Test
  fun testOnStartCommand_initConnectionWithNewScanResult() {
    serviceController = Robolectric.buildService(FakeService::class.java, intentWithScanResult)
    val service = serviceController.create().get()

    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)

    assertThat(service.connectingCars).hasSize(1)
    verify(mockConnectionManager).connect(eq(scanResult), any())
  }

  @Test
  fun testOnStartCommand_doNotInitConnectionWithAlreadyConnectingScanResult() {
    serviceController = Robolectric.buildService(FakeService::class.java, intentWithScanResult)
    val service = serviceController.create().get()
    service.connectingCars.add(bluetoothDevice)

    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)

    verify(mockConnectionManager, never()).connect(eq(scanResult), any())
  }

  @Test
  fun testOnConnected_notifiesFeatureManagers() {
    val service = serviceController.create().get()

    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)

    service.connectionCallback.onConnected(mockCar)

    assertThat(service.connectedCars).hasSize(1)
    verify(service.mockFeatureManager).onCarConnected(TEST_CAR_ID)
  }

  @Test
  fun testOnConnectionFailed_updatesConnectingCarList() {
    serviceController = Robolectric.buildService(FakeService::class.java, intentWithScanResult)
    val service = serviceController.create().get()
    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)

    assertThat(service.connectingCars).hasSize(1)

    service.connectionCallback.onConnectionFailed(scanResult.device)

    assertThat(service.connectingCars).isEmpty()
    assertThat(Shadows.shadowOf(service).isStoppedBySelf).isTrue()
  }

  @Test
  fun testOnConnectionFailed_retriesSppConnection() {
    serviceController = Robolectric.buildService(FakeService::class.java, intentWithSppDevice)
    val service = serviceController.create().get()

    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)

    service.connectionCallback.onConnectionFailed(scanResult.device)

    assertThat(Shadows.shadowOf(service).isStoppedBySelf).isFalse()
  }

  @Test
  fun testOnDisconnected_stopSelfWhenNoConnectingAndConnectedCars() {
    serviceController = Robolectric.buildService(FakeService::class.java, intentWithScanResult)
    val service = serviceController.create().get()

    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)

    service.connectionCallback.onConnected(mockCar)

    argumentCaptor<Car.Callback>().apply {
      verify(mockCar).setCallback(capture(), eq(TEST_SERVICE_RECIPIENT))
      firstValue.onDisconnected()
    }

    assertThat(service.connectedCars).isEmpty()
    assertThat(service.connectingCars).isEmpty()
    assertThat(Shadows.shadowOf(service).isStoppedBySelf).isTrue()
  }

  @Test
  fun testOnDisconnected_retriesSppConnection() {
    serviceController = Robolectric.buildService(FakeService::class.java, intentWithSppDevice)
    val service = serviceController.create().get()

    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)

    service.connectionCallback.onConnected(mockSppCar)

    argumentCaptor<Car.Callback>().apply {
      verify(mockSppCar).setCallback(capture(), eq(TEST_SERVICE_RECIPIENT))
      firstValue.onDisconnected()
    }

    assertThat(Shadows.shadowOf(service).isStoppedBySelf).isFalse()
  }

  @Test
  fun testOnStartCommand_stopsSelfWhenNoConnectingAndConnectedCar() {
    val service = serviceController.create().get()

    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)

    assertThat(Shadows.shadowOf(service).isStoppedBySelf).isTrue()
  }

  @Test
  fun testOnStartCommand_intentWithoutNotificationExtra_runsInBackground() {
    val service =
      Robolectric.buildService(FakeService::class.java, intentWithScanResult)
        .create()
        .startCommand(/* flags= */ 0, /* startId= */ 0)
        .get()

    assertThat(Shadows.shadowOf(service).isForegroundStopped).isFalse()
  }

  @Test
  fun testOnStartCommand_intentWithNotificationExtra_pushForegroundNotification() {
    val notification =
      Notification.Builder(context, "notification_channel_id").setContentTitle("title").build()

    // Also set the notification and its ID.
    val intent =
      intentWithScanResult
        .putExtra(PhoneSyncBaseService.EXTRA_FOREGROUND_NOTIFICATION, notification)
        .putExtra(PhoneSyncBaseService.EXTRA_FOREGROUND_NOTIFICATION_ID, 1)

    val service =
      Robolectric.buildService(FakeService::class.java, intent)
        .create()
        .startCommand(/* flags= */ 0, /* startId= */ 0)
        .get()

    serviceController.startCommand(/* flags= */ 0, /* startId= */ 0)
    assertThat(Shadows.shadowOf(service).isLastForegroundNotificationAttached).isTrue()
  }

  @Test
  fun testOnStartCommand_intentMissingNotificationIdExtra_NoForegroundNotification() {
    val notification =
      Notification.Builder(context, "notification_channel_id").setContentTitle("title").build()

    // Intent does not specify a notification ID.
    val intent =
      intentWithScanResult.putExtra(
        PhoneSyncBaseService.EXTRA_FOREGROUND_NOTIFICATION,
        notification
      )

    val service =
      Robolectric.buildService(FakeService::class.java, intent)
        .create()
        .startCommand(/* flags= */ 0, /* startId= */ 0)
        .get()

    assertThat(Shadows.shadowOf(service).isLastForegroundNotificationAttached).isFalse()
  }

  @Test
  fun testOnConnected_callsOnCarConnectedCallback() {
    val service = serviceController.create().get()
    service.connectionStatusCallback = mockCallback

    service.connectionCallback.onConnected(mockCar)

    verify(mockCallback).onCarConnected(TEST_CAR_ID)
  }

  @Test
  fun registerCarCallback_callsOnCarDisconnectedCallbackWhenCarDisconnected() {
    val service = serviceController.create().get()

    service.connectionStatusCallback = mockCallback
    service.connectionCallback.onConnected(mockCar)

    argumentCaptor<Car.Callback>().apply {
      verify(mockCar).setCallback(capture(), eq(TEST_SERVICE_RECIPIENT))
      firstValue.onDisconnected()
    }

    verify(mockCallback).onCarDisconnected(TEST_CAR_ID)
  }

  @Test
  fun testOnDeviceIdReceived_alreadyAssociated_notifyFeatureOfDisassociation() {
    // Mock an associated car.
    val fakeAssociatedCar = AssociatedCar(TEST_CAR_ID, "deviceName", "macAddress", FakeSecretKey())
    val future =
      SettableFuture.create<List<AssociatedCar>>().apply { set(listOf(fakeAssociatedCar)) }
    whenever(mockAssociationManager.retrieveAssociatedCars()).thenReturn(future)

    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    // Received device ID is the same as the associated car.
    service.associationCallback.onDeviceIdReceived(TEST_CAR_ID)

    verify(service.mockFeatureManager).onCarDisassociated(TEST_CAR_ID)
  }

  @Test
  fun testOnDeviceIdReceived_firstTimeAssociated_NoFeatureCleanUp() {
    val future =
      SettableFuture.create<List<AssociatedCar>>().apply { set(emptyList<AssociatedCar>()) }
    whenever(mockAssociationManager.retrieveAssociatedCars()).thenReturn(future)

    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.associationCallback.onDeviceIdReceived(TEST_CAR_ID)

    verify(service.mockFeatureManager, never()).onCarDisassociated(any())
  }

  @Test
  fun testOnCarDisassociated_notifiesListeners() {
    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.disassociationCallback.onCarDisassociated(TEST_CAR_ID)

    verify(service.mockFeatureManager).onCarDisassociated(TEST_CAR_ID)
  }

  @Test
  fun testOnAllCarDisassociated_notifiesListeners() {
    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.disassociationCallback.onAllCarsDisassociated()

    verify(service.mockFeatureManager).onAllCarsDisassociated()
  }

  @Test
  fun testOnCarDisassociated_allCarsDisassociated_disconnectsAllCars() {
    val deviceId1 = UUID.fromString("f4f4abc8-cd98-4ec3-8a10-4967e33175ab")
    val deviceId2 = UUID.fromString("bae5e530-4817-4dfe-8ea0-90bf0881a6c2")

    var car1 = createMockCar(deviceId1, "00:11:22:33:AA:BB")
    var car2 = createMockCar(deviceId2, "11:11:22:33:AA:BB")

    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.connectionCallback.onConnected(car1)
    service.connectionCallback.onConnected(car2)

    service.disassociationCallback.onAllCarsDisassociated()

    verify(service.connectionManager).disconnect(car1)
    verify(service.connectionManager).disconnect(car2)
  }

  @Test
  fun testOnCarDisassociated_disconnectsCar() {
    val deviceId1 = UUID.fromString("f4f4abc8-cd98-4ec3-8a10-4967e33175ab")
    val deviceId2 = UUID.fromString("bae5e530-4817-4dfe-8ea0-90bf0881a6c2")

    var car1 = createMockCar(deviceId1, "00:11:22:33:AA:BB")
    var car2 = createMockCar(deviceId2, "11:11:22:33:AA:BB")

    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.connectionCallback.onConnected(car1)
    service.connectionCallback.onConnected(car2)

    service.disassociationCallback.onCarDisassociated(deviceId1)

    verify(service.connectionManager).disconnect(car1)
    verify(service.connectionManager, never()).disconnect(car2)
  }

  @Test
  fun testGetFeatureManager_javaClass() {
    val service = serviceController.create().get()

    assertThat(service.getFeatureManager(FakeFeatureManager::class.java)).isNotNull()
  }

  @Test
  fun testBluetoothStateChange_off_stopsScanning() {
    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()
    service.onBluetoothStateChange(BluetoothAdapter.STATE_OFF)

    verify(service.connectionManager).stop()
  }

  @Test
  fun testBluetoothStateChange_on_doesNotStopScanning() {
    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()
    service.onBluetoothStateChange(BluetoothAdapter.STATE_ON)

    verify(service.connectionManager, never()).stop()
  }

  @Test
  fun testBluetoothStateChange_off_disconnectsAllCars() {
    val deviceId1 = UUID.fromString("f4f4abc8-cd98-4ec3-8a10-4967e33175ab")
    val deviceId2 = UUID.fromString("bae5e530-4817-4dfe-8ea0-90bf0881a6c2")

    var car1 = createMockCar(deviceId1, "00:11:22:33:AA:BB")
    var car2 = createMockCar(deviceId2, "11:11:22:33:AA:BB")

    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.connectionCallback.onConnected(car1)
    service.connectionCallback.onConnected(car2)

    service.onBluetoothStateChange(BluetoothAdapter.STATE_OFF)

    verify(service.connectionManager).disconnect(car1)
    verify(service.connectionManager).disconnect(car2)
  }

  @Test
  fun testBluetoothStateChange_on_doesNotDisconnectCars() {
    val deviceId1 = UUID.fromString("f4f4abc8-cd98-4ec3-8a10-4967e33175ab")
    val deviceId2 = UUID.fromString("bae5e530-4817-4dfe-8ea0-90bf0881a6c2")

    var car1 = createMockCar(deviceId1, "00:11:22:33:AA:BB")
    var car2 = createMockCar(deviceId2, "11:11:22:33:AA:BB")

    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.connectionCallback.onConnected(car1)
    service.connectionCallback.onConnected(car2)

    service.onBluetoothStateChange(BluetoothAdapter.STATE_ON)

    verify(service.connectionManager, never()).disconnect(car1)
    verify(service.connectionManager, never()).disconnect(car2)
  }

  /**
   * [Creates a mock [Car] with the given [mockDeviceId] and backed by a [BluetoothDevice] with the
   * given [address].
   */
  private fun createMockCar(mockDeviceId: UUID, address: String): Car = mock {
    on { deviceId } doReturn mockDeviceId
    on { bluetoothDevice } doReturn BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
  }

  class FakeService : PhoneSyncBaseService() {
    val mockFeatureManager: FeatureManager = spy(FakeFeatureManager())

    override val serviceRecipient = TEST_SERVICE_RECIPIENT
    override fun createFeatureManagers() = listOf<FeatureManager>(mockFeatureManager)

    override fun onBind(intent: Intent): Binder = mock()
  }

  open class FakeFeatureManager : FeatureManager() {
    override val featureId: UUID = FEATURE_ID

    override fun onCarConnected(deviceId: UUID) {}
    override fun onMessageReceived(message: ByteArray, deviceId: UUID) {}
    override fun onMessageSent(messageId: Int, deviceId: UUID) {}
    override fun onCarDisconnected(deviceId: UUID) {}
    override fun onCarDisassociated(deviceId: UUID) {}
    override fun onAllCarsDisassociated() {}
  }
}
