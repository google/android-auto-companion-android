package com.google.android.connecteddevice.calendarsync.android;

import static com.google.android.connecteddevice.calendarsync.android.ContentOwnership.REPLICA;
import static com.google.android.connecteddevice.calendarsync.android.ContentOwnership.SOURCE;
import static com.google.android.connecteddevice.calendarsync.android.FieldTranslator.createBooleanConstant;
import static com.google.android.connecteddevice.calendarsync.android.FieldTranslator.createIntConstant;
import static com.google.android.connecteddevice.calendarsync.android.FieldTranslator.createIntegerField;
import static com.google.android.connecteddevice.calendarsync.android.FieldTranslator.createStringField;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import com.google.android.connecteddevice.calendarsync.Calendar;
import com.google.android.connecteddevice.calendarsync.Color;
import com.google.android.connecteddevice.calendarsync.TimeZone;
import com.google.android.connecteddevice.calendarsync.android.FieldTranslator.ColumnFieldTranslator;
import com.google.android.connecteddevice.calendarsync.common.CommonLogger;
import com.google.android.connecteddevice.calendarsync.common.ContentCleanerDelegate;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.MessageLite;

/** Android specific access to the calendar content items. */
final class CalendarContentDelegate extends BaseContentDelegate<Calendar>
    implements ContentCleanerDelegate {
  private static final String TAG = "CalendarContentDelegate";

  CalendarContentDelegate(
      CommonLogger.Factory commonLoggerFactory,
      ContentResolver resolver,
      ContentOwnership ownership) {
    super(
        commonLoggerFactory.create(TAG),
        getContentUri(ownership),
        createKeyField(ownership),
        createFieldTranslators(ownership),
        resolver,
        /* idColumn= */ Calendars._ID,
        /* parentIdColumn */ ownership == SOURCE ? null : Calendars.CAL_SYNC1);
  }

  private static Uri getContentUri(ContentOwnership ownership) {
    return ownership == SOURCE
        ? Calendars.CONTENT_URI
        : addSyncAdapterParameters(Calendars.CONTENT_URI);
  }

  @Override
  protected MessageLite.Builder createMessageBuilder() {
    return Calendar.newBuilder();
  }

  /** Creates the {@link FieldTranslator}s that define how this data is stored and retrieved. */
  private static ImmutableSet<FieldTranslator<?>> createFieldTranslators(
      ContentOwnership ownership) {
    ImmutableSet.Builder<FieldTranslator<?>> builder =
        ImmutableSet.<FieldTranslator<?>>builder()
            .add(
                DISPLAY_NAME,
                COLOR,
                createOwnerAccountField(ownership),
                createTimeZoneField(ownership),
                createKeyField(ownership));

    // Write some constants to the calendar on the replica in addition to data fields.
    if (ownership == REPLICA) {
      // Created replica calendars should be visible.
      builder.add(createBooleanConstant(Calendars.VISIBLE, true));

      // Allow the current user to be a calendar editor.
      builder.add(createIntConstant(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_EDITOR));

      // Indicates that this calendar will have events.
      builder.add(createBooleanConstant(Calendars.SYNC_EVENTS, true));
    }
    return builder.build();
  }

  /**
   * Creates the key field for a {@link Calendar}.
   *
   * <p>The key value will be the calendar's rowId for the source device or on the replica device it
   * will be a special sync field that is only accessible to a sync adapter.
   */
  private static FieldTranslator<String> createKeyField(ContentOwnership ownership) {
    // Modify the standard string field to filter key queries by our owner account.
    return new ColumnFieldTranslator<String>(
        ownership == SOURCE ? Calendars._ID : Calendars._SYNC_ID,
        Calendar::getKey,
        Calendar.Builder::setKey) {
      @Override
      public String getNotNullValue(Cursor cursor, int index) {
        return cursor.getString(index);
      }

      @Override
      public void setNotNullValue(String key, ContentValues values) {
        values.put(column, key);

        // These are written to each replica calendar and used in the select when finding by key.
        if (ownership == REPLICA) {
          addAccountNameAndType(values);
        }
      }
    };
  }

  private static void addAccountNameAndType(ContentValues values) {
    values.put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME);
    values.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
  }

  private static final FieldTranslator<String> DISPLAY_NAME =
      createStringField(
          Calendars.CALENDAR_DISPLAY_NAME, Calendar::getTitle, Calendar.Builder::setTitle);

  private static FieldTranslator<String> createOwnerAccountField(ContentOwnership ownership) {
    return new OnlyWriteSourceStringColumnFieldTranslator(
        ownership,
        Calendars.OWNER_ACCOUNT,
        Calendar::getAccountName,
        Calendar.Builder::setAccountName);
  }

  private static FieldTranslator<String> createTimeZoneField(ContentOwnership ownership) {
    return new OnlyWriteSourceStringColumnFieldTranslator(
        ownership,
        Calendars.CALENDAR_TIME_ZONE,
        (Calendar message) -> message.hasTimeZone() ? message.getTimeZone().getName() : null,
        (Calendar.Builder builder, String value) ->
            builder.setTimeZone(TimeZone.newBuilder().setName(value)));
  }

  private static final FieldTranslator<Integer> COLOR =
      createIntegerField(
          Calendars.CALENDAR_COLOR,
          (Calendar message) -> message.hasColor() ? message.getColor().getArgb() : null,
          (Calendar.Builder builder, Integer value) ->
              builder.setColor(Color.newBuilder().setArgb(value)));

  @Override
  public boolean clean() {
    // Select all local calendars with our account name.
    ContentValues constraints = new ContentValues();
    addAccountNameAndType(constraints);
    WhereAndArgs whereAndArgs = buildWhereAndArgs(constraints);
    int rows = resolver.delete(getWriteContentUri(), whereAndArgs.where, whereAndArgs.args);
    logger.debug("Cleaned %s calendars", rows);
    return rows > 0;
  }

  /**
   * A {@link FieldTranslator<String>} writes to the {@link Cursor} on the replica but not on the
   * source.
   */
  private static class OnlyWriteSourceStringColumnFieldTranslator
      extends ColumnFieldTranslator<String> {

    private final ContentOwnership ownership;

    public OnlyWriteSourceStringColumnFieldTranslator(
        ContentOwnership ownership,
        String column,
        Getter<Calendar, String> getter,
        Setter<Calendar.Builder, String> setter) {
      super(column, getter, setter);
      this.ownership = ownership;
    }

    @Override
    public String getNotNullValue(Cursor cursor, int index) {
      return cursor.getString(index);
    }

    @Override
    public void setNotNullValue(String value, ContentValues values) {
      // Only the sync adaptor (source) can write this field and the car will not update it.
      if (ownership == REPLICA) {
        values.put(column, value);
      }
    }
  }
}
