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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.time.Instant
import java.util.UUID

/**
 * Queries for the trusted device data.
 *
 * For more information on the schema of this table, see go/batmobile-android-sdk-tables.
 */
@Dao
interface TrustedDeviceDao {

  /** Inserts the carId and its escrow token into the database. */
  @Insert(entity = Credential::class, onConflict = OnConflictStrategy.REPLACE)
  suspend fun storeToken(tokenAndId: TokenAndId)

  /** Updates the [carId] with its handle. Returns the number of record updated. */
  @Update(entity = Credential::class) suspend fun storeHandle(handleAndId: HandleAndId): Int

  /** Returns the credential for [carId]; `null` if [carId] does not exist in the database. */
  @Query("SELECT * FROM credentials WHERE carId = :carId")
  suspend fun getCredential(carId: UUID): Credential?

  /** Returns the escrow token for [carId]; `null` if [carId] does not exist in the database. */
  @Query("SELECT token FROM credentials WHERE carId = :carId")
  suspend fun getToken(carId: UUID): ByteArray?

  /** Deletes stored data for the given [carId]. */
  @Query("DELETE FROM credentials WHERE carId = :carId") suspend fun clearCredential(carId: UUID)

  /** Deletes unlock history for the given [carId]. */
  @Query("DELETE FROM unlock_history WHERE carId = :carId")
  suspend fun clearUnlockHistory(carId: UUID)

  /** Deletes all stored credentials. */
  @Query("DELETE FROM credentials") suspend fun clearAllCredentials()

  /** Deletes all stored unlock history. */
  @Query("DELETE FROM unlock_history") suspend fun clearAllUnlockHistory()

  /** Inserts a [UnlockHistory] into the database. */
  @Insert suspend fun storeUnlockHistory(unlockHistory: UnlockHistory)

  /** Deletes unlock history for the given [carId] that are older than [cutoff]. */
  @Query("DELETE FROM unlock_history WHERE carId = :carId AND instant < :cutoff")
  suspend fun clearUnlockHistoryBefore(carId: UUID, cutoff: Instant)

  /** Returns the unlock history for the given [carId]. */
  @Query("SELECT * FROM unlock_history WHERE carId = :carId")
  suspend fun getUnlockHistory(carId: UUID): List<UnlockHistory>

  /** Inserts the given feature state sync message to send to the `carId` into the database. */
  @Insert(entity = FeatureState::class, onConflict = OnConflictStrategy.REPLACE)
  suspend fun storeFeatureState(stateAndId: StateAndId)

  /**
   * Returns any feature state sync messages that should be sent to the car with the given [carId].
   */
  @Query("SELECT * FROM feature_state WHERE carId = :carId")
  suspend fun getFeatureState(carId: UUID): FeatureState?

  /** Deletes any stored feature state sync messages for a car with the given [carId]. */
  @Query("DELETE FROM feature_state WHERE carId = :carId")
  suspend fun clearFeatureState(carId: UUID)

  /** Deletes all stored feature state sync messages. */
  @Query("DELETE FROM feature_state") suspend fun clearAllFeatureState()
}

data class TokenAndId(val token: ByteArray, val carId: UUID)

data class HandleAndId(val handle: ByteArray, val carId: UUID)

data class StateAndId(val state: ByteArray, val carId: UUID)
