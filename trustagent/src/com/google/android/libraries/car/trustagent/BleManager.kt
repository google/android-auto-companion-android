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

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings

/** A manager of BLE-related actions. */
internal interface BleManager {
  /** `true` if BLE is currently enabled, meaning the methods of this interface can be called. */
  val isEnabled: Boolean

  /**
   * Starts a BLE scan with the given [settings] and delivers any results after filtering with
   * [filters] to the given [callback].
   *
   * If the scan is started successfully, `true` is returned.
   */
  fun startScan(filters: List<ScanFilter>, settings: ScanSettings, callback: ScanCallback): Boolean

  /**
   * Stops a BLE scan that had been started with the given [callback] and returns `true` if
   * successful.
   */
  fun stopScan(callback: ScanCallback): Boolean
}
