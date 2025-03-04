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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.api.PublicApi
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * Handles turning on messaging sync features, including turning on messaging sync and enable
 * specific messaging apps
 */
@PublicApi
class MessagingSyncManager
constructor(
  private val context: Context,
  private val lifecycleOwner: LifecycleOwner,
  private val timeProvider: TimeProvider = SystemTimeProvider(),
) : FeatureManager() {
  private val coroutineScope: CoroutineScope = lifecycleOwner.lifecycleScope

  /** A map of car ids to messaging notification handlers. */
  private val messagingHandlers = mutableMapOf<UUID, MessagingNotificationHandler>()
  private val sharedHandlerState = NotificationHandlerSharedState()
  private val messagingUtils = MessagingUtils(context, coroutineScope)
  override val featureId: UUID = FEATURE_ID

  /** Assigns the car to all known handlers */
  override fun onCarConnected(deviceId: UUID) {
    // reuse the existing messaging handler before creating a new one
    messagingHandlers.getOrPut(deviceId, { createMessagingHandler(deviceId) }).onCarConnected()
  }

  override fun onCarDisconnected(deviceId: UUID) {
    messagingHandlers.remove(deviceId)?.onCarDisconnected()
  }

  override fun onMessageReceived(message: ByteArray, deviceId: UUID) {
    messagingHandlers[deviceId]?.onMessageReceived(message)
  }

  override fun onMessageSent(messageId: Int, deviceId: UUID) {}

  override fun onCarDisassociated(deviceId: UUID) {
    messagingHandlers.remove(deviceId)?.onCarDisconnected()
    messagingUtils.disableMessagingSync(deviceId.toString())
  }

  override fun onAllCarsDisassociated() {
    for (carHandler in messagingHandlers.values) {
      carHandler.onCarDisconnected()
    }
    messagingHandlers.clear()
    messagingUtils.disableMessagingSyncForAll()
  }

  fun isMessagingSyncEnabled(deviceId: String): Boolean =
    messagingUtils.isMessagingSyncEnabled(deviceId)

  fun isNotificationAccessEnabled() = messagingUtils.isNotificationAccessEnabled()

  /** Handles the user flow to request user permissions and turn on messaging sync. */
  fun enableMessagingSync(deviceId: String, onSuccess: () -> Unit, onFailure: (() -> Unit)) =
    messagingUtils.enableMessagingSync(deviceId, onSuccess, onFailure)

  /** Turns off messaging sync feature. */
  fun disableMessagingSync(deviceId: String) = messagingUtils.disableMessagingSync(deviceId)

  private fun createMessagingHandler(deviceId: UUID) =
    MessagingNotificationHandler(
      context,
      deviceId,
      ::sendMessage,
      messagingUtils,
      timeProvider,
      sharedHandlerState,
    )

  companion object {
    // Recipient ID used by feature Third Party Messaging aka Snapback
    private val FEATURE_ID = UUID.fromString("b2337f58-18ff-4f92-a0cf-4df63ab2c889")
  }
}
