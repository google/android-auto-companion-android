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

import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.Person
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationUtilsTest {
  private val defaultKey = "key"

  @Test
  fun isCarCompatibleMessage_returnsTrueWhenValid() {
    val sbn = createSBN()
    val isCarCompatible = sbn.notification.isCarCompatibleMessagingNotification
    assertThat(isCarCompatible).isTrue()
  }

  @Test
  fun isCarCompatibleMessage_noReplyAction() {
    val sbn = createSBN(hasReplyAction = false)
    val isCarCompatible = sbn.notification.isCarCompatibleMessagingNotification
    assertThat(isCarCompatible).isFalse()
  }

  @Test
  fun isCarCompatibleMessage_noMarkAsReadAction() {
    val sbn = createSBN(hasMarkAsRead = false)
    val isCarCompatible = sbn.notification.isCarCompatibleMessagingNotification
    assertThat(isCarCompatible).isTrue()
  }

  @Test
  fun isCarCompatibleMessage_usingInvisibleActions() {
    val sbn = createSBN(useInvisibleActions = true)
    val isCarCompatible = sbn.notification.isCarCompatibleMessagingNotification
    assertThat(isCarCompatible).isTrue()
  }

  @Test
  fun isCarCompatibleMessage_showsUI() {
    val sbn = createSBN(showsUI = true)
    val isCarCompatible = sbn.notification.isCarCompatibleMessagingNotification
    assertThat(isCarCompatible).isFalse()
  }

  @Test
  fun isCarCompatibleMessage_showsUI_InvisibleActions() {
    val sbn = createSBN(showsUI = true, useInvisibleActions = true)
    val isCarCompatible = sbn.notification.isCarCompatibleMessagingNotification
    assertThat(isCarCompatible).isFalse()
  }

  @Test
  fun isCarCompatibleMessage_noMessagingStyle() {
    val sbn = createSBN(hasMessagingStyle = false)
    val isCarCompatible = sbn.notification.isCarCompatibleMessagingNotification
    assertThat(isCarCompatible).isFalse()
  }

  @Test
  fun replyAction_returnsAppropriateAction() {
    val sbn = createSBN()
    val replyAction = sbn.notification.replyAction
    assertThat(replyAction).isNotNull()
    assertThat(replyAction?.semanticAction)
      .isEqualTo(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
  }

  @Test
  fun replyAction_returnsAppropriateActio_whenInvisible() {
    val sbn = createSBN(useInvisibleActions = true)
    val replyAction = sbn.notification.replyAction
    assertThat(replyAction).isNotNull()
    assertThat(replyAction?.semanticAction)
      .isEqualTo(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
  }

  @Test
  fun replyAction_noActionFound() {
    val sbn = createSBN(hasReplyAction = false)
    val replyAction = sbn.notification.replyAction
    assertThat(replyAction).isNull()
  }

  @Test
  fun replyAction_wrongReplySemanticAction() {
    val sbn = createSBN(hasWrongReplySemanticAction = true)
    val replyAction = sbn.notification.replyAction
    assertThat(replyAction).isNull()
  }

  @Test
  fun markAsReadAction_returnsAppropriateAction() {
    val sbn = createSBN(hasMarkAsRead = true)
    val action = sbn.notification.markAsReadAction
    assertThat(action).isNotNull()
    assertThat(action?.semanticAction)
      .isEqualTo(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
  }

  @Test
  fun markAsReadAction_returnsAppropriateAction_whenInvisible() {
    val sbn = createSBN(hasMarkAsRead = true, useInvisibleActions = true)
    val action = sbn.notification.markAsReadAction
    assertThat(action).isNotNull()
    assertThat(action?.semanticAction)
      .isEqualTo(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
  }

  @Test
  fun markAsReadAction_noMarkAsReadFound() {
    val sbn = createSBN(hasMarkAsRead = false)
    val action = sbn.notification.markAsReadAction
    assertThat(action).isNull()
  }

  @Test
  fun showsUI_returnsTrueAsAppropriate() {
    val sbn = createSBN(showsUI = true)
    val showsUI = sbn.notification.showsUI
    assertThat(showsUI).isTrue()
  }

  @Test
  fun showsUI_returnsFalseAsAppropriate() {
    val sbn = createSBN(showsUI = false)
    val showsUI = sbn.notification.showsUI
    assertThat(showsUI).isFalse()
  }

  @Test
  fun messagingStyle_returnsStyleWhenFound() {
    val sbn = createSBN(hasMessagingStyle = true)
    val style = sbn.notification.messagingStyle
    assertThat(style).isNotNull()
    assertThat(sbn.notification.messagingStyle?.user?.name).isNotNull()
  }

  @Test
  fun lastMessage_getsAppropriateMessage() {
    val style = MessagingStyle(Person.Builder().setName("user").build())
    val firstMessage = MessagingStyle.Message(
      "Text Message One",
      Instant.now().toEpochMilli() + 100,
      Person.Builder().setName("senderOne").build()
    )
    val lastMessage = MessagingStyle.Message(
      "Text Message Two",
      Instant.now().toEpochMilli() + 200,
      Person.Builder().setName("senderTwo").build()
    )
    style.addMessage(lastMessage)
    style.addMessage(firstMessage)
    assertThat(style.lastMessage).isEqualTo(lastMessage)
  }

  /**
   * Default functionality is a valid message [StatusBarNotification].
   * To make invalid, you may customize the input.
   */
  private fun createSBN(
    hasMessagingStyle: Boolean = true,
    isOldMessage: Boolean = false,
    hasReplyAction: Boolean = true,
    hasWrongReplySemanticAction: Boolean = false,
    hasMarkAsRead: Boolean = true,
    useInvisibleActions: Boolean = false,
    showsUI: Boolean = false,
    key: String = defaultKey,
    connectionTime: Instant = Instant.now(),
    postSpecificMessage: MessagingStyle.Message? = null
  ): StatusBarNotification {
    return MessageNotificationMocks.createSBN(
      hasMessagingStyle = hasMessagingStyle,
      isOldMessage = isOldMessage,
      hasReplyAction = hasReplyAction,
      hasWrongReplySemanticAction = hasWrongReplySemanticAction,
      hasMarkAsRead = hasMarkAsRead,
      useInvisibleActions = useInvisibleActions,
      key = key,
      showsUI = showsUI,
      connectionTime = connectionTime,
      postSpecificMessage = postSpecificMessage
    )
  }
}
