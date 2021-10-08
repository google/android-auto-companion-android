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
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.connecteddevice.calendarsync.android.CalendarSyncAccess
import com.google.android.connecteddevice.calendarsync.common.SourceCalendarSync
import com.google.android.libraries.car.trustagent.Car
import com.google.android.libraries.car.trustagent.Car.Callback
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doAnswer
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import java.util.UUID
import java.util.function.Consumer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer

@Suppress("FunctionName")
@RunWith(AndroidJUnit4::class)
class CalendarSyncManagerV2Test {
  private val carId = UUID.randomUUID()
  private val calendarIds = setOf("1", "2", "3")
  private val message = "A message from a car".toByteArray()
  private val daysToSync = 2

  private lateinit var manager: CalendarSyncManagerV2
  private lateinit var mockCalendarSync: SourceCalendarSync
  private lateinit var mockCalendarSyncAccess: CalendarSyncAccess<SourceCalendarSync>
  private lateinit var mockCar: Car

  // Keep track of if the CalendarSyncAccess is started.
  private var calendarSyncAccessStarted: Boolean = false

  @Before
  fun setUp() {
    val context: Context = getApplicationContext()
    mockCalendarSync = mock()
    mockCalendarSyncAccess = mock {
      on { start() } doAnswer { calendarSyncAccessStarted = true }
      on { stop() } doAnswer { calendarSyncAccessStarted = false }
      on { isStarted } doAnswer { calendarSyncAccessStarted }
      on { access(any()) } doAnswer { invocation ->
        @Suppress("UNCHECKED_CAST")
        (invocation.arguments.first() as Consumer<SourceCalendarSync>).accept(mockCalendarSync)
      }
    }
    val mockCalendarSyncAccessFactory: CalendarSyncAccess.Factory<SourceCalendarSync> = mock {
      on { create(any()) } doReturn mockCalendarSyncAccess
    }
    manager = CalendarSyncManagerV2(context, daysToSync, mockCalendarSyncAccessFactory)
    mockCar = mock {
      on { deviceId } doReturn carId
    }
  }

  @Test
  fun create_withDefaultDependencies_doesNotDie() {
    CalendarSyncManagerV2(getApplicationContext())
  }

  @Test
  fun onCarConnected_disabled_doesNotStart() {
    manager.disableCar(carId)

    manager.notifyCarConnected(mockCar)

    verify(mockCalendarSyncAccess, never()).start()
  }

  @Test
  fun enableCar_sameManager_isEnabled() {
    assertThat(manager.isCarEnabled(carId)).isFalse()

    manager.enableCar(carId)

    assertThat(manager.isCarEnabled(carId)).isTrue()
  }

  @Test
  fun disableCar_isNotEnabled() {
    manager.enableCar(carId)

    manager.disableCar(carId)

    assertThat(manager.isCarEnabled(carId)).isFalse()
  }

  @Test
  fun setCalendarIdsToSync_getSameCalendarIds() {
    manager.setCalendarIdsToSync(calendarIds, carId)

    assertThat(manager.getCalendarIdsToSync(carId)).isEqualTo(calendarIds)
  }

  @Test
  fun onCarDisassociated_calendarIdsEmpty() {
    manager.setCalendarIdsToSync(calendarIds, carId)

    manager.onCarDisassociated(carId)

    assertThat(manager.getCalendarIdsToSync(carId)).isEmpty()
  }

  @Test
  fun onCarConnected_disabledCar_doesNotStart() {
    manager.notifyCarConnected(mockCar)

    verify(mockCalendarSyncAccess, never()).start()
  }

  @Test
  fun onCarConnected_enabledCar_doesStart() {
    manager.enableCar(carId)

    manager.notifyCarConnected(mockCar)

    verify(mockCalendarSyncAccess).start()
  }

  @Test
  fun disableCar_doesStop() {
    manager.enableCar(carId)
    manager.notifyCarConnected(mockCar)

    manager.disableCar(carId)

    verify(mockCalendarSyncAccess).stop()
  }

  @Test
  fun onCarDisconnected_noneConnected_doesStop() {
    var callback: Callback? = null
    whenever(mockCar.setCallback(any(), any())) doAnswer {
      callback = it.arguments.first() as Callback
    }

    manager.enableCar(carId)
    manager.notifyCarConnected(mockCar)
    callback!!.onDisconnected()

    verify(mockCalendarSync).clear(carId.toString())
    verify(mockCalendarSyncAccess).stop()
  }

  @Test
  fun onCarDisconnected_oneEnabledConnected_doesNotStop() {
    val carId2 = UUID.randomUUID()
    val mockCar2: Car = mock {
      on { deviceId } doReturn carId2
    }
    var callback: Callback? = null
    whenever(mockCar.setCallback(any(), any())) doAnswer {
      callback = it.arguments.first() as Callback
    }

    // Enable and connect both cars.
    manager.enableCar(carId)
    manager.enableCar(carId2)
    manager.notifyCarConnected(mockCar)
    manager.notifyCarConnected(mockCar2)

    // Disconnect one leaving the other connected and enabled.
    callback!!.onDisconnected()

    verify(mockCalendarSyncAccess, never()).stop()
  }

  @Test
  fun onMessageReceived_callsSyncReceive() {
    // Receive the message to from the car.
    manager.onMessageReceived(message, carId)

    // Show that the message is passed on to the CalendarSync.
    verify(mockCalendarSync).receive(eq(carId.toString()), eq(message))
  }

  @Test
  fun setCalendarIdsToSync_sync() {
    manager.enableCar(carId)
    manager.notifyCarConnected(mockCar)

    manager.setCalendarIdsToSync(calendarIds, carId)

    // Show that the given calendars are set on the sync.
    verify(mockCalendarSync).sync(carId.toString(), calendarIds)
  }

  @Test
  fun onCarDisconnected_callsSyncClear() {
    manager.enableCar(carId)
    manager.notifyCarConnected(mockCar)

    manager.onCarDisconnected(carId)

    verify(mockCalendarSync).clear(eq(carId.toString()))
  }

  @Test
  fun onDisable_callsSyncDisable() {
    manager.enableCar(carId)
    manager.notifyCarConnected(mockCar)

    manager.disableCar(carId)

    verify(mockCalendarSync).disable(eq(carId.toString()))
  }
}
