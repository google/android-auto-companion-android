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

/** Provides association functionality with a remote manager. */
interface AssociationHandler {
  /** List of mac address of associated devices. */
  val associations: List<String>

  /** Associates this app with devices filtered by [request], selected by user. */
  fun associate(
    activity: Activity,
    request: AssociationRequest,
    callback: CompanionDeviceManager.Callback
  )

  /**
   * Removes the association between this app and the device with the given mac address. Returns
   * `true` when the disassociation succeed
   */
  fun disassociate(macAddress: String): Boolean
}
