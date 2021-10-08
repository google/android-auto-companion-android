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

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

/** Callback for different states of a [GattHandle]. */
interface GattHandleCallback {
  /**
   * Invoked when the connection state of the [gatt] has changed to [newState].
   *
   * If the change was successful, then [status] will be [BluetoothGatt.GATT_SUCCESS]. [newState]
   * can be either [BluetoothProfile.STATE_DISCONNECTED] or [BluetoothProfile.STATE_CONNECTED].
   */
  fun onConnectionStateChange(status: Int, newState: Int)

  /**
   * Invoked when the MTU size of the [gatt] has been changed to the given [mtu] size.
   *
   * The size is only valid if [status] is [BluetoothGatt.GATT_SUCCESS].
   */
  fun onMtuChanged(mtu: Int, status: Int)

  /**
   * Invoked when the list of remote services, characteristics and descriptors for the [gatt] have
   * been updated.
   *
   * The results are only valid if [status] is [BluetoothGatt.GATT_SUCCESS].
   */
  fun onServicesDiscovered(status: Int)

  /** Invoked when the value associated with the specified [characteristic] has changed. */
  fun onCharacteristicChanged(characteristic: BluetoothGattCharacteristic)

  /**
   * Invoked to indicate the [status] of a write of the specified [characteristic].
   *
   * [status] will be [Bluetooth.GATT_SUCCESS] on a successful write.
   */
  fun onCharacteristicWrite(characteristic: BluetoothGattCharacteristic, status: Int)

  /**
   * Invoked to indicate the [status] of a read of the value on the specified [characteristic].
   *
   * [status] will be [Bluetooth.GATT_SUCCESS] if the read was successful. The value requested to be
   * read can be found on the `value` of the characteristic.
   */
  fun onCharacteristicRead(characteristic: BluetoothGattCharacteristic, status: Int)

  /**
   * Invoked to indicate the [status] of a write of the specified [descriptor].
   *
   * [status] will be [Bluetooth.GATT_SUCCESS] on a successful write.
   */
  fun onDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int)
}
