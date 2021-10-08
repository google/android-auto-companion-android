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
import com.google.android.libraries.car.notifications.CoroutineTestRule
import com.google.android.libraries.car.notifications.SettingsNotificationHelper.grantNotificationAccess
import com.google.android.libraries.car.notifications.SettingsNotificationHelper.revokeAllNotificationAccess
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [MessagingUtils].
 */
@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class MessagingUtilsTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val messagingUtils = MessagingUtils(context)

  private val carId = "carId"
  private val packageName = "com.package.com"
  private lateinit var onSuccess: () -> Unit
  private lateinit var onFailure: () -> Unit

  @get:Rule
  var coroutinesTestRule = CoroutineTestRule()

  @Before
  fun setup() {
    grantNotificationAccess(context)
    onSuccess = mock()
    onFailure = mock()
  }

  @After
  fun teardown() {
    messagingUtils.disableMessagingSync(carId)
    revokeAllNotificationAccess(context.contentResolver)
  }

  @Test
  fun testInitialState() {
    assertThat(messagingUtils.isMessagingSyncEnabled(carId)).isFalse()
    assertThat(messagingUtils.isNotificationAccessEnabled()).isTrue()
    revokeAllNotificationAccess(context.contentResolver)
    assertThat(messagingUtils.isNotificationAccessEnabled()).isFalse()
  }

  @Test
  fun enableMessagingSync_enables_onSuccessCallbackCalled() {
    messagingUtils.enableMessagingSync(carId, onSuccess, onFailure)
    assertThat(messagingUtils.isMessagingSyncEnabled(carId)).isTrue()
    verify(onSuccess).invoke()
    verify(onFailure, never()).invoke()
  }

  @Test
  fun isMessagingSyncEnabled_revokedAccess_enabledStateReturnsFalse() {
    messagingUtils.enableMessagingSync(carId, onSuccess, onFailure)
    revokeAllNotificationAccess(context.contentResolver)
    assertThat(messagingUtils.isMessagingSyncEnabled(carId)).isFalse()
  }

  @Test
  fun enableMessagingSync_failsWhenNotificationAccessIsNotGranted() {
    revokeAllNotificationAccess(context.contentResolver)
    messagingUtils.enableMessagingSync(carId, onSuccess, onFailure)
    assertThat(messagingUtils.isMessagingSyncEnabled(carId)).isFalse()
  }

  @Test
  fun enableMessagingSync_succeedsIfNotificationAccessIsGranted() {
    revokeAllNotificationAccess(context.contentResolver)
    val coroutineScope = messagingUtils.enableMessagingSync(carId, onSuccess, onFailure)
    grantNotificationAccess(context)
    runBlocking { coroutineScope.join() }
    assertThat(messagingUtils.isMessagingSyncEnabled(carId)).isTrue()
    verify(onSuccess).invoke()
    verify(onFailure, never()).invoke()
  }

  @Test
  fun disableMessagingSync_disables() {
    messagingUtils.enableMessagingSync(carId, onSuccess, onFailure)
    messagingUtils.disableMessagingSync(carId)
    assertThat(messagingUtils.isMessagingSyncEnabled(carId)).isFalse()
  }
}
