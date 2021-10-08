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

/** Converts value of R.string.gatt_transport to [BluetoothDevice] TRANSPORT_* constant. */
internal val Context.gattTransport: Int
  get() =
    when (getString(R.string.gatt_transport)) {
      "TRANSPORT_AUTO" -> BluetoothDevice.TRANSPORT_AUTO
      "TRANSPORT_BREDR" -> BluetoothDevice.TRANSPORT_BREDR
      "TRANSPORT_LE" -> BluetoothDevice.TRANSPORT_LE
      else -> {
        throw IllegalArgumentException("Unrecognized value of R.string.gatt_transport.")
      }
    }

/**
 * Names of bluetooth devices that we should always connect to.
 *
 * This value is intended for BLE proxy, which does not support advertising a challenge and its
 * salt. As a workaround, allow a list of device names to always connect to.
 *
 * The resource ID is R.string.always_allowed_device_names. The names are separated by comma.
 */
internal val Context.alwaysAllowedDeviceNames: List<String>
  get() {
    val delimiter = ","
    return getString(R.string.always_allowed_device_names).split(delimiter)
  }
