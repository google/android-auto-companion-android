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

package com.google.android.libraries.car.notifications

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.provider.Settings

/**
 * This class helps as a bridge to the Settings Notification Access functionality for test purposes
 */
object SettingsNotificationHelper {
  private const val NOTIFICATION_LISTENER_SETTING_NAME = "enabled_notification_listeners"

  fun revokeAllNotificationAccess(resolver: ContentResolver) {
    Settings.Secure.putString(resolver, NOTIFICATION_LISTENER_SETTING_NAME, "")
  }

  fun grantNotificationAccess(context: Context) =
    Settings.Secure.putString(
      context.contentResolver,
      NOTIFICATION_LISTENER_SETTING_NAME,
      notificationListenerComponentName(context)
    )

  fun notificationListenerComponentName(context: Context) =
    ComponentName(
      context,
      NotificationListener::class.java
    ).flattenToString()
}
