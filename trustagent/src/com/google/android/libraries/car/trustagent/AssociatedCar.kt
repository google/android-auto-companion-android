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

import com.google.android.libraries.car.trustagent.api.PublicApi
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Represents a [Car] for which this device has been associated.
 *
 * @property deviceId Unique car device ID.
 * @property name Bluetooth device name of car.
 * @property macAddress MAC address of the associated car.
 */
@PublicApi
data class AssociatedCar(
  val deviceId: UUID,
  val name: String?,
  val macAddress: String,
  internal val identificationKey: SecretKey
) {
  /** Two instances are considered equal if they have the same [deviceId]. */
  override fun equals(other: Any?) = other is AssociatedCar && deviceId == other.deviceId

  override fun hashCode() = deviceId.hashCode()
}
