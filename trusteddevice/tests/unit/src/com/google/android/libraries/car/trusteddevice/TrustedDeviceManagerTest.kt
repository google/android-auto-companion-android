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

import android.app.KeyguardManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.Car
import com.google.android.libraries.car.trusteddevice.TrustedDeviceFeature.EnrollmentError
import com.google.android.libraries.car.trusteddevice.TrustedDeviceManager.Companion.FEATURE_ID
import com.google.android.libraries.car.trusteddevice.TrustedDeviceManager.Companion.VERSION
import com.google.android.libraries.car.trusteddevice.storage.TrustedDeviceDatabase
import com.google.android.libraries.car.trusteddevice.storage.TrustedDeviceManagerStorage
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.google.companionprotos.trusteddevice.TrustedDeviceMessageOuterClass.TrustedDeviceError
import com.google.companionprotos.trusteddevice.TrustedDeviceMessageOuterClass.TrustedDeviceMessage
import com.google.companionprotos.trusteddevice.TrustedDeviceMessageOuterClass.TrustedDeviceState
import com.google.protobuf.ByteString
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.atLeastOnce
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.times
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.robolectric.Shadows

private const val DEVICE_NAME = "device_name"
private const val DEFAULT_MESSAGE_ID = 1

private val HANDLE = "handle".toByteArray()
private val TOKEN = "token".toByteArray()
private val DEVICE_ID = UUID.fromString("8a16e892-d4ad-455d-8194-cbc2dfbaebdf")
private val DEVICE_ID_2 = UUID.fromString("9a16e892-d4ad-455d-8194-cbc2dfbaebdf")
private val HANDLE_MESSAGE =
  TrustedDeviceMessage.newBuilder()
    .setVersion(VERSION)
    .setType(TrustedDeviceMessage.MessageType.HANDLE)
    .setPayload(ByteString.copyFrom(HANDLE))
    .build()
private val START_ENROLLMENT_MESSAGE =
  TrustedDeviceMessage.newBuilder()
    .setVersion(VERSION)
    .setType(TrustedDeviceMessage.MessageType.START_ENROLLMENT)
    .build()
