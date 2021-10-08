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

import com.google.android.connecteddevice.calendarsync.Calendar;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Stores {@link Calendar}s that are sent to a remote device. */
class CalendarStore {
  private final Map<String, Map<String, Calendar>> deviceIdToKeyToCalendar = new HashMap<>();

  @Inject
  CalendarStore() {}

  /** Stores the {@code calendar} for the given remove device. */
  public void store(String deviceId, Calendar calendar) {
    keyToCalendar(deviceId).put(calendar.getKey(), calendar);
  }

  /** Fetches the {@link Calendar} for the given remove device. */
  @Nullable
  public Calendar fetch(String deviceId, String calendarKey) {
    return keyToCalendar(deviceId).get(calendarKey);
  }

  /** Fetches all stored {@link Calendar}s for the given device. */
  public ImmutableSet<Calendar> fetchAll(String deviceId) {
    return ImmutableSet.copyOf(keyToCalendar(deviceId).values());
  }

  /** Removes the calendar stored with the given key for the device. */
  public void remove(String deviceId, String calendarKey) {
    keyToCalendar(deviceId).remove(calendarKey);
  }

  /** Removes all stored {@link Calendar}s for the given device. */
  public void removeAll(String deviceId) {
    deviceIdToKeyToCalendar.remove(deviceId);
  }

  /** Clears all stored {@link Calendar}s for all devices. */
  public void clear() {
    deviceIdToKeyToCalendar.clear();
  }

  private Map<String, Calendar> keyToCalendar(String deviceId) {
    return deviceIdToKeyToCalendar.computeIfAbsent(deviceId, unused -> new HashMap<>());
  }
}
