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

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.CalendarContract.Instances;
import com.google.android.connecteddevice.calendarsync.proto.Event;
import com.google.android.connecteddevice.calendarsync.proto.Event.Status;
import java.time.Instant;

/** Convert the contract provided by {@code Events} into the proto object {@code Event}. */
final class EventConverter extends BaseConverter<Event> {

  private final AttendeeRepository attendeeRepository;
  private final Instant startInstant;
  private final Instant endInstant;

  EventConverter(AttendeeRepository attendeeRepository, Instant startInstant, Instant endInstant) {
    this.attendeeRepository = attendeeRepository;
    this.startInstant = startInstant;
    this.endInstant = endInstant;
  }

  @Override
  Uri getUri() {
    Uri.Builder uriBuilder = Instances.CONTENT_URI.buildUpon();
    ContentUris.appendId(uriBuilder, startInstant.toEpochMilli());
    ContentUris.appendId(uriBuilder, endInstant.toEpochMilli());
    return uriBuilder.build();
  }

  @Override
  String[] getColumnNames() {
    return new String[] {
      BaseColumns._ID,
      Instances.ALL_DAY,
      Instances.BEGIN,
      Instances.CALENDAR_TIME_ZONE,
      Instances.DESCRIPTION,
      Instances.END,
      Instances.DISPLAY_COLOR,
      Instances.EVENT_END_TIMEZONE,
      Instances.EVENT_LOCATION,
      Instances.ORGANIZER,
      Instances.STATUS,
      Instances.TITLE,
      Instances.EVENT_ID
    };
  }

  @Override
  Event convert(Cursor cursor) {
    Event.Builder event = Event.newBuilder();
    event
        .setExternalIdentifier(String.valueOf(getLong(cursor, BaseColumns._ID)))
        .setIsAllDay(getBoolean(cursor, Instances.ALL_DAY))
        .setStartDate(toProtoTimestamp(getLong(cursor, Instances.BEGIN)))
        .setTimeZone(toProtoTimeZone(getString(cursor, Instances.CALENDAR_TIME_ZONE)))
        .setDescription(getString(cursor, Instances.DESCRIPTION))
        .setEndDate(toProtoTimestamp(getLong(cursor, Instances.END)))
        .setEndTimeZone(toProtoTimeZone(getString(cursor, Instances.EVENT_END_TIMEZONE)))
        .setLocation(getString(cursor, Instances.EVENT_LOCATION))
        .setOrganizer(getString(cursor, Instances.ORGANIZER))
        .setStatus(getStatus(getInt(cursor, Instances.STATUS)))
        .setTitle(getString(cursor, Instances.TITLE))
        .addAllAttendee(attendeeRepository.getAttendees(getLong(cursor, Instances.EVENT_ID)));

    if (isNonNull(cursor, Instances.DISPLAY_COLOR)) {
      event.setColor(toProtoColor(getInt(cursor, Instances.DISPLAY_COLOR)));
    }

    return event.build();
  }

  private static Event.Status getStatus(int status) {
    switch (status) {
      case Instances.STATUS_CANCELED:
        return Status.CANCELED;
      case Instances.STATUS_CONFIRMED:
        return Status.CONFIRMED;
      case Instances.STATUS_TENTATIVE:
        return Status.TENTATIVE;
      default:
        return Status.UNSPECIFIED_STATUS;
    }
  }
}
