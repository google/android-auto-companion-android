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

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.CalendarContract
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.android.connecteddevice.calendarsync.proto.Calendar
import com.google.android.connecteddevice.calendarsync.proto.Calendars
import com.google.android.libraries.car.calendarsync.feature.repository.CalendarRepository
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.logi
import com.google.common.collect.ImmutableSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val TAG = "CalendarSyncManager"
private const val PREFERENCES_NAME = TAG

// The duration of events to sync from the beginning of the current day.
private val SYNC_DURATION = Duration.ofDays(2)

/** Uses the experimental bi-directional sync impl when set to true.  */
private const val USE_CALENDAR_BIDIRECTIONAL_SYNC = false

/**
 * Returns an instance of a calendar sync manager to be used for managing calendar sync.
 */
// TODO(b/166134901) Remove this once v1 is deleted.
fun createCalendarSyncManager(context: Context): FeatureManager {
  return if (USE_CALENDAR_BIDIRECTIONAL_SYNC) {
    logi(TAG, "Using ${CalendarSyncManagerV2::class.simpleName}")
    CalendarSyncManagerV2(context)
  } else {
    logi(TAG, "Using ${CalendarSyncManager::class.simpleName}")
    CalendarSyncManager(context)
  }
}

/**
 * A [FeatureManager] which controls syncing local calendars to a connected car.
 *
 * State is stored in [SharedPreferences] but changes are not observed so all changes should be made
 * through this class.
 *
 * Logic for keeping the head unit (HU) in sync with the selected calendars on the mobile device:
 *
 * - Car connect sends all selected calendars and events to that car (should be no calendars on HU)
 * - Car disconnect removes all calendars and events (handled on HU)
 * - Change of state (enabled or selected calendars) causes a sync to all connected cars
 * - Enable sync sends all selected calendars and events (may be only empty calendars on HU)
 * - Disable sync clears events from all selected calendars (calendars remain empty)
 * - Change selected ids is only allowed when sync is enabled
 * - Change selected ids sends twice: added calendars & removed calendars
 * - Removed calendars sends the calendar with no events (HU calendar remains empty)
 * - Change to calendar events triggers sync
 * - Car connect schedules a sync at the start of the next day
 */
