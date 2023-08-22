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

package com.google.android.libraries.car.calendarsync.feature.repository;

import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.ALL_DAY_FALSE;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.ALL_DAY_TRUE;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.ARGB_COLOR;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.BERLIN_TIMEZONE;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.DESCRIPTION;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.END_DATE_SECONDS;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.GMT;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.INVALID_TIMEZONE;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.LOCATION;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.ORGANIZER;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.START_DATE_SECONDS;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.TITLE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.provider.CalendarContract.Instances;
import com.google.android.connecteddevice.calendarsync.Event;
import com.google.android.connecteddevice.calendarsync.Event.Status;
import com.google.android.libraries.car.calendarsync.util.FakeCursor;
import java.time.Instant;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests the {@code EventConverter} by ensuring valid & invalid timezones, statuses, are handled.
 */
@RunWith(JUnit4.class)
public class EventConverterTest {

  private Object[] demoEvent;
  private FakeCursor eventCursor;
  private EventConverter eventConverter;

  @Before
  public void setUp() {
    eventConverter =
        new EventConverter(
            new MockAttendeeRepository(), Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE));
    eventCursor = new FakeCursor();
    eventCursor.setColumnNames(Arrays.asList(eventConverter.getColumnNames()));
    demoEvent =
        new Object[] {
          1L,
          ALL_DAY_FALSE,
          SECONDS.toMillis(START_DATE_SECONDS),
          BERLIN_TIMEZONE,
          DESCRIPTION,
          SECONDS.toMillis(END_DATE_SECONDS),
          ARGB_COLOR,
          BERLIN_TIMEZONE,
          LOCATION,
          ORGANIZER,
          Instances.STATUS_TENTATIVE,
          TITLE,
          1L
        };
  }

  @Test
  public void demoEventCursor_valuesCorrectInProto() {
    Event event = getEventFromCursorObject(demoEvent);
    assertEquals(1L, Long.parseLong(event.getKey()));
    assertFalse(event.getIsAllDay());
    assertEquals(START_DATE_SECONDS, event.getBeginTime().getSeconds());
    assertEquals(BERLIN_TIMEZONE, event.getTimeZone().getName());
    assertEquals(DESCRIPTION, event.getDescription());
    assertEquals(END_DATE_SECONDS, event.getEndTime().getSeconds());
    assertEquals(ARGB_COLOR, event.getColor().getArgb());
    assertEquals(BERLIN_TIMEZONE, event.getEndTimeZone().getName());
    assertEquals(LOCATION, event.getLocation());
    assertEquals(ORGANIZER, event.getOrganizer());
    assertEquals(Status.TENTATIVE, event.getStatus());
    assertEquals(TITLE, event.getTitle());
  }

  @Test
  public void nullTimeZone_setToGmt() {
    demoEvent[3] = null;
    Event event = getEventFromCursorObject(demoEvent);
    assertEquals(GMT, event.getTimeZone().getName());
  }

  /** Fallback to GMT when a timezone of unknown/wrong ID is passed. */
  @Test
  public void invalidTimeZone_setToGmt() {
    demoEvent[3] = INVALID_TIMEZONE;
    Event event = getEventFromCursorObject(demoEvent);
    assertEquals(GMT, event.getTimeZone().getName());
  }

  /** Set Europe/Berlin time and match for either summer or winter time offset from GMT. */
  @Test
  public void berlinTimeZone_correctOffsetFromGmt() {
    demoEvent[3] = BERLIN_TIMEZONE;
    Event event = getEventFromCursorObject(demoEvent);
    assertEquals(demoEvent[3], event.getTimeZone().getName());
  }

  @Test
  public void invalidStatus_setUnspecifiedStatus() {
    demoEvent[10] = -1;
    Event event = getEventFromCursorObject(demoEvent);
    assertThat(event.getStatus(), anyOf(is(Status.UNSPECIFIED_STATUS), is(Status.UNRECOGNIZED)));
  }

  /** The cursor object stores booleans as ints underneath. Ensure 0 is false. */
  @Test
  public void allDayStatusFalse_setFalse() {
    demoEvent[1] = ALL_DAY_FALSE;
    Event event = getEventFromCursorObject(demoEvent);
    assertFalse(event.getIsAllDay());
  }

  /** The cursor object stores booleans as ints underneath. Ensure 1 is true. */
  @Test
  public void testAllDayStatusTrue_setTrue() {
    demoEvent[1] = ALL_DAY_TRUE;
    Event event = getEventFromCursorObject(demoEvent);
    assertTrue(event.getIsAllDay());
  }

  private Event getEventFromCursorObject(Object[] event) {
    eventCursor.setResults(new Object[][] {event});
    eventCursor.moveToNext();
    return eventConverter.convert(eventCursor);
  }
}
