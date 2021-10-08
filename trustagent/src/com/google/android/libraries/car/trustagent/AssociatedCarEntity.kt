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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Table entity representing an associated car.
 *
 * @property id The unique identifier of the car.
 * @property encryptionKey The key for encrypting and decrypting data to and from the car.
 * @property identificationKey The key for identifying an advertisement from an associated device.
 * @property name A human-readable name of the car.
 * @property macAddress MAC address of the car.
 * @property isUserRenamed `true` if the user manually renamed this car.
 */
@Entity(tableName = "associated_cars")
internal data class AssociatedCarEntity(
  @PrimaryKey val id: String,
  val encryptionKey: String,
  val identificationKey: String,
  val name: String?,
  val macAddress: String,
  val isUserRenamed: Boolean
)

/** Entity for a car's name in the database. */
internal data class AssociatedCarName(val id: String, val name: String, val isUserRenamed: Boolean)

/** Entity for a car's encryption key in the database. */
internal data class AssociatedCarKey(val id: String, val encryptionKey: String)

/** Entity for a car's identification key in the database. */
internal data class AssociatedCarIdentificationKey(val id: String, val identificationKey: String)
