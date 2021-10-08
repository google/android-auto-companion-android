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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.ContentResolver;
import android.provider.CalendarContract;
import androidx.annotation.Nullable;
import com.google.android.connecteddevice.calendarsync.proto.Calendar;
import com.google.android.connecteddevice.calendarsync.proto.Calendars;
import com.google.android.connecteddevice.calendarsync.proto.TimeZone;
import com.google.common.collect.ImmutableList;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Provides Calendar, Event and Attendee information. It is named Repository because it will
 * provider CRUD operations in the future. For now, it providers only the read functionality.
 */
public class CalendarRepository extends BaseRepository {

  private final EventRepository eventRepository;

  public CalendarRepository(ContentResolver contentResolver) {
    super(contentResolver);
    this.eventRepository = new EventRepository(contentResolver);
  }

  public Calendars getCalendars(Instant startInstant, Instant endInstant) {
    return getCalendars(/* calendarIds= */ null, startInstant, endInstant);
  }

  public Calendars getCalendars(
      @Nullable Set<Integer> calendarIds, Instant startInstant, Instant endInstant) {
    if (calendarIds == null || calendarIds.isEmpty()) {
      return toCalendars(
          getItems(new CalendarConverter(eventRepository, startInstant, endInstant)));
    }

    String selection =
        String.format(
            "%s in (%s)",
            CalendarContract.Calendars._ID,
            Collections.nCopies(calendarIds.size(), "?").stream().collect(Collectors.joining(",")));
    String[] selectionArgs = calendarIds.stream().map(String::valueOf).toArray(String[]::new);

    return toCalendars(
        getItems(
            new CalendarConverter(eventRepository, startInstant, endInstant),
            selection,
            selectionArgs));
  }

  private static Calendars toCalendars(ImmutableList<Calendar> calendarList) {
    Calendars.Builder builder = Calendars.newBuilder();
    builder.addAllCalendar(calendarList);
    builder.setDeviceTimeZone(getDeviceTimezone());
    return builder.build();
  }

  private static TimeZone getDeviceTimezone() {
    String timezoneId = java.util.TimeZone.getDefault().getID();
    java.util.Calendar gmt =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT"), Locale.getDefault());
    java.util.TimeZone timeZone = java.util.TimeZone.getTimeZone(timezoneId);
    return TimeZone.newBuilder()
        .setName(timeZone.getID())
        .setSecondsFromGmt(MILLISECONDS.toSeconds(timeZone.getOffset(gmt.getTimeInMillis())))
        .build();
  }
}
