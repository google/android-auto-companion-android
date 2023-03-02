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

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattHandle
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattManager
import java.util.Objects
import java.util.UUID

/**
 * A car that can be associated with.
 *
 * Association can be initiated by [AssociationManager]. A successful association creates a [Car],
 * which can be used to securely exchange messages.
 *
 * @property device discovered remote bluetooth device
 * @property name Name from BLE advertisement or the bluetooth device.
 * @property gattServiceUuid The UUID of the GATT service to start association with.
 */
open internal class DiscoveredCar
internal constructor(
  internal val device: BluetoothDevice,
  open val name: String,
  internal val gattServiceUuid: UUID,
  internal var oobConnectionManager: OobConnectionManager? = null
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    return other is DiscoveredCar &&
      device == other.device &&
      name == other.name &&
      gattServiceUuid == other.gattServiceUuid &&
      oobConnectionManager == other.oobConnectionManager
  }

  override fun hashCode(): Int {
    return Objects.hash(device, name, gattServiceUuid, oobConnectionManager)
  }
  /**
   * Converts to a list of [BluetoothConnectionManager]s.
   *
   * Each manager represents a supported connection protocol. The order of the list determines the
   * priority of connection attempt.
   */
  internal open fun toBluetoothConnectionManagers(
    context: Context
  ): List<BluetoothConnectionManager> {
    val managers =
      mutableListOf<BluetoothConnectionManager>(
        BluetoothGattManager(
          context,
          BluetoothGattHandle(device, context.gattTransport),
          gattServiceUuid,
          CLIENT_WRITE_CHARACTERISTIC_UUID,
          SERVER_WRITE_CHARACTERISTIC_UUID
        )
      )
    return managers
  }

  companion object {
    // Default value used by car enrollment service.
    private val SERVER_WRITE_CHARACTERISTIC_UUID =
      UUID.fromString("5e2a68a5-27be-43f9-8d1e-4546976fabd7")

    // Default value used by car enrollment service.
    private val CLIENT_WRITE_CHARACTERISTIC_UUID =
      UUID.fromString("5e2a68a6-27be-43f9-8d1e-4546976fabd7")
  }
}
