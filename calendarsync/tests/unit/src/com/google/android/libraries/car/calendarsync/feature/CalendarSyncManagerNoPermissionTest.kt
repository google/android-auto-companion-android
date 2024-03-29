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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.connecteddevice.calendarsync.UpdateCalendars
import com.google.android.libraries.car.calendarsync.feature.repository.CalendarRepository
import com.google.android.libraries.car.trustagent.Car
import java.time.Clock
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private val CAR_ID = UUID.fromString("eefeb989-e593-4a50-a2c7-623dbfc408f4")

@RunWith(AndroidJUnit4::class)
class CalendarSyncManagerNoPermissionTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val mockCar: Car = mock { on { deviceId } doReturn CAR_ID }

  private lateinit var calendarSyncManager: CalendarSyncManagerV2
  private lateinit var mockCalendarRepository: CalendarRepository

  @Before
  fun setUp() {
    mockCalendarRepository = mock()

    whenever(mockCalendarRepository.getCalendars(any(), any())) doReturn
      UpdateCalendars.getDefaultInstance()

    calendarSyncManager =
      CalendarSyncManagerV2(context, daysToSync = 3)
  }

  @Test
  fun permissionNotGranted_doesNotSync() {
    calendarSyncManager.enableCar(CAR_ID)
    calendarSyncManager.setCalendarIdsToSync(setOf("1234", "5678"), CAR_ID)
    calendarSyncManager.notifyCarConnected(mockCar)

    Mockito.verify(mockCar, Mockito.never()).sendMessage(anyOrNull(), anyOrNull())
  }
}
