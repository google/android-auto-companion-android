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
import android.content.IntentSender
import com.google.android.libraries.car.trustagent.api.PublicApi
import java.util.UUID

/** Parameters to start a discovery for companion device to associate with. */
@PublicApi
data class DiscoveryRequest
private constructor(
  val activity: Activity,
  val namePrefix: String,
  val associationUuid: UUID?,
  val deviceIdentifier: ByteArray?
) {
  /**
   * A builder for [DiscoveryResult]s.
   *
   * @property activity The [Activity] that will handle the discovery result, which is an
   * [IntentSender] returned through [ConnectedDeviceManager.Callback]. The discovery will be
   * stopped if the activity is destroyed.
   */
  @PublicApi
  class Builder(private val activity: Activity) {
    /**
     * May be added to the beginning of the advertised names of cars that are found.
     *
     * The prefix will be added only when doing so matches the advertisement name the car is
     * currently displaying.
     *
     * Defaults to an empty string.
     */
    var namePrefix: String = ""

    /**
     * The UUID used for association discovery.
     *
     * Defaults to `null`. If specified, overrides the value specified by
     * R.string.car_association_service_uuid in XML.
     */
    var associationUuid: UUID? = null

    /**
     * The data that identifies the device.
     *
     * Adding this value will ensure that the discovery will only attempt to surface results that
     * match the given value. Any non-`null` value here will be utilized. Defaults to `null`.
     *
     * The data can be obtained through channels before discovery is started, e.g. QR code scanning
     * or NFC, which requires setup on IHU.
     */
    // TODO(b/202849608): link to external QR code doc to explain how to retrieve deviceIdentifier.
    var deviceIdentifier: ByteArray? = null

    /** Creates a [DiscoveryRequest]. */
    fun build(): DiscoveryRequest =
      DiscoveryRequest(activity, namePrefix, associationUuid, deviceIdentifier)
  }
}

/**
 * Convenience DSL for constructing a [DiscoveryRequest].
 *
 * Refer to [DiscoveryRequest.Builder] for documentation on the properties that can be modified.
 *
 * @property activity The [Activity] that will handle the discovery result, which is an
 * [IntentSender] returned through [ConnectedDeviceManager.Callback]. The discovery will be stopped
 * if the activity is destroyed.
 * @param block Lambda with a receiver of [DiscoveryRequest.Builder].
 */
inline fun discoveryRequest(
  activity: Activity,
  block: DiscoveryRequest.Builder.() -> Unit
): DiscoveryRequest = DiscoveryRequest.Builder(activity).apply(block).build()
