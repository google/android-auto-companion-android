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

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Table entity representing any pending state messages that should be sent to the car on next
 * connection.
 *
 * @property carId The unique identifier of the car.
 * @property state A byte array of a `TrustedDeviceState` message.
 */
@Entity(tableName = "feature_state")
data class FeatureState(@PrimaryKey val carId: UUID, val state: ByteArray)
