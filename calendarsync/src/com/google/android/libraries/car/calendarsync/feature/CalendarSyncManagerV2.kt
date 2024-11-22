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
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.google.android.connecteddevice.calendarsync.android.CalendarSyncAccess
import com.google.android.connecteddevice.calendarsync.common.CommonLogger
import com.google.android.connecteddevice.calendarsync.common.SourceCalendarSync
import com.google.android.connecteddevice.calendarsync.common.TimeWindow
import com.google.android.libraries.car.calendarsync.feature.Settings.Car
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlin.reflect.KCallable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private const val TAG = "CalendarSyncManagerV2"

// LINT.IfChange(DEFAULT_DAYS_TO_SYNC)
private const val DEFAULT_DAYS_TO_SYNC = 3
// LINT.ThenChange(
//  //depot/google3/googlemac/iPhone/AndroidAuto/TrustedDeviceApp/ios/CalendarSyncMethodChannel.swift:daysToSync)

fun createCalendarSyncManager(context: Context): FeatureManager {
    return CalendarSyncManagerV2(context)
}

// TODO: Make CalendarSyncManager methods non-blocking.
/**
 * A [FeatureManager] that synchronizes local calendar data with connected cars.
 *
 * There should only exist a single instance of this class managed by a service.
 */
@PublicApi
class CalendarSyncManagerV2(
  context: Context,
  daysToSync: Int = DEFAULT_DAYS_TO_SYNC,
  calendarSyncAccessFactory: CalendarSyncAccess.Factory<SourceCalendarSync> =
    createCalendarSyncAccessFactory(context, daysToSync)
) : FeatureManager(), CalendarSyncFeature {

  override val featureId: UUID = UUID.fromString("5a1a16fd-1ebd-4dbe-bfa7-37e40de0fd80")

  /** Creates a CalendarSyncAccess which is able to reference [CalendarSyncManagerV2.this]. */
  private val calendarSyncAccess = calendarSyncAccessFactory.create(this::sendToCar)

  private val datastore: DataStore<Settings> = DataStoreFactory.create(
    produceFile = { context.dataStoreFile("settings.pb") },
    serializer = SettingsSerializer
  )

  override fun isCarEnabled(carId: UUID) = read(carId)?.enabled ?: false

  override fun getCalendarIdsToSync(carId: UUID) =
    read(carId)?.calendarIdsList?.toSet() ?: emptySet()

  override fun enableCar(carId: UUID) {
    trace(::enableCar)
    write(carId) { enabled = true }
    onCarEnabledOrConnected(carId)
  }

  override fun disableCar(carId: UUID) {
    trace(::disableCar)
    write(carId) { enabled = false }
    if (calendarSyncAccess.isStarted) {
      runWithCalendarSync { disable(carId.toString()) }
    }
    maybeStartOrStopAccess()
  }

  override fun setCalendarIdsToSync(calendarIds: Set<String>, carId: UUID) {
    trace(::setCalendarIdsToSync)
    write(carId) { clearCalendarIds().addAllCalendarIds(calendarIds) }
    maybeSyncCar(carId)
  }

  override fun onCarConnected(carId: UUID) = onCarEnabledOrConnected(carId)

  override fun onCarDisconnected(carId: UUID) {
    if (calendarSyncAccess.isStarted) {
      runWithCalendarSync { clear(carId.toString()) }
    }
    maybeStartOrStopAccess()
  }

  override fun onMessageReceived(message: ByteArray, carId: UUID) =
    runWithCalendarSync { receive(carId.toString(), message) }

  override fun onMessageSent(messageId: Int, carId: UUID): Unit = trace(::onMessageSent)

  /**
   * Removes settings for this car.
   */
  override fun onCarDisassociated(carId: UUID): Unit = runBlocking {
    datastore.updateData { settings -> settings.toBuilder().removeCars(carId.toString()).build() }
  }

  /**
   * Removes settings for all cars.
   */
  override fun onAllCarsDisassociated(): Unit = runBlocking {
    datastore.updateData { Settings.getDefaultInstance() }
  }

  // TODO: Make CalendarSyncManager methods non-blocking.
  /**
   * A blocking read to the preferences data store.
   */
  private fun read(carId: UUID): Car? = runBlocking {
    datastore.data.map { settings -> settings.carsMap[carId.toString()] }.first()
  }

  // TODO: Make CalendarSyncManager methods non-blocking.
  /**
   * A blocking write to the preferences data store.
   */
  private fun write(carId: UUID, modify: Car.Builder.() -> Unit): Unit = runBlocking {
    datastore.updateData { settings ->
      // Get a Car builder to modify or create a new empty one.
      val car = settings.carsMap[carId.toString()]?.toBuilder() ?: Car.newBuilder()

      // Run the block that modifies the car builder.
      car.modify()

      // Rebuild the settings for all cars with the modified car.
      settings.toBuilder().putCars(carId.toString(), car.build()).build()
    }
  }

  private fun onCarEnabledOrConnected(carId: UUID) {
    maybeStartOrStopAccess()
    maybeSyncCar(carId)
  }

  private fun maybeSyncCar(carId: UUID) {
    if (isCarEnabled(carId) && isCarConnected(carId)) {
      // Read the ids in the same thread as they are set.
      val calendarIdsToSync = getCalendarIdsToSync(carId)
      runWithCalendarSync { sync(carId.toString(), calendarIdsToSync) }
    }
  }

  private fun maybeStartOrStopAccess() {
    val hasEnabledConnectedCars = hasEnabledConnectedCars()
    if (hasEnabledConnectedCars && !calendarSyncAccess.isStarted) {
      calendarSyncAccess.start()
    }
    if (!hasEnabledConnectedCars && calendarSyncAccess.isStarted) {
      calendarSyncAccess.stop()
    }
  }

  private fun hasEnabledConnectedCars() = connectedCars.any { isCarEnabled(it) }

  /**
   * Runs code against the CalendarSync in the correct thread.
   */
  private fun runWithCalendarSync(block: SourceCalendarSync.() -> Unit) {
    calendarSyncAccess.access { it.block() }
  }

  /** Logs the method name at debug level. */
  private fun trace(method: KCallable<Any>) = logd(TAG, method.name)

  /** Sends a message with conversion of the carId from String to UUID . */
  private fun sendToCar(carIdText: String, message: ByteArray) {
    sendMessage(message, UUID.fromString(carIdText))
  }
}

/** Creates a [CalendarSyncAccess.Factory] with concrete dependencies. */
private fun createCalendarSyncAccessFactory(context: Context, daysToSync: Int):
  CalendarSyncAccess.Factory<SourceCalendarSync> {
    val clock = Clock.system(ZoneId.systemDefault())
    return CalendarSyncAccess.Factory.createSourceFactory(
      TrustAgentLoggerFactory,
      context.contentResolver,
      TimeWindow.wholeDayTimeWindowSupplier(clock, daysToSync),
    )
  }

// TODO This logging util should be moved from trust agent package.
private object TrustAgentLoggerFactory : CommonLogger.Factory {
  override fun create(name: String) = object : CommonLogger {
    override fun debug(message: String) = logd(name, message)
    override fun info(message: String) = logi(name, message)
    override fun warn(message: String) = logw(name, message)
    override fun error(message: String) = loge(name, message)
    override fun error(message: String, e: Exception) = loge(name, message, e)
  }
}

/**
 * Serializer for reading and writing Settings.
 */
private object SettingsSerializer : Serializer<Settings> {
  override val defaultValue: Settings = Settings.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): Settings {
    try {
      return Settings.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: Settings, output: OutputStream) = t.writeTo(output)
}
