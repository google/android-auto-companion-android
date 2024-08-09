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

package com.google.android.libraries.car.trusteddevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trusteddevice.TrustedDeviceFeature.EnrollmentError
import com.google.android.libraries.car.trusteddevice.TrustedDeviceFeature.UnlockingError
import com.google.android.libraries.car.trusteddevice.storage.TrustedDeviceManagerStorage
import com.google.companionprotos.trusteddevice.PhoneAuthProto.PhoneCredentials
import com.google.companionprotos.trusteddevice.TrustedDeviceMessageProto.TrustedDeviceError
import com.google.companionprotos.trusteddevice.TrustedDeviceMessageProto.TrustedDeviceMessage
import com.google.companionprotos.trusteddevice.TrustedDeviceMessageProto.TrustedDeviceState
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val PASSCODE_REQUIRED_KEY = "PASSCODE_REQUIRED_KEY"
private const val UNLOCK_HISTORY_ENABLED_KEY = "UNLOCK_HISTORY_ENABLED_KEY"

/**
 * Trusted device is a device that unlocks an Android user on an Infotainment Head Unit (IHU) that
 * runs Android Automotive OS.
 *
 * This device (likely a phone) first needs to be enrolled as a trusted device via a call to
 * [enroll]. It can then be used to [unlock] the IHU.
 *
 * When features are instantiated as a list, place this feature at the head of the list to ensure
 * unlock message is enqueued first in the outgoing stream for a good user experience.
 */
