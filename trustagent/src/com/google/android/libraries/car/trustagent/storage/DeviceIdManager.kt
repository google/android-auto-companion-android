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

package com.google.android.libraries.car.trustagent.storage

import android.content.Context
import java.util.UUID

private const val DEVICE_ID_SHARED_PREFERENCES =
  "com.google.android.libraries.car.trustagent.storage.DeviceIdManager"
private const val DEVICE_ID_KEY = "device_id_key"

/**
 * Retrieves the unique device ID of current device; generates a random ID if one is not available.
 */
fun getDeviceId(context: Context): UUID {
  val sharedPref = context.getSharedPreferences(DEVICE_ID_SHARED_PREFERENCES, Context.MODE_PRIVATE)
  if (!sharedPref.contains(DEVICE_ID_KEY)) {
    with(sharedPref.edit()) {
      putString(DEVICE_ID_KEY, UUID.randomUUID().toString())
      apply()
    }
  }
  val id =
    checkNotNull(sharedPref.getString(DEVICE_ID_KEY, null)) {
      """
    |Device ID is null. SharedPreference key is $DEVICE_ID_KEY;
    | SharedPreference contains key: ${sharedPref.contains(DEVICE_ID_KEY)}.
    """.trimMargin()
    }
  return UUID.fromString(id)
}
