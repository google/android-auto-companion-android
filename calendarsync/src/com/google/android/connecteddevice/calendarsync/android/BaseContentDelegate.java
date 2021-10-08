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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import com.google.android.connecteddevice.calendarsync.common.CommonLogger;
import com.google.android.connecteddevice.calendarsync.common.PlatformContentDelegate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.MessageLite;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/**
 * Base class for Android {@link PlatformContentDelegate}s.
 *
 * <p>Fields to read and write are represented by the {@link FieldTranslator}s.
 */
abstract class BaseContentDelegate<MessageT extends MessageLite>
    implements PlatformContentDelegate<MessageT> {
  // This value must not change in case content remains to be deleted on start up.
  protected static final String ACCOUNT_NAME = "CloudlessCalSync";

  protected final ContentResolver resolver;
  protected final CommonLogger logger;
  protected final FieldTranslator<String> keyField;
  private final ImmutableSet<FieldTranslator<?>> fields;
  private final String idColumn;

  // Can be null when the content cannot be read and a write uri is given from getWriteContentUri().
  @Nullable private final Uri contentUri;

  // Can be null if there is no parent content e.g. calendars on the source device.
  @Nullable private final String parentIdColumn;

  BaseContentDelegate(
      CommonLogger logger,
      @Nullable Uri contentUri,
      FieldTranslator<String> keyField,
      ImmutableSet<FieldTranslator<?>> fields,
      ContentResolver resolver,
      String idColumn,
      @Nullable String parentIdColumn) {
    this.logger = logger;
    this.contentUri = contentUri;
    this.keyField = keyField;
    this.fields = fields;
    this.resolver = resolver;
    this.parentIdColumn = parentIdColumn;
    this.idColumn = idColumn;
  }

  @Override
  @Nullable
  public Content<MessageT> read(Object parentId, String key) {
    ContentValues constraints = createKeyConstraints(parentId, key);
    return getOnlyElement(queryAllColumns(constraints), /* defaultValue= */ null);
  }

  @Override
  public ImmutableList<Content<MessageT>> readAll(Object parentId) {
    ContentValues constraints = new ContentValues();
    addParentConstraint(constraints, parentId);
    return queryAllColumns(constraints);
  }

  @Override
  public Object insert(Object parentId, MessageT content) {
    return insertFieldsToUri(parentId, content, getWriteFields(), getWriteContentUri());
  }

  /** Inserts the content using the fields to the contentUri. */
  protected long insertFieldsToUri(
      Object parentId, MessageT content, Collection<FieldTranslator<?>> fields, Uri contentUri) {
    ContentValues values = createContentValues(content, fields);
    addParentConstraint(values, parentId);
    return insertValuesToUri(contentUri, values);
  }

  /** Inserts the given values to the contentUri. */
  protected long insertValuesToUri(Uri contentUri, ContentValues values) {
    Uri uri = resolver.insert(contentUri, values);
    return ContentUris.parseId(requireNonNull(uri, "Calendar provider insert must return Uri"));
  }

  @Override
  public boolean delete(Object parentId, String key) {
    ContentValues constraints = createKeyConstraints(parentId, key);
    WhereAndArgs whereAndArgs = buildWhereAndArgs(constraints);
    int rows = resolver.delete(getWriteContentUri(), whereAndArgs.where, whereAndArgs.args);
    if (rows > 1) {
      logger.warn("Expected to delete one row but did %d for key %s", rows, key);
    }
    return rows > 0;
  }

  @Override
  public void deleteAll(Object parentId) {
    ContentValues constraints = new ContentValues();
    addParentConstraint(constraints, parentId);
    WhereAndArgs whereAndArgs = buildWhereAndArgs(constraints);
    int rows = resolver.delete(getWriteContentUri(), whereAndArgs.where, whereAndArgs.args);
    logger.debug("Deleted %s rows", rows);
  }

  @Override
  @Nullable
  public Object find(Object parentId, String key) {
    ContentValues constraints = createKeyConstraints(parentId, key);
    return getOnlyElement(
        query(ImmutableSet.of(idColumn), constraints, this::cursorToId), /* defaultValue= */ null);
  }

  @Override
  public String update(Object parentId, String key, MessageT content) {
    ContentValues values = createContentValues(content, getWriteFields());
    addParentConstraint(values, parentId);
    ContentValues constraints = createKeyConstraints(parentId, key);
    WhereAndArgs whereAndArgs = buildWhereAndArgs(constraints);
    int rows = resolver.update(getWriteContentUri(), values, whereAndArgs.where, whereAndArgs.args);
    if (rows == 1) {
      logger.debug("Deleted one row for key %s", key);
    } else {
      logger.warn("Expected to delete one row but did %d for key %s", rows, key);
    }

    // The key remains unchanged after updating the same row.
    return key;
  }

  /** Gets the {@link Uri} to use to write content. */
  protected Uri getWriteContentUri() {
    return requireNonNull(contentUri);
  }

  /** Gets the fields used when writing content. */
  protected Collection<FieldTranslator<?>> getWriteFields() {
    return fields;
  }

  private Object cursorToId(Cursor cursor) {
    return cursor.getLong(cursor.getColumnIndex(idColumn));
  }

  /**
   * Adds the {@code parentId) to the {@code constraints).
   */
  private void addParentConstraint(ContentValues constraints, @Nullable Object parentId) {
    if (parentIdColumn != null && parentId != null) {
      addIdConstraint(constraints, parentIdColumn, parentId);
    }
  }

  /**
   * Adds the {@code id) to the {@code constraints) in the given {@code column}.
   */
  private void addIdConstraint(ContentValues constraints, String column, Object id) {
    if (id instanceof String) {
      constraints.put(column, (String) id);
    } else if (id instanceof Long) {
      constraints.put(column, (Long) id);
    }
  }

  /**
   * Creates a {@link ContentValues} containing the field values for the {@code parentId} and {@code
   * key}.
   */
  private ContentValues createKeyConstraints(Object parentId, String key) {
    ContentValues constraints = new ContentValues();
    addParentConstraint(constraints, parentId);
    keyField.set(key, constraints);
    return constraints;
  }

  private ImmutableList<Content<MessageT>> queryAllColumns(ContentValues constraints) {
    return query(buildColumnProjection(fields), constraints, this::cursorToContent);
  }

  @SuppressWarnings("unchecked")
  private Content<MessageT> cursorToContent(Cursor cursor) {
    MessageLite.Builder builder = createMessageBuilder();
    for (FieldTranslator<?> field : fields) {
      field.contentToMessage(cursor, builder);
    }
    return new Content<>((MessageT) builder.build(), cursorToId(cursor));
  }

  private <ResultT> ImmutableList<ResultT> query(
      Set<String> columns,
      ContentValues constraints,
      Function<Cursor, ResultT> createResultFunction) {
    checkNotNull(contentUri, "No read content Uri");

    String[] projection = columns.toArray(new String[0]);
    WhereAndArgs whereAndArgs = buildWhereAndArgs(constraints);
    ImmutableList.Builder<ResultT> results = ImmutableList.builder();

    logger.debug(
        "Query: %s projection:%s where:%s args:%s",
        contentUri,
        Arrays.toString(projection),
        whereAndArgs.where,
        Arrays.toString(whereAndArgs.args));

    try (Cursor cursor =
        resolver.query(
            contentUri, projection, whereAndArgs.where, whereAndArgs.args, /* sortOrder= */ null)) {

      // Cursor can be null if the process is shutting down.
      if (cursor == null) {
        return ImmutableList.of();
      }

      while (cursor.moveToNext()) {
        ResultT result = createResultFunction.apply(cursor);
        results.add(result);
      }

      return results.build();
    }
  }

  /** Creates a new {@link com.google.protobuf.MessageLite.Builder} for the MessageT. */
  protected abstract MessageLite.Builder createMessageBuilder();

  protected WhereAndArgs buildWhereAndArgs(ContentValues constraints) {
    StringBuilder where = new StringBuilder();
    List<String> args = new ArrayList<>(constraints.size());
    for (String field : constraints.keySet()) {
      if (where.length() > 0) {
        where.append(" AND ");
      }
      where.append(field);
      Object value = constraints.get(field);
      if (value == null) {
        where.append(" IS NULL");
      } else {
        where.append(" = ?");
        args.add(value.toString());
      }
    }
    return new WhereAndArgs(where.toString(), args.toArray(new String[0]));
  }

  /** A simple holder for the query "where" string and args to include in its place holders. */
  protected static class WhereAndArgs {
    final String where;
    final String[] args;

    private WhereAndArgs(String where, String[] args) {
      this.where = where;
      this.args = args;
    }
  }

  /**
   * Creates a {@link ContentValues} containing all the field values from the {@code message} and
   * the {@code parentId}.
   */
  protected ContentValues createContentValues(
      MessageT message, Collection<FieldTranslator<?>> fields) {
    ContentValues values = new ContentValues();
    for (FieldTranslator<?> field : fields) {
      field.messageToContent(message, values);
    }
    return values;
  }

  private Set<String> buildColumnProjection(Set<FieldTranslator<?>> fields) {
    Set<String> columns = new HashSet<>();
    for (FieldTranslator<?> field : fields) {
      columns.addAll(field.getColumns());
    }
    columns.add(idColumn);
    return columns;
  }

  /** Adds required fields for being a "sync adapter" that allows access to special fields. */
  @CheckReturnValue
  protected static Uri addSyncAdapterParameters(Uri contentUri) {
    Uri.Builder builder = contentUri.buildUpon();

    // The calendar fields are denormalized into events and attendees so can be used in criteria.
    builder.appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME);
    builder.appendQueryParameter(
        CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
    builder.appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true");
    return builder.build();
  }
}
