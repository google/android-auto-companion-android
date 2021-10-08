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
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.ARGB_COLOR;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.BERLIN_TIMEZONE;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.DESCRIPTION;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.END_DATE_SECONDS;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.LOCATION;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.ORGANIZER;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.START_DATE_SECONDS;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.TITLE;
import static com.google.common.truth.Truth.assertThat;
import static java.time.Instant.EPOCH;
import static java.time.Instant.ofEpochMilli;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Instances;
import com.google.android.connecteddevice.calendarsync.proto.Attendee;
import com.google.android.connecteddevice.calendarsync.proto.Calendars;
import com.google.android.connecteddevice.calendarsync.proto.Event;
import com.google.android.libraries.car.calendarsync.util.FakeCursor;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tests the CalendarProvider. Since we would only like to test whether our CalendarProvider API
 * works and returns a List or returns an object of the desired class, we pass in {@code any()}
 * query parameters to the {@code contentResolver.query()} call.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarProviderTest {

  private static final Object[][] calendarData =
      new Object[][] {
        new Object[] {1L, "test1@gmail.com", 1, "Calendar Name 1"},
        new Object[] {2L, "test2@gmail.com", 2, "Calendar Name 2"},
        new Object[] {3L, "test3@gmail.com", 3, "Calendar Name 3"}
      };

  private static final Object[][] eventData =
      new Object[][] {
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
        }
      };
  private static final Object[][] attendeeData =
      new Object[][] {
        new Object[] {
          "test1@email.com", "name1", Attendees.ATTENDEE_STATUS_TENTATIVE, Attendees.TYPE_OPTIONAL
        },
        new Object[] {
          "test2@email.com", "name2", Attendees.ATTENDEE_STATUS_ACCEPTED, Attendees.TYPE_REQUIRED
        }
      };

  private CalendarRepository calendarProvider;

  @Before
  public void setUp() {
    ContentResolver contentResolver = mock(ContentResolver.class);
    initializeContentResolverForCalendar(contentResolver);
    initializeContentResolverForEvents(contentResolver);
    initializeContentResolverForAttendees(contentResolver);
    calendarProvider = new CalendarRepository(contentResolver);
  }

  @Test
  public void totalNumberOfCalendars_equalToCalendarArrayLength() {
    Calendars calendars = calendarProvider.getCalendars(EPOCH, ofEpochMilli(Long.MAX_VALUE));
    assertThat(calendars.getCalendarList()).hasSize(calendarData.length);
  }

  @Test
  public void totalNumberOfEvents_equalToEventArrayLength() {
    List<Event> eventList =
        calendarProvider
            .getCalendars(EPOCH, ofEpochMilli(Long.MAX_VALUE))
            .getCalendarList()
            .get(0)
            .getEventList();
    assertThat(eventList).hasSize(eventData.length);
  }

  @Test
  public void totalNumberOfAttendees_equalToAttendeeArrayLength() {
    List<Attendee> attendeeList =
        calendarProvider
            .getCalendars(EPOCH, ofEpochMilli(Long.MAX_VALUE))
            .getCalendarList()
            .get(0)
            .getEventList()
            .get(0)
            .getAttendeeList();
    assertThat(attendeeList).hasSize(attendeeData.length);
  }

  @Test
  public void totalNumberOfEventsInCalendar_equalToDemoEventLength() {
    Calendars calendars = calendarProvider.getCalendars(EPOCH, ofEpochMilli(Long.MAX_VALUE));
    assertThat(calendars.getCalendarList()).hasSize(calendarData.length);
    assertThat(calendars.getCalendarList().get(0).getEventList()).hasSize(eventData.length);
  }

  @Test
  public void totalNumberOfAttendeesInEvents_equalToDemoAttendeeLength() {
    List<Event> eventList =
        calendarProvider
            .getCalendars(EPOCH, ofEpochMilli(Long.MAX_VALUE))
            .getCalendarList()
            .get(0)
            .getEventList();
    assertThat(eventList).hasSize(eventData.length);
    assertThat(eventList.get(0).getAttendeeList()).hasSize(attendeeData.length);
  }

  private static void initializeContentResolverForCalendar(ContentResolver contentResolver) {
    CalendarConverter calendarConverter =
        new CalendarConverter(new MockEventRepository(), EPOCH, ofEpochMilli(Long.MAX_VALUE));

    FakeCursor calendarCursor = new FakeCursor();
    calendarCursor.setColumnNames(Arrays.asList(calendarConverter.getColumnNames()));
    calendarCursor.setResults(calendarData);

    when(contentResolver.query(eq(calendarConverter.getUri()), any(), any(), any(), any()))
        .thenReturn(calendarCursor);
  }

  private static void initializeContentResolverForEvents(ContentResolver contentResolver) {
    EventConverter eventConverter =
        new EventConverter(new MockAttendeeRepository(), EPOCH, ofEpochMilli(Long.MAX_VALUE));

    FakeCursor eventCursor = new FakeCursor();
    eventCursor.setColumnNames(Arrays.asList(eventConverter.getColumnNames()));
    eventCursor.setResults(eventData);

    when(contentResolver.query(eq(eventConverter.getUri()), any(), any(), any(), any()))
        .thenReturn(eventCursor);
  }

  private static void initializeContentResolverForAttendees(ContentResolver contentResolver) {
    AttendeeConverter attendeeConverter = new AttendeeConverter();

    FakeCursor attendeeCursor = new FakeCursor();
    attendeeCursor.setColumnNames(Arrays.asList(attendeeConverter.getColumnNames()));
    attendeeCursor.setResults(attendeeData);

    when(contentResolver.query(eq(attendeeConverter.getUri()), any(), any(), any(), any()))
        .thenReturn(attendeeCursor);
  }
}
