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

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat.MessagingStyle.Message
import androidx.core.app.RemoteInput
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.CarToPhoneMessageError.INVALID_PROTOBUF_EXCEPTION
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.CarToPhoneMessageError.UNKNOWN_ACTION
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.DUPLICATE_MESSAGE
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.MESSAGING_SYNC_FEATURE_DISABLED
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.NON_CAR_COMPATIBLE_MESSAGE_NO_MESSAGING_STYLE
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.NON_CAR_COMPATIBLE_MESSAGE_NO_REPLY
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.NON_CAR_COMPATIBLE_MESSAGE_SHOWS_UI
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.OLD_MESSAGES_IN_NOTIFICATION
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.REPLY_REPOST
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.SMS_MESSAGE
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.UNKNOWN
import com.google.android.libraries.car.notifications.NotificationHandler
import com.google.android.libraries.car.notifications.NotificationSyncManager
import com.google.android.libraries.car.notifications.lastMessage
import com.google.android.libraries.car.notifications.markAsReadAction
import com.google.android.libraries.car.notifications.messagingStyle
import com.google.android.libraries.car.notifications.passesRelaxedCarMsgRequirements
import com.google.android.libraries.car.notifications.passesStrictCarMsgRequirements
import com.google.android.libraries.car.notifications.replyAction
import com.google.android.libraries.car.notifications.showsUI
import com.google.android.libraries.car.trustagent.Car
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protos.aae.messenger.NotificationMsg.Action.ActionName.MARK_AS_READ
import com.google.protos.aae.messenger.NotificationMsg.Action.ActionName.REPLY
import com.google.protos.aae.messenger.NotificationMsg.CarToPhoneMessage
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Synchronizes car-compatible text messages with the connected [Car]. It also relays Mark-as-Read
 * and Reply actions user does on the car to the appropriate messaging app on the device.
 */
