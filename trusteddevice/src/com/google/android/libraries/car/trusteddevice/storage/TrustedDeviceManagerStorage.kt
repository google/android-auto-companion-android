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

package com.google.android.libraries.car.trusteddevice.storage

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.companionprotos.trusteddevice.PhoneAuthProto.PhoneCredentials
import com.google.protobuf.ByteString
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Manage the storage for [TrustedDeviceManager]. */
class TrustedDeviceManagerStorage(
  context: Context,
  private val clock: Clock,
  trustedDeviceDatabase: TrustedDeviceDatabase = DatabaseProvider.getInstance(context).database,
) {
  private val database = trustedDeviceDatabase.trustedDeviceDao()

  /**
   * Records current time to the unlock history for a car.
   *
   * Time is determined by [clock] passed at construction.
   */
  open suspend fun recordUnlockDate(carId: UUID) {
    database.storeUnlockHistory(UnlockHistory(carId = carId, instant = clock.instant()))
  }

  /**
   * Returns the unlock history for [carId]; an empty list if there is no unlock history.
   *
   * Unlock dates older than the [MAX_AGE] will be cleared from storage.
   */
  suspend fun getUnlockHistory(carId: UUID): List<Instant> {
    val cutoff = clock.instant().minus(MAX_AGE)
    database.clearUnlockHistoryBefore(carId, cutoff)

    return database.getUnlockHistory(carId).map { it.instant }
  }

  /** Clears all stored unlock history for the car with the given [carId]. */
  suspend fun clearUnlockHistory(carId: UUID) {
    database.clearUnlockHistory(carId)
  }

  /** Clears stored unlock history for all cars. */
  suspend fun clearAllUnlockHistory() {
    database.clearAllUnlockHistory()
  }

  /**
   * Returns whether there is a stored credential for [carId].
   *
   * Only returns `true` for cars that have completed enrollment, namely they must have received a
   * handle for the escrow tokens sent out.
   */
  suspend fun containsCredential(carId: UUID): Boolean =
    database.getCredential(carId)?.handle != null

  /**
   * Returns the credential for [carId]; `null` if there isn't one.
   *
   * Only returns a result when both token and handle exist in storage. Namely, returns `null` for
   * cars currently going through enrollment with only token stored.
   */
  suspend fun getCredential(carId: UUID): PhoneCredentials? {
    val credential = database.getCredential(carId)
    if (credential == null || credential.handle == null) {
      return null
    }

    return PhoneCredentials.newBuilder()
      .setEscrowToken(ByteString.copyFrom(credential.token))
      .setHandle(ByteString.copyFrom(credential.handle))
      .build()
  }

  @VisibleForTesting suspend fun getToken(carId: UUID): ByteArray? = database.getToken(carId)

  suspend fun storeToken(token: ByteArray, carId: UUID) {
    database.storeToken(TokenAndId(token, carId))
  }

  /**
   * Stores enrollment [handle] that can be used to uniquely identify an enrollment session.
   *
   * Returns `true` if [carId] with a corresponding escrow token was updated with its [handle].
   */
  suspend fun storeHandle(handle: ByteArray, carId: UUID): Boolean =
    database.storeHandle(HandleAndId(handle, carId)) == 1

  /** Stores the given [state] that should be synced to the car with the given [carId]. */
  suspend fun storeFeatureState(state: ByteArray, carId: UUID) =
    database.storeFeatureState(StateAndId(state, carId))

  /**
   * Returns any feature state sync messages that should be sent to the car with the given [carId].
   */
  suspend fun getFeatureState(carId: UUID): ByteArray? = database.getFeatureState(carId)?.state

  /** Removes any stored tokens, handles and unlock history for the car with the given [carId]. */
  suspend fun clearCredentials(carId: UUID) {
    database.clearCredential(carId)
    database.clearUnlockHistory(carId)
  }

  /** Removes any stored feature state sync messages for a car with the given [carId]. */
  suspend fun clearFeatureState(carId: UUID) {
    database.clearFeatureState(carId)
  }

  /** Clears all stored data. */
  suspend fun clearAll() {
    database.clearAllCredentials()
    database.clearAllUnlockHistory()
    database.clearAllFeatureState()
  }

  companion object {
    /** How far back to keep unlock records. */
    private val MAX_AGE = Duration.ofDays(7)
  }
}
