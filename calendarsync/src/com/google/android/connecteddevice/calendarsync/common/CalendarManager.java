package com.google.android.connecteddevice.calendarsync.common;

import static com.google.android.connecteddevice.calendarsync.common.TimeProtoUtil.toInstantRange;
import static com.google.android.connecteddevice.calendarsync.common.TimeProtoUtil.toTimeRange;

import com.google.android.connecteddevice.calendarsync.Calendar;
import com.google.android.connecteddevice.calendarsync.Event;
import com.google.android.connecteddevice.calendarsync.UpdateAction;
import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Manage the {@link Calendar} relationship with {@link Event}s.
 *
 * <p>{@inheritDoc}
 */
class CalendarManager extends ContentManager<Calendar, Calendar.Builder, Event, Event.Builder> {
  private static final String TAG = "CalendarManager";

  private final EventManagerFactory eventManagerFactory;
  private final CalendarStore calendarStore;
  private final Map<String, Range<Instant>> calendarKeyToTimeRange;

  @AutoFactory(allowSubclasses = true)
  CalendarManager(
      @Provided CommonLogger.Factory commonLoggerFactory,
      @Provided PlatformContentDelegate<Calendar> calendarContentDelegate,
      @Provided EventManagerFactory eventManagerFactory,
      CalendarStore calendarStore,
      Map<String, Range<Instant>> calendarKeyToTimeRange) {
    super(commonLoggerFactory.create(TAG), calendarContentDelegate);
    this.eventManagerFactory = eventManagerFactory;
    this.calendarStore = calendarStore;
    this.calendarKeyToTimeRange = calendarKeyToTimeRange;
  }

  @Override
  protected String key(Calendar calendar) {
    return calendar.getKey();
  }

  @Override
  protected List<Event> children(Calendar message) {
    return message.getEventsList();
  }

  @Override
  protected void addChildren(Calendar.Builder builder, Iterable<Event> children) {
    builder.addAllEvents(children);
  }

  @Override
  protected Calendar createReplaceMessage(Calendar current) {
    return current.toBuilder().setAction(UpdateAction.REPLACE).build();
  }

  @Override
  protected void replace(Object parentId, Calendar calendar) {
    delete(parentId, calendar.getKey());

    // Legacy source devices send an empty calendar to signal deletion.
    if (calendar.getEventsCount() > 0) {
      create(parentId, calendar);
    }
  }

  @Nullable
  @Override
  protected ContentManager<Event, Event.Builder, ?, ?> getChildManager(String key) {
    return eventManagerFactory.create(calendarKeyToTimeRange.get(key));
  }

  @Override
  protected void setAction(Calendar.Builder builder, UpdateAction action) {
    builder.setAction(action);
  }

  @Override
  protected void setKey(Calendar.Builder builder, String key) {
    builder.setKey(key);
  }

  @Override
  protected UpdateAction action(Calendar action) {
    return action.getAction();
  }

  @Override
  protected Calendar.Builder newMessageBuilder() {
    return Calendar.newBuilder();
  }

  @Override
  protected void clearChildren(Calendar.Builder builder) {
    builder.clearEvents();
  }

  @Override
  protected void onCreateBuilder(Calendar.Builder builder) {
    // Add the time range to every calendar that is read.
    String calendarKey = builder.getKey();
    Range<Instant> range = calendarKeyToTimeRange.get(calendarKey);
    if (range == null) {
      throw new IllegalStateException("Missing range for calendar: " + calendarKey);
    }
    builder.setRange(toTimeRange(range));
  }

  @Override
  public void create(Object parentId, Calendar content) {
    logger.debug("Create calendar %s", content.getKey());
    updateTimeRange(content);
    super.create(parentId, content);
    calendarStore.store((String) parentId, content);
  }

  @Override
  protected void update(Object parentId, String key, Calendar update) {
    logger.debug("Update calendar %s", key);
    updateTimeRange(update);
    super.update(parentId, key, update);
  }

  @Override
  protected void unchanged(Object parentId, Calendar update) {
    logger.debug("Unchanged calendar %s", update.getKey());
    updateTimeRange(update);
    super.unchanged(parentId, update);
  }

  @Override
  protected void onContentUpdated(Object parentId, Calendar update) {
    // Must read the calendar to capture the updates applied.
    Calendar calendar = read(parentId, update.getKey());
    if (calendar == null) {
      logger.info(
          "No calendar with parentId %s and update key %s. Ignored.", parentId, update.getKey());
      return;
    }
    calendarStore.store((String) parentId, calendar);
  }

  /**
   * Sets the time range specified in the {@code calendar}.
   *
   * <p>If the calendar does not have a time range an exception is thrown.
   */
  private void updateTimeRange(Calendar calendar) {
    Range<Instant> timeRange = null;
    if (calendar.hasRange()) {
      timeRange = toInstantRange(calendar.getRange());
    }
    if (timeRange == null) {
      throw new IllegalStateException("Calendar does not have range: " + calendar.getKey());
    }
    calendarKeyToTimeRange.put(calendar.getKey(), timeRange);
  }

  @Override
  public void delete(Object parentId, String key) {
    logger.debug("Delete calendar %s", key);
    super.delete(parentId, key);
    calendarKeyToTimeRange.remove(key);
    calendarStore.remove((String) parentId, key);
  }

  /** Creates REPLACE messages for older replica devices that to not support UPDATE. */
  public ImmutableSet<Calendar> createReplaceMessages(
      Collection<Calendar> previousCalendars, Collection<Calendar> currentCalendars) {
    return createReplaceOrUpdateMessages(previousCalendars, currentCalendars, /* update= */ false);
  }
}
