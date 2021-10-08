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

import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.testutils.FakeLifecycleOwner
import com.google.android.libraries.car.trustagent.testutils.FakeSecretKey
import com.google.android.libraries.car.trustagent.testutils.createScanRecord
import com.google.android.libraries.car.trustagent.testutils.createScanResult
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.timeout
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.nhaarman.mockitokotlin2.whenever
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.fakes.RoboIntentSender

private val TEST_BLUETOOTH_DEVICE =
  BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:AA:BB:CC")

@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectedDeviceManagerTest {

  private lateinit var manager: ConnectedDeviceManager
  private val testDispatcher = TestCoroutineDispatcher()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val fakeLifecycleOwner = FakeLifecycleOwner()
  private val fakeAssociatedCar =
    AssociatedCar(
      deviceId = DEVICE_ID,
      name = "NAME",
      macAddress = "MACADDRESS",
      identificationKey = FakeSecretKey()
    )

  private val mockFeature: FeatureManager = mock()
  private val mockAssociationManager: AssociationManager = mock {
    on { isBluetoothEnabled } doReturn true
  }
  private val mockConnectionManager: ConnectionManager = mock {
    on { startScanForAssociatedCars(any<ScanCallback>()) } doReturn true
  }
  private val mockCar: Car = mock {
    on { deviceId } doReturn DEVICE_ID
    on { toAssociatedCar() } doReturn fakeAssociatedCar
  }

  private val mockSppCar: Car = mock {
    on { bluetoothDevice } doReturn TEST_BLUETOOTH_DEVICE
    on { isSppDevice() } doReturn true
  }

  @Before
  fun setUp() {
    manager =
      ConnectedDeviceManager(
        context,
        fakeLifecycleOwner.getLifecycle(),
        mockAssociationManager,
        mockConnectionManager,
        listOf(mockFeature),
        testDispatcher
      )
  }

  @After
  fun after() {
    testDispatcher.cleanupTestCoroutines()
  }

  @Test
  fun start_startSppConnection() = runBlocking {
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn
        Futures.immediateFuture(listOf(TEST_BLUETOOTH_DEVICE))
    }
    // Move the lifecycle to onCreated().
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    verify(mockConnectionManager).startScanForAssociatedCars(any())
    verify(mockConnectionManager).connect(TEST_BLUETOOTH_DEVICE)
  }

  @Test
  fun destroy_stopReconnection() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.DESTROYED)

    verify(mockConnectionManager).stop()
  }

  @Test
  fun destroy_disconnectOngoingConnection() {
    // Set up a connection.
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureAssociationCallback().onAssociated(mockCar)

    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.DESTROYED)

    verify(mockCar).disconnect()
  }

  @Test
  fun associate() {
    val request = AssociationRequest.Builder(Intent()).build()
    manager.associate(request)

    verify(mockAssociationManager).associate(request)
  }

  @Test
  fun associate_ignoresSubsequentRequest() {
    val request = AssociationRequest.Builder(Intent()).build()
    manager.associate(request)

    // Subsequent call should be ignored; verify the manager is still invoked once.
    manager.associate(request)

    verify(mockAssociationManager).associate(request)
  }

  @Test
  fun associate_firstAssociationFails_acceptsNextRequest() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    val request = AssociationRequest.Builder(Intent()).build()
    manager.associate(request)

    captureAssociationCallback().onAssociationFailed()

    manager.associate(request)
    verify(mockAssociationManager, times(2)).associate(request)
  }

  @Test
  fun associate_firstAssociationSucceeds_acceptsNextRequest() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    val request = AssociationRequest.Builder(Intent()).build()
    manager.associate(request)

    captureAssociationCallback().onAssociated(mockCar)

    manager.associate(request)
    verify(mockAssociationManager, times(2)).associate(request)
  }

  @Test
  fun onAssociated_connectionKeptInConnectedCars() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureAssociationCallback().onAssociated(mockCar)

    assertThat(manager.connectedCars).containsExactly(fakeAssociatedCar)
  }

  @Test
  fun onAssociated_notifyFeatures() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureAssociationCallback().onAssociated(mockCar)

    verify(mockFeature).notifyCarConnected(mockCar)
  }

  @Test
  fun forwardAssociationManagerCallback() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    // Verify the callback is properly forwarded.
    // Oh that sweet sweet coverage.

    captureAssociationCallback().onAssociated(mockCar)
    verify(mockCallback).onAssociated(fakeAssociatedCar)

    captureAssociationCallback().onAssociationStart()
    verify(mockCallback).onAssociationStart()

    val authString = "authString"
    captureAssociationCallback().onAuthStringAvailable(authString)
    verify(mockCallback).onAuthStringAvailable(eq(authString))

    // onDeviceIdReceived() is skipped.
    captureAssociationCallback().onDeviceIdReceived(UUID.randomUUID())
    verifyNoMoreInteractions(mockCallback)
  }

  @Test
  fun connectedDeviceManager_onDeviceFound() {
    val intentSender =
      RoboIntentSender(
        PendingIntent.getActivity(
          context,
          /* requestCode= */ 0,
          /* intent= */ FakeActivity.createIntent(context),
          /* flags= */ 0,
          /* options= */ null
        )
      )
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    manager.companionDeviceManagerCallback.onDeviceFound(intentSender)

    verify(mockCallback).onDeviceDiscovered(intentSender)
  }

  @Test
  fun connectedDeviceManager_onFailure() {
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    manager.companionDeviceManagerCallback.onFailure("error")

    verify(mockCallback).onDiscoveryFailed()
  }

  @Test
  fun sppConnection_receivedNonConnectedState_ignored() {
    val intent =
      Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED).apply {
        putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
      }
    manager.startSppBroadcastReceiver.onReceive(context, intent)

    verify(mockConnectionManager, never()).connect(any<BluetoothDevice>())
  }

  @Test
  fun sppConnection_bluetoothDeviceNotAssociated_ignored() {
    whenever(mockAssociationManager.loadIsAssociated(any()))
      .doReturn(Futures.immediateFuture(false))
    val intent =
      Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED).apply {
        putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
        putExtra(BluetoothDevice.EXTRA_DEVICE, TEST_BLUETOOTH_DEVICE)
      }
    manager.startSppBroadcastReceiver.onReceive(context, intent)

    verify(mockConnectionManager, never()).connect(any<BluetoothDevice>())
  }

  @Test
  fun sppConnection_connect() {
    whenever(mockAssociationManager.loadIsAssociated(any())).doReturn(Futures.immediateFuture(true))
    val intent =
      Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED).apply {
        putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
        putExtra(BluetoothDevice.EXTRA_DEVICE, TEST_BLUETOOTH_DEVICE)
      }
    manager.startSppBroadcastReceiver.onReceive(context, intent)

    verify(mockConnectionManager).connect(eq(TEST_BLUETOOTH_DEVICE))
  }

  @Test
  fun reconnectionScan_filteredResult_connect() = runBlocking {
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData = emptyMap()
      )
    val fakeScanResult = createScanResult(fakeScanRecord)
    mockConnectionManager.stub {
      onBlocking { filterForConnectableCars(any()) } doReturn listOf(fakeScanResult)
      onBlocking { fetchConnectedBluetoothDevices() } doReturn Futures.immediateFuture(emptyList())
    }

    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)

    verify(mockConnectionManager).connect(eq(fakeScanResult))
  }

  @Test
  fun reconnectionScan_bluetoothOff_doNotConnect() {
    whenever(mockAssociationManager.isBluetoothEnabled).doReturn(false)
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData = emptyMap()
      )
    val fakeScanResult = createScanResult(fakeScanRecord)

    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)

    verify(mockConnectionManager, never()).connect(any<ScanResult>())
  }

  @Test
  fun reconnectionScan_noFilteredResult_noOp() = runBlocking {
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData = emptyMap()
      )
    val fakeScanResult = createScanResult(fakeScanRecord)
    mockConnectionManager.stub {
      onBlocking { filterForConnectableCars(any()) } doReturn emptyList()
      onBlocking { fetchConnectedBluetoothDevices() } doReturn Futures.immediateFuture(emptyList())
    }

    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)

    verify(mockConnectionManager, never()).connect(any<ScanResult>())
  }

  @Test
  fun reconnectionScan_duplicatedScanResult_ignored() = runBlocking {
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData = emptyMap()
      )
    val fakeScanResult = createScanResult(fakeScanRecord)
    mockConnectionManager.stub {
      onBlocking { filterForConnectableCars(any()) } doReturn listOf(fakeScanResult)
      onBlocking { fetchConnectedBluetoothDevices() } doReturn Futures.immediateFuture(emptyList())
    }

    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)
    // Make a second callback; result should be ignored.
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)

    verify(mockConnectionManager).connect(eq(fakeScanResult))
  }

  @Test
  fun onCarDisassociated_disconnectDevice() {
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn
        Futures.immediateFuture(listOf(TEST_BLUETOOTH_DEVICE))
    }
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(true))
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)

    captureAssociationCallback().onAssociated(mockCar)
    captureDisassociationCallback().onCarDisassociated(DEVICE_ID)

    verify(mockCar).disconnect()
  }

  @Test
  fun onCarDisassociated_stillAssociated_doNotStop() {
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(true))
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    captureDisassociationCallback().onCarDisassociated(DEVICE_ID)

    verify(mockConnectionManager, never()).stop()
  }

  @Test
  fun onCarDisassociated_noAssociatedCar_stopReconnection() {
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn
        Futures.immediateFuture(listOf(TEST_BLUETOOTH_DEVICE))
    }
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(false))
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    captureDisassociationCallback().onCarDisassociated(DEVICE_ID)

    verify(mockConnectionManager).stop()
  }

  @Test
  fun onCarDisassociated_notifyFeature() {
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn
        Futures.immediateFuture(listOf(TEST_BLUETOOTH_DEVICE))
    }
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(false))
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)

    captureDisassociationCallback().onCarDisassociated(DEVICE_ID)

    verify(mockFeature).onCarDisassociated(DEVICE_ID)
  }

  @Test
  fun disassociate_clearCdmAssociation() = runBlocking {
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn
        Futures.immediateFuture(listOf(TEST_BLUETOOTH_DEVICE))
    }

    mockAssociationManager.stub {
      onBlocking { clearCdmAssociatedCar(DEVICE_ID) } doReturn true
      onBlocking { loadIsAssociated() } doReturn Futures.immediateFuture(false)
    }
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)

    assertThat(manager.disassociate(DEVICE_ID).get()).isTrue()
    verify(mockAssociationManager).clearCdmAssociatedCar(DEVICE_ID)
    verify(mockConnectionManager).stop()
    verify(mockFeature).onCarDisassociated(DEVICE_ID)
  }

  @Test
  fun onAllCarsDisassociated_disconnectAllConnections() {
    val mockCar1: Car = mock()
    val mockCar2: Car = mock()
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureConnectionCallback().onConnected(mockCar1)
    captureConnectionCallback().onConnected(mockCar2)

    captureDisassociationCallback().onAllCarsDisassociated()

    verify(mockCar1).disconnect()
    verify(mockCar2).disconnect()
  }

  @Test
  fun onAllCarsDisassociated_notifyFeature() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)

    captureDisassociationCallback().onAllCarsDisassociated()

    verify(mockFeature).onAllCarsDisassociated()
  }

  @Test
  fun onConnected_forwardsCallback() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    captureConnectionCallback().onConnected(mockCar)

    verify(mockCallback).onConnected(any())
  }

  @Test
  fun onConnected_connectionKeptInConnectedCars() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureConnectionCallback().onConnected(mockCar)

    assertThat(manager.connectedCars).containsExactly(fakeAssociatedCar)
  }

  @Test
  fun onConnectionFailed_sppDeviceStillNearby_reconnectSpp() = runBlocking {
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(true))
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn
        Futures.immediateFuture(listOf(TEST_BLUETOOTH_DEVICE))
    }
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    verify(mockConnectionManager).connect(TEST_BLUETOOTH_DEVICE)

    captureConnectionCallback().onConnectionFailed(TEST_BLUETOOTH_DEVICE)

    verify(mockConnectionManager, timeout((ConnectedDeviceManager.SPP_RETRY_THROTTLE).toMillis()))
      .connect(TEST_BLUETOOTH_DEVICE)
  }

  @Test
  fun carDisconnected_removedFromConnectedCars() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureConnectionCallback().onConnected(mockCar)

    val carCallback =
      argumentCaptor<Car.Callback>().run {
        verify(mockCar).setCallback(capture(), eq(ConnectedDeviceManager.RECIPIENT_ID))
        firstValue
      }

    carCallback.onDisconnected()

    assertThat(manager.connectedCars).isEmpty()
    verify(mockCar).clearCallback(eq(carCallback), eq(ConnectedDeviceManager.RECIPIENT_ID))
  }

  @Test
  fun carDisconnected_forwardsCallback() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)
    captureConnectionCallback().onConnected(mockCar)

    val carCallback =
      argumentCaptor<Car.Callback>().run {
        verify(mockCar).setCallback(capture(), eq(ConnectedDeviceManager.RECIPIENT_ID))
        firstValue
      }

    carCallback.onDisconnected()

    verify(mockCallback).onDisconnected(any())
  }

  @Test
  fun carDisconnected_bluetoothConnected_retrySppConnection() = runBlocking {
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(true))
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn
        Futures.immediateFuture(listOf(TEST_BLUETOOTH_DEVICE))
    }

    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    verify(mockConnectionManager).connect(TEST_BLUETOOTH_DEVICE)

    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)
    captureConnectionCallback().onConnected(mockSppCar)

    val carCallback =
      argumentCaptor<Car.Callback>().run {
        verify(mockSppCar).setCallback(capture(), eq(ConnectedDeviceManager.RECIPIENT_ID))
        firstValue
      }

    carCallback.onDisconnected()

    verify(mockConnectionManager, timeout(ConnectedDeviceManager.SPP_RETRY_THROTTLE.toMillis()))
      .connect(TEST_BLUETOOTH_DEVICE)
  }

  @Test
  fun carDisconnected_bluetoothNotConnected_doNotRetrySppConnection() = runBlocking {
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(true))
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn Futures.immediateFuture(emptyList())
    }

    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)
    captureConnectionCallback().onConnected(mockSppCar)

    val carCallback =
      argumentCaptor<Car.Callback>().run {
        verify(mockSppCar).setCallback(capture(), eq(ConnectedDeviceManager.RECIPIENT_ID))
        firstValue
      }

    carCallback.onDisconnected()

    verify(
        mockConnectionManager,
        timeout(ConnectedDeviceManager.SPP_RETRY_THROTTLE.toMillis()).times(0)
      )
      .connect(TEST_BLUETOOTH_DEVICE)
  }

  @Test
  fun stop_stopReconnection() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureConnectionCallback().onConnected(mockCar)

    manager.stop()

    verify(mockConnectionManager).stop()
  }

  @Test
  fun stop_disconnectConnectedCars() {
    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)
    captureConnectionCallback().onConnected(mockCar)

    manager.stop()

    verify(mockCar).disconnect()
  }

  @Test
  fun renameCar_updateConnectedCar() {
    val updatedName = "updatedName"
    whenever(mockAssociationManager.renameCar(DEVICE_ID, updatedName))
      .doReturn(Futures.immediateFuture(true))
    mockConnectionManager.stub {
      onBlocking { fetchConnectedBluetoothDevices() } doReturn Futures.immediateFuture(emptyList())
    }

    fakeLifecycleOwner.registry.setCurrentState(Lifecycle.State.CREATED)

    captureConnectionCallback().onConnected(mockCar)

    assertThat(manager.renameCar(DEVICE_ID, updatedName).get()).isTrue()
    verify(mockCar).name = updatedName
  }

  private fun captureAssociationCallback(): AssociationManager.AssociationCallback {
    return argumentCaptor<AssociationManager.AssociationCallback>().run {
      verify(mockAssociationManager).registerAssociationCallback(capture())
      firstValue
    }
  }

  private fun captureDisassociationCallback(): AssociationManager.DisassociationCallback {
    return argumentCaptor<AssociationManager.DisassociationCallback>().run {
      verify(mockAssociationManager).registerDisassociationCallback(capture())
      firstValue
    }
  }

  private fun captureConnectionCallback(): ConnectionManager.ConnectionCallback {
    return argumentCaptor<ConnectionManager.ConnectionCallback>().run {
      verify(mockConnectionManager).registerConnectionCallback(capture())
      firstValue
    }
  }

  private fun captureScanCallback(): ScanCallback {
    return argumentCaptor<ScanCallback>().run {
      verify(mockConnectionManager).startScanForAssociatedCars(capture())
      firstValue
    }
  }

  companion object {
    private val DEVICE_ID = UUID.fromString("a99d8e3c-77bc-427d-a4fa-744d6b84a4cd")
    private val SERVICE_UUID = UUID.fromString("8a16e891-d4ad-455d-8194-cbc2dfbaebdf")
  }
}

class FakeActivity : Activity() {
  companion object {
    fun createIntent(context: Context) = Intent(context, FakeActivity::class.java)
  }
}
