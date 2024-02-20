package com.google.android.connecteddevice.calendarsync.common;

import com.google.android.connecteddevice.calendarsync.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.protobuf.MessageLite;
import java.time.Instant;
import javax.annotation.Nullable;

/**
 * The interface that platform specific implementations must fulfill to access a single type of
 * calendar content. Each platform will provide subclasses that can work with calendars, events and
 * attendees that correspond to definitions in the iCalendar protocol (RFC 5545).
 *
 * <p>Relationships between the content items are <i>not</i> managed in these classes but instead in
 * the related {@link ContentManager} classes. For example, an implementation for calendar content
 * will not be responsible for reading or writing event content. This keeps the relationships in a
 * single place and simplifies the platform specific logic by pushing the complexity into the common
 * code that is reused across platforms.
 *
 * <p>Each item of content can be identified by its {@code String key} and a {@code Object parentId}
 * that is platform dependent. The key must be unique amongst children of the same parent. The
 * parentId is an object used to find the children by key and so it must be unique amongst all
 * parent items.
 *
 * <p>Reading data returns {@link Content} values which wrap the proto message and an {@code id}
 * object that is used to find its children. This {@code id} is required because the {@code String
 * key} is not enough to identify a parent uniquely (as it is only required to be unique under its
 * parent).
 *
 * <p>For example, on Android when reading an Event we get a {@link Content} that contains a {@code
 * long id} that is actually the rowId of the database row in the "events" table and that is
 * required to query the "attendees" table for children (it's the foreign key). The Event's key
 * cannot be used to look up the child attendees as it is not unique. Even if it were unique, it
 * would still require the platform to first query for the event with that key value and then use
 * the rowId to find the child attendees which would be inefficient.
 *
 * <p>Note, on iOS rowIds are not used but actual object references to an EKEvent object which is
 * used to return the child attendees. Thus, the id is of type Object.
 */
public interface PlatformContentDelegate<MessageT extends MessageLite> {

  /** Reads a single content item with the given parent id and key. */
  @Nullable
  Content<MessageT> read(@Nullable Object parentId, String key);

  /** Reads all content items for the given parent. */
  ImmutableList<Content<MessageT>> readAll(@Nullable Object parentId);

  /**
   * Inserts the content item with the given {@code parentId} and returns the id of the new item.
   */
  Object insert(Object parentId, MessageT content);

  /**
   * Deletes the content item with the given {@code parentId} and {@code key} and returns {@code
   * true} if the item was deleted.
   */
  boolean delete(Object parentId, String key);

  /** Deletes all content items with the given {@code parentId}. */
  void deleteAll(Object parentId);

  /** Finds the id of the content item with the given {@code parentId} and {@code key}. */
  Object find(Object parentId, String key);

  /**
   * Updates the content item with the given {@code parentId} and {@code key} with {@code content}.
   *
   * <p>The key is returned which might change as a result of this update.
   */
  String update(Object parentId, String key, MessageT content);

  /**
   * A holder for the proto {@code message} and the {@code id} which is an object that will be used
   * as the parentId by the platform implementation to find the children. The {@code id} will be
   * null if this item can have no children.
   */
  final class Content<MessageT extends MessageLite> {
    private final MessageT message;
    @Nullable private final Object id;

    public Content(MessageT message, @Nullable Object id) {
      this.message = message;
      this.id = id;
    }

    /** The message representing the calendar content item. */
    public MessageT getMessage() {
      return message;
    }

    /** The platform specific id to be used to query for children. */
    @Nullable
    public Object getId() {
      return id;
    }
  }

  /** A factory to create {@code PlatformContent<Event>} for a given time range. */
  interface EventContentDelegateFactory {
    /**
     * Creates a PlatformContent for event instances beginning on or after {@code begin} and before
     * {@code end} (exclusive).
     */
    PlatformContentDelegate<Event> create(@Nullable Range<Instant> range);
  }
}