internal class MessagingNotificationHandler(
  private val context: Context,
  private val carId: UUID,
  private val sendMessage: (data: ByteArray, carId: UUID) -> Int,
  private val messagingUtils: MessagingUtils,
  private val timeSource: TimeProvider,
  sharedState: NotificationHandlerSharedState
) : NotificationHandler {
  /**
   * The notification map where the key is [StatusBarNotification.getKey] and the Notification
   * represents [StatusBarNotification.getNotification]
   */
  private val notificationMap = mutableMapOf<String, Notification>()
  private val replyMessages = sharedState.replyMessages
  private var connectionTime: Instant? = null
  var isCarConnected = false
    private set

  fun onMessageReceived(data: ByteArray) =
    try {
      val carToPhoneMsg = CarToPhoneMessage.parseFrom(data)
      when (val actionName = carToPhoneMsg.actionRequest.actionName) {
        REPLY -> sendReply(carToPhoneMsg)
        MARK_AS_READ -> markAsRead(carToPhoneMsg)
        else -> DebugLogs.logCarToPhoneMessageError(UNKNOWN_ACTION, actionName.toString())
      }
    } catch (e: InvalidProtocolBufferException) {
      DebugLogs.logCarToPhoneMessageError(INVALID_PROTOBUF_EXCEPTION, e.javaClass.name, e)
    }

  /**
   * Enables handler to listen for notifications and post to car.
   * Clears car-level caching.
   *
   * Example use:
   * ```
   * MessagingNotificationHandler().also { it.onCarConnected }
   * ```
   */
  fun onCarConnected() {
    notificationMap.clear()

    if (isCarConnected) return
    NotificationSyncManager.addNotificationHandler(this)
    connectionTime = timeSource.now()
    isCarConnected = true
    DebugLogs.logOnCarConnected()
  }

  /**
   * Clears all car-level caching on disconnect. Also stops listening for notifications, rendering
   * this handler as inoperable. To reuse handler re-call [onCarConnected],
   */
  fun onCarDisconnected() {
    NotificationSyncManager.removeNotificationHandler(this)
    notificationMap.clear()
    connectionTime = null
    isCarConnected = false
    DebugLogs.logOnCarDisconnected()
  }

  override fun onNotificationReceived(sbn: StatusBarNotification) {
    if (!canHandleNotification(sbn)) {
      val reasons = cannotHandleNotificationReasons(sbn)
      DebugLogs.logPhoneToCarMessageErrors(reasons, "${sbn.packageName}")
      return
    }
    DebugLogs.logMessageNotificationReceived(sbn.packageName)
    val message = sbn.toMessageDAO()?.toMessage() ?: return
    sendMessage(message.toByteArray(), carId)
    notificationMap[sbn.key] = sbn.notification
    DebugLogs.logMessageToCarSent(message.message.textMessage)
  }

  @VisibleForTesting
  fun canHandleNotification(sbn: StatusBarNotification) =
    isFeatureEnabled() &&
      isCarCompatibleNotification(sbn) &&
      isUnique(sbn) &&
      isRecentTextMessage(sbn) &&
      !isReplyRepost(sbn) &&
      !isSMSMessage(sbn.packageName)

  /**
   * Returns a string concatenation with the error code name and details of the
   * [StatusBarNotification]
   */
  @VisibleForTesting
  fun cannotHandleNotificationReasons(sbn: StatusBarNotification): List<String> {
    val reasons = buildList {
      if (!isFeatureEnabled()) {
        add("${MESSAGING_SYNC_FEATURE_DISABLED.name}")
      }
      if (sbn.notification.messagingStyle == null) {
        add("${NON_CAR_COMPATIBLE_MESSAGE_NO_MESSAGING_STYLE.name}")
      }
      if (sbn.notification.replyAction == null ) {
        add("${NON_CAR_COMPATIBLE_MESSAGE_NO_REPLY.name}")
      }
      if (sbn.notification.showsUI ) {
        add("${NON_CAR_COMPATIBLE_MESSAGE_SHOWS_UI.name}")
      }
      if (!isUnique(sbn)) {
        add("${DUPLICATE_MESSAGE.name}")
      }
      if (!isRecentTextMessage(sbn)) {
        add("${OLD_MESSAGES_IN_NOTIFICATION.name}")
      }
      if (isReplyRepost(sbn)) {
        add("${REPLY_REPOST.name}")
      }
      if (isSMSMessage(sbn.packageName)) {
        add("${SMS_MESSAGE.name}")
      }
      if (isEmpty()) {
        add("${UNKNOWN.name}")
      }
    }
    return reasons
  }

  private fun isFeatureEnabled() = messagingUtils.isMessagingSyncEnabled(carId.toString())

  private fun StatusBarNotification.toMessageDAO(): PhoneToCarMessageDAO? {
    val isNewConversation = notificationMap[key] == null
    val style = notification.messagingStyle
    val lastMessage = style?.lastMessage ?: return null
    val appIcon = notification.smallIcon ?: return null
    val appIconColor = notification.color
    val connectionInstant = connectionTime ?: return null
    return PhoneToCarMessageDAO(
      key = key,
      context = context,
      packageName = packageName,
      style = style,
      appIcon = appIcon,
      appIconColor = appIconColor,
      isNewConversation = isNewConversation,
      lastMessage = lastMessage,
      connectionTime = connectionInstant
    )
  }

  private fun sendReply(carToPhoneMsg: CarToPhoneMessage) {
    val action = notificationMap[carToPhoneMsg.notificationKey]?.replyAction
    val remoteInput = action?.remoteInputs?.first() ?: return
    val actionDataList = carToPhoneMsg.actionRequest.mapEntryList
    val response = actionDataList.firstOrNull { it.key == REPLY_KEY } ?: return
    val bundle = Bundle().apply { putCharSequence(remoteInput.resultKey, response.value) }
    val intent = Intent()
    val remoteInputs = action.remoteInputs ?: emptyArray<RemoteInput>()
    RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
    action.actionIntent?.send(context, 0, intent)

    DebugLogs.logSendReplyToPhone()
    replyMessages.put(
      carToPhoneMsg.notificationKey,
      Message(
        response.value,
        timeSource.now().toEpochMilli(),
        notificationMap[carToPhoneMsg.notificationKey]?.messagingStyle?.user
      )
    )
  }

  private fun markAsRead(carToPhoneMsg: CarToPhoneMessage) {
    val notification = notificationMap[carToPhoneMsg.notificationKey] ?: return
    val action = notification.markAsReadAction
    action?.actionIntent?.send(context, 0, Intent())
    DebugLogs.logSendMarkAsReadToPhone()
  }

  /**
   * Allowlisted applications do not require mark as read intent to be present in the notification.
   */
  private fun isCarCompatibleNotification(sbn: StatusBarNotification) =
    if (isAllowlistedForRelaxedReqs(sbn.packageName))
      sbn.notification.passesRelaxedCarMsgRequirements
    else
      sbn.notification.passesStrictCarMsgRequirements

  private fun isAllowlistedForRelaxedReqs(packageName: String): Boolean {
    val allowList = context.getString(R.string.relaxedAllowlist).split(",")
    return packageName in allowList
  }

  /**
   * SMS is already supported through Bluetooth Message Profile. For v1, the handler ignores any sms
   * message from the default sms package Future work includes also checking 3p messaging apps that
   * posts sms notifications.
   */
  private fun isSMSMessage(packageName: String) =
    Telephony.Sms.getDefaultSmsPackage(context) == packageName

  /**
   *
   * Previous, unread messages are occasionally reposted in new notifications.
   *
   * When a new notification is received, we check to see if the phone is connected to a car before
   * handling the notification.
   *
   * We also need to verify that the text message is recent and was received after the phone was
   * connected to the car.
   *
   * Returns true if text message was received after the phone connected with the car.
   */
  private fun isRecentTextMessage(sbn: StatusBarNotification): Boolean {
    val connectionInstant = connectionTime ?: return false
    val lastMessageTime =
      Instant.ofEpochMilli(sbn.notification.messagingStyle?.lastMessage?.timestamp ?: 0)
    return lastMessageTime >= connectionInstant
  }

  /**
   * Returns true if this is a non-duplicate/unique notification It is a known issue that WhatsApp
   * sends out the same message multiple times. To handle this, we check the timestamp to make sure
   * this is not a duplicate message
   */
  private fun isUnique(sbn: StatusBarNotification): Boolean {
    val newStyle = sbn.notification.messagingStyle
    val newLastMessage = newStyle?.lastMessage ?: return true

    val previousNotification = notificationMap[sbn.key]
    val previousStyle = previousNotification?.messagingStyle
    val previousLastMessage = previousStyle?.lastMessage ?: return true

    return !(previousLastMessage.timestamp == newLastMessage.timestamp &&
      previousLastMessage.text == newLastMessage.text &&
      previousLastMessage.person?.name == newLastMessage.person?.name)
  }

  /**
   * Returns true if this is a notification update representing the user's reply to a message.
   *
   * We compare the last known reply message from our internal cache to the latest message in the
   * Status Bar Notification to verify this notification is not a reply repost.
   *
   * It is important to note that user can trigger a response outside of IHU interactions. The reply
   * cache will not know of these responses, so we also check to see if there are other indications
   * that this message is clearly from the user with a call to [isMessageClearlyFromUser] which
   * checks to see if unique identifiers such as user key or user uri are present.
   *
   * These identifiers are optional and not always present in message notifications, so the reply
   * cache is still necessary as a fallback to check for replies by the user triggered by the IHU.
   *
   * The reply timestamp is an approximation of the expected reply timestamp, created when we send a
   * reply intent to the 3p messaging app. The 3p messaging app sets the true timestamp for the
   * reply message. In this case the true timestamp is unknown but we anticipate it would be within
   * 2 seconds, plus or minus of our approximation.
   */
  private fun isReplyRepost(sbn: StatusBarNotification): Boolean {
    if (isMessageClearlyFromUser(sbn)) return true
    val replyMessage = replyMessages[sbn.key] ?: return false
    val lastMessage = sbn.notification.messagingStyle?.lastMessage ?: return false
    val isWithinTimeInterval =
      abs(lastMessage.timestamp - replyMessage.timestamp) <= REPLY_REPOST_INTERVAL_MS
    return isWithinTimeInterval &&
      lastMessage.person?.name == replyMessage.person?.name &&
      lastMessage.text == replyMessage.text
  }

  /**
   * Returns true if the message notification is clearly from current user, using unique identifiers
   * such as key or uri. Sender name is not a sufficient unique identifier as there can be multiple
   * users with the same name. The unique identifiers (uri and key) are optional and may not be set
   * by the messaging app. If method returns false, it means more checks need to be made to
   * determine if the message is from the current user, such as checking the last reply cache sent
   * directly from the IHU.
   */
  private fun isMessageClearlyFromUser(sbn: StatusBarNotification): Boolean {
    val messagingStyle = sbn.notification.messagingStyle ?: return false
    val lastMessage = messagingStyle.lastMessage ?: return false
    lastMessage.person?.key?.let {
      return it == messagingStyle.user.key
    }
    lastMessage.person?.uri?.let {
      return it == messagingStyle.user.uri
    }
    return false
  }

  companion object {
    private const val REPLY_KEY = "REPLY"
    /**
     * The expected time interval between the SDK sending out the reply pending intent to the
     * application, and the application posting a notification with this reply.
     */
    private val REPLY_REPOST_INTERVAL_MS = Duration.ofSeconds(2).toMillis()
  }
}
