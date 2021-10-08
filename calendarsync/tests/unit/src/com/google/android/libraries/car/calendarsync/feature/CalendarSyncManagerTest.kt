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

import android.Manifest.permission
import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.connecteddevice.calendarsync.proto.Calendar
import com.google.android.connecteddevice.calendarsync.proto.Calendars
import com.google.android.connecteddevice.calendarsync.proto.TimeZone
import com.google.android.libraries.car.calendarsync.feature.CalendarSyncManager.Companion.KEY_CALENDAR_IDS
import com.google.android.libraries.car.calendarsync.feature.CalendarSyncManager.Companion.KEY_ENABLED
import com.google.android.libraries.car.calendarsync.feature.CalendarSyncManager.Companion.key
import com.google.android.libraries.car.calendarsync.feature.repository.CalendarRepository
import com.google.android.libraries.car.trustagent.Car
import com.google.common.collect.ImmutableSet
import com.google.common.time.ZoneIds
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.anyOrNull
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class CalendarSyncManagerTest {
  private val zoneId = ZoneIds.googleZoneId()
  private val zonedDateTime = ZonedDateTime.of(2020, 3, 10, 11, 12, 13, 14, zoneId)
  private val fixedTimeClock = Clock.fixed(zonedDateTime.toInstant(), zoneId)
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val carId = UUID.randomUUID()
  private val mockCar: Car = mock {
    on { deviceId } doReturn carId
  }

  private val calendarId1 = "111"
  private val calendarId2 = "222"

  // Create a dummy value that contains a unique field we can use to recognize its bytes.
  private val calendars = Calendars.newBuilder()
    .setDeviceTimeZone(TimeZone.newBuilder().setName("DummyTimeZone")).build()

  private lateinit var calendarSyncManager: CalendarSyncManager
  private lateinit var mockCalendarRepository: CalendarRepository
  private val sharedPreferences by lazy {
    context.getSharedPreferences("CalendarSyncManagerTest", Context.MODE_PRIVATE)
  }

  @get:Rule
  val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
    permission.READ_CALENDAR
  )

  @Before
  fun setUp() {
    mockCalendarRepository = mock()

    // Make sure something is returned whenever we do not care about the returned content.
    whenever(
      mockCalendarRepository.getCalendars(any(), any(), any())
    ) doReturn calendars

    whenever(
      mockCalendarRepository.getCalendars(any(), any())
    ) doReturn calendars

    calendarSyncManager = CalendarSyncManager(
      context,
      mockCalendarRepository,
      fixedTimeClock,
      sharedPreferences
    )
  }

  @Test
  fun onCarConnected_enabled_withIds_queriesCalendarsFromStartOfDay() {
    calendarSyncManager.enableCar(carId)
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1, calendarId2), carId)

    calendarSyncManager.notifyCarConnected(mockCar)

    val expectedCalendarIds = setOf(Integer.parseInt(calendarId1), Integer.parseInt(calendarId2))
    val expectedStartInstant = zonedDateTime.truncatedTo(ChronoUnit.DAYS).toInstant()
    argumentCaptor<Instant>().apply {
      verify(mockCalendarRepository).getCalendars(eq(expectedCalendarIds), capture(), capture())
      assertThat(firstValue).isEqualTo(expectedStartInstant)
      assertThat(firstValue).isLessThan(secondValue)
    }
  }

  @Test
  fun onCarConnected_enabled_withIds_doesSync() {
    calendarSyncManager.enableCar(carId)
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)

    calendarSyncManager.notifyCarConnected(mockCar)

    verify(mockCar).sendMessage(calendars.toByteArray(), CalendarSyncManager.FEATURE_ID)
  }

  @Test
  fun onCarConnected_enabled_emptyIds_doesNotSync() {
    calendarSyncManager.enableCar(carId)

    calendarSyncManager.notifyCarConnected(mockCar)

    verify(mockCar, never()).sendMessage(anyOrNull(), anyOrNull())
  }

  @Test
  fun onCarConnected_disabled_doesNotSync() {
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)

    calendarSyncManager.notifyCarConnected(mockCar)

    verify(mockCar, never()).sendMessage(anyOrNull(), anyOrNull())
  }

  @Test
  fun onCarConnected_syncsOnNextDay() {
    calendarSyncManager.enableCar(carId)
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)

    calendarSyncManager.notifyCarConnected(mockCar)

    var foundScheduledTaskTomorrow = false
    val loopers = ShadowLooper.getAllLoopers()
    for (looper in loopers) {
      val shadowLooper: ShadowLooper = shadowOf(looper)
      val tomorrowDateTime = zonedDateTime.plusDays(1).truncatedTo(ChronoUnit.DAYS)
      if (zonedDateTime.plus(shadowLooper.nextScheduledTaskTime) >= tomorrowDateTime) {
        foundScheduledTaskTomorrow = true

        // Run tomorrows task so we can assert it caused a sync.
        shadowLooper.runOneTask()
      }
    }
    assertThat(foundScheduledTaskTomorrow).isTrue()

    verify(mockCar, times(2))
      .sendMessage(calendars.toByteArray(), CalendarSyncManager.FEATURE_ID)
  }

  fun onCarDisassociated_disablesCalendarSync() {
    calendarSyncManager.enableCar(carId)

    calendarSyncManager.onCarDisassociated(carId)

    assertThat(calendarSyncManager.isCarEnabled(carId)).isFalse()
  }

  @Test
  fun onCarDisassociated_removesCarPreferences() {
    calendarSyncManager.enableCar(carId)
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)
    // Another car
    calendarSyncManager.enableCar(UUID.randomUUID())

    calendarSyncManager.onCarDisassociated(carId)

    assertThat(sharedPreferences.contains(key(KEY_CALENDAR_IDS, carId))).isFalse()
    assertThat(sharedPreferences.contains(key(KEY_ENABLED, carId))).isFalse()
    assertThat(sharedPreferences.all).isNotEmpty()
  }

  @Test
  fun onAllCarsDisassociated_disablesCalendarSync() {
    calendarSyncManager.enableCar(carId)

    calendarSyncManager.onAllCarsDisassociated()

    assertThat(calendarSyncManager.isCarEnabled(carId)).isFalse()
  }

  @Test
  fun onAllCarsDisassociated_clearsAllPreferences() {
    sharedPreferences.edit().putString("key", "value").apply()

    calendarSyncManager.onAllCarsDisassociated()

    assertThat(sharedPreferences.all).isEmpty()
  }

  @Test
  fun enableCar_storesValue() {
    calendarSyncManager.enableCar(carId)

    assertThat(
      sharedPreferences.getBoolean(
        key(CalendarSyncManager.KEY_ENABLED, carId),
        false
      )
    ).isTrue()
  }

  @Test
  fun enableCar_withoutStoredCalendarIds_doesNotSync() {
    calendarSyncManager.notifyCarConnected(mockCar)

    calendarSyncManager.enableCar(carId)

    verify(mockCar, never()).sendMessage(any(), eq(CalendarSyncManager.FEATURE_ID))
  }

  @Test
  fun enableCar_withStoredCalendarIds_doesSync() {
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)
    calendarSyncManager.notifyCarConnected(mockCar)

    calendarSyncManager.enableCar(carId)

    verify(mockCar).sendMessage(calendars.toByteArray(), CalendarSyncManager.FEATURE_ID)
  }

  @Test
  fun enableCar_disconnected_withStoredCalendarIds_doesNotSync() {
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)

    calendarSyncManager.enableCar(carId)

    verify(mockCar, never()).sendMessage(any(), eq(CalendarSyncManager.FEATURE_ID))
  }

  @Test
  fun disableCar_storesValue() {
    calendarSyncManager.disableCar(carId)

    assertThat(
      sharedPreferences.getBoolean(
        key(CalendarSyncManager.KEY_ENABLED, carId),
        true
      )
    ).isFalse()
  }

  @Test
  fun disableCar_withStoredCalendarIds_doesSync() {
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)
    calendarSyncManager.notifyCarConnected(mockCar)

    calendarSyncManager.disableCar(carId)

    verify(mockCar).sendMessage(any(), eq(CalendarSyncManager.FEATURE_ID))
  }

  @Test
  fun disableCar_withoutStoredCalendarIds_doesNotSync() {
    calendarSyncManager.notifyCarConnected(mockCar)

    calendarSyncManager.disableCar(carId)

    verify(mockCar, never()).sendMessage(any(), eq(CalendarSyncManager.FEATURE_ID))
  }

  @Test
  fun disableCar_disconnected_withStoredCalendarIds_doesNotSync() {
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)

    calendarSyncManager.disableCar(carId)

    verify(mockCar, never()).sendMessage(any(), eq(CalendarSyncManager.FEATURE_ID))
  }

  @Test
  fun isCarEnabled_noStoredPreferences_returnFalse() {
    assertThat(calendarSyncManager.isCarEnabled(carId)).isFalse()
  }

  @Test
  fun getCalendarIdsToSync_calendarsInRepository_returnsCalendars() {
    val calendarIds = setOf("first", "second", "third")
    whenever(
      mockCalendarRepository.getCalendars(any(), any())
    ) doReturn generateEmptyCalendars(calendarIds)

    assertThat(calendarSyncManager.getCalendarIdsToSync(carId)).isEqualTo(calendarIds)
  }

  @Test
  fun getCalendarIdsToSync_noCalendarsInRepository_returnEmptyList() {
    assertThat(calendarSyncManager.getCalendarIdsToSync(carId)).isEmpty()
  }

  @Test
  fun getCalendarIdsToSync() {
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1, calendarId2), carId)

    assertThat(calendarSyncManager.getCalendarIdsToSync(carId))
      .containsExactly(calendarId1, calendarId2)
    assertThat(calendarSyncManager.getCalendarIdsToSync(UUID.randomUUID())).isEmpty()
  }

  @Test
  fun setCalendarIdsToSync_storesValue() {
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1, calendarId2), carId)

    val storedCalendars = sharedPreferences.getStringSet(
      key(CalendarSyncManager.KEY_CALENDAR_IDS, carId), ImmutableSet.of()
    )!!
    assertThat(storedCalendars).containsExactly(calendarId1, calendarId2)
  }

  @Test
  fun setCalendarIdsToSync_doesSyncNewCalendar() {
    calendarSyncManager.notifyCarConnected(mockCar)
    configureCarPreferences(
      carId, enable = true, calendarIds = setOf(calendarId1, calendarId2)
    )

    val newCalendarId = "333"
    calendarSyncManager.setCalendarIdsToSync(
      setOf(calendarId1, calendarId2, newCalendarId), carId
    )

    verify(mockCalendarRepository)
      .getCalendars(eq(setOf(Integer.parseInt(newCalendarId))), any(), any())
    verify(mockCar).sendMessage(calendars.toByteArray(), CalendarSyncManager.FEATURE_ID)
  }

  @Test
  fun setCalendarIdsToSync_doesSyncRemovedCalendar() {
    calendarSyncManager.notifyCarConnected(mockCar)
    configureCarPreferences(
      carId, enable = true, calendarIds = setOf(calendarId1, calendarId2)
    )

    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)

    val calendarsToRemove = generateEmptyCalendars(setOf(calendarId2))
    verify(mockCar)
      .sendMessage(calendarsToRemove.toByteArray(), CalendarSyncManager.FEATURE_ID)
    verify(mockCalendarRepository, never()).getCalendars(any(), any(), any())
  }

  @Test
  fun setCalendarIdsToSync_onDisabledCar_doesNotSync() {
    calendarSyncManager.notifyCarConnected(mockCar)
    configureCarPreferences(carId, enable = false, calendarIds = setOf())

    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1), carId)

    verify(mockCar, never()).sendMessage(anyOrNull(), anyOrNull())
  }

  @Test
  fun carsConnected_registersCalendarObserver() {
    calendarSyncManager.enableCar(carId)

    // Register another car which should not start another observer.
    val carId2 = UUID.randomUUID()
    val mockCar2: Car = mock {
      on { deviceId } doReturn carId2
    }
    calendarSyncManager.enableCar(carId2)

    val contentResolver: ShadowContentResolver = shadowOf(context.contentResolver)
    assertThat(contentResolver.getContentObservers(CalendarContract.Instances.CONTENT_URI))
      .isEmpty()

    // Connect the cars.
    calendarSyncManager.notifyCarConnected(mockCar)
    calendarSyncManager.notifyCarConnected(mockCar2)

    assertThat(contentResolver.getContentObservers(CalendarContract.Instances.CONTENT_URI))
      .hasSize(1)
  }

  @Test
  fun carsDisconnected_unregistersCalendarObserver() {
    calendarSyncManager.enableCar(carId)

    // Register another car which should not start another observer.
    val carId2 = UUID.randomUUID()
    val mockCar2: Car = mock {
      on { deviceId } doReturn carId2
    }
    calendarSyncManager.enableCar(carId2)

    // Connect the cars.
    calendarSyncManager.notifyCarConnected(mockCar)
    calendarSyncManager.notifyCarConnected(mockCar2)

    val shadowContentResolver = shadowOf(context.contentResolver)
    assertThat(shadowContentResolver.getContentObservers(CalendarContract.Instances.CONTENT_URI))
      .isNotEmpty()

    // Disconnect the first car to remove it and trigger onCarDisconnected.
    argumentCaptor<Car.Callback>().apply {
      verify(mockCar).setCallback(capture(), eq(CalendarSyncManager.FEATURE_ID))
      firstValue.onDisconnected()
      assertThat(shadowContentResolver.getContentObservers(CalendarContract.Instances.CONTENT_URI))
        .isNotEmpty()
    }

    // Only removing the last car should stop observing the calendar event instances.
    argumentCaptor<Car.Callback>().apply {
      verify(mockCar2).setCallback(capture(), eq(CalendarSyncManager.FEATURE_ID))
      firstValue.onDisconnected()
      assertThat(shadowContentResolver.getContentObservers(CalendarContract.Instances.CONTENT_URI))
        .isEmpty()
    }
  }

  @Test
  fun eventsChanged_syncToCar() {
    calendarSyncManager.enableCar(carId)
    calendarSyncManager.setCalendarIdsToSync(setOf(calendarId1, calendarId2), carId)
    calendarSyncManager.notifyCarConnected(mockCar)
    idleAllLoopers()

    val shadowContentResolver = shadowOf(context.contentResolver)
    val observer =
      shadowContentResolver.getContentObservers(CalendarContract.Instances.CONTENT_URI).first()
    observer.dispatchChange(false, null)
    idleAllLoopers()
    // The first sync will occur on connection, the second due to the changed events.
    verify(mockCar, times(2))
      .sendMessage(calendars.toByteArray(), CalendarSyncManager.FEATURE_ID)
  }

  /** Creates a [Calendars] proto with the given calendar ids. */
  private fun generateEmptyCalendars(calendarIds: Set<String>): Calendars {
    val calendarList = calendarIds.map { Calendar.newBuilder().setUuid(it).build() }
    return Calendars.newBuilder().addAllCalendar(calendarList).build()
  }

  /** Configure preferences for the given carId. */
  private fun configureCarPreferences(
    carId: UUID,
    enable: Boolean,
    calendarIds: Set<String>
  ) {
    sharedPreferences.edit()
      .clear()
      .putBoolean(key(KEY_ENABLED, carId), enable)
      .putStringSet(key(KEY_CALENDAR_IDS, carId), calendarIds)
      .apply()
  }

  private fun idleAllLoopers() = ShadowLooper.getAllLoopers().forEach { shadowOf(it).idle() }
}
