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

import com.google.android.connecteddevice.calendarsync.Attendee;
import com.google.android.connecteddevice.calendarsync.UpdateAction;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.MessageLite;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Manage the {@link Attendee} attributes.
 *
 * <p>{@inheritDoc}
 */
class AttendeeManager
    extends ContentManager<Attendee, Attendee.Builder, MessageLite, MessageLite.Builder> {
  private static final String TAG = "AttendeeManager";

  @Inject
  AttendeeManager(
      CommonLogger.Factory commonLoggerFactory,
      PlatformContentDelegate<Attendee> attendeeContentDelegate) {
    super(commonLoggerFactory.create(TAG), attendeeContentDelegate);
  }

  @Override
  protected void setAction(Attendee.Builder builder, UpdateAction action) {
    builder.setAction(action);
  }

  @Override
  protected void setKey(Attendee.Builder builder, String email) {
    builder.setEmail(email);
  }

  @Override
  protected UpdateAction action(Attendee message) {
    return message.getAction();
  }

  @Override
  protected Attendee.Builder newMessageBuilder() {
    return Attendee.newBuilder();
  }

  @Override
  protected String key(Attendee message) {
    return message.getEmail();
  }

  @Override
  @Nullable
  protected ContentManager<MessageLite, MessageLite.Builder, ?, ?> getChildManager(String key) {
    return null;
  }

  @Override
  protected List<MessageLite> children(Attendee message) {
    return ImmutableList.of();
  }

  @Override
  protected void clearChildren(Attendee.Builder builder) {
    // Nothing to do because there are no children.
  }

  @Override
  protected void addChildren(Attendee.Builder builder, Iterable<MessageLite> children) {
    throw new UnsupportedOperationException();
  }
}
