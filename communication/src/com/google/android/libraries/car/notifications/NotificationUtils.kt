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

import android.app.Notification
import androidx.core.app.NotificationCompat

/** Notification Helper extension methods */

/**
 * Get Action based on the semantic action
 *
 * @param semanticAction semantic Action
 * @return [NotificationCompat.Action] or null if none found
 */
private fun Notification.getAction(
  @NotificationCompat.Action.SemanticAction semanticAction: Int
): NotificationCompat.Action? =
  getVisibleAndInvisibleActions().firstOrNull { it.semanticAction == semanticAction }

private fun Notification.getVisibleAndInvisibleActions(): List<NotificationCompat.Action> {
  val list = actions ?: arrayOf<Notification.Action>()
  return list.indices.mapNotNull { NotificationCompat.getAction(this, it) }.toMutableList().also {
    it.addAll(NotificationCompat.getInvisibleActions(this))
  }
}

/**
 * Get reply Action
 *
 * @return [NotificationCompat.Action] or null if none found
 */
val Notification.replyAction: NotificationCompat.Action?
  get() {
    val action = getAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
    return action?.takeIf { it.remoteInputs?.size == 1 }
  }

/**
 * Get Mark As Read Action
 *
 * @return [NotificationCompat.Action] or null if none found
 */
val Notification.markAsReadAction: NotificationCompat.Action?
  get() = getAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)

/**
 * Checks to see if the mark as read or reply action shows a user interface when clicked
 *
 * @return true if notification shows user interface
 */
val Notification.showsUI: Boolean
  get() = markAsReadAction?.showsUserInterface ?: false || replyAction?.showsUserInterface ?: false

/**
 * Gets the last message based on the message timestamps
 *
 * @return [NotificationCompat.MessagingStyle.Message] or null if none found
 */
val NotificationCompat.MessagingStyle.lastMessage: NotificationCompat.MessagingStyle.Message?
  get() = messages.maxByOrNull { it.timestamp }

/** Returns [NotificationCompat.MessagingStyle] */
val Notification.messagingStyle: NotificationCompat.MessagingStyle?
  get() = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(this)

/**
 * Returns true if is car-compatible messaging notification Removing [markAsReadAction] non-null
 * check requirement to support Hangouts which, from the last manual test, does not have Mark As
 * read action.
 */
val Notification.isCarCompatibleMessagingNotification: Boolean
  get() = messagingStyle != null && replyAction != null && !showsUI