private val ACK_MESSAGE =
  TrustedDeviceMessage.newBuilder()
    .setVersion(VERSION)
    .setType(TrustedDeviceMessage.MessageType.ACK)
    .build()

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class TrustedDeviceManagerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val shadowKeyguardManager =
    Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java))
  private val testDispatcher = UnconfinedTestDispatcher()

  private val mockCallback: TrustedDeviceFeature.Callback = mock()
  private val mockCar: Car = mock {
    on { sendMessage(any(), any()) } doReturn DEFAULT_MESSAGE_ID
    on { deviceId } doReturn DEVICE_ID
    on { name } doReturn DEVICE_NAME
  }
  private val mockCar2: Car = mock {
    on { sendMessage(any(), any()) } doReturn DEFAULT_MESSAGE_ID
    on { deviceId } doReturn DEVICE_ID_2
    on { name } doReturn DEVICE_NAME
  }

  private lateinit var trustedDeviceManager: TrustedDeviceManager
  private lateinit var carCallbacks: MutableSet<Car.Callback>
  private lateinit var storage: TrustedDeviceManagerStorage
  private lateinit var database: TrustedDeviceDatabase

  @Before
  fun setUp() {
    carCallbacks = mutableSetOf()

    database =
      Room.inMemoryDatabaseBuilder(context, TrustedDeviceDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(directExecutor())
        .build()
    storage = TrustedDeviceManagerStorage(context, Clock.systemUTC(), database)

    trustedDeviceManager =
      TrustedDeviceManager(context, storage, CoroutineScope(testDispatcher)).apply {
        registerCallback(mockCallback)
      }

    shadowKeyguardManager.setIsDeviceSecure(true)
  }

  @After
  fun cleanUp() {
    trustedDeviceManager.sharedPref.edit().clear().commit()
    database.close()
  }

  @Test
  fun testPendingInit_receiveStartEnrollmentMessage_sendsEscrowToken() {
    shadowKeyguardManager.setIsDeviceSecure(true)

    trustedDeviceManager.notifyCarConnected(mockCar)

    argumentCaptor<Car.Callback>().apply {
      verify(mockCar).setCallback(capture(), any())
      carCallbacks.add(firstValue)
    }
    carCallbacks.forEach { it.onMessageReceived(START_ENROLLMENT_MESSAGE.toByteArray()) }

    // Sends escrow token
    argumentCaptor<ByteArray>().apply {
      verify(mockCar).sendMessage(capture(), eq(FEATURE_ID))
      with(TrustedDeviceMessage.parseFrom(firstValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
      }
    }
    verify(mockCallback).onEnrollmentRequested(DEVICE_ID)
  }

  @Test
  fun testPendingInit_receiveNonStartEnrollmentMessage_ignoreMessage() {
    shadowKeyguardManager.setIsDeviceSecure(true)
    trustedDeviceManager.notifyCarConnected(mockCar)

    argumentCaptor<Car.Callback>().apply {
      verify(mockCar).setCallback(capture(), any())
      carCallbacks.add(firstValue)
    }
    val initMessage =
      TrustedDeviceMessage.newBuilder()
        // Message type is not START_ENROLLMENT.
        .setType(TrustedDeviceMessage.MessageType.ACK)
        .setVersion(VERSION)
        .build()
    carCallbacks.forEach { it.onMessageReceived(initMessage.toByteArray()) }

    verify(mockCar, never()).sendMessage(any(), any())
  }

  @Test
  fun testEnroll_sendMessage_enrollStart() {
    shadowKeyguardManager.setIsDeviceSecure(true)

    trustedDeviceManager.notifyCarConnected(mockCar)
    trustedDeviceManager.enroll(DEVICE_ID)

    argumentCaptor<ByteArray>().apply {
      verify(mockCar).sendMessage(capture(), eq(FEATURE_ID))
      with(TrustedDeviceMessage.parseFrom(firstValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
      }
    }
  }

  @Test
  fun testEnroll_receivedHandle_enrollComplete() =
    runTest(UnconfinedTestDispatcher()) {
      shadowKeyguardManager.setIsDeviceSecure(true)
      trustedDeviceManager.notifyCarConnected(mockCar)
      trustedDeviceManager.enroll(DEVICE_ID)

      argumentCaptor<Car.Callback>().apply {
        verify(mockCar).setCallback(capture(), any())
        carCallbacks.add(firstValue)
      }
      argumentCaptor<ByteArray>().apply {
        verify(mockCar).sendMessage(capture(), eq(FEATURE_ID))
        with(TrustedDeviceMessage.parseFrom(firstValue)) {
          assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
        }
      }

      carCallbacks.forEach { it.onMessageReceived(HANDLE_MESSAGE.toByteArray()) }
      verify(mockCallback).onEnrollmentSuccess(DEVICE_ID, false)
      // Expected to be not null.
      val credential = storage.getCredential(DEVICE_ID)!!
      // Token is generated so we can simply assume credential exists.
      assertThat(credential.handle.toByteArray()).isEqualTo(HANDLE)
    }

  @Test
  fun testEnroll_receiveNonHandleMessage_enrollmentNotComplete() {
    shadowKeyguardManager.setIsDeviceSecure(true)

    trustedDeviceManager.notifyCarConnected(mockCar)
    trustedDeviceManager.enroll(DEVICE_ID)

    argumentCaptor<Car.Callback>().apply {
      verify(mockCar).setCallback(capture(), any())
      carCallbacks.add(firstValue)
    }
    argumentCaptor<ByteArray>().apply {
      verify(mockCar).sendMessage(capture(), eq(FEATURE_ID))
      with(TrustedDeviceMessage.parseFrom(firstValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
      }
    }

    val message =
      TrustedDeviceMessage.newBuilder()
        .setVersion(VERSION)
        // Message type is not HANDLE.
        .setType(TrustedDeviceMessage.MessageType.ACK)
        .setPayload(ByteString.copyFrom(HANDLE))
        .build()
    carCallbacks.forEach { it.onMessageReceived(message.toByteArray()) }
    verify(mockCallback, never()).onEnrollmentSuccess(any(), any())
  }

  @Test
  fun testEnrollmentSuccess_initiatedFromCar() {
    shadowKeyguardManager.setIsDeviceSecure(true)

    completeEnrollmentFromCar()
    verify(mockCallback).onEnrollmentSuccess(DEVICE_ID, true)
  }

  @Test
  fun testEnrollmentSuccess_initiatedFromPhone() {
    shadowKeyguardManager.setIsDeviceSecure(true)

    completeEnrollmentFromPhone(mockCar)
    verify(mockCallback).onEnrollmentSuccess(DEVICE_ID, false)
  }

  @Test
  fun testReenroll_receiveStartEnrollmentMessage_startEnrollment() {
    shadowKeyguardManager.setIsDeviceSecure(true)

    trustedDeviceManager.notifyCarConnected(mockCar)
    trustedDeviceManager.enroll(DEVICE_ID)
    argumentCaptor<Car.Callback>().apply {
      verify(mockCar).setCallback(capture(), any())
      carCallbacks.add(firstValue)
    }

    carCallbacks.forEach { it.onMessageReceived(HANDLE_MESSAGE.toByteArray()) }
    verify(mockCallback).onEnrollmentSuccess(DEVICE_ID, false)

    // Init new enrollment for an enrolled car.
    carCallbacks.forEach { it.onMessageReceived(START_ENROLLMENT_MESSAGE.toByteArray()) }

    argumentCaptor<ByteArray>().apply {
      verify(mockCar, times(3)).sendMessage(capture(), eq(FEATURE_ID))

      // First send messages for token and ACK.
      with(TrustedDeviceMessage.parseFrom(firstValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
      }
      with(TrustedDeviceMessage.parseFrom(secondValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ACK)
      }
      // START_ENROMMENT_MESSAGE triggers sending token again.
      with(TrustedDeviceMessage.parseFrom(thirdValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
      }
    }
  }

  @Test
  fun testOnCarConnected_foundToken_unlock() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.isPasscodeRequired = true
    shadowKeyguardManager.setIsDeviceSecure(true)

    // Use base class method, which triggers OnCarConnected.
    trustedDeviceManager.notifyCarConnected(mockCar)

    argumentCaptor<ByteArray>().apply {
      verify(mockCar, atLeastOnce()).sendMessage(capture(), eq(FEATURE_ID))
      with(TrustedDeviceMessage.parseFrom(lastValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.UNLOCK_CREDENTIALS)
      }
    }
  }

  @Test
  fun testOnCarConnected_passcodeDisabled_foundToken_unlock() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.isPasscodeRequired = false
    shadowKeyguardManager.setIsDeviceSecure(false)

    // Use base class method, which triggers OnCarConnected.
    trustedDeviceManager.notifyCarConnected(mockCar)

    argumentCaptor<ByteArray>().apply {
      verify(mockCar, atLeastOnce()).sendMessage(capture(), eq(FEATURE_ID))
      with(TrustedDeviceMessage.parseFrom(lastValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.UNLOCK_CREDENTIALS)
      }
    }
  }

  @Test
  fun testOnCarConnected_noFoundToken_noOp() {
    // Use base class method, which triggers OnCarConnected.
    trustedDeviceManager.notifyCarConnected(mockCar)

    verify(mockCar, never()).sendMessage(any(), any())
  }

  @Test
  fun testUnlock_receiveMessage_unlockDone() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.isPasscodeRequired = true
    shadowKeyguardManager.setIsDeviceSecure(true)

    unlockMockCarSuccesfully(mockCar)

    verify(mockCallback).onUnlockingSuccess(DEVICE_ID)
  }

  @Test
  fun testUnlock_noPasscodeRequired_receiveMessage_unlockDone() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.isPasscodeRequired = false
    shadowKeyguardManager.setIsDeviceSecure(false)

    unlockMockCarSuccesfully(mockCar)

    verify(mockCallback).onUnlockingSuccess(DEVICE_ID)
  }

  @Test
  fun testUnlock_storesUnlockHistory() =
    runTest(UnconfinedTestDispatcher()) {
      completeEnrollmentFromPhone(mockCar)
      unlockMockCarSuccesfully(mockCar)

      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID)).hasSize(1)
    }

  @Test
  fun testUnlock_doesNotStore_ifDisabled() =
    runTest(UnconfinedTestDispatcher()) {
      completeEnrollmentFromPhone(mockCar)

      trustedDeviceManager.isUnlockHistoryEnabled = false
      unlockMockCarSuccesfully(mockCar)

      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID)).isEmpty()
    }

  @Test
  fun testUnlock_returnsEmptyList_afterDisabling() =
    runTest(UnconfinedTestDispatcher()) {
      completeEnrollmentFromPhone(mockCar)
      completeEnrollmentFromPhone(mockCar2)

      unlockMockCarSuccesfully(mockCar)
      unlockMockCarSuccesfully(mockCar2)
      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID)).hasSize(1)
      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID_2)).hasSize(1)

      trustedDeviceManager.isUnlockHistoryEnabled = false
      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID)).isEmpty()
      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID_2)).isEmpty()

      // Even after turning back on, history should be cleared.
      trustedDeviceManager.isUnlockHistoryEnabled = true
      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID)).isEmpty()
      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID_2)).isEmpty()
    }

  @Test
  fun testClearUnlockHistory() =
    runTest(UnconfinedTestDispatcher()) {
      completeEnrollmentFromPhone(mockCar)
      unlockMockCarSuccesfully(mockCar)

      trustedDeviceManager.clearUnlockHistory(DEVICE_ID)

      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID)).isEmpty()
    }

  @Test
  fun testClearUnlockHistory_clearsForRightCar() =
    runTest(UnconfinedTestDispatcher()) {
      completeEnrollmentFromPhone(mockCar)
      unlockMockCarSuccesfully(mockCar)

      // Clear for a random device id.
      trustedDeviceManager.clearUnlockHistory(DEVICE_ID_2)

      // Should not affect the unlock history for the unlocked car
      assertThat(trustedDeviceManager.getUnlockHistory(DEVICE_ID)).hasSize(1)
    }

  @Test
  fun testDeviceSecured_enrollInitiated() {
    shadowKeyguardManager.setIsDeviceSecure(true)

    trustedDeviceManager.notifyCarConnected(mockCar)
    trustedDeviceManager.enroll(DEVICE_ID)

    argumentCaptor<ByteArray>().apply {
      verify(mockCar).sendMessage(capture(), eq(FEATURE_ID))

      with(TrustedDeviceMessage.parseFrom(firstValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
      }
    }
  }

  @Test
  fun testEnrollSucceeds_whenPasscodeDisabled() {
    trustedDeviceManager.isPasscodeRequired = false
    shadowKeyguardManager.setIsDeviceSecure(false)

    trustedDeviceManager.notifyCarConnected(mockCar)
    trustedDeviceManager.enroll(DEVICE_ID)

    argumentCaptor<ByteArray>().apply {
      verify(mockCar).sendMessage(capture(), eq(FEATURE_ID))

      with(TrustedDeviceMessage.parseFrom(firstValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ESCROW_TOKEN)
      }
    }
  }

  @Test
  fun testDeviceNotSecured_enrollFails() {
    shadowKeyguardManager.setIsDeviceSecure(false)

    trustedDeviceManager.notifyCarConnected(mockCar)
    trustedDeviceManager.enroll(DEVICE_ID)

    verify(mockCallback).onEnrollmentFailure(DEVICE_ID, EnrollmentError.PASSCODE_NOT_SET)
    argumentCaptor<ByteArray>().apply {
      verify(mockCar).sendMessage(capture(), eq(FEATURE_ID))

      with(TrustedDeviceMessage.parseFrom(firstValue)) {
        assertThat(type).isEqualTo(TrustedDeviceMessage.MessageType.ERROR)
        with(TrustedDeviceError.parseFrom(payload)) {
          assertThat(type).isEqualTo(TrustedDeviceError.ErrorType.DEVICE_NOT_SECURED)
        }
      }
    }
  }

  @Test
  fun testDeviceNotSecured_unlockNotStarted() {
    completeEnrollmentFromPhone(mockCar)

    shadowKeyguardManager.setIsDeviceSecure(false)
    trustedDeviceManager.notifyCarConnected(mockCar)

    verify(mockCallback, never()).onUnlockingStarted(DEVICE_ID)
    verify(mockCallback).onUnlockingFailure(DEVICE_ID)
  }

  @Test
  fun testDeviceUnlockRequired_deviceUnlocked_unlockInitiated() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.setDeviceUnlockRequired(DEVICE_ID, true)
    shadowKeyguardManager.setIsDeviceLocked(false)
    shadowKeyguardManager.setIsDeviceSecure(true)

    trustedDeviceManager.notifyCarConnected(mockCar)

    verify(mockCallback).onUnlockingStarted(DEVICE_ID)
  }

  @Test
  fun testDeviceUnlockRequired_deviceUnlocked_passcodeDisabled_unlockInitiated() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.setDeviceUnlockRequired(DEVICE_ID, true)
    shadowKeyguardManager.setIsDeviceLocked(false)

    trustedDeviceManager.isPasscodeRequired = false
    shadowKeyguardManager.setIsDeviceSecure(true)

    trustedDeviceManager.notifyCarConnected(mockCar)

    verify(mockCallback).onUnlockingStarted(DEVICE_ID)
  }

  @Test
  fun testDeviceUnlockRequired_deviceLocked_unlockFails() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.setDeviceUnlockRequired(DEVICE_ID, true)
    shadowKeyguardManager.setIsDeviceSecure(true)
    shadowKeyguardManager.setIsDeviceLocked(true)

    trustedDeviceManager.notifyCarConnected(mockCar)

    verify(mockCallback).onUnlockingFailure(DEVICE_ID)
  }

  @Test
  fun testDeviceUnlockRequired_deviceUnlocked_unlockContinues() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.setDeviceUnlockRequired(DEVICE_ID, true)
    shadowKeyguardManager.setIsDeviceSecure(true)
    shadowKeyguardManager.setIsDeviceLocked(true)

    trustedDeviceManager.notifyCarConnected(mockCar)

    verify(mockCallback).onUnlockingFailure(DEVICE_ID)

    shadowKeyguardManager.setIsDeviceLocked(false)
    trustedDeviceManager.handlePhoneUnlocked()

    verify(mockCallback).onUnlockingStarted(DEVICE_ID)
  }

  @Test
  fun testDeviceUnlockNotRequired_deviceLocked_unlockSucceeds() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.setDeviceUnlockRequired(DEVICE_ID, false)
    shadowKeyguardManager.setIsDeviceSecure(true)

    trustedDeviceManager.notifyCarConnected(mockCar)

    verify(mockCallback).onUnlockingStarted(DEVICE_ID)
  }

  @Test
  fun testDeviceUnlockNotRequired_deviceLocked_passcodeDisabled_unlockSucceeds() {
    completeEnrollmentFromPhone(mockCar)

    trustedDeviceManager.setDeviceUnlockRequired(DEVICE_ID, false)

    trustedDeviceManager.isPasscodeRequired = false
    shadowKeyguardManager.setIsDeviceSecure(false)

    trustedDeviceManager.notifyCarConnected(mockCar)

    verify(mockCallback).onUnlockingStarted(DEVICE_ID)
  }

  @Test
  fun testCarDisassociated_clearsCredentials() =
    runTest(UnconfinedTestDispatcher()) {
      completeEnrollmentFromPhone(mockCar)

      trustedDeviceManager.onCarDisassociated(DEVICE_ID)

      assertThat(storage.containsCredential(DEVICE_ID)).isFalse()
      assertThat(trustedDeviceManager.isEnabled(DEVICE_ID)).isFalse()
    }

  @Test
  fun testCarDisassociated_clearsUnlockHistory() =
    runTest(UnconfinedTestDispatcher()) {
      completeEnrollmentFromPhone(mockCar)
      unlockMockCarSuccesfully(mockCar)

      trustedDeviceManager.onCarDisassociated(DEVICE_ID)

      assertThat(storage.getUnlockHistory(DEVICE_ID)).isEmpty()
    }

  @Test
  fun testAllCarsDisassociated_clearsAllCredentials() =
    runTest(UnconfinedTestDispatcher()) {
      completeEnrollmentFromPhone(mockCar)

      trustedDeviceManager.onAllCarsDisassociated()

      assertThat(storage.containsCredential(DEVICE_ID)).isFalse()
      assertThat(trustedDeviceManager.isEnabled(DEVICE_ID)).isFalse()
    }

  @Test
  fun testStopEnrollment_notEnrolled_doesNotNotifyCallback() =
    runTest(UnconfinedTestDispatcher()) {
      shadowKeyguardManager.setIsDeviceSecure(true)

      trustedDeviceManager.stopEnrollment(DEVICE_ID)

      verify(mockCallback, never()).onUnenroll(eq(DEVICE_ID), any())
    }

  @Test
  fun testStopEnrollment_notifiesCallback() =
    runTest(UnconfinedTestDispatcher()) {
      shadowKeyguardManager.setIsDeviceSecure(true)
      completeEnrollmentFromPhone(mockCar)

      trustedDeviceManager.stopEnrollment(DEVICE_ID)

      verify(mockCallback).onUnenroll(DEVICE_ID, initiatedFromCar = false)
    }

  @Test
  fun testFeatureSync_disabledClearsLocalEnrollment() =
    runTest(UnconfinedTestDispatcher()) {
      shadowKeyguardManager.setIsDeviceSecure(true)
      completeEnrollmentFromPhone(mockCar)

      carCallbacks.forEach { it.onMessageReceived(createStateSyncMessage(enabled = false)) }

      assertThat(trustedDeviceManager.isEnabled(DEVICE_ID)).isFalse()
      verify(mockCallback).onUnenroll(DEVICE_ID, initiatedFromCar = true)
    }

  @Test
  fun testFeatureSync_ignoresEnabledMessage() =
    runTest(UnconfinedTestDispatcher()) {
      shadowKeyguardManager.setIsDeviceSecure(true)
      completeEnrollmentFromPhone(mockCar)

      carCallbacks.forEach { it.onMessageReceived(createStateSyncMessage(enabled = true)) }

      assertThat(trustedDeviceManager.isEnabled(DEVICE_ID)).isTrue()
      verify(mockCallback, never()).onUnenroll(eq(DEVICE_ID), any())
    }

  @Test
  fun testFeatureSync_sendsSyncAfterEnrollmentCleared() =
    runTest(UnconfinedTestDispatcher()) {
      shadowKeyguardManager.setIsDeviceSecure(true)
      completeEnrollmentFromPhone(mockCar)

      argumentCaptor<ByteArray>().apply {
        verify(mockCar, atLeastOnce()).sendMessage(capture(), eq(FEATURE_ID))
        assertThat(lastValue).isNotEqualTo(createStateSyncMessage(enabled = false))
      }

      trustedDeviceManager.stopEnrollment(DEVICE_ID)

      argumentCaptor<ByteArray>().apply {
        verify(mockCar, atLeastOnce()).sendMessage(capture(), eq(FEATURE_ID))
        assertThat(lastValue).isEqualTo(createStateSyncMessage(enabled = false))
      }
    }

  @Test
  fun testFeatureSync_onlySendsSyncOnceOnSubsequentConnections() =
    runTest(UnconfinedTestDispatcher()) {
      shadowKeyguardManager.setIsDeviceSecure(true)
      completeEnrollmentFromPhone(mockCar)

      carCallbacks.forEach { it.onDisconnected() }

      trustedDeviceManager.stopEnrollment(DEVICE_ID)
      trustedDeviceManager.notifyCarConnected(mockCar)

      // The sync message should be sent on the first reconnection.
      argumentCaptor<ByteArray>().apply {
        verify(mockCar, atLeastOnce()).sendMessage(capture(), eq(FEATURE_ID))
        assertThat(lastValue).isEqualTo(createStateSyncMessage(enabled = false))
      }

      // Verify that second disconnection should not resend the sync message.
      carCallbacks.forEach { it.onDisconnected() }
      trustedDeviceManager.notifyCarConnected(mockCar)

      argumentCaptor<ByteArray>().apply {
        verify(mockCar, atLeastOnce()).sendMessage(capture(), eq(FEATURE_ID))

        val syncMessages =
          allValues.filter { it contentEquals createStateSyncMessage(enabled = false) }

        // The sync message should still only have been sent once.
        assertThat(syncMessages).hasSize(1)
      }
    }

  @Test
  fun testFeatureSync_doesNotSyncAfterDisassociation() =
    runTest(UnconfinedTestDispatcher()) {
      shadowKeyguardManager.setIsDeviceSecure(true)
      completeEnrollmentFromPhone(mockCar)

      trustedDeviceManager.onCarDisassociated(DEVICE_ID)

      // Re-associate the device.
      completeEnrollmentFromPhone(mockCar)

      argumentCaptor<ByteArray>().apply {
        verify(mockCar, atLeastOnce()).sendMessage(capture(), eq(FEATURE_ID))
        assertThat(lastValue).isNotEqualTo(createStateSyncMessage(enabled = false))
      }
    }

  private fun triggerEnrollmentFromCar() {
    trustedDeviceManager.notifyCarConnected(mockCar)

    argumentCaptor<Car.Callback>().apply {
      verify(mockCar).setCallback(capture(), any())
      carCallbacks.add(firstValue)
    }

    carCallbacks.forEach { it.onMessageReceived(START_ENROLLMENT_MESSAGE.toByteArray()) }
  }

  private fun completeEnrollmentFromCar() {
    triggerEnrollmentFromCar()

    // Send back the handle the complete enrollment.
    carCallbacks.forEach { it.onMessageReceived(HANDLE_MESSAGE.toByteArray()) }
  }

  /**
   * Begins the enrollment process with the given [car] and simulates the process as being started
   * by the current phone.
   */
  private fun triggerEnrollmentFromPhone(car: Car) {
    trustedDeviceManager.notifyCarConnected(car)

    argumentCaptor<Car.Callback>().apply {
      verify(car, atLeastOnce()).setCallback(capture(), any())
      carCallbacks.add(firstValue)
    }

    trustedDeviceManager.enroll(car.deviceId)
  }

  /**
   * Completes enrollment with the given [car] and simulates the process as being started by the
   * current phone.
   */
  private fun completeEnrollmentFromPhone(car: Car) {
    triggerEnrollmentFromPhone(car)

    // Send back the handle to complete enrollment.
    carCallbacks.forEach { it.onMessageReceived(HANDLE_MESSAGE.toByteArray()) }
  }

  /**
   * Simulates the situation where a car connects, an unlock message is sent and the car responds
   * that the unlock message was received.
   */
  private fun unlockMockCarSuccesfully(car: Car) {
    trustedDeviceManager.notifyCarConnected(car)
    argumentCaptor<Car.Callback>().apply {
      verify(car, atLeastOnce()).setCallback(capture(), any())
      carCallbacks.add(firstValue)
    }

    val message =
      TrustedDeviceMessage.newBuilder()
        .setVersion(VERSION)
        .setType(TrustedDeviceMessage.MessageType.ACK)
        .build()
    // Need to send the ACK message to finalize the unlock process.
    carCallbacks.forEach { it.onMessageReceived(message.toByteArray()) }
  }

  private fun createStateSyncMessage(enabled: Boolean): ByteArray {
    val state = TrustedDeviceState.newBuilder().setEnabled(enabled).build().toByteString()

    return TrustedDeviceMessage.newBuilder()
      .setVersion(VERSION)
      .setType(TrustedDeviceMessage.MessageType.STATE_SYNC)
      .setPayload(state)
      .build()
      .toByteArray()
  }
}
