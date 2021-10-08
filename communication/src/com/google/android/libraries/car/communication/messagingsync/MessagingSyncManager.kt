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
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.api.PublicApi
import java.util.UUID

/**
 * Handles turning on messaging sync features, including turning on messaging sync
 * and enable specific messaging apps
 */
@PublicApi
class MessagingSyncManager constructor(
  private val context: Context,
  private val timeProvider: TimeProvider = SystemTimeProvider()
) : FeatureManager() {
  /** A map of car ids to messaging notification handlers. */
  private val messagingHandlers = mutableMapOf<UUID, MessagingNotificationHandler>()
  private val sharedHandlerState = NotificationHandlerSharedState()
  private val messagingUtils = MessagingUtils(context)
  override val featureId: UUID = FEATURE_ID

  /**
   * Assigns the car to all known handlers
   */
  override fun onCarConnected(carId: UUID) {
    // reuse the existing messaging handler before creating a new one
    messagingHandlers.getOrPut(carId, { createMessagingHandler(carId) }).onCarConnected()
  }

  override fun onCarDisconnected(carId: UUID) {
    messagingHandlers.remove(carId)?.onCarDisconnected()
  }

  override fun onMessageReceived(message: ByteArray, carId: UUID) {
    messagingHandlers[carId]?.onMessageReceived(message)
  }

  override fun onMessageSent(messageId: Int, carId: UUID) {}

  override fun onCarDisassociated(carId: UUID) {}

  override fun onAllCarsDisassociated() {
    // No-op
  }

  fun isMessagingSyncEnabled(carId: String): Boolean =
    messagingUtils.isMessagingSyncEnabled(carId)

  fun isNotificationAccessEnabled() =
    messagingUtils.isNotificationAccessEnabled()

  /**
   * Handles the user flow to request user permissions and turn on messaging sync.
   */
  fun enableMessagingSync(carId: String, onSuccess: () -> Unit, onFailure: (() -> Unit)?) =
    messagingUtils.enableMessagingSync(carId, onSuccess, onFailure)

  /**
   * Turns off messaging sync feature.
   */
  fun disableMessagingSync(carId: String) =
    messagingUtils.disableMessagingSync(carId)

  private fun createMessagingHandler(carId: UUID) = MessagingNotificationHandler(
    context,
    carId,
    ::sendMessage,
    messagingUtils,
    timeProvider,
    sharedHandlerState
  )

  companion object {
    // Recipient ID used by feature Third Party Messaging aka Snapback
    private val FEATURE_ID = UUID.fromString("b2337f58-18ff-4f92-a0cf-4df63ab2c889")
  }
}
