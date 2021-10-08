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
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import java.util.UUID

/** Provides Bluetooth GATT functionality with a remote peripheral. */
interface GattHandle {
  /** The remote [BluetoothDevice] that this GATT communicates with. */
  val device: BluetoothDevice

  /** The callback that will be notified of various GATT events. */
  var callback: GattHandleCallback?

  /** Attempts to connect to the remote [device]. */
  fun connect(context: Context)

  /**
   * Attempts to disconnect the current connection with the remote [device].
   *
   * The [callback] set on this handle will be notified of the status of this call.
   */
  fun disconnect()

  /**
   * Closes this handle and disposes of this handle.
   *
   * This method does not necessarily disconnect from the remote device. Ensure that [disconnect] is
   * called first.
   */
  fun close()

  /** Clears the internal cache and forces a refresh of the services of the remote device. */
  fun refresh()

  /**
   * Discover the services that are currently being offered by the remote [device] and returns
   * `true` if this call initiates successfully.
   *
   * Discovered services are passed to the [callback] that is set on this class.
   */
  fun discoverServices(): Boolean

  /** Retrieves the service on the remote [device] that is identified by the given [serviceUuid]. */
  fun getService(serviceUuid: UUID): BluetoothGattService?

  /**
   * Attempts to write the given [characteristic] and its value to the remote [device].
   *
   * This method returns `true` if the write was initiated successfully, but this does not mean that
   * the operation itself succeeded. The [callback] set on this handle will be notified of the
   * resulting status.
   */
  fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean

  /**
   * Issues a request to read the given [characteristic] on the remote [device] and returns `true`
   * if the request was initiated successfully.
   *
   * The [callback] set on this handle will be notified of the result of the read.
   */
  fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean

  /**
   * Sets if notifications for the given [characteristic] are enabled and returns `true` if the
   * request was sent successfully.
   *
   * If notifications are enabled, then the [callback] set on this handle will be notified whenever
   * the value in the characteristic has changed.
   */
  fun setCharacteristicNotification(
    characteristic: BluetoothGattCharacteristic,
    isEnabled: Boolean
  ): Boolean

  /**
   * Issues a request to set the MTU of the GATT connection to be the given [mtuSize] and returns
   * `true` if the operation was initiated successfully.
   *
   * The [callback] set on this handle will be notified of the resulting MTU size and if the request
   * succeeded.
   */
  fun requestMtu(mtuSize: Int): Boolean

  /**
   * Issues a request to set the connection `priority` and returns 'true' if the operation was
   * initiated succesfully.
   */
  fun requestConnectionPriority(priority: Int): Boolean

  /**
   * Writes the value of [descriptor] to the remote [device].
   *
   * Returns `true` if the operation was initiated succesfully; the [callback] set on this handle
   * will be notified of the result of the write.
   */
  fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean
}
