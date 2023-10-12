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
import android.content.Intent
import android.content.IntentSender
import androidx.annotation.CallSuper
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.trustagent.AssociatedCar
import com.google.android.libraries.car.trustagent.ConnectedDeviceManager
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.R
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.logi
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * A service that is responsible for creating [FeatureManager]s and posting foreground notification
 * to inform user of companion device connections.
 *
 * ## Running in the foreground
 *
 * This service will post a notification and start running in the foreground when a companion device
 * has connected. Implementation is responsible for providing the notification.
 *
 * ## [FeatureManager] instantiation
 *
 * Implementation should create features that need to exchange messages with a companion device.
 */
@PublicApi
abstract class ConnectedDeviceBaseService : FeatureManagerService() {

  lateinit var connectedDeviceManager: ConnectedDeviceManager

  private var foregroundNotificationInfo: Pair<Notification, Int>? = null

  private val connectedDevices = mutableSetOf<AssociatedCar>()

  // The type declaration is necessary for test to properly create a fake implementation.
  @VisibleForTesting
  internal val connectedDeviceManagerCallback: ConnectedDeviceManager.Callback =
    object : ConnectedDeviceManager.Callback {
      override fun onAssociated(associatedCar: AssociatedCar) {
        handleConnection(associatedCar)
      }

      override fun onConnected(associatedCar: AssociatedCar) {
        handleConnection(associatedCar)
      }

      override fun onDisconnected(associatedCar: AssociatedCar) {
        handleDisconnection(associatedCar)
      }

      override fun onDeviceDiscovered(chooserLauncher: IntentSender) {}

      override fun onDiscoveryFailed() {}

      override fun onAssociationStart() {}

      override fun onAuthStringAvailable(authString: String) {}

      override fun onAssociationFailed() {}
    }

  @CallSuper
  override fun onCreate() {
    logi(
      TAG,
      "Service created. Companion SDK version is " +
        "${getResources().getString(R.string.android_companion_sdk_version)}"
    )
    super.onCreate()

    connectedDeviceManager =
      ConnectedDeviceManager(
          this,
          lifecycle,
          featureManagers,
        )
        .apply { registerCallback(connectedDeviceManagerCallback) }
  }

  override fun onBind(intent: Intent): ServiceBinder {
    super.onBind(intent)

    logi(TAG, "onBind called. Returning service binder.")
    return ServiceBinder()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)

    foregroundNotificationInfo = intent?.foregroundNotificationInfo

    EventLog.onServiceStarted()
    return START_STICKY
  }

  override fun onDestroy() {
    EventLog.onServiceStopped()

    super.onDestroy()
  }

  @CallSuper
  override protected fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<String>) {
    super.dump(fd, writer, args)
    writer.printf(
      "Companion SDK version is %s\n",
      getResources().getString(R.string.android_companion_sdk_version)
    )
  }

  private fun handleConnection(associatedCar: AssociatedCar) {
    logi(TAG, "$associatedCar has connected.")
    EventLog.onCarConnected(associatedCar.deviceId)

    connectedDevices.add(associatedCar)
    foregroundNotificationInfo?.let { (notification, notificationId) ->
      logi(TAG, "A device has connected. Starting foreground.")

      EventLog.onStartForeground()
      startForeground(notificationId, notification)
    }
  }

  private fun handleDisconnection(associatedCar: AssociatedCar) {
    logi(TAG, "$associatedCar has disconnected.")
    connectedDevices.remove(associatedCar)
    if (connectedDevices.isEmpty()) {
      logi(TAG, "No device is connected. Stopping foreground.")
      EventLog.onStopForeground()
      stopForeground(/* removeNotification= */ true)
    }
  }

  @PublicApi
  open inner class ServiceBinder : FeatureManagerServiceBinder() {
    open override fun getService(): ConnectedDeviceBaseService = this@ConnectedDeviceBaseService
  }

  @PublicApi
  companion object {
    private const val TAG = "ConnectedDeviceBaseService"

    /**
     * An optional [Notification] that will be posted via [Service.startForeground] for connection.
     *
     * Must be used in conjunction with `EXTRA_FOREGROUND_NOTIFICATION_ID`.
     *
     * If set, this notification indicates that a car is currently connected. It allows the user to
     * be better informed of the connection status. In addtion, the service staying in foreground
     * reduces the likelihood of it being killed.
     *
     * The notification will be posted with notification ID by `EXTRA_FOREGROUND_NOTIFICATION_ID`.
     */
    const val EXTRA_FOREGROUND_NOTIFICATION =
      "com.google.android.libraries.car.connectionservice.EXTRA_FOREGROUND_NOTIFICATION"

    /**
     * A non-negative Int as notification ID.
     *
     * This ID will be used to posted the notification set by `EXTRA_FOREGROUND_NOTIFICATION`.
     * Missing this extra or negative value will lead to notification not being posting.
     */
    const val EXTRA_FOREGROUND_NOTIFICATION_ID =
      "com.google.android.libraries.car.connectionservice.EXTRA_FOREGROUND_NOTIFICATION_ID"

    private val Intent.foregroundNotificationInfo: Pair<Notification, Int>?
      get() {
        val notification = getParcelableExtra<Notification?>(EXTRA_FOREGROUND_NOTIFICATION)
        if (notification == null) {
          logi(TAG, "Intent does not contain foregroud notification.")
          return null
        }

        val notificationId = getIntExtra(EXTRA_FOREGROUND_NOTIFICATION_ID, -1)
        if (notificationId < 0) {
          logi(TAG, "Intent does not contain foregroud notification ID.")
          return null
        }

        logi(TAG, "Retrieved from intent: $notification and $notificationId.")
        return Pair(notification, notificationId)
      }
  }
}
