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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle.Message
import android.support.v4.graphics.drawable.IconCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.net.URLConnection.guessContentTypeFromStream
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [PhoneToCarMessageDAO].
 */
@RunWith(AndroidJUnit4::class)
class PhoneToCarMessageDAOTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val appName = context.packageManager.getApplicationLabel(context.applicationInfo)
  private val appIcon: Icon
    get() {
      val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
      Canvas(bmp).run {
        drawColor(Color.RED)
      }
      return Icon.createWithBitmap(bmp)
    }
  private val avatarIcon: IconCompat
    get() {
      val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
      Canvas(bmp).run {
        drawColor(Color.RED)
      }
      return IconCompat.createWithBitmap(bmp)
    }
  private val key = "key"
  private val connectionTime = Instant.parse("2020-01-01T00:00:00Z")
  private val compressFormat = "image/png"
  private val maxWidthAndHeight = 50
  private val msgAppPackageName = "app.package.com"

  /**
   * The default sender with no avatar
   */
  private val person: Person
    get() {
      return Person.Builder().setName("Sender").build()
    }

  /**
   * The default sender with an avatar
   */
  private val personWithAvatar: Person
    get() {
      return Person.Builder().setName("Sender").setIcon(avatarIcon).build()
    }

  /**
   * The default valid firstMessage unless customized in test case
   */
  private val firstMessage = Message(
    "First text message",
    connectionTime.toEpochMilli() + 10,
    person
  )

  /**
   * The default valid lastMessage unless customized in test case
   */
  private val lastMessage = Message(
    "Last Message",
    connectionTime.toEpochMilli() + 100,
    person
  )

  private val messageWithAvatar = Message(
    "A message with avatar",
    connectionTime.toEpochMilli() + 10,
    personWithAvatar
  )

  private val oldMessage =
    Message(
      "Really old Message",
      connectionTime.toEpochMilli() - 1000,
      person
    )

  @Test
  fun toMessage_newConversation() {
    val messages = listOf(firstMessage, lastMessage)
    val dao = createDAO(
      isNewConversation = true,
      isGroupConversation = false,
      listOfMessages = messages,
      lastMessage = lastMessage
    )
    val expectedStyle = createStyle(
      listOfMessages = messages
    )
    val result = dao.toMessage()
    assertThat(result.messageDataCase.name).isEqualTo(CONVERSATION_DATA_TYPE)
    result.conversation.run {
      assertThat(messagingAppPackageName).isEqualTo(msgAppPackageName)
      assertThat(timeMs).isEqualTo(lastMessage.timestamp)
      val inputType = guessContentTypeFromStream(
        ByteArrayInputStream(appIcon.toByteArray())
      )
      assertThat(inputType).isEqualTo("image/png")
      assertThat(appIconColor).isEqualTo(Color.RED)
    }
    val messagingStyle = result.conversation.messagingStyle
    messagingStyle.run {
      assertThat(isGroupConvo).isEqualTo(false)
      assertThat(userDisplayName).isEqualTo(expectedStyle.user.name)
      assertThat(convoTitle).isEqualTo(expectedStyle.conversationTitle)
      assertThat(messages).hasSize(messagingStyleMsgCount)
      messages.forEachIndexed { index, expectedMessage ->
        val message = messagingStyleMsgList[index]
        assertThat(expectedMessage.text).isEqualTo(message.textMessage)
        assertThat(expectedMessage.timestamp).isEqualTo(message.timestamp)
        assertThat(expectedMessage.person?.name).isEqualTo(message.sender.name)
      }
    }
  }

  @Test
  fun toMessage_preExistingConversation() {
    val dao = createDAO(
      isNewConversation = false,
      isGroupConversation = false,
      listOfMessages = listOf(firstMessage, lastMessage),
      lastMessage = lastMessage
    )
    val result = dao.toMessage()
    assertThat(result.messageDataCase.name).isEqualTo(MESSAGE_DATA_TYPE)
    result.message.run {
      assertThat(isRead).isFalse()
      assertThat(timestamp).isEqualTo(lastMessage.timestamp)
      assertThat(textMessage).isEqualTo(lastMessage.text)
      assertThat(sender.name).isEqualTo(lastMessage.person?.name)
    }
  }

  @Test
  fun testGroupConversation() {
    val dao = createDAO(
      isNewConversation = true,
      isGroupConversation = true,
      listOfMessages = listOf(firstMessage, lastMessage),
      lastMessage = lastMessage
    )
    val result = dao.toMessage()
    assertThat(result.conversation.messagingStyle.isGroupConvo).isEqualTo(true)
  }

  @Test
  fun toMessage_sendsSenderAvatarForNewConversation() {
    val dao = createDAO(
      isNewConversation = true,
      isGroupConversation = true,
      listOfMessages = listOf(messageWithAvatar),
      lastMessage = messageWithAvatar
    )
    val result = dao.toMessage()
    val messageList = result.conversation.messagingStyle.messagingStyleMsgList
    val message = messageList[0]
    val avatarArray = message.sender.avatar.toByteArray()
    val inputType = guessContentTypeFromStream(
      ByteArrayInputStream(avatarArray)
    )
    assertThat(inputType).isEqualTo(compressFormat)
    val bitmap = BitmapFactory.decodeByteArray(avatarArray, /* offset= */0, avatarArray.size)
    assertThat(bitmap.width).isAtMost(maxWidthAndHeight)
    assertThat(bitmap.height).isAtMost(maxWidthAndHeight)
    assertThat(messageList).hasSize(1)
  }

  @Test
  fun toMessage_alwaysSendsSenderAvatarInGroupConversation() {
    val dao = createDAO(
      isNewConversation = false,
      isGroupConversation = true,
      listOfMessages = listOf(messageWithAvatar),
      lastMessage = messageWithAvatar
    )
    val result = dao.toMessage()
    val message = result.message
    val avatarArray = message.sender.avatar.toByteArray()
    val inputType = guessContentTypeFromStream(
      ByteArrayInputStream(avatarArray)
    )
    assertThat(inputType).isEqualTo(compressFormat)
    val bitmap = BitmapFactory.decodeByteArray(avatarArray, /* offset= */ 0, avatarArray.size)
    assertThat(bitmap.width <= maxWidthAndHeight).isTrue()
    assertThat(bitmap.height <= maxWidthAndHeight).isTrue()
  }

  @Test
  fun toMessage_preexistingOneOnOneConversationIgnoresSenderAvatar() {
    val dao = createDAO(
      isNewConversation = false,
      isGroupConversation = false,
      listOfMessages = listOf(messageWithAvatar),
      lastMessage = messageWithAvatar
    )
    val result = dao.toMessage()
    assertThat(result.messageDataCase.name).isEqualTo(MESSAGE_DATA_TYPE)
    assertThat(result.message.sender.avatar.toByteArray()).isEmpty()
  }

  @Test
  fun toMessage_ignoresOldMessage() {
    val dao = createDAO(
      isNewConversation = true,
      isGroupConversation = false,
      listOfMessages = listOf(oldMessage, lastMessage),
      lastMessage = lastMessage
    )
    val result = dao.toMessage()
    val messageList = result.conversation.messagingStyle.messagingStyleMsgList
    assertThat(messageList).hasSize(1)
    val message = messageList.first()
    assertThat(lastMessage.text).isEqualTo(message.textMessage)
    assertThat(lastMessage.timestamp).isEqualTo(message.timestamp)
    assertThat(lastMessage.person?.name).isEqualTo(message.sender.name)
  }

  private fun createStyle(
    isGroupConversation: Boolean = false,
    listOfMessages: List<Message> = listOf(firstMessage, lastMessage)
  ): NotificationCompat.MessagingStyle {
    val user: Person = Person.Builder().setName("UserName").build()
    val style = NotificationCompat.MessagingStyle(user)
    style.isGroupConversation = isGroupConversation
    style.conversationTitle = "ConversationTitle"
    style.messages.addAll(listOfMessages)
    return style
  }

  private fun createDAO(
    isNewConversation: Boolean = false,
    isGroupConversation: Boolean = false,
    listOfMessages: List<Message>,
    lastMessage: Message
  ) = PhoneToCarMessageDAO(
    context,
    key,
    msgAppPackageName,
    createStyle(
      listOfMessages = listOfMessages,
      isGroupConversation = isGroupConversation
    ),
    lastMessage,
    isNewConversation,
    appIcon,
    Color.RED,
    connectionTime
  )

  companion object {
    const val MESSAGE_DATA_TYPE = "MESSAGE"
    const val CONVERSATION_DATA_TYPE = "CONVERSATION"
  }
}
