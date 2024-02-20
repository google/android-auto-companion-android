package com.google.android.connecteddevice.calendarsync.common;

import com.google.android.connecteddevice.calendarsync.UpdateCalendars;
import com.google.android.connecteddevice.calendarsync.common.Scheduler.ScheduledTaskHandle;
import com.google.common.collect.Range;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Sends calendar data to a remote device in a defined time range and receives back changes.
 *
 * <p>This class will be used on the iOS phones and Android phones and the car will use {@link
 * ReplicaCalendarSync}.
 *
 * <p>If the remote device is not a recent version that supports sending updates then no messages
 * will be received and this device will only send full calendars (not updates).
 *
 * <p>{@inheritDoc}
 */
public class SourceCalendarSync extends BaseCalendarSync {
  private static final String TAG = "SourceCalendarSync";
  private final Supplier<TimeWindow> timeWindowSupplier;
  @MonotonicNonNull // Initialized through start() method.
  private ScheduledTaskHandle scheduledTaskHandle;

  @Inject
  SourceCalendarSync(
      CommonLogger.Factory commonLoggerFactory,
      RemoteSender sender,
      CalendarStore store,
      CalendarsObservable calendarsObservable,
      CalendarManagerFactory calendarManagerFactory,
      Supplier<TimeWindow> timeWindowSupplier,
      Scheduler scheduler) {
    super(
        commonLoggerFactory.create(TAG),
        sender,
        store,
        scheduler,
        calendarsObservable,
        calendarManagerFactory);
    this.timeWindowSupplier = timeWindowSupplier;
  }

  /**
   * Sets the ids of local calendars to synchronize to the given remote device.
   *
   * <p>Any changes to the previous set of synchronized calendars are sent to the remote device.
   */
  public void sync(String deviceId, Set<String> calendarKeys) {
    throwIfNotStarted();
    DeviceState state = getOrCreateState(deviceId);
    Range<Instant> range = timeWindowSupplier.get().toInstantRange();
    state.setCalendarTimeRanges(calendarKeys, range);
    sendDeviceChangeUpdate(state);
  }

  @Override
  protected void handleDisableMessage(String deviceId) {
    throw new UnsupportedOperationException("Source device cannot be remotely disabled");
  }

  @Override
  public void start() {
    super.start();
    scheduleNextUpdate();
  }

  private void scheduleNextUpdate() {
    TimeWindow window = timeWindowSupplier.get();
    scheduledTaskHandle = scheduler.schedule(window.getRefresh(), this::updateAndScheduleNext);
  }

  private void updateAndScheduleNext() {
    Range<Instant> range = timeWindowSupplier.get().toInstantRange();
    logger.info("Scheduled update to time range %s", range);

    // Update all time ranges before sending updates.
    for (DeviceState state : deviceIdToState.values()) {
      state.setCalendarTimeRanges(state.getCalendarKeys(), range);
    }
    sendAllChangeUpdates();
    scheduleNextUpdate();
  }

  /**
   * Clears state for the remote device and also sends a message to the remote device to remove its
   * state for this device.
   */
  public void disable(String deviceId) {
    logger.debug("Disable device " + deviceId);
    clear(deviceId);

    // Send a message to the remote replica device to remove all stored data for this device.
    send(newUpdateBuilder(UpdateCalendars.Type.DISABLE).build(), deviceId);
  }

  @Override
  public void stop() {
    super.stop();
    if (scheduledTaskHandle != null) {
      scheduledTaskHandle.cancel();
    }
  }
}
