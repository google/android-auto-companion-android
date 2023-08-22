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
import android.provider.CalendarContract.Attendees;
import com.google.android.connecteddevice.calendarsync.Attendee;
import com.google.common.collect.ImmutableList;

/**
 * Provides Attendee information. Queries the content provider and parses the result into the
 * Attendee proto.
 */
class AttendeeRepository extends BaseRepository {

  private static final String ATTENDEE_SELECTION = "(" + Attendees.EVENT_ID + " = ?)";
  private final AttendeeConverter attendeeConverter;

  AttendeeRepository(ContentResolver contentResolver) {
    super(contentResolver);
    attendeeConverter = new AttendeeConverter();
  }

  ImmutableList<Attendee> getAttendees(long eventId) {
    String[] selectionArgs = new String[] {String.valueOf(eventId)};
    return getItems(attendeeConverter, ATTENDEE_SELECTION, selectionArgs);
  }
}
