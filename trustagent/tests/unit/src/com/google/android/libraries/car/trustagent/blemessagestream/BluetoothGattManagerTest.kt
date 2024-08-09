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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper

// The UUIDs here are randomly generated.
private val SERVICE_UUID = UUID.fromString("473db2ea-673c-420a-8bf9-ec0b7f7f0236")
private val WRITE_UUID = UUID.fromString("3ba56224-367d-4b5c-9cbd-271d8ff28bf8")
private val READ_UUID = UUID.fromString("b1cb5670-26f8-40d1-9b28-65c279f49217")
private val ADVERTISE_DATA_UUID = UUID.fromString("8080cb48-c110-44f3-9f36-55c4ac81a75b")

private val TEST_MESSAGE = "message".toByteArray(Charsets.UTF_8)
private val ADVERTISE_DATA = "beef".encodeToByteArray()

@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class BluetoothGattManagerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testDispatcher = UnconfinedTestDispatcher()
  private val testScope = TestScope(testDispatcher)

  private lateinit var bluetoothDevice: BluetoothDevice
  private lateinit var manager: BluetoothGattManager
  private lateinit var bluetoothAdapter: BluetoothAdapter

  private lateinit var gattHandle: GattHandle
  private lateinit var connectionCallback: BluetoothConnectionManager.ConnectionCallback

  private lateinit var containingService: BluetoothGattService
  private lateinit var writeCharacteristic: BluetoothGattCharacteristic
  private lateinit var readCharacteristic: BluetoothGattCharacteristic
  private lateinit var advertiseDataCharacteristic: BluetoothGattCharacteristic

  private lateinit var gapService: BluetoothGattService
  private lateinit var deviceNameCharacteristic: BluetoothGattCharacteristic

  @Before
  fun setUp() {
    bluetoothDevice = Shadow.newInstanceOf(BluetoothDevice::class.java)

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter
    bluetoothAdapter.enable()

    writeCharacteristic =
      BluetoothGattCharacteristic(WRITE_UUID, /* properties= */ 0, /* permissions= */ 0)
    readCharacteristic =
      BluetoothGattCharacteristic(READ_UUID, /* properties= */ 0, /* permissions= */ 0)
    advertiseDataCharacteristic =
      BluetoothGattCharacteristic(ADVERTISE_DATA_UUID, /* properties= */ 0, /* permissions= */ 0)
        .apply { value = ADVERTISE_DATA }

    containingService =
      BluetoothGattService(SERVICE_UUID, /* serviceType= */ 0).apply {
        addCharacteristic(writeCharacteristic)
        addCharacteristic(readCharacteristic)
        addCharacteristic(advertiseDataCharacteristic)
      }

    deviceNameCharacteristic =
      BluetoothGattCharacteristic(
        BluetoothGattManager.DEVICE_NAME_UUID,
        /* properties= */ 0,
        /* permissions= */ 0,
      )
    gapService =
      BluetoothGattService(BluetoothGattManager.GENERIC_ACCESS_PROFILE_UUID, /* serviceType= */ 0)
    gapService.addCharacteristic(deviceNameCharacteristic)

    gattHandle = mock { on { device } doReturn bluetoothDevice }

    manager =
      BluetoothGattManager(
        context,
        gattHandle,
        SERVICE_UUID,
        clientWriteCharacteristicUuid = WRITE_UUID,
        serverWriteCharacteristicUuid = READ_UUID,
        advertiseDataCharacteristicUuid = ADVERTISE_DATA_UUID,
      )

    connectionCallback = mock()
    manager.registerConnectionCallback(connectionCallback)
  }

  @After
  fun cleanUp() {
    // Clear stored default MTU; defaults to MAXIMUM_MTU.
    context.deleteSharedPreferences(BluetoothGattManager.SHARED_PREF)
  }

  @Test
  fun testConnect_CallsConnectForGattHandle() {
    triggerConnect()

    verify(gattHandle).connect(context)
  }

  @Test
  fun testOnConnectionStateChange_disconnected_retriesConnection() {
    triggerConnect()

    triggerOnConnectionStateChange(BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)

    // Gatt should be closed before another connection is tried.
    verify(gattHandle).close()

    // Handle message: MSG_CONNECT_GATT
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    // Two calls to `connect` -- the initial `connect` call and a retry.
    verify(gattHandle, times(2)).connect(context)
  }

  @Test
  fun testOnConnectionStateChange_disconnected_notifiesCallbackAfterMaxRetries() {
    triggerConnect()

    repeat(BluetoothGattManager.MAX_RETRY_COUNT) {
      triggerOnConnectionStateChange(
        BluetoothGatt.GATT_FAILURE,
        BluetoothProfile.STATE_DISCONNECTED,
      )

      // A failure connection triggers a re-connect attempt.
      // Handle message: MSG_CONNECT
      ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    verify(gattHandle, times(BluetoothGattManager.MAX_RETRY_COUNT)).connect(context)
    verify(connectionCallback).onConnectionFailed()
  }

  @Test
  fun testOnConnectionStateChange_connected_requestsDefaultMtu() {
    whenever(gattHandle.requestMtu(any())).thenReturn(true)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    verify(gattHandle).requestMtu(BluetoothGattManager.MAXIMUM_MTU)
  }

  @Test
  fun testBondingStateChange_bonding_connectionPaused() {
    whenever(gattHandle.requestMtu(any())).thenReturn(true)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // requestMtu should have been invoked.

    setBondStateTransition(BOND_NONE, BOND_BONDING)

    assertThat(manager.isConnectionPaused.get()).isTrue()
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    // Attempt to handle message: MSG_DISCOVER_SERVICES, but it should not be scheduled.
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    verify(gattHandle, never()).discoverServices()
  }

  @Test
  fun testBondingStateChange_bonded_restartConnection() {
    triggerConnect()

    setBondStateTransition(BOND_NONE, BOND_BONDING)

    // This message is scheduled but should not be handled.
    // It should be cleared after bonding state changes.
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

    // Device state is now BONDED
    setBondStateTransition(BOND_BONDING, BOND_BONDED)
    // Handle message: MSG_CONNECT
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    // After bonding state changes, another attempt was made.
    verify(gattHandle, times(2)).connect(any())
    assertThat(manager.isConnectionPaused.get()).isFalse()
  }

  @Test
  fun testBondingStateChange_none_restartConnection() {
    triggerConnect()

    setBondStateTransition(BOND_NONE, BOND_BONDING)

    // This message is scheduled but should not be handled.
    // It should be cleared after bonding state changes.
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

    // Device state is now NONE
    setBondStateTransition(BOND_BONDING, BOND_NONE)
    // Handle message: MSG_CONNECT
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    // After bonding state changes, another attempt was made.
    verify(gattHandle, times(2)).connect(any())
    assertThat(manager.isConnectionPaused.get()).isFalse()
  }

  @Test
  fun testSetDefaultMtu_requestsConfiguredMtu() {
    whenever(gattHandle.requestMtu(any())).thenReturn(true)

    // Arbitrary MTU size for this test.
    val defaultMtu = 200
    BluetoothGattManager.setDefaultMtu(context, defaultMtu)

    // Create a new instance because MTU is loaded at construction.
    manager =
      BluetoothGattManager(
        context,
        gattHandle,
        SERVICE_UUID,
        clientWriteCharacteristicUuid = WRITE_UUID,
        serverWriteCharacteristicUuid = READ_UUID,
      )
    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    verify(gattHandle).requestMtu(defaultMtu)
  }

  @Test
  fun testOnConnectionStateChange_requestMtuFails_retriesUpToMax() {
    // Mocking MTU request to fail.
    whenever(gattHandle.requestMtu(any())).thenReturn(false)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

    repeat(BluetoothGattManager.MAX_RETRY_COUNT) {
      // Handle message: MSG_REQUEST_MTU
      ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    verify(gattHandle, times(BluetoothGattManager.MAX_RETRY_COUNT)).requestMtu(any())
    // Max tries reached, so we should notify client of connection failure.
    verify(connectionCallback).onConnectionFailed()
  }

  @Test
  fun testOnMtuChanged_discoversServices() {
    whenever(gattHandle.requestMtu(any())).thenReturn(true)
    whenever(gattHandle.discoverServices()).thenReturn(true)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    triggerOnMtuChanged()

    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    verify(gattHandle).discoverServices()
  }

  @Test
  fun testRequestMtu_noMtuCallback_skipsToDiscoverServices() {
    whenever(gattHandle.requestMtu(any())).thenReturn(true)
    whenever(gattHandle.discoverServices()).thenReturn(true)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    verify(gattHandle, never()).discoverServices()

    // Handle message: MSG_SKIP_MTU_CALLBACK
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    verify(gattHandle).discoverServices()
  }

  @Test
  fun testDiscoverServicesFails_retriesUpToMax() {
    whenever(gattHandle.requestMtu(any())).thenReturn(true)
    whenever(gattHandle.discoverServices()).thenReturn(false)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnMtuChanged()

    repeat(BluetoothGattManager.MAX_RETRY_COUNT) {
      // Handle message: MSG_DISCOVER_SERVICES
      ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    verify(gattHandle, times(BluetoothGattManager.MAX_RETRY_COUNT)).discoverServices()
    verify(connectionCallback).onConnectionFailed()
  }

  @Test
  fun testOnServicesDiscovered_noContainingService_refreshes() {
    // GattHandle will not return any valid services.
    whenever(gattHandle.requestMtu(any())).thenReturn(true)
    whenever(gattHandle.discoverServices()).thenReturn(true)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnMtuChanged()
    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnServicesDiscoverd()

    verify(gattHandle).refresh()
  }

  @Test
  fun testOnServicesDiscovered_noReadOrWriteCharacteristics_refreshes() {
    whenever(gattHandle.requestMtu(any())).thenReturn(true)
    whenever(gattHandle.discoverServices()).thenReturn(true)

    // Returning a service without the read/write characteristics.
    val emptyService = BluetoothGattService(SERVICE_UUID, /* serviceType= */ 0)
    whenever(gattHandle.getService(SERVICE_UUID)).thenReturn(emptyService)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnMtuChanged()
    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnServicesDiscoverd()

    verify(gattHandle).refresh()
  }

  @Test
  fun testOnServiceDiscovered_enableCharacteristicNotification() {
    setUpValidGattHandle()
    // Additional setup to add descriptor.
    val descriptor =
      BluetoothGattDescriptor(
        BluetoothGattManager.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
      )
    containingService.getCharacteristic(READ_UUID).addDescriptor(descriptor)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnMtuChanged()
    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnServicesDiscoverd()

    // Handle message: MSG_ON_SERVICES_DISCOVERED
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    verify(gattHandle).writeDescriptor(descriptor)
  }

  @Test
  fun testOnServicesDiscovered_descriptorNotAvailable_retrievesDeviceNames() {
    setUpValidGattHandle()

    triggerConnect()

    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnMtuChanged()
    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnServicesDiscoverd()

    verify(gattHandle).readCharacteristic(deviceNameCharacteristic)
  }

  @Test
  fun testOnDescriptorWrite_retrieveDeviceName() {
    setUpValidGattHandle()
    // Additional setup to add descriptor.
    // Adding descriptor also associates the descriptor back to the characteristic.
    val descriptor =
      BluetoothGattDescriptor(
        BluetoothGattManager.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
      )
    containingService.getCharacteristic(READ_UUID).addDescriptor(descriptor)

    triggerConnect()
    manager.gattCallback.onDescriptorWrite(descriptor, BluetoothGatt.GATT_SUCCESS)

    verify(gattHandle).readCharacteristic(deviceNameCharacteristic)
  }

  @Test
  fun testNoDeviceNameCallback_notifiesCallback() {
    setUpValidGattHandle()

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnMtuChanged()
    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnServicesDiscoverd()

    // Simulate the timeout with waiting for the `onCharacteristicRead` callback.
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    verify(connectionCallback).onConnected()
  }

  @Test
  fun testOnDeviceNameRetrieved_notifiesCallback() {
    setUpValidGattHandle()

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    // Handle message: MSG_REQUEST_MTU
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnMtuChanged()
    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnServicesDiscoverd()

    manager.gattCallback.onCharacteristicRead(deviceNameCharacteristic, BluetoothGatt.GATT_SUCCESS)

    verify(connectionCallback).onConnected()
  }

  @Test
  fun testRetrieveAdvertisedData_success() =
    runTest(UnconfinedTestDispatcher()) {
      setUpValidGattHandle()
      triggerConnect()

      val deferredAdvertiseData = testScope.async { manager.retrieveAdvertisedData() }
      shadowOf(Looper.getMainLooper()).idle()

      manager.gattCallback.onCharacteristicRead(
        advertiseDataCharacteristic,
        BluetoothGatt.GATT_SUCCESS,
      )

      assertThat(deferredAdvertiseData.await() contentEquals ADVERTISE_DATA).isTrue()
    }

  @Test
  fun testRetrieveAdvertisedData_missingCharacteristic_null() =
    runTest(UnconfinedTestDispatcher()) {
      containingService.getCharacteristics().remove(advertiseDataCharacteristic)

      setUpValidGattHandle()
      triggerConnect()

      val deferredAdvertiseData = testScope.async { manager.retrieveAdvertisedData() }
      shadowOf(Looper.getMainLooper()).idle()
      ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

      verify(gattHandle, never()).readCharacteristic(any())
      assertThat(deferredAdvertiseData.await()).isNull()
    }

  @Test
  fun testOnServiceChanged_connected_discoverServices() {
    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

    triggerOnServiceChanged()

    verify(gattHandle).discoverServices()
  }

  @Test
  fun testOnServiceChanged_serviceDiscoveryCompleted_disconnect() {
    setUpValidGattHandle()
    // Additional setup to add descriptor.
    val descriptor =
      BluetoothGattDescriptor(
        BluetoothGattManager.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
      )
    containingService.getCharacteristic(READ_UUID).addDescriptor(descriptor)

    triggerConnect()
    triggerOnConnectionStateChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    triggerOnMtuChanged()

    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    triggerOnServicesDiscoverd()

    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    triggerOnServiceChanged()

    verify(gattHandle).disconnect()
  }

  @Test
  fun testDisconnect_bluetoothEnabled_callsGattDisconnect() {
    bluetoothAdapter.enable()
    manager.disconnect()
    verify(gattHandle).disconnect()
  }

  @Test
  fun testDisconnect_bluetoothDisabled_closesGatt() {
    bluetoothAdapter.disable()
    manager.disconnect()
    verify(gattHandle).close()
  }

  @Test
  fun testDisconnect_bluetoothDisabled_notifiesCallbacks() {
    bluetoothAdapter.disable()
    manager.disconnect()
    verify(connectionCallback).onDisconnected()
  }

  private fun triggerConnect() {
    manager.connect()
    // Handle message: MSG_CONNECT_GATT
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
  }

  private fun triggerOnConnectionStateChange(status: Int, state: Int) {
    manager.gattCallback.onConnectionStateChange(status, state)
    // Handle message: MSG_ON_CONNECTION_STATE_CHANGE
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
  }

  private fun triggerOnMtuChanged() {
    manager.gattCallback.onMtuChanged(BluetoothGattManager.MAXIMUM_MTU, BluetoothGatt.GATT_SUCCESS)
    // Handle message: MSG_ON_MTU_CHANGED
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
  }

  private fun triggerOnServicesDiscoverd() {
    manager.gattCallback.onServicesDiscovered(BluetoothGatt.GATT_SUCCESS)
    // Handle message: MSG_ON_SERVICES_DISCOVERED
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
  }

  private fun triggerOnServiceChanged() {
    manager.gattCallback.onServiceChanged()
    // Handle message: MSG_DISCOVER_SERVICES
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
  }

  private fun setBondStateTransition(from: Int, to: Int) {
    val intent =
      Intent(ACTION_BOND_STATE_CHANGED).apply {
        putExtra(EXTRA_DEVICE, bluetoothDevice)
        putExtra(EXTRA_PREVIOUS_BOND_STATE, from)
        putExtra(EXTRA_BOND_STATE, to)
      }
    manager.bluetoothBondStateBroadcastReceiver.onReceive(context, intent)
  }

  /**
   * Sets up a GATT Handle that will return all valid values necessary for a proper connection flow.
   */
  private fun setUpValidGattHandle() {
    whenever(gattHandle.requestMtu(any())).thenReturn(true)
    whenever(gattHandle.discoverServices()).thenReturn(true)

    // To allow for notifications when the car writes a values.
    whenever(gattHandle.setCharacteristicNotification(readCharacteristic, true)).thenReturn(true)

    // The service for device name retrieval.
    whenever(gattHandle.getService(BluetoothGattManager.GENERIC_ACCESS_PROFILE_UUID))
      .thenReturn(gapService)

    // The service that has the write and read characteristics.
    whenever(gattHandle.getService(SERVICE_UUID)).thenReturn(containingService)
  }
}
