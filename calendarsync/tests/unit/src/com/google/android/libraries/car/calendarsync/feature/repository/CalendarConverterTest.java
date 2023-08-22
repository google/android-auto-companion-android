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

import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.EMPTY;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.TEST_CALENDAR_NAME;
import static com.google.android.libraries.car.calendarsync.feature.repository.Constants.TEST_EMAIL;
import static com.google.common.truth.Truth.assertThat;
import static java.time.Instant.EPOCH;
import static java.time.Instant.ofEpochMilli;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.android.connecteddevice.calendarsync.Calendar;
import com.google.android.libraries.car.calendarsync.util.FakeCursor;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests the {@code CalendarConverter} by ensuring nulls, empty strings and negative numbers are
 * handled.
 */
@RunWith(JUnit4.class)
public class CalendarConverterTest {

  private FakeCursor calendarCursor;
  private CalendarConverter calendarConverter;

  @Before
  public void setUp() {
    calendarConverter =
        new CalendarConverter(new MockEventRepository(), EPOCH, ofEpochMilli(Long.MAX_VALUE));
    calendarCursor = new FakeCursor();
    calendarCursor.setColumnNames(Arrays.asList(calendarConverter.getColumnNames()));
  }

  @Test
  public void demoCalendarCursor_valuesCorrectInProto() {
    Object[] calendarValid = new Object[] {1L, TEST_EMAIL, 0, TEST_CALENDAR_NAME};
    calendarCursor.setResults(new Object[][] {calendarValid});
    calendarCursor.moveToNext();
    Calendar calendar = calendarConverter.convert(calendarCursor);
    assertEquals(calendarValid[0], Long.parseLong(calendar.getKey()));
    assertEquals(calendarValid[1], calendar.getAccountName());
    assertEquals(calendarValid[2], calendar.getColor().getArgb());
    assertEquals(calendarValid[3], calendar.getTitle());
  }

  @Test
  public void nullString_isEmptyString() {
    Object[] calendarNulls = new Object[] {3L, null, 0, null};
    assertStringsNotNull(calendarNulls);
  }

  @Test
  public void nullColor_hasNoColor() {
    Object[] calendarNulls = new Object[] {3L, TEST_EMAIL, null, TEST_CALENDAR_NAME};
    calendarCursor.setResults(new Object[][] {calendarNulls});
    calendarCursor.moveToNext();
    Calendar calendar = calendarConverter.convert(calendarCursor);
    assertFalse(calendar.hasColor());
  }

  @Test
  public void emptyString_isEmptyString() {
    Object[] calendarEmpty = new Object[] {2L, EMPTY, 1, EMPTY};
    assertStringsNotNull(calendarEmpty);
  }

  @Test
  public void negativeLong_isNegative() {
    Object[] calendarNegatives = new Object[] {-1L, null, -1, null};
    calendarCursor.setResults(new Object[][] {calendarNegatives});
    calendarCursor.moveToNext();
    Calendar calendar = calendarConverter.convert(calendarCursor);
    assertEquals(calendarNegatives[0], Long.parseLong(calendar.getKey()));
    assertEquals(calendarNegatives[2], calendar.getColor().getArgb());
  }

  private void assertStringsNotNull(Object[] calendarData) {
    calendarCursor.setResults(new Object[][] {calendarData});
    calendarCursor.moveToNext();
    Calendar calendar = calendarConverter.convert(calendarCursor);
    assertThat(calendar.getTitle()).isEmpty();
    assertThat(calendar.getAccountName()).isEmpty();
  }
}
