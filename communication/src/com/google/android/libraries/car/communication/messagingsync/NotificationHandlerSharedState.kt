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

import android.util.LruCache
import androidx.core.app.NotificationCompat.MessagingStyle.Message

/**
 * Hold state that needs to be shared across all [MessagingNotificationHandler]. Currently there's
 * one handler per car connection. Any state that needs to be shared across all car connections can
 * reside here.
 */
internal data class NotificationHandlerSharedState(
  /**
   * Map from the [android.service.notification.StatusBarNotification.getKey] of a conversation
   * notification with the user's latest reply message to this conversation.
   */
  val replyMessages: LruCache<String, Message> = LruCache(MAX_REPLY_CACHE_SIZE)
) {
  companion object {
    private const val MAX_REPLY_CACHE_SIZE = 10
  }
}
