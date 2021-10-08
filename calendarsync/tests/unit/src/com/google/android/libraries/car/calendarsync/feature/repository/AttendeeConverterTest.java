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

import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.TEST_EMAIL;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.TEST_NAME;
import static junit.framework.TestCase.assertEquals;

import android.provider.CalendarContract.Attendees;
import com.google.android.connecteddevice.calendarsync.proto.Attendee;
import com.google.android.connecteddevice.calendarsync.proto.Attendee.Status;
import com.google.android.libraries.car.calendarsync.util.FakeCursor;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests the {@code AttendeeConverter} by ensuring invalid Attendee Types and Statuses are handled.
 */
@RunWith(JUnit4.class)
public class AttendeeConverterTest {

  private Object[] demoAttendee;
  private FakeCursor attendeeCursor;
  private AttendeeConverter attendeeConverter;

  @Before
  public void setUp() {
    attendeeConverter = new AttendeeConverter();
    attendeeCursor = new FakeCursor();
    attendeeCursor.setColumnNames(Arrays.asList(attendeeConverter.getColumnNames()));
    demoAttendee =
        new Object[] {
          TEST_EMAIL, TEST_NAME, Attendees.ATTENDEE_STATUS_TENTATIVE, Attendees.TYPE_OPTIONAL
        };
  }

  @Test
  public void demoAttendeeCursor_valuesCorrectInProto() {
    Attendee attendee = getAttendeeFromCursor(demoAttendee);
    assertEquals(TEST_EMAIL, attendee.getEmail());
    assertEquals(TEST_NAME, attendee.getName());
    assertEquals(Attendee.Type.OPTIONAL, attendee.getType());
    assertEquals(Status.TENTATIVE, attendee.getStatus());
  }

  @Test
  public void invalidStatus_setUnspecifiedStatus() {
    demoAttendee[2] = -1;
    Attendee attendee = getAttendeeFromCursor(demoAttendee);
    assertEquals(Status.UNSPECIFIED_STATUS, attendee.getStatus());
  }

  @Test
  public void invalidType_setUnspecifiedType() {
    demoAttendee[3] = -1;
    Attendee attendee = getAttendeeFromCursor(demoAttendee);
    assertEquals(Attendee.Type.UNSPECIFIED_TYPE, attendee.getType());
  }

  private Attendee getAttendeeFromCursor(Object[] attendee) {
    attendeeCursor.setResults(new Object[][] {attendee});
    attendeeCursor.moveToNext();
    return attendeeConverter.convert(attendeeCursor);
  }
}
