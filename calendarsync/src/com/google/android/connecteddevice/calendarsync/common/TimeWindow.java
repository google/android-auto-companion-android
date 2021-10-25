package com.google.android.connecteddevice.calendarsync.common;

import static java.time.temporal.ChronoUnit.DAYS;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * A time range for calendar events to include when reading from a calendar "source".
 *
 * <p>When calendars are synchronized, only a subset of events are sent to the remote device to
 * avoid slow data transfers and storage operations (e.g. reads or writes to the calendar provider).
 *
 * <p>This class additionally holds the time at which the event data should be refreshed. For
 * example, perhaps the time range covers 7 days from the beginning of today and should be refreshed
 * at the end of today.
 *
 * <p>Note that calendar applications that depend on this data expect complete days of data to
 * handle all-day events correctly. An all-day event is stored as a 24 hour event in the UTC time
 * zone so to ensure the event is seen in any time zone, at least part of the current day in UTC
 * should be included in the time range. Reading the full current day is one way to ensure this is
 * fulfilled.
 */
@AutoValue
public abstract class TimeWindow {

  static TimeWindow create(Instant from, Instant to, Instant refresh) {
    Preconditions.checkArgument(from.isBefore(refresh) && refresh.isBefore(to));
    return new AutoValue_TimeWindow(from, to, refresh);
  }

  /** The begin time of the window, inclusive of the returned instant. */
  abstract Instant getFrom();

  /** The end time of the window, exclusive of the returned instant. */
  abstract Instant getTo();

  /** The time to refresh the current time window and get a new time window. */
  abstract Instant getRefresh();

  /** Creates a {@link Range<Instant>} using {@link #getFrom()} and {@link #getTo()}. */
  public Range<Instant> toInstantRange() {
    return Range.openClosed(getFrom(), getTo());
  }

  /**
   * Creates a TimeWindow that starts at the beginning of the given day and ends a given number of
   * days later. The refresh time will be the end of the given day.
   */
  // Suppress use of parameter "days" as it does not make sense to pass a Duration here.
  @SuppressWarnings("GoodTime")
  public static TimeWindow wholeDayTimeWindow(ZonedDateTime time, int days) {
    Instant from = time.truncatedTo(DAYS).toInstant();
    Instant to = from.plus(days, DAYS);
    Instant refresh = from.plus(1, DAYS);
    return TimeWindow.create(from, to, refresh);
  }

  /**
   * Creates a supplier of {@link TimeWindow} that uses a clock to call {@code wholeDayTimeWindow}.
   */
  // Suppress use of parameter "days" as it does not make sense to pass a Duration here.
  @SuppressWarnings("GoodTime")
  public static Supplier<TimeWindow> wholeDayTimeWindowSupplier(Clock clock, int days) {
    return () -> wholeDayTimeWindow(ZonedDateTime.now(clock), days);
  }
}
