// Copyright 2022 Google LLC
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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

/** A fake implementation of a [BleManager] that allows its return values to be set. */
open class FakeBleManager() : BleManager {
  override var isEnabled = true

  private var callback: ScanCallback? = null

  /** Set to determine the return result of [startScan]. */
  var startScanSucceeds = true

  override fun startScan(
    filters: List<ScanFilter>,
    settings: ScanSettings,
    callback: ScanCallback
  ): Boolean {
    this.callback = callback
    return startScanSucceeds
  }

  override fun stopScan(callback: ScanCallback): Boolean {
    if (this.callback == callback) {
      this.callback = null
      return true
    }

    return false
  }

  /**
   * Triggers [ScanCallback.onScanResult] with the given [result] on a `callback` that is passed
   * to a call of [startScan].
   */
  fun triggerOnScanResult(result: ScanResult?) {
    callback?.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result)
  }
}
