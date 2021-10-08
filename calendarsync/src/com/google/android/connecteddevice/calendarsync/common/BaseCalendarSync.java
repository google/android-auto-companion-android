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

package com.google.android.connecteddevice.calendarsync.common;

import static com.google.common.collect.Sets.difference;

import com.google.android.connecteddevice.calendarsync.Calendar;
import com.google.android.connecteddevice.calendarsync.UpdateCalendars;
import com.google.android.connecteddevice.calendarsync.common.CalendarsObservable.ObservationHandle;
import com.google.android.connecteddevice.calendarsync.common.Scheduler.ScheduledTaskHandle;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.Sets.SetView;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A base class for {@link ReplicaCalendarSync} and {@link SourceCalendarSync} that handles all
 * common synchronization behaviour. {@link SourceCalendarSync} sends calendar data from the device
 * to a {@link ReplicaCalendarSync} which holds a copy of the data.
 *
 * <p>Both subclasses synchronize calendar data from the <a
 * href="https://developer.android.com/guide/topics/providers/calendar-provider">calendar
 * provider</a> on this device with remote devices. Additionally, {@link SourceCalendarSync} will
 * periodically send extra event data to the remote device to keep its time window current.
 *
 * <p>Each synchronized calendar has a <i>time window</i> for events that will be observed for
 * changes. Changes to events outside of that window will be ignored. The time window is defined by
 * the {@link SourceCalendarSync} and sent to the {@link ReplicaCalendarSync} with the calendar
 * data. Using a time window limits the amount of data that needs to be sent between devices.
 *
 * <p>Early versions of this feature do not support sending calendar changes in both directions but
 * have separate <i>sender</i> and a <i>receiver</i> components. This version supports sending and
 * receiving incremental updates to calendars.
 *
 * <p>For backwards compatibility this class supports two modes depending on the version of the
 * remote device feature:
 *
 * <ol>
 *   <li><b>Replace mode for legacy remote devices</b>
 *       <p>When a remote device does not support updates this device will send <i>or</i> receive
 *       the <i>full calendar data</i> when there is a change to the synchronized calendars. Only
 *       the source device will observe calendars and send data and only the replica device will
 *       receive data.
 *   <li><b>Update mode</b>
 *       <p>When a remote device does support updates this device will send <i>and</i> receive
 *       <i>incremental updates</i> to synchronized calendars. Both the source and the replica will
 *       observe calendars and send and receive data.
 * </ol>
 *
 * The initial message to a remote device always assumes the receiver is a legacy device. Only after
 * receiving a message from the remote device can it know the version. This is fine because the
 * first message will always contain the full source calendar data. Upon receiving the first message
 * the version is updated and subsequent changes may be sent as updates.
 *
 * <p>This class is not thread-safe and after construction should only be accessed by a single
 * non-main thread.
 */
// TODO(b/166134901) Work in progress - complete the features documented above.
public abstract class BaseCalendarSync {
  /** The current version of the protocol. */
  private static final int PROTOCOL_VERSION = 1;

  /** The minimum version of the protocol that supports updating instead of replacing data. */
  private static final int UPDATABLE_VERSION = 1;

  /** The duration to wait for further changes before processing a calendar change event. */
  private static final Duration CHANGE_DEBOUNCE_DELAY = Duration.ofMillis(500);

  protected final CommonLogger logger;
  protected final RemoteSender sender;
  protected final CalendarManagerFactory calendarManagerFactory;
  protected final CalendarStore calendarStore;
  protected final Scheduler scheduler;
  private final CalendarsObservable calendarsObservable;

  // State is not persistent so if the process is stopped this will be lost.
  // This is ok because on startup all related persistent data will also be erased.
  protected final Map<String, DeviceState> deviceIdToState = new HashMap<>();

  @Nullable private ObservationHandle calendarsObservationHandle;
  @Nullable private ScheduledTaskHandle delayedChangeUpdate;

  BaseCalendarSync(
      CommonLogger logger,
      RemoteSender sender,
      CalendarStore calendarStore,
      Scheduler scheduler,
      CalendarsObservable calendarsObservable,
      CalendarManagerFactory calendarManagerFactory) {
    this.logger = logger;
    this.sender = sender;
    this.calendarStore = calendarStore;
    this.scheduler = scheduler;
    this.calendarsObservable = calendarsObservable;
    this.calendarManagerFactory = calendarManagerFactory;
  }

