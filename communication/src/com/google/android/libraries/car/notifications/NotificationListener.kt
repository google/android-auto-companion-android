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

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.android.libraries.car.trustagent.api.PublicApi

/**
 * Notification listener service. Intercepts notifications if permission is given to do so.
 */
@PublicApi
class NotificationListener : NotificationListenerService() {
  override fun onNotificationPosted(sbn: StatusBarNotification) {
    NotificationSyncManager.onNotificationReceived(sbn)
  }
}

interface NotificationHandler {
  /**
   * @param sbn [StatusBarNotification] received when a notification is posted
   */
  fun onNotificationReceived(sbn: StatusBarNotification)
}
