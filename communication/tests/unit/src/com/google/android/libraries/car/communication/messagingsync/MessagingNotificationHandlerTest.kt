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
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.DUPLICATE_MESSAGE
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.MESSAGING_SYNC_FEATURE_DISABLED
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.NON_CAR_COMPATIBLE_MESSAGE_NO_MESSAGING_STYLE
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.NON_CAR_COMPATIBLE_MESSAGE_NO_REPLY
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.NON_CAR_COMPATIBLE_MESSAGE_SHOWS_UI
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.OLD_MESSAGES_IN_NOTIFICATION
import com.google.android.libraries.car.communication.messagingsync.DebugLogs.PhoneToCarMessageError.REPLY_REPOST
import com.google.android.libraries.car.notifications.CoroutineTestRule
import com.google.android.libraries.car.notifications.MessageNotificationMocks
import com.google.android.libraries.car.notifications.NotificationSyncManager
import com.google.android.libraries.car.notifications.SettingsNotificationHelper.grantNotificationAccess
import com.google.common.truth.Truth.assertThat
import com.google.protos.aae.messenger.NotificationMsg
import com.google.protos.aae.messenger.NotificationMsg.PhoneToCarMessage
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.reset
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class MessagingNotificationHandlerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val carId = UUID.fromString("c2337f28-18ff-4f92-a0cf-4df63ab2c881")
  private val sharedState = NotificationHandlerSharedState()
  private val replyMessages = sharedState.replyMessages
  private val messagingUtils = MessagingUtils(context)
  private val defaultKey = "key"

  private lateinit var sendMessage: (data: ByteArray, carId: UUID) -> Int
  private lateinit var connectionTime: Instant
  private lateinit var handler: MessagingNotificationHandler

  @get:Rule var coroutinesTestRule = CoroutineTestRule()

  @Before
  fun setup() {
    sendMessage = mock()
    handler = createHandler().also { it.onCarConnected() }
    connectionTime = Instant.now()
    grantNotificationAccess(context)
    messagingUtils.enableMessagingSync(carId.toString(), {}, {})
  }

  @After
  fun tearDown() {
    replyMessages.evictAll()
    handler.onCarDisconnected()
  }

  @Test
  fun setup_assertExpectedState() {
    assertThat(messagingUtils.isMessagingSyncEnabled(carId.toString())).isTrue()
  }

  @Test
  fun onCarConnected_addedToSyncManager() {
    assertThat(handler.isCarConnected).isTrue()
    assertThat(NotificationSyncManager.containsHandler(handler)).isTrue()
  }

  @Test
  fun onCarDisconnected_removesHandlerFromSyncManager() {
    handler.onCarDisconnected()
    assertThat(handler.isCarConnected).isFalse()
    assertThat(NotificationSyncManager.containsHandler(handler)).isFalse()
  }

  @Test
  fun onCarDisconnected_noMessagesSent() {
    val sbn = createSBN()
    handler.onCarDisconnected()
    handler.onNotificationReceived(sbn)
    verify(sendMessage, never()).invoke(any(), any())
  }

  @Test
  fun onCarConnected_afterOnCarDisconnected_startsFromCleanSlate() {
    val sbn = createSBN()
    handler.onNotificationReceived(sbn)
    handler.onCarDisconnected()
    handler.onCarConnected()
    connectionTime = Instant.now()
    val anotherSBN = createSBN(connectionTime = connectionTime)
    handler.onNotificationReceived(anotherSBN)
    val byteArrayCaptor = argumentCaptor<ByteArray>()
    val carIdCaptor = argumentCaptor<UUID>()
    verify(sendMessage, times(2)).invoke(byteArrayCaptor.capture(), carIdCaptor.capture())
    val firstMessage = PhoneToCarMessage.parseFrom(byteArrayCaptor.firstValue)
    val secondMessage = PhoneToCarMessage.parseFrom(byteArrayCaptor.secondValue)
    assertThat(firstMessage.messageDataCase.name).isEqualTo(CONVERSATION_DATA_TYPE)
    assertThat(secondMessage.messageDataCase.name).isEqualTo(CONVERSATION_DATA_TYPE)
  }

  @Test
  fun onNotificationReceived_validMessageSentToCar() {
    val sbn = createSBN()
    handler.onNotificationReceived(sbn)
    val byteArrayCaptor = argumentCaptor<ByteArray>()
    val carIdCaptor = argumentCaptor<UUID>()
    verify(sendMessage).invoke(byteArrayCaptor.capture(), carIdCaptor.capture())
    assertThat(carIdCaptor.firstValue).isEqualTo(carId)
    val phoneToCarMessage = PhoneToCarMessage.parseFrom(byteArrayCaptor.firstValue)
    assertThat(phoneToCarMessage.notificationKey).isEqualTo(defaultKey)
  }

  @Test
  fun onMessageReceived_testReply() {
    val sbn = createSBN()
    handler.onNotificationReceived(sbn)
    handler.onMessageReceived(buildReply(sbn.key))
    assertThat(replyMessages.size()).isEqualTo(1)
    assertThat(replyMessages[defaultKey]).isNotNull()
    assertThat(replyMessages.maxSize()).isEqualTo(10)
  }

  @Test
  fun onNotificationReceived_sameThread() {
    val sbn = createSBN()
    handler.onNotificationReceived(sbn)
    val anotherSBN =
      createSBN(
        postSpecificMessage =
          NotificationCompat.MessagingStyle.Message(
            "Another message in the same thread",
            connectionTime.toEpochMilli() + 300,
            Person.Builder().setName("senderName").build()
          )
      )
    handler.onNotificationReceived(anotherSBN)
    val byteArrayCaptor = argumentCaptor<ByteArray>()
    val carIdCaptor = argumentCaptor<UUID>()
    verify(sendMessage, times(2)).invoke(byteArrayCaptor.capture(), carIdCaptor.capture())
    val firstMessage = PhoneToCarMessage.parseFrom(byteArrayCaptor.firstValue)
    val secondMessage = PhoneToCarMessage.parseFrom(byteArrayCaptor.secondValue)
    assertThat(carIdCaptor.firstValue).isEqualTo(carId)
    assertThat(carIdCaptor.secondValue).isEqualTo(carId)
    assertThat(firstMessage.notificationKey).isEqualTo(defaultKey)
    assertThat(secondMessage.notificationKey).isEqualTo(defaultKey)
    assertThat(firstMessage.messageDataCase.name).isEqualTo(CONVERSATION_DATA_TYPE)
    assertThat(secondMessage.messageDataCase.name).isEqualTo(MESSAGE_DATA_TYPE)
  }

  @Test
  fun onNotificationReceived_separateThread() {
    val sbn = createSBN()
    handler.onNotificationReceived(sbn)
    val anotherSBN = createSBN(key = "${defaultKey}_2")
    handler.onNotificationReceived(anotherSBN)
    val byteArrayCaptor = argumentCaptor<ByteArray>()
    val carIdCaptor = argumentCaptor<UUID>()
    verify(sendMessage, times(2)).invoke(byteArrayCaptor.capture(), carIdCaptor.capture())
    val firstMessage = PhoneToCarMessage.parseFrom(byteArrayCaptor.firstValue)
    val secondMessage = PhoneToCarMessage.parseFrom(byteArrayCaptor.secondValue)
    assertThat(carIdCaptor.firstValue).isEqualTo(carId)
    assertThat(carIdCaptor.secondValue).isEqualTo(carId)
    assertThat(firstMessage.notificationKey).isEqualTo(defaultKey)
    assertThat(secondMessage.notificationKey).isEqualTo("${defaultKey}_2")
    assertThat(firstMessage.messageDataCase.name).isEqualTo(CONVERSATION_DATA_TYPE)
    assertThat(secondMessage.messageDataCase.name).isEqualTo(CONVERSATION_DATA_TYPE)
  }

  @Test
  fun onNotificationReceived_featureDisabled() {
    messagingUtils.disableMessagingSync(carId.toString())
    val sbn = createSBN()
    assertThat(handler.canHandleNotification(sbn)).isFalse()
    assertThat(handler.cannotHandleNotificationReason(sbn))
      .contains(MESSAGING_SYNC_FEATURE_DISABLED.name)
    handler.onNotificationReceived(sbn)
    verify(sendMessage, never()).invoke(any(), any())
  }

  @Test
  fun onNotificationReceived_duplicateNotification() {
    val sbn = createSBN()
    handler.onNotificationReceived(sbn).also { reset(sendMessage) }
    assertThat(handler.canHandleNotification(sbn)).isFalse()
    assertThat(handler.cannotHandleNotificationReason(sbn)).contains(DUPLICATE_MESSAGE.name)
    handler.onNotificationReceived(sbn)
    verify(sendMessage, never()).invoke(any(), any())
  }

  @Test
  fun onNotificationReceived_replyRepost() {
    val sbn = createSBN()
    handler.onNotificationReceived(sbn).also { reset(sendMessage) }
    handler.onMessageReceived(buildReply(defaultKey))
    val responseSBN = createSBN(postSpecificMessage = replyMessages[defaultKey])
    assertThat(handler.canHandleNotification(responseSBN)).isFalse()
    assertThat(handler.cannotHandleNotificationReason(responseSBN)).contains(REPLY_REPOST.name)
    handler.onNotificationReceived(responseSBN)
    verify(sendMessage, never()).invoke(any(), any())
  }

  @Test
  fun onNotificationReceived_oldMessage() {
    val sbn = createSBN(isOldMessage = true)
    assertThat(handler.canHandleNotification(sbn)).isFalse()
    assertThat(handler.cannotHandleNotificationReason(sbn))
      .contains(OLD_MESSAGES_IN_NOTIFICATION.name)
    handler.onNotificationReceived(sbn)
    verify(sendMessage, never()).invoke(any(), any())
  }

  @Test
  fun onNotificationReceived_noMessagingStyle() {
    val sbn = createSBN(hasMessagingStyle = false)
    assertThat(handler.canHandleNotification(sbn)).isFalse()
    assertThat(handler.cannotHandleNotificationReason(sbn))
      .contains(NON_CAR_COMPATIBLE_MESSAGE_NO_MESSAGING_STYLE.name)
    handler.onNotificationReceived(sbn)
    verify(sendMessage, never()).invoke(any(), any())
  }

  @Test
  fun onNotificationReceived_noReplyAction() {
    val sbn = createSBN(hasReplyAction = false)
    assertThat(handler.canHandleNotification(sbn)).isFalse()
    assertThat(handler.cannotHandleNotificationReason(sbn))
      .contains(NON_CAR_COMPATIBLE_MESSAGE_NO_REPLY.name)
    handler.onNotificationReceived(sbn)
    verify(sendMessage, never()).invoke(any(), any())
  }

  @Test
  fun onNotificationReceived_wrongReplySemanticAction() {
    val sbn = createSBN(hasWrongReplySemanticAction = true)
    assertThat(handler.canHandleNotification(sbn)).isFalse()
    assertThat(handler.cannotHandleNotificationReason(sbn))
      .contains(NON_CAR_COMPATIBLE_MESSAGE_NO_REPLY.name)
    handler.onNotificationReceived(sbn)
    verify(sendMessage, never()).invoke(any(), any())
  }

  @Test
  fun onNotificationReceived_showsUI() {
    val sbn = createSBN(showsUI = true)
    assertThat(handler.canHandleNotification(sbn)).isFalse()
    assertThat(handler.cannotHandleNotificationReason(sbn))
      .contains(NON_CAR_COMPATIBLE_MESSAGE_SHOWS_UI.name)
    handler.onNotificationReceived(sbn)
    verify(sendMessage, never()).invoke(any(), any())
  }

  /** Default functionality is a valid message SBN. To make invalid, you may customize the input. */
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
    postSpecificMessage: NotificationCompat.MessagingStyle.Message? = null
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

  private fun createHandler() =
    MessagingNotificationHandler(
      context,
      carId,
      sendMessage,
      messagingUtils,
      SystemTimeProvider(),
      sharedState = sharedState
    )

  private fun buildReply(key: String) =
    NotificationMsg.CarToPhoneMessage.newBuilder()
      .setNotificationKey(key)
      .setActionRequest(
        NotificationMsg.Action.newBuilder()
          .setActionName(NotificationMsg.Action.ActionName.REPLY)
          .addMapEntry(
            NotificationMsg.MapEntry.newBuilder().setKey("REPLY").setValue("my response")
          )
      )
      .build()
      .toByteArray()

  companion object {
    private const val MESSAGE_DATA_TYPE = "MESSAGE"
    private const val CONVERSATION_DATA_TYPE = "CONVERSATION"
  }
}
