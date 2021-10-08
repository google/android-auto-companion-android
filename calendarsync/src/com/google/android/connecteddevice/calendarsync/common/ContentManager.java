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

package com.google.android.connecteddevice.calendarsync.common;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.android.connecteddevice.calendarsync.UpdateAction;
import com.google.android.connecteddevice.calendarsync.common.PlatformContentDelegate.Content;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.MessageLite;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Base class for working with the hierarchy of calendar content items.
 *
 * <p>A {@link PlatformContentDelegate} knows how to access a single calendar content item but does
 * not need to know the relationships between them which keeps all hierarchical logic here in this
 * class. This reduces the amount of duplication for each platform.
 *
 * <p>This class is responsible for all common operations on the hierarchy: reading, creating,
 * updating and generating updates ("deltas") by examining two hierarchies.
 *
 * <p>Subclasses are responsible for defining how to work with their messages (e.g. Event proto) and
 * how to get their children's manager.
 */
abstract class ContentManager<
    MessageT extends MessageLite,
    BuilderT extends MessageLite.Builder,
    ChildMessageT extends MessageLite,
    ChildBuilderT extends MessageLite.Builder> {
  protected final CommonLogger logger;
  private final PlatformContentDelegate<MessageT> delegate;

  ContentManager(CommonLogger logger, PlatformContentDelegate<MessageT> delegate) {
    this.logger = logger;
    this.delegate = delegate;
  }

  /** Reads a single content item with all children for the given parentId and key. */
  @Nullable
  public MessageT read(@Nullable Object parentId, String key) {
    Content<MessageT> content = this.delegate.read(parentId, key);
    if (content == null) {
      return null;
    }
    return setChildren((Content<MessageT>) content);
  }

  /** Reads all content items with their children for the given parentId. */
  public ImmutableList<MessageT> readAll(@Nullable Object parentId) {
    ImmutableList<Content<MessageT>> contents = delegate.readAll(parentId);
    ImmutableList.Builder<MessageT> results = ImmutableList.builder();
    for (Content<MessageT> content : contents) {
      MessageT contentWithChildren = setChildren(content);
      results.add(contentWithChildren);
    }
    return results.build();
  }

  @SuppressWarnings("unchecked")
  private MessageT setChildren(Content<MessageT> content) {
    BuilderT builder = (BuilderT) content.getMessage().toBuilder();
    onCreateBuilder(builder);
    maybeSetChildren(builder, key(content.getMessage()), content.getId());
    return (MessageT) builder.build();
  }

  /**
   * Deletes the content item with the given {@code parentId} and {@code key} and all descendants.
   */
  public void delete(Object parentId, String key) {
    logger.debug("Delete %s", key);

    // The platform handles deleting ancestors.
    delegate.delete(parentId, key);
  }

  /** Deletes all content items with the given {@code parentId} and all descendants. */
  public void deleteAll(Object parentId) {
    logger.debug("Delete all for parent %s", parentId);

    // The platform handles deleting descendants.
    delegate.deleteAll(parentId);
  }

  /** Creates a new content item with the given {@code parentId} and all descendants. */
  public void create(Object parentId, MessageT content) {
    logger.debug("Create %s", content);
    Object id = delegate.insert(parentId, content);
    String key = key(content);
    ContentManager<ChildMessageT, ?, ?, ?> childManager = getChildManager(key);
    if (childManager == null) {
      return;
    }
    Collection<ChildMessageT> children = children(content);
    for (ChildMessageT child : children) {
      childManager.create(id, child);
    }
  }

  public ImmutableSet<MessageT> createUpdateMessages(
      Collection<MessageT> previousContents, Collection<MessageT> currentContents) {
    return createReplaceOrUpdateMessages(previousContents, currentContents, true);
  }

  @SuppressWarnings("unchecked")
  public MessageT createUpdateMessage(MessageT previous, MessageT current) {
    String key = key(current);
    checkArgument(key.equals(key(previous)));

    BuilderT updateMessageBuilder;
    if (previous == current) {
      // Optimization for identical items.
      updateMessageBuilder = newMessageBuilder();
      setKey(updateMessageBuilder, key);
      setAction(updateMessageBuilder, UpdateAction.UNCHANGED);
      onCreateBuilder(updateMessageBuilder);
      return (MessageT) updateMessageBuilder.build();
    }

    BuilderT previousBuilder = (BuilderT) previous.toBuilder();
    resetExternalFields(previousBuilder);

    BuilderT currentBuilder = (BuilderT) current.toBuilder();
    resetExternalFields(currentBuilder);

    // Using .equals works because children are removed and there are no floats.
    if (previousBuilder.build().equals(currentBuilder.build())) {
      updateMessageBuilder = newMessageBuilder();
      setKey(updateMessageBuilder, key);
      setAction(updateMessageBuilder, UpdateAction.UNCHANGED);
    } else {
      updateMessageBuilder = currentBuilder;
      setAction(currentBuilder, UpdateAction.UPDATE);
    }
    onCreateBuilder(updateMessageBuilder);

    ContentManager<ChildMessageT, ChildBuilderT, ?, ?> childManager = getChildManager(key);
    if (childManager != null) {
      Collection<ChildMessageT> previousChildren = children(previous);
      Collection<ChildMessageT> currentChildren = children(current);
      Collection<ChildMessageT> childUpdateMessages =
          childManager.createUpdateMessages(previousChildren, currentChildren);
      addChildren(updateMessageBuilder, childUpdateMessages);
    }

    return (MessageT) updateMessageBuilder.build();
  }

  public void applyUpdateMessages(Object parentId, Collection<MessageT> updates) {
    for (MessageT update : updates) {
      applyUpdateMessage(parentId, update);
    }
  }

  @SuppressWarnings("UnnecessaryDefaultInEnumSwitch")
  public void applyUpdateMessage(Object parentId, MessageT update) {
    UpdateAction action = action(update);
    switch (action) {
      case ACTION_UNSPECIFIED:
        // Fall-through to default to action REPLACE.
      case REPLACE:
        replace(parentId, update);
        break;
      case CREATE:
        create(parentId, update);
        break;
      case DELETE:
        delete(parentId, key(update));
        break;
      case UPDATE:
        update(parentId, key(update), update);
        break;
      case UNCHANGED:
        unchanged(parentId, update);
        break;
      default:
        // Cannot use UNRECOGNIZED with J2ObjC which does not see this generated value.
        logger.error("Unrecognized action %s for key %s", action.name(), key(update));
    }
  }

  @SuppressWarnings("unchecked")
  protected ImmutableSet<MessageT> createReplaceOrUpdateMessages(
      Collection<MessageT> previousContents, Collection<MessageT> currentContents, boolean update) {
    if (previousContents == currentContents) {
      // Optimization for identical collections.
      return ImmutableSet.of();
    }

    // Map previous contents by id for efficient lookup.
    Map<String, MessageT> keyToPrevious = new LinkedHashMap<>();
    for (MessageT previous : previousContents) {
      keyToPrevious.put(key(previous), previous);
    }

    ImmutableSet.Builder<MessageT> updates = ImmutableSet.builder();
    for (MessageT current : currentContents) {
      MessageT previous = keyToPrevious.remove(key(current));
      if (previous == null) {
        // There is no type parameter connection between the builder and its message.
        BuilderT builder = (BuilderT) current.toBuilder();
        onCreateBuilder(builder);
        setAction(builder, UpdateAction.CREATE);
        updates.add((MessageT) builder.build());
      } else if (update) {
        MessageT updateMessage = createUpdateMessage(previous, current);
        if (!isEmptyUnchanged(updateMessage)) {
          updates.add(updateMessage);
        }
      } else {
        updates.add(createReplaceMessage(current));
      }
    }

    // All remaining previous children were removed.
    for (String previousKey : keyToPrevious.keySet()) {
      BuilderT deleteMessageBuilder = newMessageBuilder();
      setKey(deleteMessageBuilder, previousKey);
      setAction(deleteMessageBuilder, UpdateAction.DELETE);
      updates.add((MessageT) deleteMessageBuilder.build());
    }

    return updates.build();
  }

  /** Creates a REPLACE message if supported or throws an exception. */
  protected MessageT createReplaceMessage(MessageT content) {
    throw new UnsupportedOperationException("Replace not supported");
  }

  /** Adds the children to the message. */
  protected abstract void addChildren(BuilderT builder, Iterable<ChildMessageT> children);

  /** Gets the key from the content. */
  protected abstract String key(MessageT content);

  /** Gets the children from the message. */
  protected abstract List<ChildMessageT> children(MessageT content);

  /** Clears the children. */
  protected abstract void clearChildren(BuilderT builder);

  /** Sets the {@link UpdateAction} for this content item. */
  protected abstract void setAction(BuilderT builder, UpdateAction action);

  /** Sets the key for this content item. */
  protected abstract void setKey(BuilderT builder, String key);

  /** Gets the {@link UpdateAction} for this content item. */
  protected abstract UpdateAction action(MessageT content);

  /** Creates a new message builder. */
  protected abstract BuilderT newMessageBuilder();

  /**
   * Replaces an existing content item and its children with the given content.
   *
   * <p>Only calendars support replacing and other content types will throw an exception.
   */
  protected void replace(Object parentId, MessageT content) {
    throw new UnsupportedOperationException("Replace not supported for this content");
  }

  /** Does not modify the content item but may update its children. */
  protected void unchanged(Object parentId, MessageT update) {
    if (maybeUpdateChildren(parentId, update, key(update))) {
      onContentUpdated(parentId, update);
    }
  }

  /** Updates the content item and may update its children. */
  protected void update(Object parentId, String key, MessageT update) {
    logger.debug("Update %s with %s", key, update);
    String updatedKey = delegate.update(parentId, key, update);
    maybeUpdateChildren(parentId, update, updatedKey);
    onContentUpdated(parentId, update);
  }

  /** Allows subclasses to update state after this content was updated. */
  protected void onContentUpdated(Object parentId, MessageT update) {}

  /** Allows subclasses to modify messages without creating a new copy. */
  protected void onCreateBuilder(BuilderT builder) {}

  /**
   * Gets the manager to use for children.
   *
   * <p>Passing the key to get the child manager allows {@link CalendarManager} to give an {@link
   * EventManager} that knows its time window.
   */
  @Nullable
  protected abstract ContentManager<ChildMessageT, ChildBuilderT, ?, ?> getChildManager(String key);

  private boolean maybeUpdateChildren(Object parentId, MessageT update, String key) {
    ContentManager<ChildMessageT, ChildBuilderT, ?, ?> childManager = getChildManager(key);
    if (childManager == null) {
      return false;
    }
    List<ChildMessageT> children = children(update);
    if (children.isEmpty()) {
      return false;
    }
    Object id = delegate.find(parentId, key);
    childManager.applyUpdateMessages(id, children);
    return true;
  }

  private boolean isEmptyUnchanged(MessageT childUpdateMessage) {
    return action(childUpdateMessage) == UpdateAction.UNCHANGED
        && children(childUpdateMessage).isEmpty();
  }

  /** Clears or sets all fields that do not represent the persistent content. */
  private void resetExternalFields(BuilderT builder) {
    setAction(builder, UpdateAction.ACTION_UNSPECIFIED);
    clearChildren(builder);
  }

  private void maybeSetChildren(BuilderT builder, String key, Object id) {
    ContentManager<ChildMessageT, ChildBuilderT, ?, ?> childManager = getChildManager(key);
    if (childManager != null) {
      ImmutableList<ChildMessageT> children = childManager.readAll(id);
      addChildren(builder, children);
    }
  }
}
