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

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings

/** A wrapper around the [BluetoothManager] that abstracts away access to any BLE functionality. */
internal class BluetoothManagerWrapper(bluetoothManager: BluetoothManager) : BleManager {
  private val bluetoothAdapter = bluetoothManager.adapter

  // On some Android devices, the BluetoothLeScanner is `null` if Bluetooth is currently
  // turned off but returns correctly once it is turned on. So always retrieve the scanner out of
  // the adapter.
  private val bluetoothLeScanner: BluetoothLeScanner?
    get() = bluetoothAdapter.bluetoothLeScanner

  override val isEnabled: Boolean
    get() = bluetoothAdapter.isEnabled()

  override fun startScan(
    filters: List<ScanFilter>,
    settings: ScanSettings,
    callback: ScanCallback
  ): Boolean {
    if (!isEnabled) {
      return false
    }

    bluetoothLeScanner?.let {
      it.startScan(filters, settings, callback)
      return true
    }

    return false
  }

  override fun stopScan(callback: ScanCallback): Boolean {
    if (!isEnabled) {
      return false
    }

    bluetoothLeScanner?.let {
      it.stopScan(callback)
      return true
    }

    return false
  }
}
