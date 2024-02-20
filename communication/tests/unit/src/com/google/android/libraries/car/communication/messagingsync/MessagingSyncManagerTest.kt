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
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class MessagingSyncManagerTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val deviceId = UUID.fromString("c2337f28-18ff-4f92-a0cf-4df63ab2c881")
  private val messagingSyncManager = MessagingSyncManager(context)
  private val handlers: Map<*, *>
    get() {
      val handlersField = messagingSyncManager.javaClass.getDeclaredField("messagingHandlers")
      handlersField.isAccessible = true
      return handlersField.get(messagingSyncManager) as Map<*, *>
    }
  private val msgAppPackageName = "com.package.com"

  @get:Rule
  val coroutinesTestRule = CoroutineTestRule()

  @Before
  fun setup() {
    grantNotificationAccess(context)
  }

  @Test
  fun initialState() {
    assertThat(messagingSyncManager.isNotificationAccessEnabled()).isTrue()
    assertThat(messagingSyncManager.isNotificationAccessEnabled()).isTrue()
    assertThat(messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())).isFalse()
  }

  @Test
  fun onCarConnected_newHandlerIsCreated() {
    messagingSyncManager.onCarConnected(deviceId)
    val handler = handlers[deviceId] as? MessagingNotificationHandler
    assertThat(handler).isNotNull()
    assertThat(handler?.isCarConnected).isTrue()
  }

  @Test
  fun onCarConnected_CalledTwice_PreviousHandlerReused() {
    messagingSyncManager.onCarConnected(deviceId)
    val handler = handlers[deviceId] as MessagingNotificationHandler
    messagingSyncManager.onCarConnected(deviceId)
    assertThat(handlers[deviceId] == handler).isTrue()
    assertThat(handler.isCarConnected).isTrue()
  }

  @Test
  fun onCarDisconnected_handlerDisconnectedAndRemoved() {
    messagingSyncManager.onCarConnected(deviceId)
    val previousHandler = handlers[deviceId] as MessagingNotificationHandler
    messagingSyncManager.onCarDisconnected(deviceId)
    assertThat(handlers[deviceId]).isNull()
    assertThat(previousHandler.isCarConnected).isFalse()
  }

  @Test
  fun onCarDisassociated() {
    messagingSyncManager.onCarConnected(deviceId)
    val previousHandler = handlers[deviceId] as MessagingNotificationHandler
    messagingSyncManager.onCarDisassociated(deviceId)
    assertThat(handlers[deviceId]).isNull()
    assertThat(previousHandler.isCarConnected).isFalse()
    assertThat(messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())).isFalse()
  }

  @Test
  fun onAllCarsDisassociated() {
    messagingSyncManager.onCarConnected(deviceId)
    val previousHandler = handlers[deviceId] as MessagingNotificationHandler
    messagingSyncManager.onAllCarsDisassociated()
    assertThat(handlers).isEmpty()
    assertThat(previousHandler.isCarConnected).isFalse()
    assertThat(messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())).isFalse()
  }

  @Test
  fun reassociateCar_disableMessagingSync() {
    messagingSyncManager.onCarConnected(deviceId)
    val previousHandler = handlers[deviceId] as MessagingNotificationHandler
    messagingSyncManager.onCarDisassociated(deviceId)
    assertThat(handlers[deviceId]).isNull()
    assertThat(previousHandler.isCarConnected).isFalse()
    assertThat(messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())).isFalse()

    messagingSyncManager.onCarConnected(deviceId)
    val handler = handlers[deviceId] as? MessagingNotificationHandler
    assertThat(handler).isNotNull()
    assertThat(handler?.isCarConnected).isTrue()
    assertThat(messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())).isFalse()
  }

  @Test
  fun isMessagingSyncEnabled_ReturnsTrueWhenEnabled() {
    messagingSyncManager.enableMessagingSync(deviceId.toString(), {}, {})
    val result = messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())
    assertThat(result).isTrue()
  }

  @Test
  fun isMessagingSyncEnabled_ReturnsFalseWhenEnabled() {
    messagingSyncManager.disableMessagingSync(deviceId.toString())
    val result = messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())
    assertThat(result).isFalse()
  }

  @Test
  fun enableMessagingSync_enables() {
    messagingSyncManager.enableMessagingSync(deviceId.toString(), {}, {})
    assertThat(messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())).isTrue()
  }

  @Test
  fun enableMessagingSync_disables() {
    messagingSyncManager.enableMessagingSync(deviceId.toString(), {}, {})
    messagingSyncManager.disableMessagingSync(deviceId.toString())
    assertThat(messagingSyncManager.isMessagingSyncEnabled(deviceId.toString())).isFalse()
  }

  @Test
  fun isNotificationAccessEnabled_returnsFalseWhenRevoked() {
    revokeAllNotificationAccess(context.contentResolver)
    val result = messagingSyncManager.isNotificationAccessEnabled()
    assertThat(result).isFalse()
  }
}
