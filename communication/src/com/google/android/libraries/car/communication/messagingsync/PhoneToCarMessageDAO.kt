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
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.google.android.libraries.car.trustagent.util.getAppName
import com.google.android.libraries.car.trustagent.util.reduceSize
import com.google.android.libraries.car.trustagent.util.toBitmap
import com.google.android.libraries.car.trustagent.util.toByteString
import com.google.protobuf.ByteString
import com.google.protos.aae.messenger.NotificationMsg
import com.google.protos.aae.messenger.NotificationMsg.ConversationNotification
import com.google.protos.aae.messenger.NotificationMsg.MessagingStyleMessage
import com.google.protos.aae.messenger.NotificationMsg.PhoneToCarMessage
import java.time.Instant

/** Converts notification to a message for the car */
internal class PhoneToCarMessageDAO(
  private val context: Context,
  private val key: String,
  private val packageName: String,
  private val style: NotificationCompat.MessagingStyle,
  private val lastMessage: NotificationCompat.MessagingStyle.Message,
  private val isNewConversation: Boolean,
  private val appIcon: Icon,
  private val appIconColor: Int,
  private val connectionTime: Instant
) {

  private val DEFAULT_USER_NAME = "DRIVER"
  private val DEFAULT_CONVERSATION_TITLE = "CONVERSATION"

  private val appIconBytes: ByteString
    get() = appIcon.toByteString()

  /** Converts to [PhoneToCarMessage] */
  fun toMessage(): PhoneToCarMessage =
    PhoneToCarMessage.newBuilder().setNotificationKey(key).setMessageOrConversation().build()

  private fun PhoneToCarMessage.Builder.setMessageOrConversation(): PhoneToCarMessage.Builder =
    if (isNewConversation) {
      setConversation(buildNewConversation())
    } else {
      setMessage(lastMessage.buildNewMessage())
    }

  private fun buildNewConversation() =
    ConversationNotification.newBuilder()
      .setMessagingAppPackageName(packageName)
      .setMessagingAppDisplayName(getAppName(context, packageName))
      .setAppIcon(appIconBytes)
      .setAppIconColor(appIconColor)
      .setTimeMs(lastMessage.timestamp)
      .setMessagingStyle(style.buildMessageStyle())
      .build()

  private fun NotificationCompat.MessagingStyle.buildMessageStyle() =
    NotificationMsg.MessagingStyle.newBuilder()
      .setUserDisplayName(
        if (user?.name?.isNullOrEmpty() == true) DEFAULT_USER_NAME else user.name.toString())
      .setIsGroupConvo(isGroupConversation)
      .setConvoTitle(conversationTitle?.toString() ?: DEFAULT_CONVERSATION_TITLE)
      .apply {
        messages.filter { Instant.ofEpochMilli(it.timestamp) >= connectionTime }.forEach { message
          ->
          addMessagingStyleMsg(message.buildNewMessage())
        }
      }
      .build()

  private fun NotificationCompat.MessagingStyle.Message.buildNewMessage() =
    MessagingStyleMessage.newBuilder()
      .setTextMessage(text.toString())
      .setIsRead(false)
      .setTimestamp(timestamp)
      .apply { this@buildNewMessage.person?.let { person -> sender = person.buildSender() } }
      .build()

  private fun Person.buildSender(): NotificationMsg.Person =
    NotificationMsg.Person.newBuilder()
      .setName(name.toString())
      .apply {
        icon?.let {
          avatar =
            it.toIcon(context)
              .toByteString(quality = AVATAR_QUALITY, maxWidthHeight = AVATAR_SIZE_PX)
        }
      }
      .build()

  private fun Icon.toByteString(
    compressFormat: Bitmap.CompressFormat = COMPRESS_FORMAT,
    quality: Int = ICON_QUALITY_DEFAULT,
    maxWidthHeight: Int? = null
  ) =
    loadDrawable(context)!!.toBitmap()
      .run { maxWidthHeight?.let { maxSize -> reduceSize(maxSize) } ?: this }
      .toByteString(compressFormat, quality)

  companion object {
    /**
     * Quality Hint to the compressor, 0-100. The value is interpreted differently depending on the
     * [Bitmap.CompressFormat].
     */
    private const val ICON_QUALITY_DEFAULT = 100
    private const val AVATAR_QUALITY = 0
    /**
     * Length and height value for the avatar icon. This value is chosen as a balance between
     * quality and file size.
     */
    private const val AVATAR_SIZE_PX = 150
    // PNG is required for icon transparency
    private val COMPRESS_FORMAT = Bitmap.CompressFormat.PNG
  }
}
