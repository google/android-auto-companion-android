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

package com.google.android.connecteddevice.calendarsync.android;

import android.content.ContentResolver;
import android.provider.CalendarContract.Attendees;
import com.google.android.connecteddevice.calendarsync.Attendee;
import com.google.android.connecteddevice.calendarsync.Attendee.Status;
import com.google.android.connecteddevice.calendarsync.common.CommonLogger;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.MessageLite;

/** Android specific access to the attendee content items. */
final class AttendeeContentDelegate extends BaseContentDelegate<Attendee> {
  private static final String TAG = "AttendeeContentDelegate";

  AttendeeContentDelegate(CommonLogger.Factory commonLoggerFactory, ContentResolver resolver) {
    super(
        commonLoggerFactory.create(TAG),
        Attendees.CONTENT_URI,
        ATTENDEE_EMAIL,
        FIELDS,
        resolver,
        /* idColumn= */ Attendees._ID,
        /* parentIdColumn= */ Attendees.EVENT_ID);
  }

  @Override
  protected MessageLite.Builder createMessageBuilder() {
    return Attendee.newBuilder();
  }

  private static final FieldTranslator<String> ATTENDEE_EMAIL =
      FieldTranslator.createStringField(
          Attendees.ATTENDEE_EMAIL, Attendee::getEmail, Attendee.Builder::setEmail);

  private static final ImmutableSet<FieldTranslator<?>> FIELDS =
      ImmutableSet.of(
          ATTENDEE_EMAIL,
          FieldTranslator.createStringField(
              Attendees.ATTENDEE_NAME, Attendee::getName, Attendee.Builder::setName),
          FieldTranslator.createIntegerField(
              Attendees.ATTENDEE_STATUS,
              (Attendee message) -> statusToInt(message.getStatus()),
              (Attendee.Builder builder, Integer value) -> builder.setStatus(intToStatus(value))),
          FieldTranslator.createIntegerField(
              Attendees.ATTENDEE_TYPE,
              (Attendee message) -> typeToInt(message.getType()),
              (Attendee.Builder builder, Integer value) -> builder.setType(intToType(value))));

  private static Status intToStatus(int status) {
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

  private static int statusToInt(Status status) {
    switch (status) {
      case ACCEPTED:
        return Attendees.ATTENDEE_STATUS_ACCEPTED;
      case DECLINED:
        return Attendees.ATTENDEE_STATUS_DECLINED;
      case INVITED:
        return Attendees.ATTENDEE_STATUS_INVITED;
      case TENTATIVE:
        return Attendees.ATTENDEE_STATUS_TENTATIVE;
      default:
        return Attendees.ATTENDEE_STATUS_NONE;
    }
  }

  private static Attendee.Type intToType(int type) {
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

  private static int typeToInt(Attendee.Type type) {
    switch (type) {
      case OPTIONAL:
        return Attendees.TYPE_OPTIONAL;
      case REQUIRED:
        return Attendees.TYPE_REQUIRED;
      case RESOURCE:
        return Attendees.TYPE_RESOURCE;
      default:
        return Attendees.TYPE_NONE;
    }
  }
}