@PublicApi
// TODO(b/159453310) Remove this from public API only exposing the interface above.
class CalendarSyncManager(
  private val context: Context,
  private val calendarRepository: CalendarRepository = CalendarRepository(context.contentResolver),
  private val clock: Clock = Clock.systemDefaultZone(),
  private val preferences: SharedPreferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
) : FeatureManager(), CalendarSyncFeature {

  override val featureId = FEATURE_ID

  private var eventContentObserver: EventContentObserver? = null

  private val isEventObserverStarted
    get() = eventContentObserver != null

  override fun onCarConnected(carId: UUID) {
    if (isCarEnabled(carId)) {
      syncStoredCalendars(carId)

      // Only start observing events if at least one connected car is enabled.
      if (!isEventObserverStarted) {
        startEventObserver()
      }
    }
  }

  private fun startEventObserver() {
    // Only observe event changes if we have the required calendar read permission.
    if (hasPermission()) {
      eventContentObserver = EventContentObserver().also {
        context.contentResolver
          .registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, it)
      }
    }
  }

  private fun stopEventObserver() {
    eventContentObserver?.let {
      context.contentResolver.unregisterContentObserver(it)
      it.stop()
    }
    eventContentObserver = null
  }

  override fun onMessageReceived(message: ByteArray, carId: UUID) {
    // No-op. This manager is not waiting to receive any messages from the car.
  }

  override fun onMessageSent(messageId: Int, carId: UUID) {}

  override fun onCarDisconnected(carId: UUID) {
    if (connectedCars.isEmpty()) {
      stopEventObserver()
    }
  }

  override fun onCarDisassociated(carId: UUID) {
    // Disable calendar sync so that a new association will require the user to go through the
    // enabling flow.
    preferences.edit()
      .remove(key(KEY_CALENDAR_IDS, carId))
      .remove(key(KEY_ENABLED, carId))
      .apply()
  }

  override fun onAllCarsDisassociated() {
    // No need for a synchronous `commit()` because field values already set explicitly.
    preferences.edit().clear().apply()
  }

  /**
   * Returns true if the required permissions are granted.
   *
   * <p>If permission is not granted calendar is disabled for all connected cars.
   */
  private fun hasPermission(): Boolean {
    // Make sure that read calendar permission is granted. If the feature is enabled this will
    // typically be the case, but users can manually revoke the permission. In this case the feature
    // will be disabled and users have to enable it again through the Companion App.
    // TODO(b/151917680): Implement a callback mechanism into the companion app.
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
      != PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "Calendar read permission not granted")
      return false
    }
    return true
  }

  override fun isCarEnabled(carId: UUID): Boolean {
    // TODO(b/152163075): Migrate to Room
    return preferences.getBoolean(key(KEY_ENABLED, carId), false)
  }

  override fun enableCar(carId: UUID) {
    // TODO(b/152163075): Migrate to Room
    preferences.edit().putBoolean(key(KEY_ENABLED, carId), true).apply()
    syncStoredCalendars(carId)
  }

  override fun disableCar(carId: UUID) {
    // TODO(b/152163075): Migrate to Room
    preferences.edit().putBoolean(key(KEY_ENABLED, carId), false).apply()
    unsyncStoredCalendars(carId)
  }

  override fun getCalendarIdsToSync(carId: UUID): Set<String> {
    // TODO(b/152163075): Migrate to Room
    val key = key(KEY_CALENDAR_IDS, carId)
    return if (preferences.contains(key)) {
      preferences.getStringSet(key, ImmutableSet.of())!!
    } else {
      // Default to syncing all calendars.
      val allCalendarIds = fetchAllCalendarIds()
      Log.d(TAG, "Defaulting to sync all ${allCalendarIds.size} calendars")
      storeCalendarIds(allCalendarIds, carId)
      allCalendarIds
    }
  }

  private fun fetchAllCalendarIds(): Set<String> {
    val now = Instant.now()
    return calendarRepository.getCalendars(now, now).calendarList.map { it.uuid }.toSet()
  }

  override fun setCalendarIdsToSync(calendarIds: Set<String>, carId: UUID) {
    // TODO(b/152163075): Migrate to Room
    val storedIds = getCalendarIdsToSync(carId)
    storeCalendarIds(calendarIds, carId)

    if (!isCarEnabled(carId)) {
      return
    }

    val removedIds = storedIds - calendarIds
    val addedIds = calendarIds - storedIds
    sync(addedIds, carId)
    unsync(removedIds, carId)
  }

  private fun storeCalendarIds(calendarIds: Set<String>, carId: UUID) {
    preferences.edit().putStringSet(key(KEY_CALENDAR_IDS, carId), calendarIds.toSet()).apply()
  }

  private fun syncStoredCalendars(carId: UUID) {
    sync(getCalendarIdsToSync(carId), carId)
  }

  /**
   * Synchronize the given [calendarIds] to the car with the given [carId].
   */
  private fun sync(calendarIds: Set<String>, carId: UUID) {
    if (!hasPermission() || !isCarConnected(carId)) return

    if (Log.isLoggable(TAG, Log.DEBUG)) {
      Log.d(TAG, "Syncing $calendarIds with car (id: $carId)")
    }

    if (calendarIds.isEmpty()) {
      return
    }

    val start = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.DAYS).toInstant()
    val calendars = calendarRepository.getCalendars(
      calendarIds.map(Integer::parseInt).toSet(), start, start + SYNC_DURATION
    )
    sendMessage(calendars.toByteArray(), carId)
  }

  private fun unsyncStoredCalendars(carId: UUID) {
    unsync(getCalendarIdsToSync(carId), carId)
  }

  /**
   * Un-synchronize the given [calendarIds] to the car with the given [carId].
   */
  private fun unsync(calendarIds: Set<String>, carId: UUID) {
    if (!hasPermission() || !isCarConnected(carId)) return

    if (Log.isLoggable(TAG, Log.DEBUG)) {
      Log.d(TAG, "Removing $calendarIds with car (id: $carId)")
    }

    if (calendarIds.isEmpty()) {
      return
    }

    val calendarList = calendarIds.map { Calendar.newBuilder().setUuid(it).build() }
    val calendars = Calendars.newBuilder().addAllCalendar(calendarList).build()
    sendMessage(calendars.toByteArray(), carId)
  }

  /**
   * Calls [sync] when calendar events change or the day changes.
   */
  private inner class EventContentObserver(
    private val startedHandlerThread: HandlerThread =
      HandlerThread("CalendarSyncBackgroundThread").apply { start() },
    private val handler: Handler = Handler(startedHandlerThread.looper)
  ) : ContentObserver(handler) {

    init {
      scheduleNextDayUpdate()
    }

    /**
     * Update the events at the start of each new day.
     */
    private fun scheduleNextDayUpdate() {
      // Should not be required, but to be safe, remove any existing callbacks.
      handler.removeCallbacksAndMessages(this)

      val now = ZonedDateTime.now(clock)
      val tomorrow = now.plusDays(1).truncatedTo(ChronoUnit.DAYS)
      val difference = Duration.between(now, tomorrow)

      Log.i(TAG, "Scheduling sync in $difference")
      val nextDayUptimeMs: Long = SystemClock.uptimeMillis() + difference.toMillis()
      handler.postAtTime(::updateAndScheduleNextDayUpdate, /* token= */ this, nextDayUptimeMs)
    }

    private fun updateAndScheduleNextDayUpdate() {
      Log.i(TAG, "Syncing new day at ${ZonedDateTime.now(clock)}")
      update()
      scheduleNextDayUpdate()
    }

    override fun onChange(selfChange: Boolean) {
      update()
    }

    private fun update() {
      for (carId in connectedCars) {
        syncStoredCalendars(carId)
      }
    }

    fun stop() {
      startedHandlerThread.quit()
      handler.removeCallbacksAndMessages(this)
    }
  }

  companion object {
    /** Calendar Sync's feature ID shared between the head unit and the clients. */
    @JvmField
    val FEATURE_ID: UUID = UUID.fromString("5a1a16fd-1ebd-4dbe-bfa7-37e40de0fd80")

    @VisibleForTesting
    const val KEY_CALENDAR_IDS = "ids"

    @VisibleForTesting
    const val KEY_ENABLED = "enabled"

    @VisibleForTesting
    fun key(prefix: String, carId: UUID) = "${prefix}_$carId"
  }
}
