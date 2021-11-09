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

import android.content.Intent
import com.google.android.libraries.car.trustagent.api.PublicApi

/** Parameters to start association with a companion device. */
@PublicApi
data class AssociationRequest private constructor(val intent: Intent, val oobData: OobData?) {
  /**
   * Builder for [AssociationRequest].
   *
   * @param Intent that contains the device that user selected to associate.
   * [ConnectedDeviceManager.Callback.onDeviceDiscovered] returns an [IntentSender]. Launching the
   * IntentSender allows the user to select a device to associate. The user selection is pass back
   * to [Activity.onActivityResult] as an [Intent]. The Intent should be set in this request.
   */
  @PublicApi
  class Builder(private val intent: Intent) {
    /**
     * The out-of-band data from out-of-band channel.
     *
     * Defaults to `null`. If set, the association will attempt to confirm the encryption based on
     * this data.
     */
    var oobData: OobData? = null

    /** Creates a [AssociationRequest]. */
    fun build(): AssociationRequest = AssociationRequest(intent, oobData)
  }
}

/**
 * Convenience DSL for constructing a [AssociationRequest].
 *
 * Refer to [AssociationRequest.Builder] for documentation on the properties that can be modified.
 *
 * @property activity The [Activity] that will handle the discovery result, which is an
 * [IntentSender] returned through [ConnectedDeviceManager.Callback]. The discovery will be stopped
 * if the activity is destroyed.
 * @param block Lambda with a receiver of [AssociationRequest.Builder].
 */
inline fun associationRequest(
  intent: Intent,
  block: AssociationRequest.Builder.() -> Unit
): AssociationRequest = AssociationRequest.Builder(intent).apply(block).build()
