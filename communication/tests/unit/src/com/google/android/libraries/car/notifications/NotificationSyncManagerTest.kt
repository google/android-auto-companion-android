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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [NotificationSyncManager].
 */
@RunWith(AndroidJUnit4::class)
class NotificationSyncManagerTest {
  private lateinit var handler: NotificationHandler
  private lateinit var sbn: StatusBarNotification
  private lateinit var notificationListener: NotificationListener

  @Before
  fun setup() {
    handler = mock()
    sbn = mock()
    notificationListener = NotificationListener()
  }

  @Test
  fun onNotificationPosted_onNotificationReceivedIsCalled() {
    NotificationSyncManager.addNotificationHandler(handler)
    notificationListener.onNotificationPosted(sbn)
    verify(handler).onNotificationReceived(sbn)
  }

  @Test
  fun addNotificationHandler_onNotificationReceivedIsCalled() {
    NotificationSyncManager.addNotificationHandler(handler)
    NotificationSyncManager.onNotificationReceived(sbn)
    verify(handler).onNotificationReceived(sbn)
  }

  @Test
  fun removeNotificationHandler_onNotificationReceivedIsNotCalled() {
    NotificationSyncManager.addNotificationHandler(handler)
    NotificationSyncManager.removeNotificationHandler(handler)
    NotificationSyncManager.onNotificationReceived(sbn)
    verify(handler, never()).onNotificationReceived(sbn)
  }
}
