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

import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.CalendarContract.Calendars;
import com.google.android.connecteddevice.calendarsync.Calendar;
import java.time.Instant;

/** Convert the contract provided by {@code Calendars} into the proto object {@code Calendar}. */
final class CalendarConverter extends BaseConverter<Calendar> {

  private final EventRepository eventRepository;
  private final Instant startInstant;
  private final Instant endInstant;

  CalendarConverter(EventRepository eventRepository, Instant startInstant, Instant endInstant) {
    this.eventRepository = eventRepository;
    this.startInstant = startInstant;
    this.endInstant = endInstant;
  }

  @Override
  Uri getUri() {
    return Calendars.CONTENT_URI;
  }

  @Override
  String[] getColumnNames() {
    return new String[] {
      BaseColumns._ID, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR, Calendars.NAME
    };
  }

  @Override
  Calendar convert(Cursor cursor) {
    long calendarId = getLong(cursor, BaseColumns._ID);
    Calendar.Builder calendar = Calendar.newBuilder();
    calendar
        .setKey(String.valueOf(calendarId))
        .setAccountName(getString(cursor, Calendars.ACCOUNT_NAME))
        .setTitle(getString(cursor, Calendars.NAME))
        .addAllEvents(eventRepository.getEvents(calendarId, startInstant, endInstant));

    if (isNonNull(cursor, Calendars.CALENDAR_COLOR)) {
      calendar.setColor(toProtoColor(getInt(cursor, Calendars.CALENDAR_COLOR)));
    }

    return calendar.build();
  }
}
