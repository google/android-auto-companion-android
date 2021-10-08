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

  fun isMessagingSyncEnabled(carId: String) =
    enabledCars.contains(carId) &&
      NotificationAccessUtils.hasNotificationAccess(context)

  fun isNotificationAccessEnabled() =
    NotificationAccessUtils.hasNotificationAccess(context)

  /**
   * Handles the user flow to request user permissions and turn on messaging sync.
   */
  fun enableMessagingSync(carId: String, onSuccess: () -> Unit, onFailure: (() -> Unit)?) =
    CoroutineScope(Dispatchers.Main).launch {
      val notificationGranted =
        NotificationAccessUtils.requestNotificationAccess(context)

      if (notificationGranted) {
        enableMessagingSyncSharedPreferences(carId)
        DebugLogs.logMessagingSyncFeatureEnabled()
        onSuccess()
      } else {
        onFailure?.let { it() }
      }
    }

  /**
   * Turns off messaging sync feature.
   */
  fun disableMessagingSync(carId: String) =
    sharedPreferences.putStringSet(
      ENABLED_CARS_KEY,
      enabledCars.apply {
        remove(carId)
      }
    ).also {
      DebugLogs.logMessagingSyncFeatureDisabled()
    }

  private fun enableMessagingSyncSharedPreferences(carId: String) =
    sharedPreferences.putStringSet(
      ENABLED_CARS_KEY,
      enabledCars.apply {
        add(carId)
      }
    )

  private fun SharedPreferences.putStringSet(key: String, set: Set<String>) =
    edit().putStringSet(key, set).apply()

  companion object {
    private const val MESSAGING_SYNC_SHARED_PREFERENCE_KEY =
      "TrustedDevice.MessagingSyncPreferences"
    private const val ENABLED_CARS_KEY = "enabledCars"
    private const val MESSAGING_APP_LIST_FEATURE_FLAG_ENABLED = false
  }
}