@PublicApi
class TrustedDeviceManager
internal constructor(
  private val context: Context,
  private val trustedDeviceManagerStorage: TrustedDeviceManagerStorage,
  private val coroutineScope: CoroutineScope,
) : TrustedDeviceFeature, FeatureManager() {
  override val featureId = FEATURE_ID

  private val callbacks = mutableListOf<TrustedDeviceFeature.Callback>()

  @VisibleForTesting
  internal val sharedPref = context.getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE)

  private val enrollingCars = mutableSetOf<UUID>()
  private val unlockingCars = mutableSetOf<UUID>()
  private var initiateEnrollmentCars = mutableListOf<UUID>()

  /** Cars which should be unlocked after the phone is unlocked. */
  private val unlockPendingCars = mutableListOf<UUID>()

  private val phoneUnlockedBroadcastReceiver: BroadcastReceiver =
    PhoneUnlockedBroadcastReceiver(this::handlePhoneUnlocked)

  /** Creates a new instance of this manager. */
  constructor(
    context: Context
  ) : this(context, TrustedDeviceManagerStorage(context, Clock.systemUTC()), MainScope())

  /** `true` if a passcode is required before an unlock of a remote vehicle can occur. */
  override var isPasscodeRequired: Boolean
    get() = sharedPref.getBoolean(PASSCODE_REQUIRED_KEY, true)
    set(value) = sharedPref.edit().putBoolean(PASSCODE_REQUIRED_KEY, value).apply()

  /** `true` if storing unlock history should be enabled by this manager. */
  override var isUnlockHistoryEnabled: Boolean
    get() = sharedPref.getBoolean(UNLOCK_HISTORY_ENABLED_KEY, true)
    set(enabled) {
      sharedPref.edit().putBoolean(UNLOCK_HISTORY_ENABLED_KEY, enabled).apply()
      if (!enabled) clearAllUnlockHistory()
    }

  override fun onCarConnected(carId: UUID) {
    context.registerReceiver(
      phoneUnlockedBroadcastReceiver,
      IntentFilter(Intent.ACTION_USER_PRESENT),
    )

    // Use `runBlocking` instead of `launch` to ensure the immediate execution of `unlock()`.
    // Launching as coroutine allows other features to enqueue their messages first, delaying the
    // delivery of unlock message.
    val enrolled = runBlocking { trustedDeviceManagerStorage.containsCredential(carId) }
    logd(TAG, "Car $carId connected. Enrolled: $enrolled.")

    if (enrolled) {
      unlock(carId)
    } else {
      coroutineScope.launch { maybeSyncFeatureState(carId) }
    }
  }

  override fun onMessageReceived(message: ByteArray, carId: UUID) {
    val protoMessage = parseAndValidate(message)
    if (protoMessage == null) {
      loge(
        TAG,
        "Received message from $carId, but could not parse into a valid " +
          "TrustedDeviceMessage. Ignoring.",
      )
      return
    }
    when (protoMessage.type) {
      TrustedDeviceMessage.MessageType.START_ENROLLMENT ->
        coroutineScope.launch { handleStartEnrollmentMessage(carId) }
      TrustedDeviceMessage.MessageType.HANDLE ->
        coroutineScope.launch { handleHandleMessage(protoMessage.payload, carId) }
      TrustedDeviceMessage.MessageType.ACK ->
        coroutineScope.launch { handleUnlockAckMessage(carId) }
      TrustedDeviceMessage.MessageType.STATE_SYNC ->
        coroutineScope.launch { handleStateSyncMessage(protoMessage.payload, carId) }
      TrustedDeviceMessage.MessageType.UNLOCK_REQUEST ->
        coroutineScope.launch { handleUnlockRequestMessage(carId) }
      else -> {
        loge(
          TAG,
          "Received unrecognized proto message type ${protoMessage.type} for car $carId. " +
            "Ignoring.",
        )
      }
    }
  }

  override fun onMessageSent(messageId: Int, carId: UUID) {}

  override fun onCarDisconnected(carId: UUID) {
    when (carId) {
      in enrollingCars -> coroutineScope.launch { handleEnrollDisconnection(carId) }
      in unlockingCars -> coroutineScope.launch { handleUnlockDisconnection(carId) }
      in unlockPendingCars -> handlePendingUnlockDisconnection(carId)
      else -> logd(TAG, "Car $carId disconnected. Nothing to be done.")
    }
    if (connectedCars.isEmpty()) {
      context.unregisterReceiver(phoneUnlockedBroadcastReceiver)
    }
  }

  override fun onCarDisassociated(carId: UUID) {
    coroutineScope.launch {
      logi(TAG, "Car ($carId) has been disassociated. Clearing any credentials and unlock history.")
      clearEnrollment(carId, syncToCar = false)
      trustedDeviceManagerStorage.clearFeatureState(carId)
    }
  }

  override fun onAllCarsDisassociated() {
    coroutineScope.launch {
      logi(TAG, "All cars have been disassociated. Clearing all credentials.")
      enrollingCars.clear()
      trustedDeviceManagerStorage.clearAll()
    }
  }

  /**
   * Sends credentials to those connected cars which would need the phone to be unlocked first.
   *
   * Should be called when the phone is locked.
   */
  @VisibleForTesting
  internal fun handlePhoneUnlocked() {
    logi(TAG, "Phone unlocked, send credentials to device unlock required cars.")
    unlockPendingCars.forEach { unlock(it) }
    unlockPendingCars.clear()
  }

  override suspend fun isEnabled(carId: UUID): Boolean =
    trustedDeviceManagerStorage.containsCredential(carId)

  override fun enroll(carId: UUID) {
    coroutineScope.launch {
      if (isPasscodeRequired && !isDeviceSecure(context)) {
        logi(TAG, "Could not enroll because device needs to be secured first.")

        sendEnrollmentErrorMessage(carId)
        callbacks.forEach { it.onEnrollmentFailure(carId, EnrollmentError.PASSCODE_NOT_SET) }

        // After sending the error message, there is nothing to reset since this is the first step.
        return@launch
      }

      val token = createToken()
      trustedDeviceManagerStorage.storeToken(token, carId)

      val message =
        TrustedDeviceMessage.newBuilder()
          .setVersion(VERSION)
          .setType(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
          .setPayload(ByteString.copyFrom(token))
          .build()

      if (!isCarConnected(carId)) {
        callbacks.forEach { it.onEnrollmentFailure(carId, EnrollmentError.CAR_NOT_CONNECTED) }
        return@launch
      }

      sendMessage(message.toByteArray(), carId)
      enrollingCars.add(carId)
    }
  }

  /**
   * Sends a message to the car with the given [carId] informing it that enrollment encountered an
   * error.
   */
  private fun sendEnrollmentErrorMessage(carId: UUID) {
    val error =
      TrustedDeviceError.newBuilder()
        .setType(TrustedDeviceError.ErrorType.DEVICE_NOT_SECURED)
        .build()

    val message =
      with(TrustedDeviceMessage.newBuilder()) {
        version = VERSION
        type = TrustedDeviceMessage.MessageType.ERROR
        payload = error.toByteString()
        build()
      }

    sendMessage(message.toByteArray(), carId)
  }

  /**
   * Attempts to send a message to the car with the given [carId] informing it that trusted device
   * has been disabled from this current phone.
   *
   * The car should also disable trusted device upon receiving this message. If the car is not
   * currently connected, the message will be saved until the car next connects.
   */
  private suspend fun syncDisabledStateSync(carId: UUID) {
    val state = TrustedDeviceState.newBuilder().setEnabled(false).build().toByteString()

    val message =
      TrustedDeviceMessage.newBuilder()
        .setVersion(VERSION)
        .setType(TrustedDeviceMessage.MessageType.STATE_SYNC)
        .setPayload(state)
        .build()
        .toByteArray()

    if (isCarConnected(carId)) {
      logi(TAG, "Car $carId currently connected. Sending disable feature status to it.")
      sendMessage(message, carId)
      return
    }

    logi(
      TAG,
      "Car $carId's enrollment status cleared, but not currently connected. " +
        "Saving status to send on next connection.",
    )

    trustedDeviceManagerStorage.storeFeatureState(message, carId)
  }

  override fun isDeviceUnlockRequired(carId: UUID) = isDeviceUnlockRequired(carId, sharedPref)

  override fun setDeviceUnlockRequired(carId: UUID, isRequired: Boolean) =
    setDeviceUnlockRequired(carId, isRequired, sharedPref)

  private suspend fun handleStartEnrollmentMessage(carId: UUID) {
    initiateEnrollmentCars.add(carId)
    enroll(carId)
    callbacks.forEach { it.onEnrollmentRequested(carId) }
  }

  private suspend fun handleHandleMessage(payload: ByteString, carId: UUID) {
    if (carId !in enrollingCars) {
      loge(TAG, "Received handle for $carId when car currently enrolling. Ignoring.")
      return
    }

    val handle = payload.toByteArray()
    if (!trustedDeviceManagerStorage.storeHandle(handle, carId)) {
      loge(TAG, "Could not store handle from car $carId.")
      clearEnrollment(carId, syncToCar = true)
      callbacks.forEach { it.onEnrollmentFailure(carId, EnrollmentError.INTERNAL) }
      return
    }

    val ack =
      TrustedDeviceMessage.newBuilder()
        .setVersion(VERSION)
        .setType(TrustedDeviceMessage.MessageType.ACK)
        .build()
    sendMessage(ack.toByteArray(), carId)

    logi(TAG, "Successfully enrolled car $carId.")

    // As precaution, clear any feature state messages since a sync is no longer needed if a new
    // successful enrollment has occurred.
    trustedDeviceManagerStorage.clearFeatureState(carId)

    val initiatedFromCar = initiateEnrollmentCars.contains(carId)

    enrollingCars.remove(carId)
    initiateEnrollmentCars.remove(carId)
    callbacks.forEach { it.onEnrollmentSuccess(carId, initiatedFromCar) }
  }

  private suspend fun handleEnrollDisconnection(carId: UUID) {
    // There could be a race condition where we received the handle but have yet processed it,
    // thus carId still exists in enrollingCars.
    enrollingCars.remove(carId)

    if (trustedDeviceManagerStorage.containsCredential(carId)) {
      logi(TAG, "Car disconnected after successful enrollment.")
      return
    }

    loge(TAG, "Car $carId disconnected before enrollment completed. Aborted.")
    clearEnrollment(carId, syncToCar = false)
    callbacks.forEach { it.onEnrollmentFailure(carId, EnrollmentError.INTERNAL) }
  }

  override suspend fun stopEnrollment(carId: UUID) {
    clearEnrollment(carId, syncToCar = true)
  }

  override fun unlock(carId: UUID) {
    if (isPasscodeRequired && !isDeviceSecure(context)) {
      loge(
        TAG,
        "Could not unlock car with id $carId since passcode has not been set on this device.",
      )
      notifyUnlockingError(carId, UnlockingError.PASSCODE_NOT_SET)
      return
    }

    // Use `runBlocking` to ensure the message is immediately queued for sending.
    // Launching as coroutine allows other features to enqueue their messages first, delaying the
    // delivery of unlock message.
    runBlocking {
      if (isDeviceUnlockRequired(carId) && isDeviceLocked(context)) {
        logi(TAG, "Could not unlock because device needs to be unlocked first.")
        unlockPendingCars.add(carId)
        notifyUnlockingError(carId, UnlockingError.DEVICE_LOCKED)
        return@runBlocking
      }

      if (carId in unlockingCars) {
        loge(TAG, "Already initiated unlocking for $carId. Ignored.")
        return@runBlocking
      }

      logi(TAG, "Sending unlock credentials to car $carId")

      retrieveCredential(carId)?.let {
        val message =
          TrustedDeviceMessage.newBuilder()
            .setVersion(VERSION)
            .setType(TrustedDeviceMessage.MessageType.UNLOCK_CREDENTIALS)
            .setPayload(ByteString.copyFrom(it.toByteArray()))
            .build()
        sendMessage(message.toByteArray(), carId)
        unlockingCars.add(carId)
        callbacks.forEach { it.onUnlockingStarted(carId) }
      }
    }
  }

  /**
   * Clears the enrollment status of the car with the given [carId] and, if [syncToCar] is `true`,
   * informs the car of this.
   *
   * If the request to clear the enrollment was sent by the car, then set [initiatedFromCar] to
   * `true`.
   */
  private suspend fun clearEnrollment(
    carId: UUID,
    syncToCar: Boolean,
    initiatedFromCar: Boolean = false,
  ) {
    logi(TAG, "Clearing enrollment for car $carId")

    val enrolled = isEnabled(carId)

    enrollingCars.remove(carId)
    initiateEnrollmentCars.remove(carId)

    trustedDeviceManagerStorage.clearCredentials(carId)

    if (!enrolled) {
      logi(
        TAG,
        "Car $carId has cleared or stopped enrollment, but is not currently enrolled. " +
          "Not notifying callbacks.",
      )
      return
    }

    // Only need to sync the status to the car if previously enrolled.
    if (syncToCar) {
      syncDisabledStateSync(carId)
    }

    logi(
      TAG,
      "Car $carId has cleared its enrollment. Initiated from car: $initiatedFromCar. " +
        "Notifying callbacks",
    )

    callbacks.forEach { it.onUnenroll(carId, initiatedFromCar) }
  }

  /**
   * Checks if the car with the given [carId] has a pending feature state messages to be synced and
   * syncs it if such a message exists.
   */
  private suspend fun maybeSyncFeatureState(carId: UUID) {
    val featureState = trustedDeviceManagerStorage.getFeatureState(carId)

    if (featureState == null) {
      logi(TAG, "Car $carId does not have any stored feature status messages to send.")
      return
    }

    logi(TAG, "Car $carId has stored feature status messages to sync. Sending to car.")

    sendMessage(featureState, carId)
    trustedDeviceManagerStorage.clearFeatureState(carId)
  }

  private suspend fun retrieveCredential(carId: UUID): PhoneCredentials? =
    trustedDeviceManagerStorage.getCredential(carId)

  private suspend fun handleUnlockAckMessage(carId: UUID) {
    if (carId !in unlockingCars) {
      loge(TAG, "Received message from unrecognized $carId. Ignored.")
      return
    }

    logi(TAG, "Received ack message for successful unlock from car $carId. Notifying listeners.")

    unlockingCars.remove(carId)

    if (isUnlockHistoryEnabled) {
      logd(TAG, "Unlock history is enabled. Storing unlock date.")
      trustedDeviceManagerStorage.recordUnlockDate(carId)
    }

    callbacks.forEach { it.onUnlockingSuccess(carId) }
  }

  private fun handleUnlockDisconnection(carId: UUID) {
    unlockingCars.remove(carId)
    // The cause for No receiving ACK could be that car is already unlocked. No-op.
    logi(TAG, "Car $carId disconnected before received ACK. Ignored.")
  }

  private fun handlePendingUnlockDisconnection(carId: UUID) {
    unlockPendingCars.remove(carId)
    loge(TAG, "Car $carId disconnected before pending unlock completes. Aborted.")
    notifyUnlockingError(carId, UnlockingError.CAR_NOT_CONNECTED)
  }

  private suspend fun handleStateSyncMessage(payload: ByteString, carId: UUID) {
    val state =
      try {
        TrustedDeviceState.parseFrom(payload.toByteArray())
      } catch (e: InvalidProtocolBufferException) {
        loge(TAG, "Received state sync message from $carId but unable to parse. Ignoring.", e)
        return
      }

    // The user could only turn off trusted device from the head unit when the phone is not
    // connected. Thus, only need to sync state if the feature is disabled. Otherwise, if the two
    // devices are connected and the feature is enabled, then the normal enrollment flow will be
    // triggered.
    if (state.enabled) {
      logd(
        TAG,
        "Received state sync message from $carId with enabled state. Ignoring message since " +
          "the normal enrollment flow should toggle this on.",
      )
      return
    }

    logi(
      TAG,
      "Received state sync message from $carId indicating trusted device feature has disabled." +
        "Clearing local enrollment",
    )

    clearEnrollment(carId, syncToCar = false, initiatedFromCar = true)
  }

  private fun handleUnlockRequestMessage(carId: UUID) {
    logd(TAG, "Received unlock message from car $carId. Removing it from unlocking lists.")
    unlockingCars.remove(carId)
    unlockPendingCars.remove(carId)
    // Use `runBlocking` instead of `launch` to ensure the immediate execution of `unlock()`.
    // Launching as coroutine allows other features to enqueue their messages first, delaying the
    // delivery of unlock message.
    val enrolled = runBlocking { trustedDeviceManagerStorage.containsCredential(carId) }
    logd(TAG, "Car $carId connected. Enrolled: $enrolled. Sending credential to unlock.")

    // No need to sync status during connected session.
    if (enrolled) {
      unlock(carId)
    }
  }

  /** Returns `null` for invalid message, i.e. version is not expected. */
  private fun parseAndValidate(message: ByteArray): TrustedDeviceMessage? {
    with(TrustedDeviceMessage.parseFrom(message)) {
      if (version != VERSION) {
        return null
      }
      return this
    }
  }

  private fun notifyUnlockingError(carId: UUID, error: UnlockingError) {
    for (callback in callbacks) {
      callback.onUnlockingFailure(carId, error)
      callback.onUnlockingFailure(carId)
    }
  }

  override suspend fun getUnlockHistory(carId: UUID): List<Instant> =
    trustedDeviceManagerStorage.getUnlockHistory(carId)

  override fun clearUnlockHistory(carId: UUID) {
    coroutineScope.launch { trustedDeviceManagerStorage.clearUnlockHistory(carId) }
  }

  private fun clearAllUnlockHistory() {
    coroutineScope.launch { trustedDeviceManagerStorage.clearAllUnlockHistory() }
  }

  override fun registerCallback(callback: TrustedDeviceFeature.Callback) {
    callbacks.add(callback)
  }

  override fun unregisterCallback(callback: TrustedDeviceFeature.Callback) {
    callbacks.remove(callback)
  }

  companion object {
    private const val TAG = "TrustedDeviceManager"

    internal const val VERSION = 2
    val FEATURE_ID: UUID = UUID.fromString("85dff28b-3036-4662-bb22-baa7f898dc47")

    private const val SHARED_PREF =
      "com.google.android.libraries.car.trusteddevice.TrustedDeviceManager"

    private const val ESCROW_TOKEN_SIZE_BYTES = 8

    /** Returns the escrow token to be used for enrolling as a trusted device. */
    private fun createToken(): ByteArray =
      ByteArray(ESCROW_TOKEN_SIZE_BYTES).apply { SecureRandom().nextBytes(this) }
  }
}
