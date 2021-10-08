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

import android.service.notification.StatusBarNotification

/**
 * Manages syncing message notifications to the connected car
 */
object NotificationSyncManager {
  private val handlers = HashSet<NotificationHandler>()

  fun containsHandler(notificationHandler: NotificationHandler) =
    handlers.contains(notificationHandler)

  fun addNotificationHandler(notificationHandler: NotificationHandler) {
    handlers.add(notificationHandler)
  }

  fun removeNotificationHandler(notificationHandler: NotificationHandler) {
    handlers.remove(notificationHandler)
  }

  /**
   * Calls known [NotificationHandler] with [NotificationHandler.onNotificationReceived]
   */
  fun onNotificationReceived(sbn: StatusBarNotification) {
    handlers.forEach {
      it.onNotificationReceived(sbn)
    }
  }
}
