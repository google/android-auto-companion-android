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

package com.google.android.libraries.car.calendarsync.feature

import com.google.android.libraries.car.trustagent.api.PublicApi
import java.util.UUID

/**
 * A companion feature that syncs calendar data to connected cars.
 */
@PublicApi
interface CalendarSyncFeature : CompanionFeature {
  /** Sets the specified car to be enabled for calendar sync. */
  fun enableCar(carId: UUID)

  /** Sets the specified car to be disabled for calendar sync. */
  fun disableCar(carId: UUID)

  /** Returns true if the specified car is enabled for calendar sync. */
  fun isCarEnabled(carId: UUID): Boolean

  /** Sets the ids of the calendars to sync for the specified car. */
  fun setCalendarIdsToSync(calendarIds: Set<String>, carId: UUID)

  /** Returns the ids of the calendars to be synced for the specified car. */
  fun getCalendarIdsToSync(carId: UUID): Set<String>
}
