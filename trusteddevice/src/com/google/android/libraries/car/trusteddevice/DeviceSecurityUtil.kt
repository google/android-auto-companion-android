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

package com.google.android.libraries.car.trusteddevice

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logwtf
import java.util.UUID

private const val TAG = "DeviceSecurityUtil"
private const val IS_DEVICE_UNLOCK_REQUIRED_KEY_PREFIX = "is_device_unlock_required_key_prefix"
private const val DEFAULT_IS_DEVICE_UNLOCK_REQURED = true

internal fun isDeviceLocked(context: Context): Boolean {
  val keyguardManager = context.getSystemService(KeyguardManager::class.java)
  if (keyguardManager == null) {
    loge(TAG, "Could not determine if device is unlocked.")
    // Treat as device was locked.
    return true
  }
  return keyguardManager.isDeviceLocked
}

internal fun isDeviceSecure(context: Context): Boolean {
  val keyguardManager = context.getSystemService(KeyguardManager::class.java)
  if (keyguardManager == null) {
    loge(TAG, "Could not determine if device is secured.")
    // Treat as device was not secure.
    return false
  }
  return keyguardManager.isDeviceSecure
}

/**
 * Sets whether the phone is required to be unlocked before it can unlock the car with id [carId].
 */
fun setDeviceUnlockRequired(carId: UUID, isRequired: Boolean, sharedPref: SharedPreferences) {
  with(sharedPref.edit()) {
    putBoolean(IS_DEVICE_UNLOCK_REQUIRED_KEY_PREFIX + carId.toString(), isRequired)
    if (!commit()) {
      logwtf(TAG, "setDeviceUnlockRequired: could not persist config: $isRequired for car $carId.")
    }
  }
}

/**
 * Whether the phone is required to be unlocked before it can unlock the car with id [carId].
 */
fun isDeviceUnlockRequired(carId: UUID, sharedPref: SharedPreferences): Boolean {
  with(sharedPref) {
    return getBoolean(
      IS_DEVICE_UNLOCK_REQUIRED_KEY_PREFIX + carId.toString(),
      DEFAULT_IS_DEVICE_UNLOCK_REQURED
    )
  }
}
