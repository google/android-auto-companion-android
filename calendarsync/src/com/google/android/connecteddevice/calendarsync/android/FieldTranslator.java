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

import android.content.ContentValues;
import android.database.Cursor;
import com.google.android.connecteddevice.calendarsync.Timestamp;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Translates a proto field to and from a set of {@link Cursor} columns.
 *
 * <p>Representing this relationship as an object allows a single definition to control reading and
 * writing to the database and the proto message. All type information and conversions are handled
 * in a single place.
 *
 * <p>A collection of these columns defines a database projection which ensures that each column
 * (and no more) is read and written.
 *
 * <p>The type parameter {@code ValueT} may be different to both the type stored in the Cursor and
 * the type stored in the proto. e.g. an {@link Instant} is stored as a {@code long} in the Cursor
 * and a {@link Timestamp} in the proto message.
 *
 * @param <ValueT> The type of the value used in code.
 */
public abstract class FieldTranslator<ValueT> {

  /** Create a {@link ColumnFieldTranslator} that stores a {@code String} value. */
  public static <BuilderT, MessageT> ColumnFieldTranslator<String> createStringField(
      String column, Getter<MessageT, String> getter, Setter<BuilderT, String> setter) {
    return new ColumnFieldTranslator<String>(column, getter, setter) {
      @Override
      public String getNotNullValue(Cursor cursor, int index) {
        return cursor.getString(index);
      }

      @Override
      public void setNotNullValue(String value, ContentValues values) {
        values.put(column, value);
      }
    };
  }

  /** Create a {@link ColumnFieldTranslator} that stores an {@code Integer} value. */
  public static <BuilderT, MessageT> ColumnFieldTranslator<Integer> createIntegerField(
      String column, Getter<MessageT, Integer> getter, Setter<BuilderT, Integer> setter) {
    return new ColumnFieldTranslator<Integer>(column, getter, setter) {
      @Override
      public Integer getNotNullValue(Cursor cursor, int index) {
        return cursor.getInt(index);
      }

      @Override
      public void setNotNullValue(Integer value, ContentValues values) {
        values.put(column, value);
      }
    };
  }

  /** Create a {@link ColumnFieldTranslator} that stores a {@code Long} value. */
  public static <BuilderT, MessageT> ColumnFieldTranslator<Long> createLongField(
      String column, Getter<MessageT, Long> getter, Setter<BuilderT, Long> setter) {
    return new ColumnFieldTranslator<Long>(column, getter, setter) {
      @Override
      public Long getNotNullValue(Cursor cursor, int index) {
        return cursor.getLong(index);
      }

      @Override
      public void setNotNullValue(Long value, ContentValues values) {
        values.put(column, value);
      }
    };
  }

  /** Create a {@link ColumnFieldTranslator} that stores an {@code Instant} value. */
  public static <BuilderT, MessageT> ColumnFieldTranslator<Instant> createInstantField(
      String column, Getter<MessageT, Instant> getter, Setter<BuilderT, Instant> setter) {
    return new ColumnFieldTranslator<Instant>(column, getter, setter) {
      @Override
      public Instant getNotNullValue(Cursor cursor, int index) {
        return Instant.ofEpochMilli(cursor.getLong(index));
      }

      @Override
      public void setNotNullValue(Instant value, ContentValues values) {
        values.put(column, value.toEpochMilli());
      }
    };
  }

  /** Create a {@link ColumnFieldTranslator} that stores a {@code Boolean} value as 0 or 1. */
  public static <BuilderT, MessageT> ColumnFieldTranslator<Boolean> createBooleanField(
      String column, Getter<MessageT, Boolean> getter, Setter<BuilderT, Boolean> setter) {
    return new ColumnFieldTranslator<Boolean>(column, getter, setter) {
      @Override
      public Boolean getNotNullValue(Cursor cursor, int index) {
        return intToBoolean(cursor.getInt(index));
      }

      @Override
      public void setNotNullValue(Boolean value, ContentValues values) {
        values.put(column, booleanToInt(value));
      }
    };
  }

  /**
   * Creates a {@link FieldTranslator} that puts a constant boolean value in a {@link Cursor}
   * column.
   */
  public static FieldTranslator<Boolean> createBooleanConstant(String column, boolean value) {
    return new ConstantFieldTranslator<Boolean>() {
      @Override
      void setTypedValue(ContentValues values) {
        values.put(column, booleanToInt(value));
      }
    };
  }

  /**
   * Creates a {@link FieldTranslator} that puts a constant String value in a {@link Cursor} column.
   */
  public static FieldTranslator<String> createStringConstant(String column, String value) {
    return new ConstantFieldTranslator<String>() {
      @Override
      void setTypedValue(ContentValues values) {
        values.put(column, value);
      }
    };
  }

  /**
   * Creates a {@link FieldTranslator} that puts a constant Long value in a {@link Cursor} column.
   */
  public static FieldTranslator<Long> createLongConstant(String column, long value) {
    return new ConstantFieldTranslator<Long>() {
      @Override
      void setTypedValue(ContentValues values) {
        values.put(column, value);
      }
    };
  }

