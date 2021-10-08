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
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.IBinder
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Action
import androidx.core.app.NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ
import androidx.core.app.NotificationCompat.Action.SEMANTIC_ACTION_REPLY
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationCompat.MessagingStyle.Message
import androidx.core.app.RemoteInput
import android.support.v4.graphics.drawable.IconCompat.createFromIcon
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.whenever
import java.time.Instant

/**
 * Builds mocks specifically for testing message notifications
 * For other types of notifications (such as email, etc.),
 * please create a separate class for mock purposes than overloading this class.
 */
object MessageNotificationMocks {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val appIcon: Icon
    get() {
      val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
      Canvas(bmp).also { it.drawColor(Color.RED) }
      return Icon.createWithBitmap(bmp)
    }
  private val actionIcon = Icon.createWithResource(context, 1)

  private class MockService : Service() {
    override fun onBind(intent: Intent?): IBinder? { return null }
  }

  private val sender = Person.Builder().setName("name").build()

  private val pendingIntent = PendingIntent.getService(
    context,
    101,
    Intent(context, MockService::class.java),
    FLAG_UPDATE_CURRENT
  )

  private val markAsReadAction = Action.Builder(
    createFromIcon(actionIcon),
    "Mark As Read",
    pendingIntent
  ).addRemoteInput(RemoteInput.Builder("123").build())
    .setSemanticAction(SEMANTIC_ACTION_MARK_AS_READ)
    .setShowsUserInterface(false)
    .build()

  private val user =
    Person.Builder().setName("userName").build()

  /**
   * Builds a testable Status Bar Notification using mock values
   * To customize the result, change the default values
   */
  fun createSBN(
    hasMessagingStyle: Boolean = false,
    isOldMessage: Boolean = false,
    hasReplyAction: Boolean = false,
    hasWrongReplySemanticAction: Boolean = false,
    hasMarkAsRead: Boolean = false,
    useInvisibleActions: Boolean = false,
    showsUI: Boolean = false,
    key: String = "key",
    connectionTime: Instant = Instant.now(),
    postSpecificMessage: Message? = null
  ): StatusBarNotification {
    val notification = buildNotification(
      hasMessagingStyle,
      isOldMessage,
      hasReplyAction,
      hasWrongReplySemanticAction,
      hasMarkAsRead,
      useInvisibleActions,
      showsUI,
      connectionTime,
      postSpecificMessage
    )
    val item: StatusBarNotification = mock()
    whenever(item.key).thenReturn(key)
    whenever(item.packageName).thenReturn("packageName")
    whenever(item.notification).thenReturn(notification)
    return item
  }

  private fun buildStyle(
    isOldMessage: Boolean = true,
    connectionTime: Instant = Instant.now(),
    postSpecificMessage: Message? = null
  ): MessagingStyle {
    val builder = MessagingStyle(user)
    val connectionTimeMs = connectionTime.toEpochMilli()
    if (postSpecificMessage != null) {
      return builder.addMessage(postSpecificMessage)
    }
    if (isOldMessage) {
      return builder.addMessage(
        "old message",
        connectionTimeMs - 100,
        sender
      )
    }
    // create the default message for this builder: two valid messages
    return builder
      .addMessage(
        "First Message",
        connectionTimeMs + 100,
        sender
      )
      .addMessage(
        "A Second Message",
        connectionTimeMs + 100,
        sender
      )
  }

  private fun buildNotification(
    hasMessagingStyle: Boolean = false,
    isOldMessage: Boolean = false,
    hasReplyAction: Boolean = false,
    hasReplyWrongSemanticAction: Boolean = false,
    hasMarkAsRead: Boolean = false,
    useInvisibleActions: Boolean = false,
    showsUI: Boolean = false,
    connectionTime: Instant = Instant.now(),
    postSpecificMessage: Message? = null
  ): Notification {
    return spy(
      NotificationCompat.Builder(context, "123")
        .setContentTitle("2 new messages with $sender")
        .setContentText("subject")
        .apply {
          if (hasMessagingStyle) {
            setStyle(
              buildStyle(
                isOldMessage,
                connectionTime,
                postSpecificMessage
              )
            )
          }
          if (hasReplyAction) {
            val semanticAction = if (hasReplyWrongSemanticAction) {
              Action.SEMANTIC_ACTION_NONE
            } else {
              SEMANTIC_ACTION_REPLY
            }
            val action = replyAction(showsUI, semanticAction)
            if (useInvisibleActions) {
              addInvisibleAction(action)
            } else {
              addAction(action)
            }
          }
          if (hasMarkAsRead) {
            if (useInvisibleActions) {
              addInvisibleAction(markAsReadAction)
            } else {
              addAction(markAsReadAction)
            }
          }
        }
        .build()
    ).also {
      whenever(it.smallIcon).thenReturn(appIcon)
    }
  }

  private fun replyAction(
    showsUI: Boolean = false,
    semanticAction: Int = SEMANTIC_ACTION_REPLY
  ): Action {
    return Action.Builder(
      createFromIcon(actionIcon),
      "Reply",
      pendingIntent
    ).addRemoteInput(RemoteInput.Builder("123").build())
      .setSemanticAction(semanticAction)
      .setShowsUserInterface(showsUI)
      .build()
  }
}
