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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.testutils.FakeSecretKey
import com.google.android.libraries.car.trustagent.testutils.createScanRecord
import com.google.android.libraries.car.trustagent.testutils.createScanResult
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboIntentSender

@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectedDeviceManagerTest {

  private lateinit var manager: ConnectedDeviceManager

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testLifecycleOwner = TestLifecycleOwner()
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
    on { startCdmDiscovery(any(), any()) } doReturn true
  }
  private val mockConnectionManager: ConnectionManager = mock {
    on { startScanForAssociatedCars(any<ScanCallback>()) } doReturn true
  }
  private val mockCar: Car = mock {
    on { deviceId } doReturn DEVICE_ID
    on { toAssociatedCar() } doReturn fakeAssociatedCar
  }

  @Before
  fun setUp() {
    manager =
      ConnectedDeviceManager(
        context,
        testLifecycleOwner.lifecycle,
        mockAssociationManager,
        mockConnectionManager,
        listOf(mockFeature),
        coroutineDispatcher = UnconfinedTestDispatcher(),
        backgroundDispatcher = UnconfinedTestDispatcher(),
      )
  }

  @Test
  fun startDiscovery_doubleCalls_secondIsRejected() {
    val discoveryRequest =
      discoveryRequest(FakeActivity()) {
        namePrefix = "namePrefix"
        associationUuid = null
        deviceIdentifier = null
      }

    assertThat(manager.startDiscovery(discoveryRequest)).isTrue()
    // Second call is rejected.
    assertThat(manager.startDiscovery(discoveryRequest)).isFalse()
  }

  @Test
  fun startDiscovery_afterDiscoveryCallbackOnFailure_secondCallIsAccepted() {
    val discoveryRequest =
      discoveryRequest(FakeActivity()) {
        namePrefix = "namePrefix"
        associationUuid = null
        deviceIdentifier = null
      }

    assertThat(manager.startDiscovery(discoveryRequest)).isTrue()
    manager.companionDeviceManagerCallback.onFailure("error")

    assertThat(manager.startDiscovery(discoveryRequest)).isTrue()
  }

  @Test
  fun startDiscovery_afterSuccessDiscoveryCallback_onFailureIsIgnored() {
    val discoveryRequest =
      discoveryRequest(FakeActivity()) {
        namePrefix = "namePrefix"
        associationUuid = null
        deviceIdentifier = null
      }
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
    // Request discovery and receive a success callback.
    assertThat(manager.startDiscovery(discoveryRequest)).isTrue()
    manager.companionDeviceManagerCallback.onDeviceFound(intentSender)

    // The following failure callback should be ignored.
    manager.companionDeviceManagerCallback.onFailure("error")

    verify(mockCallback, never()).onDiscoveryFailed()
  }

  @Test
  fun startDiscovery_afterDiscoveryCallbackOnDeviceFound_secondCallIsAccepted() {
    val discoveryRequest =
      discoveryRequest(FakeActivity()) {
        namePrefix = "namePrefix"
        associationUuid = null
        deviceIdentifier = null
      }
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

    assertThat(manager.startDiscovery(discoveryRequest)).isTrue()
    manager.companionDeviceManagerCallback.onDeviceFound(intentSender)

    assertThat(manager.startDiscovery(discoveryRequest)).isTrue()
  }

  @Test
  fun destroy_stopReconnection() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    testLifecycleOwner.currentState = Lifecycle.State.DESTROYED

    verify(mockConnectionManager).stop()
  }

  @Test
  fun destroy_disconnectOngoingConnection() {
    // Set up a connection.
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureAssociationCallback().onAssociated(mockCar)

    testLifecycleOwner.currentState = Lifecycle.State.DESTROYED

    verify(mockCar).disconnect()
  }

  @Test
  fun associate() {
    val request = associationRequest(Intent()) {}
    manager.associate(request)

    verify(mockAssociationManager).associate(request)
  }

  @Test
  fun associate_ignoresSubsequentRequest() {
    val request = associationRequest(Intent()) {}
    manager.associate(request)

    // Subsequent call should be ignored; verify the manager is still invoked once.
    manager.associate(request)

    verify(mockAssociationManager).associate(request)
  }

  @Test
  fun associate_firstAssociationFails_acceptsNextRequest() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    val request = associationRequest(Intent()) {}
    manager.associate(request)

    captureAssociationCallback().onAssociationFailed()

    manager.associate(request)
    verify(mockAssociationManager, times(2)).associate(request)
  }

  @Test
  fun associate_firstAssociationSucceeds_acceptsNextRequest() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    val request = associationRequest(Intent()) {}
    manager.associate(request)

    captureAssociationCallback().onAssociated(mockCar)

    manager.associate(request)
    verify(mockAssociationManager, times(2)).associate(request)
  }

  @Test
  fun onAssociated_connectionKeptInConnectedCars() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureAssociationCallback().onAssociated(mockCar)

    assertThat(manager.connectedCars).containsExactly(fakeAssociatedCar)
  }

  @Test
  fun onAssociated_notifyFeatures() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureAssociationCallback().onAssociated(mockCar)

    verify(mockFeature).notifyCarConnected(mockCar)
  }

  @Test
  fun forwardAssociationManagerCallback() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
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
    val discoveryRequest =
      discoveryRequest(FakeActivity()) {
        namePrefix = "namePrefix"
        associationUuid = null
        deviceIdentifier = null
      }
    assertThat(manager.startDiscovery(discoveryRequest)).isTrue()

    manager.companionDeviceManagerCallback.onFailure("error")

    verify(mockCallback).onDiscoveryFailed()
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
    }

    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)
    shadowOf(Looper.getMainLooper()).idle()

    verify(mockConnectionManager).connect(eq(fakeScanResult), any())
  }

  @Test
  fun reconnectionScan_bluetoothOff_doNotConnect() = runBlocking {
    whenever(mockAssociationManager.isBluetoothEnabled).doReturn(false)
    val fakeScanRecord =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(SERVICE_UUID),
        serviceData = emptyMap()
      )
    val fakeScanResult = createScanResult(fakeScanRecord)

    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)

    verify(mockConnectionManager, never()).connect(any<ScanResult>(), any())
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
    }

    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)

    verify(mockConnectionManager, never()).connect(any<ScanResult>(), any())
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
    }

    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)
    // Make a second callback; result should be ignored.
    captureScanCallback().onScanResult(/* callbackType= */ 0, fakeScanResult)
    shadowOf(Looper.getMainLooper()).idle()

    verify(mockConnectionManager).connect(eq(fakeScanResult), any())
  }

  @Test
  fun onCarDisassociated_disconnectDevice() {
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(true))
    testLifecycleOwner.currentState = Lifecycle.State.CREATED

    captureAssociationCallback().onAssociated(mockCar)
    captureDisassociationCallback().onCarDisassociated(DEVICE_ID)

    verify(mockCar).disconnect()
  }

  @Test
  fun onCarDisassociated_stillAssociated_doNotStop() {
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(true))
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    captureDisassociationCallback().onCarDisassociated(DEVICE_ID)

    verify(mockConnectionManager, never()).stop()
  }

  @Test
  fun onCarDisassociated_noAssociatedCar_stopReconnection() {
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(false))
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    captureDisassociationCallback().onCarDisassociated(DEVICE_ID)

    verify(mockConnectionManager).stop()
  }

  @Test
  fun onCarDisassociated_notifyFeature() {
    whenever(mockAssociationManager.loadIsAssociated()).doReturn(Futures.immediateFuture(false))
    testLifecycleOwner.currentState = Lifecycle.State.CREATED

    captureDisassociationCallback().onCarDisassociated(DEVICE_ID)

    verify(mockFeature).onCarDisassociated(DEVICE_ID)
  }

  @Test
  fun clearCurrentAssociation_ableToReassociate() {
    whenever(mockAssociationManager.stopDiscovery()).doReturn(true)
    val request = associationRequest(Intent()) {}
    manager.associate(request)

    manager.clearCurrentAssociation()
    manager.associate(request)

    verify(mockAssociationManager, times(2)).associate(request)
  }

  @Test
  fun disassociate_clearCdmAssociation() = runBlocking {
    mockAssociationManager.stub {
      onBlocking { clearCdmAssociatedCar(DEVICE_ID) } doReturn true
      onBlocking { loadIsAssociated() } doReturn Futures.immediateFuture(false)
    }

    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    assertThat(manager.disassociate(DEVICE_ID).get()).isTrue()
    shadowOf(Looper.getMainLooper()).idle()

    verify(mockAssociationManager).clearCdmAssociatedCar(DEVICE_ID)
    verify(mockConnectionManager).stop()
    verify(mockFeature).onCarDisassociated(DEVICE_ID)
  }

  @Test
  fun onAllCarsDisassociated_disconnectAllConnections() {
    val mockCar1: Car = mock { on { deviceId } doReturn UUID.randomUUID() }
    val mockCar2: Car = mock { on { deviceId } doReturn UUID.randomUUID() }

    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureConnectionCallback().onConnected(mockCar1)
    captureConnectionCallback().onConnected(mockCar2)

    captureDisassociationCallback().onAllCarsDisassociated()

    verify(mockCar1).disconnect()
    verify(mockCar2).disconnect()
  }

  @Test
  fun onAllCarsDisassociated_notifyFeature() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED

    captureDisassociationCallback().onAllCarsDisassociated()

    verify(mockFeature).onAllCarsDisassociated()
  }

  @Test
  fun onConnected_forwardsCallback() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    val mockCallback: ConnectedDeviceManager.Callback = mock()
    manager.registerCallback(mockCallback)

    captureConnectionCallback().onConnected(mockCar)

    verify(mockCallback).onConnected(any())
  }

  @Test
  fun onConnected_connectionKeptInConnectedCars() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureConnectionCallback().onConnected(mockCar)

    assertThat(manager.connectedCars).containsExactly(fakeAssociatedCar)
  }

  @Test
  fun carDisconnected_removedFromConnectedCars() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
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
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
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
  fun stop_stopReconnection() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureConnectionCallback().onConnected(mockCar)

    manager.stop()

    verify(mockConnectionManager).stop()
  }

  @Test
  fun stop_disconnectConnectedCars() {
    testLifecycleOwner.currentState = Lifecycle.State.CREATED
    captureConnectionCallback().onConnected(mockCar)

    manager.stop()

    verify(mockCar).disconnect()
  }

  @Test
  fun renameCar_updateConnectedCar() {
    val updatedName = "updatedName"
    whenever(mockAssociationManager.renameCar(DEVICE_ID, updatedName))
      .doReturn(Futures.immediateFuture(true))
    testLifecycleOwner.currentState = Lifecycle.State.CREATED

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