  /** Receives a message from the remote device. */
  @SuppressWarnings("UnnecessaryDefaultInEnumSwitch")
  public void receive(String deviceId, byte[] message) {
    throwIfNotStarted();
    UpdateCalendars update;
    try {
      update = UpdateCalendars.parseFrom(message, ExtensionRegistryLite.getEmptyRegistry());
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Invalid message received", e);
    }
    DeviceState state = getOrCreateState(deviceId);
    state.version = update.getVersion();

    logger.info(
        "Received %s with %d calendars and size %d from device %s",
        update.getType().name(), update.getCalendarsCount(), message.length, deviceId);

    switch (update.getType()) {
      case TYPE_UNSPECIFIED:
        // Fall-through. The default v1 behaviour is to receive content.
      case RECEIVE:
        handleReceiveMessage(state, update.getCalendarsList());
        break;
      case ACKNOWLEDGE:
        // Do nothing else besides setting the device version above.
        break;
      case DISABLE:
        handleDisableMessage(deviceId);
        break;
      default:
        // Cannot use UNRECOGNIZED with J2ObjC which does not see this generated value.
        logger.warn("Unrecognized update type number %d", update.getType().getNumber());
    }
  }

  protected DeviceState getOrCreateState(String deviceId) {
    return deviceIdToState.computeIfAbsent(deviceId, (unused) -> new DeviceState(deviceId));
  }

  /** Replaces the given {@code calendars} by deleting and maybe recreating them. */
  protected void handleReceiveMessage(DeviceState state, List<Calendar> updates) {
    // TODO(b/182912880) Changes made concurrently by another application could be lost.
    CalendarManager manager = getCalendarManager(state);
    manager.applyUpdateMessages(state.deviceId, updates);
    send(newUpdateBuilder(UpdateCalendars.Type.ACKNOWLEDGE).build(), state.deviceId);
  }

  /**
   * Disables synchronizing calendars with the given {@code deviceId).
   */
  protected abstract void handleDisableMessage(String deviceId);

  /** Clears all temporary state for the given device. */
  public void clear(String deviceId) {
    logger.debug("Clear device " + deviceId);
    deviceIdToState.remove(deviceId);
    calendarStore.removeAll(deviceId);
  }

  /**
   * Starts this instance to be ready to synchronize with remote devices.
   *
   * <p>Must be called before using the other methods of this class.
   */
  public void start() {
    logger.debug("Start");
    calendarsObservationHandle = calendarsObservable.observe(this::onCalendarsChanged);
  }

  /**
   * Calls {@link #onCalendarsChangedDebounced()} after changes have stopped for some time.
   *
   * <p>Updating the calendar can result in many change events being fired in rapid succession.
   */
  private void onCalendarsChanged() {
    logger.debug("Calendars changed");
    if (delayedChangeUpdate != null) {
      delayedChangeUpdate.cancel();
    }
    delayedChangeUpdate = scheduler.delay(CHANGE_DEBOUNCE_DELAY, this::onCalendarsChangedDebounced);
  }

  private void onCalendarsChangedDebounced() {
    logger.info("Sending updates from calendars change");
    delayedChangeUpdate = null;
    sendAllChangeUpdates();
  }

  /**
   * Stops this instance synchronizing with remote devices.
   *
   * <p>Must be called when the synchronization will be stopped.
   */
  public void stop() {
    logger.debug("Stop");
    deviceIdToState.clear();
    calendarStore.clear();
    if (calendarsObservationHandle != null) {
      calendarsObservationHandle.unregister();
      calendarsObservationHandle = null;
    } else {
      logger.error("Calendars were not observed");
    }
  }

  /** Updates all remote devices with changes to calendars. */
  protected void sendAllChangeUpdates() {
    for (DeviceState state : deviceIdToState.values()) {
      sendDeviceChangeUpdate(state);
    }
  }

