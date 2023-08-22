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

import com.google.android.connecteddevice.calendarsync.Event;
import com.google.common.collect.ImmutableList;
import java.time.Instant;

/** A simple mock that returns an empty list of events for any content resolver. */
final class MockEventRepository extends EventRepository {

  MockEventRepository() {
    super(null);
  }

  @Override
  ImmutableList<Event> getEvents(long calendarId, Instant startInstant, Instant endInstant) {
    return ImmutableList.of();
  }
}
