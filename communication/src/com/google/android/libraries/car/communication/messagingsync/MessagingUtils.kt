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

package com.google.android.libraries.car.communication.messagingsync

import android.content.Context
import android.content.SharedPreferences
import com.google.android.libraries.car.notifications.NotificationAccessUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Provides util methods for Messaging Sync Feature
 */
internal class MessagingUtils constructor(private val context: Context) {
  private val enabledCars
    get() = sharedPreferences
      .getStringSet(ENABLED_CARS_KEY, mutableSetOf())
      ?: mutableSetOf()

  private val sharedPreferences
    get() = context.getSharedPreferences(
      MESSAGING_SYNC_SHARED_PREFERENCE_KEY,
      Context.MODE_PRIVATE
    )

  fun isMessagingSyncEnabled(deviceId: String) =
    enabledCars.contains(deviceId) &&
      NotificationAccessUtils.hasNotificationAccess(context)

  fun isNotificationAccessEnabled() =
    NotificationAccessUtils.hasNotificationAccess(context)

  /**
   * Handles the user flow to request user permissions and turn on messaging sync.
   */
  fun enableMessagingSync(deviceId: String, onSuccess: () -> Unit, onFailure: (() -> Unit)?) =
    CoroutineScope(Dispatchers.Main).launch {
      val notificationGranted =
        NotificationAccessUtils.requestNotificationAccess(context)

      if (notificationGranted) {
        enableMessagingSyncSharedPreferences(deviceId)
        DebugLogs.logMessagingSyncFeatureEnabled()
        onSuccess()
      } else {
        onFailure?.let { it() }
      }
    }

  /**
   * Turns off messaging sync feature.
   */
  fun disableMessagingSync(deviceId: String) =
    sharedPreferences.putStringSet(
      ENABLED_CARS_KEY,
      enabledCars.apply {
        remove(deviceId)
      }
    ).also {
      DebugLogs.logMessagingSyncFeatureDisabled()
    }

  /**
   * Turns off messaging sync feature for all cars.
   */
  fun disableMessagingSyncForAll() =
    sharedPreferences.edit().remove(ENABLED_CARS_KEY).apply()

  private fun enableMessagingSyncSharedPreferences(deviceId: String) =
    sharedPreferences.putStringSet(
      ENABLED_CARS_KEY,
      enabledCars.apply {
        add(deviceId)
      }
    )

  private fun SharedPreferences.putStringSet(key: String, set: Set<String>) =
    edit().putStringSet(key, set).apply()

  companion object {
    private const val MESSAGING_SYNC_SHARED_PREFERENCE_KEY =
      "TrustedDevice.MessagingSyncPreferencesKey"
    private const val ENABLED_CARS_KEY = "enabledCars"
  }
}
