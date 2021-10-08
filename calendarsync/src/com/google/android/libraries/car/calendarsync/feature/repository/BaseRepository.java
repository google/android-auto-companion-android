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
import android.database.Cursor;
import com.google.common.collect.ImmutableList;

/**
 * A base class for providing CRUD functionality using the ContentProvider. At the moment this just
 * supports reading with query parameters.
 */
abstract class BaseRepository {

  private final ContentResolver contentResolver;

  BaseRepository(ContentResolver contentResolver) {
    this.contentResolver = contentResolver;
  }

  /** @return a list of items with no query parameters. */
  <T> ImmutableList<T> getItems(BaseConverter<T> converter) {
    return getItems(converter, /* selection= */ null, /* selectionArgs= */ null);
  }

  /** @return a list of items of the generic T type. */
  <T> ImmutableList<T> getItems(
      BaseConverter<T> converter, String selection, String[] selectionArgs) {
    ImmutableList.Builder<T> items = ImmutableList.builder();
    try (Cursor cursor =
        contentResolver.query(
            converter.getUri(),
            converter.getColumnNames(),
            selection,
            selectionArgs,
            /* sortOrder= */ null)) {
      if (cursor == null) {
        return items.build();
      }
      while (cursor.moveToNext()) {
        items.add(converter.convert(cursor));
      }
    }
    return items.build();
  }
}
