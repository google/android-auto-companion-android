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
import android.provider.CalendarContract.Attendees;
import com.google.android.connecteddevice.calendarsync.Attendee;
import com.google.android.connecteddevice.calendarsync.Attendee.Status;

/** Convert the contract provided by {@code Attendees} into the proto object {@code Attendee}. */
final class AttendeeConverter extends BaseConverter<Attendee> {

  @Override
  Uri getUri() {
    return Attendees.CONTENT_URI;
  }

  @Override
  String[] getColumnNames() {
    return new String[] {
      Attendees.ATTENDEE_EMAIL,
      Attendees.ATTENDEE_NAME,
      Attendees.ATTENDEE_STATUS,
      Attendees.ATTENDEE_TYPE
    };
  }

  @Override
  Attendee convert(Cursor cursor) {
    Attendee.Builder builder = Attendee.newBuilder();
    builder
        .setEmail(getString(cursor, Attendees.ATTENDEE_EMAIL))
        .setName(getString(cursor, Attendees.ATTENDEE_NAME))
        .setStatus(getAttendeeStatus(getInt(cursor, Attendees.ATTENDEE_STATUS)))
        .setType(getAttendeeType(getInt(cursor, Attendees.ATTENDEE_TYPE)));
    return builder.build();
  }

  private static Status getAttendeeStatus(int status) {
    switch (status) {
      case Attendees.ATTENDEE_STATUS_ACCEPTED:
        return Status.ACCEPTED;
      case Attendees.ATTENDEE_STATUS_DECLINED:
        return Status.DECLINED;
      case Attendees.ATTENDEE_STATUS_INVITED:
        return Status.INVITED;
      case Attendees.ATTENDEE_STATUS_TENTATIVE:
        return Status.TENTATIVE;
      case Attendees.ATTENDEE_STATUS_NONE:
        return Status.NONE_STATUS;
      default:
        return Status.UNSPECIFIED_STATUS;
    }
  }

  private static Attendee.Type getAttendeeType(int type) {
    switch (type) {
      case Attendees.TYPE_NONE:
        return Attendee.Type.NONE_TYPE;
      case Attendees.TYPE_OPTIONAL:
        return Attendee.Type.OPTIONAL;
      case Attendees.TYPE_REQUIRED:
        return Attendee.Type.REQUIRED;
      case Attendees.TYPE_RESOURCE:
        return Attendee.Type.RESOURCE;
      default:
        return Attendee.Type.UNSPECIFIED_TYPE;
    }
  }
}
