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

package com.google.android.libraries.car.trustagent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Queries for the associated car table.
 *
 * For more information on the schema of this table, see go/batmobile-android-association-table.
 */
@Dao
internal interface AssociatedCarDao {
  /** Returns all cars currently associated with the current phone. */
  @Query("SELECT * FROM associated_cars")
  suspend fun loadAllAssociatedCars(): List<AssociatedCarEntity>

  /** Returns `true` if there are cars currently associated with this phone. */
  @Query("SELECT EXISTS(SELECT * FROM associated_cars)") suspend fun loadIsAssociated(): Boolean

  /** Returns `true` if there is a car with the specified [carId] is associated with this phone. */
  @Query("SELECT EXISTS(SELECT * FROM associated_cars WHERE id = :carId)")
  suspend fun loadIsAssociatedByCarId(carId: String): Boolean

  /**
   * Returns `true` if there is a car with the specified [macAddress] is associated with this phone.
   */
  @Query("SELECT EXISTS(SELECT * FROM associated_cars WHERE macAddress = :macAddress)")
  suspend fun loadIsAssociatedByMacAddress(macAddress: String): Boolean

  /** Loads the key used for encrypting and decrypting data from the car with the given [carId]. */
  @Query("SELECT id, encryptionKey FROM associated_cars WHERE id = :carId LIMIT 1")
  suspend fun loadEncryptionKey(carId: String): AssociatedCarKey?

  /** Loads the mac address of the given [carId]. */
  @Query("SELECT macAddress FROM associated_cars WHERE id = :carId LIMIT 1")
  suspend fun loadMacAddressByCarId(carId: String): String

  /** Loads the name of the given [carId]. */
  @Query("SELECT name FROM associated_cars WHERE id = :carId LIMIT 1")
  suspend fun loadNameByCarId(carId: String): String

  /**
   * Inserts a [car] into the database.
   *
   * This operation replaces if a car already exists with the same car id.
   */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAssociatedCar(car: AssociatedCarEntity)

  /**
   * Updates the name of the car that matches the given [car] and returns the number of entries that
   * were updated.
   */
  @Update(entity = AssociatedCarEntity::class) suspend fun updateName(car: AssociatedCarName): Int

  /**
   * Updates the encryption key that matches the given [car] and returns the number of entires that
   * were updated.
   */
  @Update(entity = AssociatedCarEntity::class)
  suspend fun updateEncryptionKey(car: AssociatedCarKey): Int

  @Query("SELECT id, identificationKey FROM associated_cars")
  suspend fun loadAllIdentificationKeys(): List<AssociatedCarIdentificationKey>

  @Query("SELECT identificationKey FROM associated_cars WHERE id = :carId")
  suspend fun loadIdentificationKey(carId: String): String

  /**
   * Deletes an associated car that has the given [carId] and returns the number of entries that
   * were deleted.
   */
  @Query("DELETE FROM associated_cars WHERE id = :carId")
  suspend fun deleteAssociatedCar(carId: String): Int

  /** Deletes all currently associated cars and returns the number of entries that were updated. */
  @Query("DELETE FROM associated_cars") suspend fun deleteAllAssociatedCars(): Int
}
