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

import com.google.android.libraries.car.trustagent.api.PublicApi
import java.time.Instant
import java.util.UUID

/**
 * Trusted device is a device that unlocks the user profile on another Android device.
 *
 * This feature interface provides methods to `enroll` this device as a trusted device, and
 * act as trusted device to `unlock` the user profile.
 */
@PublicApi
interface TrustedDeviceFeature {

  /**
   * `true` if a passcode is required on the current device before this manager will attempt an
   * unlock of a remote car.
   *
   * Setting this value should persist unless app data is cleared.
   *
   * This value should be `true` by default.
   */
  var isPasscodeRequired: Boolean

  /**
   * `true` if this feature should store unlock history.
   *
   * If this value is enabled, then the date will be stored internally for successful unlock
   * attempts. These values can get retrieved by calling [getUnlockHistory]. No unlock history
   * will be stored if the value is disabled.
   *
   * Setting this value should persist unless app data is cleared.
   *
   * This value is `true` by default.
   */
  var isUnlockHistoryEnabled: Boolean

  /**
   * Returns `true` if trusted device feature is enabled by the car with ID [carId].
   */
  suspend fun isEnabled(carId: UUID): Boolean

  /**
   * Initiate enrolling this device as a trusted device of [carId].
   *
   * Enrollment will exchange credential with [carId], and may require user interaction on the car.
   * Result will be notified by [Callback].
   */
  fun enroll(carId: UUID)

  /**
   * Returns `true` if this device is required to be unlocked before unlocking [carId].
   */
  fun isDeviceUnlockRequired(carId: UUID): Boolean

  /**
   * Sets whether this device is required to be unlocked before unlocking [carId].
   */
  fun setDeviceUnlockRequired(carId: UUID, isRequired: Boolean)

  /**
   * Stops current enrollment with [carId].
   *
   * Also clears the stored token and handle of the [carId] if there is one.
   */
  suspend fun stopEnrollment(carId: UUID)

  /**
   * Unlocks [carId] by sending phone authentication.
   *
   * If [carId] has not be enrolled, this method takes no op and returns silently.
   * If unlock [carId] has already been requested, no-op for subsequent calls for the same [carId].
   *
   * Result will be notified by [Callback]. If successful, the event will be persisted. If
   * [isUnlockHistoryEnabled] is `true`, then the dates of unlocks can be retrieved by
   * [getUnlockHistory].
   */
  fun unlock(carId: UUID)

  /**
   * Returns the unlock history for [carId]; sorted from oldest to newest.
   *
   * Each entry in the history is the number of milliseconds from the epoch.
   * Returns an empty list if there is no unlock history for the car.
   */
  suspend fun getUnlockHistory(carId: UUID): List<Instant>

  /** Removes all stored unlock history for the car with the given [carId]. */
  fun clearUnlockHistory(carId: UUID)

  /** Registers to receive [Callback] of trusted device events. */
  fun registerCallback(callback: Callback)

  /** Unregisters to stop receiving [Callback] of trusted device events. */
  fun unregisterCallback(callback: Callback)

  /**
   * Callbacks that will be invoked for enrollment and unlock progress and result.
   */
  @PublicApi
  interface Callback {
    /**
     * Invoked when [carId] requests to start enrollment.
     *
     * Upon this callback, enrollment has already been automatically started,
     * namely [enroll] invoked for [carId].
     */
    fun onEnrollmentRequested(carId: UUID)

    /**
     * Invoked when this device has been successfully as Trusted Device for [carId].
     *
     * Enrollment can be started by either this device (invoking [enroll]), or requested by the
     * remote device, in which case [initiatedFromCar] will be `true`.
     */
    fun onEnrollmentSuccess(carId: UUID, initiatedFromCar: Boolean)

    /**
     * Invoked when attempt to enroll as Trusted Device for [carId] failed.
     */
    fun onEnrollmentFailure(carId: UUID, error: EnrollmentError)

    /**
     * Invoked when the car with the given [carId] has unenrolled from the trusted device feature.
     *
     * The user will need to re-enroll in order for the feature to work again. This method will
     * only fire for a `carId` that had been previously enrolled in the feature.
     *
     * This unenrollment can be triggered by the remote car, in which case [initiatedFomrCar] will
     * be `true`.
     */
    fun onUnenroll(carId: UUID, initiatedFromCar: Boolean)

    /**
     * Invoked when this device started to unlock [carId].
     */
    fun onUnlockingStarted(carId: UUID)

    /**
     * Invoked when attempt to unlock [carId] failed.
     */
    fun onUnlockingFailure(carId: UUID)

    /**
     * Invoked when this device successfully unlocked [carId].
     */
    fun onUnlockingSuccess(carId: UUID)
  }

  /** Code for error encountered during trusted device enrollment. */
  @PublicApi
  enum class EnrollmentError {
    /** There was an unrecoverable internal error. */
    INTERNAL,

    /** There is no passcode set on the current device. */
    PASSCODE_NOT_SET,

    /** The car to enroll is not currently connected and enrollment cannot occur. */
    CAR_NOT_CONNECTED
  }
}
