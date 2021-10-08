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

package com.google.android.libraries.car.calendarsync.util;

import android.content.ContentResolver;
import android.database.CharArrayBuffer;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/**
 * A copy of Robolectric's implementation of {@link android.database.Cursor}. It is a simple
 * dictionary using an Object[][] array.
 *
 * <p>This was copied in to this repository to minimize dependency on Robolectric as it is in the
 * process of being deprecated.
 */
public class FakeCursor implements Cursor {
  public Uri uri;
  public String[] projection;
  public String selection;
  public String[] selectionArgs;
  public String sortOrder;
  protected Object[][] results = new Object[0][0];
  protected List<String> columnNames = new ArrayList<>();
  private int resultsIndex = -1;
  private boolean closeWasCalled;
  private Bundle extras;

  public void setQuery(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    this.uri = uri;
    this.projection = projection;
    this.selection = selection;
    this.selectionArgs = selectionArgs;
    this.sortOrder = sortOrder;
  }

  @Override
  public int getColumnIndexOrThrow(String columnName) {
    int col = getColumnIndex(columnName);
    if (col == -1) {
      throw new IllegalArgumentException("No column with name: " + columnName);
    }
    return col;
  }

  @Override
  public int getColumnIndex(String columnName) {
    return columnNames.indexOf(columnName);
  }

  @Override
  public String getString(int columnIndex) {
    Object value = results[resultsIndex][columnIndex];
    return value == null ? null : value.toString();
  }

  @Override
  public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {}

  @Override
  public short getShort(int columnIndex) {
    Object value = results[resultsIndex][columnIndex];
    return value == null
        ? 0
        : (value instanceof Number
            ? ((Number) value).shortValue()
            : Short.parseShort(value.toString()));
  }

  @Override
  public int getInt(int columnIndex) {
    Object value = results[resultsIndex][columnIndex];
    return value == null
        ? 0
        : (value instanceof Number
            ? ((Number) value).intValue()
            : Integer.parseInt(value.toString()));
  }

  @Override
  public long getLong(int columnIndex) {
    Object value = results[resultsIndex][columnIndex];
    return value == null
        ? 0
        : (value instanceof Number
            ? ((Number) value).longValue()
            : Long.parseLong(value.toString()));
  }

  @Override
  public float getFloat(int columnIndex) {
    Object value = results[resultsIndex][columnIndex];
    return value == null
        ? 0
        : (value instanceof Number
            ? ((Number) value).floatValue()
            : Float.parseFloat(value.toString()));
  }

  @Override
  public double getDouble(int columnIndex) {
    Object value = results[resultsIndex][columnIndex];
    return value == null
        ? 0
        : (value instanceof Number
            ? ((Number) value).doubleValue()
            : Double.parseDouble(value.toString()));
  }

  @Override
  public byte[] getBlob(int columnIndex) {
    return (byte[]) results[resultsIndex][columnIndex];
  }

  @Override
  public int getType(int columnIndex) {
    return getTypeOfObject(results[0][columnIndex]);
  }

  @Override
  public boolean isNull(int columnIndex) {
    return results[resultsIndex][columnIndex] == null;
  }

  @Override
  public void deactivate() {}

  @Override
  public boolean requery() {
    return false;
  }

  @Override
  public int getCount() {
    return results.length;
  }

  @Override
  public boolean moveToNext() {
    return doMoveToPosition(resultsIndex + 1);
  }

  @Override
  public boolean moveToFirst() {
    return doMoveToPosition(0);
  }

  @Override
  public boolean moveToPosition(int position) {
    return doMoveToPosition(position);
  }

  private boolean doMoveToPosition(int position) {
    resultsIndex = position;
    return resultsIndex >= 0 && resultsIndex < results.length;
  }

  @Override
  public void close() {
    closeWasCalled = true;
  }

  @Override
  public int getColumnCount() {
    if (columnNames.isEmpty()) {
      return results[0].length;
    } else {
      return columnNames.size();
    }
  }

  @Override
  public String getColumnName(int index) {
    return columnNames.get(index);
  }

  @Override
  public boolean isBeforeFirst() {
    return resultsIndex < 0;
  }

  @Override
  public boolean isAfterLast() {
    return resultsIndex > results.length - 1;
  }

  @Override
  public boolean isFirst() {
    return resultsIndex == 0;
  }

  @Override
  public boolean isLast() {
    return resultsIndex == results.length - 1;
  }

  @Override
  public int getPosition() {
    return resultsIndex;
  }

  @Override
  public boolean move(int offset) {
    return doMoveToPosition(resultsIndex + offset);
  }

  @Override
  public boolean moveToLast() {
    return doMoveToPosition(results.length - 1);
  }

  @Override
  public boolean moveToPrevious() {
    return doMoveToPosition(resultsIndex - 1);
  }

  @Override
  public String[] getColumnNames() {
    return columnNames.toArray(new String[columnNames.size()]);
  }

  @Override
  public boolean isClosed() {
    return closeWasCalled;
  }

  @Override
  public void registerContentObserver(ContentObserver observer) {}

  @Override
  public void unregisterContentObserver(ContentObserver observer) {}

  @Override
  public void registerDataSetObserver(DataSetObserver observer) {}

  @Override
  public void unregisterDataSetObserver(DataSetObserver observer) {}

  @Override
  public void setNotificationUri(ContentResolver cr, Uri uri) {}

  @Override
  public Uri getNotificationUri() {
    return null;
  }

  @Override
  public boolean getWantsAllOnMoveCalls() {
    return false;
  }

  @Override
  public Bundle getExtras() {
    return extras;
  }

  @Override
  public Bundle respond(Bundle extras) {
    return null;
  }

  @Override
  public void setExtras(Bundle extras) {
    this.extras = extras;
  }

  public void setColumnNames(List<String> columnNames) {
    this.columnNames = columnNames;
  }

  public void setResults(Object[][] results) {
    this.results = results;
  }

  public boolean getCloseWasCalled() {
    return closeWasCalled;
  }

  private static int getTypeOfObject(Object obj) {
    if (obj == null) {
      return Cursor.FIELD_TYPE_NULL;
    } else if (obj instanceof byte[]) {
      return Cursor.FIELD_TYPE_BLOB;
    } else if (obj instanceof Float || obj instanceof Double) {
      return Cursor.FIELD_TYPE_FLOAT;
    } else if (obj instanceof Long
        || obj instanceof Integer
        || obj instanceof Short
        || obj instanceof Byte) {
      return Cursor.FIELD_TYPE_INTEGER;
    } else {
      return Cursor.FIELD_TYPE_STRING;
    }
  }
}
