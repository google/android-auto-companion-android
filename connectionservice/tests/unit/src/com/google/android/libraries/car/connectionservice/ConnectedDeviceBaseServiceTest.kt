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

package com.google.android.libraries.car.connectionservice

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.AssociatedCar
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.testutils.FakeSecretKey
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class ConnectedDeviceBaseServiceTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var fakeAssociatedCar: AssociatedCar

  private val notification =
    Notification.Builder(context, "notification_channel_id").setContentTitle("title").build()
  private val intentWithFgNotification =
    Intent()
      .putExtra(ConnectedDeviceBaseService.EXTRA_FOREGROUND_NOTIFICATION, notification)
      .putExtra(ConnectedDeviceBaseService.EXTRA_FOREGROUND_NOTIFICATION_ID, 1)

  @Before
  fun setUp() {
    fakeAssociatedCar = AssociatedCar(DEVICE_ID, "name", "00:11:22:33:AA:BB", FakeSecretKey())
  }

  @Test
  fun testOnCreate_initializesConnectedDeviceManager() {
    val serviceController = Robolectric.buildService(TestService::class.java)
    val service = serviceController.create().get()

    // Verify that connectedDeviceManager has been initialized by accessing its method arbitrarily.
    // ::isInitialized may seem like an option but it is only available for the properties that
    // are lexically accessible, i.e. within the class.
    service.connectedDeviceManager.registerCallback(mock())
  }

  @Test
  fun onStartCommandReceivesForegroudNotification_connection_postNotification() {
    val serviceController =
      Robolectric.buildService(TestService::class.java, intentWithFgNotification)
    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.connectedDeviceManagerCallback.onConnected(fakeAssociatedCar)

    assertThat(Shadows.shadowOf(service).isLastForegroundNotificationAttached).isTrue()
  }

  @Test
  fun onStartCommandReceivesForegroudNotification_disconnection_stopForeground() {
    val serviceController =
      Robolectric.buildService(TestService::class.java, intentWithFgNotification)
    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.connectedDeviceManagerCallback.onConnected(fakeAssociatedCar)
    service.connectedDeviceManagerCallback.onDisconnected(fakeAssociatedCar)

    assertThat(Shadows.shadowOf(service).isLastForegroundNotificationAttached).isFalse()
  }

  @Test
  fun onStartCommandNoForegroundNotification_association_noNotification() {
    val serviceController = Robolectric.buildService(TestService::class.java)
    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.connectedDeviceManagerCallback.onAssociated(fakeAssociatedCar)

    assertThat(Shadows.shadowOf(service).isLastForegroundNotificationAttached).isFalse()
  }

  @Test
  fun onStartCommandNoForegroundNotification_connection_noNotification() {
    val serviceController = Robolectric.buildService(TestService::class.java)
    val service = serviceController.create().startCommand(/* flags= */ 0, /* startId= */ 0).get()

    service.connectedDeviceManagerCallback.onConnected(fakeAssociatedCar)

    assertThat(Shadows.shadowOf(service).isLastForegroundNotificationAttached).isFalse()
  }

  class TestService : ConnectedDeviceBaseService() {
    override fun createFeatureManagers() = emptyList<FeatureManager>()
  }

  companion object {
    private val DEVICE_ID = UUID.fromString("8a16e892-d4ad-455d-8194-cbc2dfbaebdf")
  }
}
