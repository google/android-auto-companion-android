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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import java.util.UUID

/**
 * Provides GATT functionality by delegating to a [BluetoothGatt].
 *
 * @property[transport] Transport used for GATT connection. Can only be set as values of
 * BluetoothDevice.TRANSPORT_*.
 */
class BluetoothGattHandle(
  private val bluetoothDevice: BluetoothDevice,
  private val transport: Int
) : GattHandle {
  private var bluetoothGatt: BluetoothGatt? = null

  override val device
    get() = bluetoothGatt?.device ?: bluetoothDevice

  override var callback: GattHandleCallback? = null

  override fun connect(context: Context) {
    bluetoothGatt?.let {
      logw(TAG, "Call to `connect`, but already connected. Ignoring.")
      return
    }

    logi(TAG, "Call to connect. Using GATT transport: $transport")
    bluetoothGatt =
      bluetoothDevice.connectGatt(context, /* autoConnect= */ false, gattCallback, transport)
  }

  override fun disconnect() {
    bluetoothGatt?.disconnect()
  }

  override fun close() {
    bluetoothGatt?.close()
    bluetoothGatt = null
  }

  /**
   * Forces a refresh of the cached services from the remote device.
   *
   * This method will take up to 3ms. Refer to b/150745581 for more context.
   */
  override fun refresh() {
    val gatt = bluetoothGatt
    if (gatt == null) {
      logw(TAG, "Request to refresh internal cache, but `connect` has not been called yet.")
      return
    }

    try {
      // Method link:
      // https://cs/android/frameworks/base/core/java/android/bluetooth/BluetoothGatt.java?q=refresh%5C(%5C)
      //
      // This method will clear the internal cache and force a refresh of the services from the
      // remote device.
      val refreshMethod = gatt.javaClass.getMethod("refresh")
      refreshMethod.invoke(gatt)
    } catch (e: Exception) {
      loge(TAG, "Call to `refresh` encountered an exception: ${e.message}")
    }
  }

  override fun discoverServices(): Boolean {
    return bluetoothGatt?.discoverServices() ?: false
  }

  override fun getService(serviceUuid: UUID): BluetoothGattService? {
    return bluetoothGatt?.getService(serviceUuid)
  }

  override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
    return bluetoothGatt?.writeCharacteristic(characteristic) ?: false
  }

  override fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
    return bluetoothGatt?.readCharacteristic(characteristic) ?: false
  }

  override fun setCharacteristicNotification(
    characteristic: BluetoothGattCharacteristic,
    isEnabled: Boolean
  ): Boolean {
    return bluetoothGatt?.setCharacteristicNotification(characteristic, isEnabled) ?: false
  }

  override fun requestMtu(mtuSize: Int): Boolean {
    return bluetoothGatt?.requestMtu(mtuSize) ?: false
  }

  override fun requestConnectionPriority(priority: Int): Boolean {
    return bluetoothGatt?.requestConnectionPriority(priority) ?: false
  }

  override fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean {
    return bluetoothGatt?.writeDescriptor(descriptor) ?: false
  }

  private val gattCallback =
    object : BluetoothGattCallback() {
      override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        callback?.onConnectionStateChange(status, newState)
      }

      override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        callback?.onMtuChanged(mtu, status)
      }

      override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        callback?.onServicesDiscovered(status)
      }

      override fun onCharacteristicChanged(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?
      ) {
        if (characteristic == null) {
          loge(TAG, "onCharacteristicChanged received null characteristic. Not notifying callback.")
          return
        }
        callback?.onCharacteristicChanged(characteristic)
      }

      override fun onCharacteristicWrite(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
        status: Int
      ) {
        if (characteristic == null) {
          loge(TAG, "onCharacteristicWrite received null characteristic. Not notifying callback.")
          return
        }
        callback?.onCharacteristicWrite(characteristic, status)
      }

      override fun onCharacteristicRead(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        status: Int
      ) {
        callback?.onCharacteristicRead(characteristic, status)
      }

      override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
      ) {
        callback?.onDescriptorWrite(descriptor, status)
      }
    }

  companion object {
    private const val TAG = "BluetoothGattHandle"
  }
}