  /**
   * Creates a {@link FieldTranslator} that puts a constant int value in a {@link Cursor} column.
   */
  public static FieldTranslator<Integer> createIntConstant(String column, int value) {
    return new ConstantFieldTranslator<Integer>() {
      @Override
      void setTypedValue(ContentValues values) {
        values.put(column, value);
      }
    };
  }

  /** The function to get the value from the message. */
  @Nullable private final Getter<?, ValueT> getter;

  /** The function to set the value to the message. */
  @Nullable private final Setter<?, ValueT> setter;

  /** The column names that are read to create the value. */
  private final Set<String> columns;

  protected <BuilderT, MessageT> FieldTranslator(
      @Nullable Getter<MessageT, ValueT> getter,
      @Nullable Setter<BuilderT, ValueT> setter,
      Set<String> columns) {
    this.getter = getter;
    this.setter = setter;
    this.columns = columns;
  }

  /** Read the value from the {@code cursor} and set it in the message {@code builder}. */
  // BuilderT is not kept to reduce verbosity of FieldTranslator declarations.
  @SuppressWarnings("unchecked")
  public <BuilderT> void contentToMessage(Cursor cursor, BuilderT builder) {
    if (setter == null) {
      return;
    }
    ValueT value = get(cursor);
    if (value != null) {
      ((Setter<BuilderT, ValueT>) setter).set(builder, value);
    }
  }

  /** Read the value from the {@code message} and set it in the {@code cursor} */
  // MessageT is not kept to reduce verbosity of FieldTranslator declarations.
  @SuppressWarnings({"unchecked"})
  public <MessageT> void messageToContent(MessageT message, ContentValues values) {
    if (getter == null) {
      return;
    }
    ValueT value = ((Getter<MessageT, ValueT>) getter).get(message);
    if (value != null) {
      set(value, values);
    }
  }

  /** Names used to generate the query projection (fields to read from database). */
  public Set<String> getColumns() {
    return columns;
  }

  /** Get the value from the cursor. */
  @Nullable
  public abstract ValueT get(Cursor cursor);

  /** Put the value in the cursor content values. */
  public abstract void set(@Nullable ValueT value, ContentValues values);

  /** A {@link FieldTranslator} that stores data in a single cursor column. */
  protected abstract static class ColumnFieldTranslator<ValueT> extends FieldTranslator<ValueT> {
    protected final String column;

    public <BuilderT, MessageT> ColumnFieldTranslator(
        String column, Getter<MessageT, ValueT> getter, Setter<BuilderT, ValueT> setter) {
      super(getter, setter, ImmutableSet.of(column));
      this.column = column;
    }

    @Override
    @Nullable
    public ValueT get(Cursor cursor) {
      int index = getColumnIndex(cursor, column);
      if (cursor.isNull(index)) {
        return null;
      } else {
        return getNotNullValue(cursor, index);
      }
    }

    @Override
    public void set(@Nullable ValueT value, ContentValues values) {
      if (value == null) {
        values.putNull(column);
      } else {
        setNotNullValue(value, values);
      }
    }

    public boolean has(Cursor cursor) {
      return !cursor.isNull(getColumnIndex(cursor, column));
    }

    /** Get the value which can not be null. */
    public abstract ValueT getNotNullValue(Cursor cursor, int index);

    /** Set the value which can not be null. */
    public abstract void setNotNullValue(ValueT value, ContentValues values);
  }

  /** Allows subclasses to write any values directly into the cursor values. */
  protected abstract static class ConstantFieldTranslator<ValueT> extends FieldTranslator<ValueT> {
    public ConstantFieldTranslator() {
      super(/* getter= */ null, /* setter= */ null, ImmutableSet.of());
    }

    @Override
    public <MessageT> void messageToContent(MessageT message, ContentValues values) {
      // Overridden to not try to read the value from the message.
      setTypedValue(values);
    }

    /** Allows subclasses to modify the cursor values. */
    abstract void setTypedValue(ContentValues values);

    @Override
    public <BuilderT> void contentToMessage(Cursor cursor, BuilderT builder) {
      // No value is added to the message.
    }

    @Nullable
    @Override
    public ValueT get(Cursor cursor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void set(@Nullable ValueT value, ContentValues values) {
      throw new UnsupportedOperationException();
    }
  }

  private static boolean intToBoolean(int value) {
    return value == 1;
  }

  private static int booleanToInt(boolean value) {
    return value ? 1 : 0;
  }

  protected static int getColumnIndex(Cursor cursor, String column) {
    int index = cursor.getColumnIndex(column);
    if (index < 0) {
      throw new IllegalStateException("Column not found: " + column);
    }
    return index;
  }

  /**
   * A function that sets a field on a proto message builder.
   *
   * <p>Often this can be passed as a method reference like: {@code MyProto.Builder::setFieldName}
   */
  public interface Setter<BuilderT, ValueT> {
    BuilderT set(BuilderT builder, ValueT value);
  }

  /**
   * A function that sets a field from a proto message.
   *
   * <p>Often this can be passed as a method reference like: {@code MyProto.Builder::getFieldName}
   */
  public interface Getter<MessageT, ValueT> {
    ValueT get(MessageT message);
  }
}