  /** Updates a single remote device with changes to calendars. */
  protected void sendDeviceChangeUpdate(DeviceState state) {
    // The store reflects what was sent
    ImmutableSet<Calendar> previousCalendars = calendarStore.fetchAll(state.deviceId);

    CalendarManager manager = getCalendarManager(state);
    Set<Calendar> currentCalendars = new HashSet<>();
    for (String currentCalendarKey : state.getCalendarKeys()) {
      Calendar currentCalendar = manager.read(state.deviceId, currentCalendarKey);
      if (currentCalendar == null) {
        // Calendar might have been removed from this device.
        logger.warn("Could not read calendar %s", currentCalendarKey);

        // Not adding the calendar will cause a DELETE action to be sent.
        continue;
      }

      calendarStore.store(state.deviceId, currentCalendar);
      currentCalendars.add(currentCalendar);
    }

    // Keep the store up-to-date with the calendars on the remove device.
    SetView<String> removedCalendarKeys =
        difference(keys(previousCalendars), keys(currentCalendars));
    for (String removedCalendarKey : removedCalendarKeys) {
      calendarStore.remove(state.deviceId, removedCalendarKey);
    }

    ImmutableSet<Calendar> calendarUpdateMessages;
    if (isUpdatable(state)) {
      logger.debug("Creating calendar update messages for device %s", state.deviceId);
      calendarUpdateMessages = manager.createUpdateMessages(previousCalendars, currentCalendars);
    } else {
      logger.debug("Creating calendar replace messages for device %s", state.deviceId);
      calendarUpdateMessages = manager.createReplaceMessages(previousCalendars, currentCalendars);
    }

    if (calendarUpdateMessages.isEmpty()) {
      logger.debug("Not sending empty update");
      return;
    }

    UpdateCalendars update =
        newUpdateBuilder(UpdateCalendars.Type.RECEIVE)
            .addAllCalendars(calendarUpdateMessages)
            .build();
    send(update, state.deviceId);
  }

  /** Creates an {@link UpdateCalendars.Builder} builder with the common required fields. */
  protected UpdateCalendars.Builder newUpdateBuilder(UpdateCalendars.Type type) {
    return UpdateCalendars.newBuilder().setVersion(PROTOCOL_VERSION).setType(type);
  }

  /** Send the {@code update} to the given remote device. */
  protected void send(UpdateCalendars update, String deviceId) {
    byte[] message = update.toByteArray();
    logger.debug(
        "Sending %s with %d calendar and size %s for device %s",
        update.getType().name(), update.getCalendarsCount(), message.length, deviceId);
    sender.send(deviceId, message);
  }

  private Set<String> keys(Set<Calendar> calendars) {
    return calendars.stream().map(Calendar::getKey).collect(Collectors.toSet());
  }

  /** Ensures the client of this object has called start. */
  protected void throwIfNotStarted() {
    if (calendarsObservationHandle == null) {
      throw new IllegalStateException("Must call start first");
    }
  }

  /** A holder for non-persistent state related to a remote device. */
  protected static final class DeviceState {

    /** The unique identifier of the remote device this instance represents. */
    private final String deviceId;

    /** The calendar keys to sync with their event time windows. */
    private final Map<String, Range<Instant>> calendarKeyToTimeRange = new HashMap<>();

    /**
     * The protocol version used by this remove device.
     *
     * <p>Until a response is received from the remote device the version is assumed to be version 0
     * which does not support sending updates.
     */
    private int version = 0;

    private DeviceState(String deviceId) {
      this.deviceId = deviceId;
    }

    public String getDeviceId() {
      return deviceId;
    }

    public Set<String> getCalendarKeys() {
      return calendarKeyToTimeRange.keySet();
    }

    public void setCalendarTimeRanges(Set<String> calendarKeys, Range<Instant> range) {
      calendarKeyToTimeRange.clear();
      for (String calendarKey : calendarKeys) {
        setCalendarTimeRange(calendarKey, range);
      }
    }

    void setCalendarTimeRange(String key, Range<Instant> range) {
      calendarKeyToTimeRange.put(key, range);
    }
  }

  /** Returns {@code true} if the given device can receive calendar updates. */
  protected static boolean isUpdatable(DeviceState state) {
    return state.version >= UPDATABLE_VERSION;
  }

  /** Gets a {@link CalendarManager} for the given remote {@code deviceId}. */
  protected CalendarManager getCalendarManager(String deviceId) {
    DeviceState state = deviceIdToState.get(deviceId);
    return getCalendarManager(state);
  }

  /** Gets a {@link CalendarManager} for the given remote device {@code state}. */
  protected CalendarManager getCalendarManager(DeviceState state) {
    return calendarManagerFactory.create(calendarStore, state.calendarKeyToTimeRange);
  }
}
