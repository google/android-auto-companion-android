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
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Provides useful methods to request Notification Access and poll for permission granted */
object NotificationAccessUtils {
  @VisibleForTesting const val MAXIMUM_NOTIFICATION_ACCESS_ITERATIONS = 50
  @VisibleForTesting const val NOTIFICATION_ACCESS_CHECK_TIME_MS = 1000L
  @VisibleForTesting const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
  @VisibleForTesting const val EXTRA_SHOW_FRAGMENT_ARGS_KEY = ":settings:show_fragment_args"

  /**
   * Routes the user to Notification Access Settings Page, and reroutes onSuccess or onFailure
   *
   * @param onSuccess, called if notification is granted
   * @param onFailure, called if notification access is not granted
   */
  fun requestNotificationAccess(context: Context, onSuccess: () -> Unit, onFailure: (() -> Unit)?) {
    CoroutineScope(Dispatchers.Main).launch {
      val notificationGranted = NotificationAccessUtils.requestNotificationAccess(context)

      if (notificationGranted) {
        onSuccess()
      } else {
        onFailure?.let { it() }
      }
    }
  }

  /**
   * Routes the user to Notification Access Settings Page, and continuously checks to see if
   * permission is granted.
   *
   * @param context Context to be passed to [hasNotificationAccess]
   */
  suspend fun requestNotificationAccess(context: Context): Boolean {
    if (hasNotificationAccess(context)) {
      return true
    }
    navigateToNotificationAccessSettings(context)
    return pollForNotificationAccessGrant(context)
  }

  fun hasNotificationAccess(context: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

  private suspend fun pollForNotificationAccessGrant(context: Context): Boolean {
    return withContext(Dispatchers.Default) {
      for (i in 0..MAXIMUM_NOTIFICATION_ACCESS_ITERATIONS) {
        if (hasNotificationAccess(context)) break
        delay(NOTIFICATION_ACCESS_CHECK_TIME_MS)
      }
      hasNotificationAccess(context)
    }
  }

  private fun navigateToNotificationAccessSettings(context: Context) =
    context.startActivity(getNotificationPermissionIntentWithHighlighted(context))

  /**
   * Requests permission intent from [.getNotificationPermissionIntent], put extras to highlight the
   * Notification Listener in notification settings.
   *
   * Note that the highlighting feature **ONLY** works on P+.
   */
  @VisibleForTesting
  fun getNotificationPermissionIntentWithHighlighted(context: Context) =
    notificationPermissionIntent.apply {
      putExtra(EXTRA_SHOW_FRAGMENT_ARGS_KEY, notificationPermissionHighlightBundle(context))
    }

  /**
   * Returns an Intent that is safe to call [Context.startActivity] on. The intent will launch the
   * Android settings app's notification listener permission screen.
   *
   * Returns null if the notification listener permission activity could not be resolved.
   */
  private val notificationPermissionIntent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

  private fun notificationPermissionHighlightBundle(context: Context) =
    Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, notificationListenerComponentName(context)) }

  private fun notificationListenerComponentName(context: Context) =
    ComponentName(context, NotificationListener::class.java).flattenToString()
}
