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

import android.content.ContentResolver;
import android.provider.CalendarContract.Instances;
import com.google.android.connecteddevice.calendarsync.Event;
import com.google.common.collect.ImmutableList;
import java.time.Instant;

/**
 * Provides Event and Attendee information. Queries the content provider and parses the result into
 * the Event proto.
 */
class EventRepository extends BaseRepository {

  private static final String EVENT_SELECTION = String.format("(%s = ?)", Instances.CALENDAR_ID);
  private final AttendeeRepository attendeeRepository;

  EventRepository(ContentResolver contentResolver) {
    super(contentResolver);
    attendeeRepository = new AttendeeRepository(contentResolver);
  }

  ImmutableList<Event> getEvents(long calendarId, Instant startInstant, Instant endInstant) {
    String[] selectionArgs = new String[] {String.valueOf(calendarId)};
    return getItems(
        new EventConverter(attendeeRepository, startInstant, endInstant),
        EVENT_SELECTION,
        selectionArgs);
  }
}
