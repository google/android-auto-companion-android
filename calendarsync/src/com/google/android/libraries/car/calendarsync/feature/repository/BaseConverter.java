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

import android.database.Cursor;
import android.net.Uri;
import androidx.annotation.Nullable;
import com.google.android.connecteddevice.calendarsync.Color;
import com.google.android.connecteddevice.calendarsync.TimeZone;
import com.google.android.connecteddevice.calendarsync.Timestamp;

/** A Base class for convert the contract provided by Android's {@code cursor} into proto object. */
abstract class BaseConverter<T> {

  abstract Uri getUri();

  abstract String[] getColumnNames();

  abstract T convert(Cursor cursor);

  static boolean isNonNull(Cursor cursor, String columnName) {
    int column = cursor.getColumnIndex(columnName);
    return column >= 0 && !cursor.isNull(column);
  }

  static int getInt(Cursor cursor, String columnName) {
    int column = cursor.getColumnIndex(columnName);
    return cursor.isNull(column) ? 0 : cursor.getInt(column);
  }

  /** @return empty string if the value of the requested column is not found or is null. */
  static String getString(Cursor cursor, String columnName) {
    String value = cursor.getString(cursor.getColumnIndex(columnName));
    return value == null ? "" : value;
  }

  static long getLong(Cursor cursor, String columnName) {
    int column = cursor.getColumnIndex(columnName);
    return cursor.isNull(column) ? 0 : cursor.getLong(column);
  }

  /**
   * @return false for 0, true for 1. This method is required because the logical type is boolean,
   *     but the physical type is int.
   */
  static boolean getBoolean(Cursor cursor, String columnName) {
    int column = cursor.getColumnIndex(columnName);
    return !cursor.isNull(column) && cursor.getInt(column) != 0;
  }

  static Timestamp toProtoTimestamp(long millis) {
    return Timestamp.newBuilder().setSeconds(MILLISECONDS.toSeconds(millis)).build();
  }

  static Color toProtoColor(@Nullable Integer argb) {
    return argb == null ? null : Color.newBuilder().setArgb(argb).build();
  }

  /**
   * @param id TimeZone ID - defaults to the device's current TimeZone if null is passed.
   * @return a {@code TimeZone} object containing the seconds offset from GMT.
   */
  static TimeZone toProtoTimeZone(@Nullable String id) {
    if (id == null) {
      id = java.util.TimeZone.getDefault().getID();
    }
    java.util.TimeZone timeZone = java.util.TimeZone.getTimeZone(id);
    return TimeZone.newBuilder().setName(timeZone.getID()).build();
  }
}
