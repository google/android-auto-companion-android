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

import android.content.Context
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.notifications.NotificationAccessUtils.EXTRA_FRAGMENT_ARG_KEY
import com.google.android.libraries.car.notifications.NotificationAccessUtils.EXTRA_SHOW_FRAGMENT_ARGS_KEY
import com.google.android.libraries.car.notifications.NotificationAccessUtils.MAXIMUM_NOTIFICATION_ACCESS_ITERATIONS
import com.google.android.libraries.car.notifications.NotificationAccessUtils.NOTIFICATION_ACCESS_CHECK_TIME_MS
import com.google.android.libraries.car.notifications.NotificationAccessUtils.getNotificationPermissionIntentWithHighlighted
import com.google.android.libraries.car.notifications.NotificationAccessUtils.hasNotificationAccess
import com.google.android.libraries.car.notifications.SettingsNotificationHelper.grantNotificationAccess
import com.google.android.libraries.car.notifications.SettingsNotificationHelper.notificationListenerComponentName
import com.google.android.libraries.car.notifications.SettingsNotificationHelper.revokeAllNotificationAccess
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for [NotificationAccessUtils].  */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class NotificationAccessUtilsTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @get:Rule
  val coroutinesTestRule = CoroutineTestRule()

  @Before
  fun setup() {
    revokeAllNotificationAccess(context.contentResolver)
  }

  @Test
  fun expectedSetupState() {
    assertThat(hasNotificationAccess(context)).isFalse()
  }

  @Test
  fun getNotificationPermissionIntentWithHighlighted_notificationListenerInBundle() {
    val intent = getNotificationPermissionIntentWithHighlighted(context)
    val extra = intent.getBundleExtra(EXTRA_SHOW_FRAGMENT_ARGS_KEY)
    assertThat(intent.flags or FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    assertThat(extra?.getString(EXTRA_FRAGMENT_ARG_KEY))
      .isEqualTo(notificationListenerComponentName(context))
  }

  @Test
  fun requestNotificationAccess_pollsForAccessGranted() {
    var granted = false
    val coroutineScope = CoroutineScope(coroutinesTestRule.testDispatcher).launch {
      granted = NotificationAccessUtils.requestNotificationAccess(context)
    }
    assertThat(granted).isFalse()
    grantNotificationAccess(context)
    runBlocking { coroutineScope.join() }
    assertThat(hasNotificationAccess(context)).isTrue()
    assertThat(granted).isTrue()
  }

  @Test
  fun pollForAccessGranted_maximumIterationTime() {
    assertThat(NOTIFICATION_ACCESS_CHECK_TIME_MS).isEqualTo(1000L)
    assertThat(MAXIMUM_NOTIFICATION_ACCESS_ITERATIONS).isEqualTo(50)
  }
}
