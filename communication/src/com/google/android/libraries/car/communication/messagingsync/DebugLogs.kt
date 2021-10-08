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

import android.util.Log

/**
 * Debug Logs for Messaging Sync Feature
 */
internal object DebugLogs {
  private const val TAG = "MessagingSyncFeature"
  private val isDebugLoggingEnabled
    get() = Log.isLoggable(TAG, Log.DEBUG)

  enum class PhoneToCarMessageError {
    DUPLICATE_MESSAGE,
    // This error occurs when a notification is posted after a car connection
    // but the messages in the messaging style are old messages.
    OLD_MESSAGES_IN_NOTIFICATION,
    MESSAGING_SYNC_FEATURE_DISABLED,
    NON_CAR_COMPATIBLE_MESSAGE_NO_MESSAGING_STYLE,
    NON_CAR_COMPATIBLE_MESSAGE_NO_REPLY,
    NON_CAR_COMPATIBLE_MESSAGE_NO_MARK_AS_READ,
    NON_CAR_COMPATIBLE_MESSAGE_SHOWS_UI,
    REPLY_REPOST,
    SMS_MESSAGE,
    UNKNOWN
  }

  enum class CarToPhoneMessageError {
    INVALID_PROTOBUF_EXCEPTION,
    UNKNOWN_ACTION,
  }

  fun logPhoneToCarMessageError(reason: String) {
    Log.w(TAG, "Ignoring message because: $reason")
  }

  fun logPhoneToCarMessageError(
    errorCode: PhoneToCarMessageError,
    metadata: String,
    throwable: Throwable? = null
  ) {
    Log.w(TAG, "Ignoring message because: ${errorCode.name}, $metadata.", throwable)
  }

  fun logCarToPhoneMessageError(
    errorCode: CarToPhoneMessageError,
    metadata: String,
    throwable: Throwable? = null
  ) {
    Log.w(TAG, "Unable to handle message ${errorCode.name}, $metadata.", throwable)
  }

  fun logMessageNotificationReceived(metadata: String) {
    if (!isDebugLoggingEnabled) return
    Log.d(TAG, "Message notification received on the phone, $metadata.")
  }

  fun logMessageToCarSent(metadata: String) {
    if (!isDebugLoggingEnabled) return
    Log.d(TAG, "Message sent to car, $metadata.")
  }

  fun logMessagingSyncFeatureEnabled() {
    if (!isDebugLoggingEnabled) return
    Log.d(TAG, "Messaging Sync Feature Enabled.")
  }

  fun logMessagingSyncFeatureDisabled() {
    if (!isDebugLoggingEnabled) return
    Log.d(TAG, "Messaging Sync Feature Disabled.")
  }

  fun logOnCarConnected() {
    if (!isDebugLoggingEnabled) return
    Log.d(TAG, "Car connected.")
  }

  fun logOnCarDisconnected() {
    if (!isDebugLoggingEnabled) return
    Log.d(TAG, "Car disconnected.")
  }

  fun logSendReplyToPhone() {
    if (!isDebugLoggingEnabled) return
    Log.d(TAG, "Sent reply to Phone")
  }

  fun logSendMarkAsReadToPhone() {
    if (!isDebugLoggingEnabled) return
    Log.d(TAG, "Sent mark as read to Phone")
  }
}
