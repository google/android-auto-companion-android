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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.notifications.SettingsNotificationHelper.grantNotificationAccess
import com.google.android.libraries.car.notifications.SettingsNotificationHelper.revokeAllNotificationAccess
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Tests for [MessagingUtils]. */
@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class MessagingUtilsTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val testScope = TestScope()

  private val deviceId = "deviceId"
  private val packageName = "com.package.com"
  private lateinit var onSuccess: () -> Unit
  private lateinit var onFailure: () -> Unit

  private lateinit var messagingUtils: MessagingUtils

  @Before
  fun setup() {
    messagingUtils = MessagingUtils(context, testScope)

    grantNotificationAccess(context)

    onSuccess = mock()
    onFailure = mock()
  }

  @After
  fun teardown() {
    messagingUtils.disableMessagingSync(deviceId)
    revokeAllNotificationAccess(context.contentResolver)
  }

  @Test
  fun testInitialState() {
    assertThat(messagingUtils.isMessagingSyncEnabled(deviceId)).isFalse()
    assertThat(messagingUtils.isNotificationAccessEnabled()).isTrue()
    revokeAllNotificationAccess(context.contentResolver)
    assertThat(messagingUtils.isNotificationAccessEnabled()).isFalse()
  }

  @Test
  fun enableMessagingSync_enables_onSuccessCallbackCalled() {
    messagingUtils.enableMessagingSync(deviceId, onSuccess, onFailure)
    // enableMessagingSync() launches a coroutine to request notification access.
    // Run the scheduled coroutine to complete the request, which makes a callback of whether the
    // access has been granted.
    testScope.testScheduler.runCurrent()

    assertThat(messagingUtils.isMessagingSyncEnabled(deviceId)).isTrue()
    verify(onSuccess).invoke()
    verify(onFailure, never()).invoke()
  }

  @Test
  fun isMessagingSyncEnabled_revokedAccess_enabledStateReturnsFalse() {
    messagingUtils.enableMessagingSync(deviceId, onSuccess, onFailure)
    testScope.testScheduler.runCurrent()
    revokeAllNotificationAccess(context.contentResolver)

    assertThat(messagingUtils.isMessagingSyncEnabled(deviceId)).isFalse()
  }

  @Test
  fun enableMessagingSync_failsWhenNotificationAccessIsNotGranted() {
    revokeAllNotificationAccess(context.contentResolver)
    messagingUtils.enableMessagingSync(deviceId, onSuccess, onFailure)
    testScope.testScheduler.runCurrent()

    assertThat(messagingUtils.isMessagingSyncEnabled(deviceId)).isFalse()
  }

  @Test
  fun enableMessagingSync_succeedsIfNotificationAccessIsGranted() {
    revokeAllNotificationAccess(context.contentResolver)
    messagingUtils.enableMessagingSync(deviceId, onSuccess, onFailure)
    grantNotificationAccess(context)
    testScope.testScheduler.runCurrent()

    assertThat(messagingUtils.isMessagingSyncEnabled(deviceId)).isTrue()
    verify(onSuccess).invoke()
    verify(onFailure, never()).invoke()
  }

  @Test
  fun disableMessagingSync_disables() {
    messagingUtils.enableMessagingSync(deviceId, onSuccess, onFailure)
    testScope.testScheduler.runCurrent()

    messagingUtils.disableMessagingSync(deviceId)

    assertThat(messagingUtils.isMessagingSyncEnabled(deviceId)).isFalse()
  }

  @Test
  fun disableMessagingSync_all() {
    messagingUtils.enableMessagingSync(deviceId, onSuccess, onFailure)
    testScope.testScheduler.runCurrent()

    messagingUtils.disableMessagingSyncForAll()

    assertThat(messagingUtils.isMessagingSyncEnabled(deviceId)).isFalse()
  }
}
