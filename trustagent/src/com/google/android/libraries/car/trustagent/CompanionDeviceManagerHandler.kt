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
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import com.google.android.libraries.car.trustagent.util.logi

/**
 * Provides association functionality by delegating to a [CompanionDeviceManager].
 *
 * @property [context] Used to get the [CompanionDeviceManager] instance.
 */
class CompanionDeviceManagerHandler(private val context: Context) : AssociationHandler {

  /**
   * List of mac address of devices which is associated with [CompanionDeviceManager] via the app.
   */
  override val associations: List<String>
    get() = context.getSystemService(CompanionDeviceManager::class.java).associations

  override fun associate(
    activity: Activity,
    request: AssociationRequest,
    callback: CompanionDeviceManager.Callback
  ) {
    activity
      .getSystemService(CompanionDeviceManager::class.java)
      .associate(request, callback, /* handler= */ null)
  }

  override fun disassociate(macAddress: String): Boolean {
    if (macAddress in associations) {
      logi(TAG, "Clearing CompanionDeviceManager association with device $macAddress.")
      context.getSystemService(CompanionDeviceManager::class.java).disassociate(macAddress)
      return true
    }
    return false
  }
  companion object {
    private const val TAG = "CompanionDeviceManagerHandler"
  }
}
