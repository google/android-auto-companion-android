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

import com.google.android.connecteddevice.calendarsync.Calendar.TimeRange;
import com.google.android.connecteddevice.calendarsync.Timestamp;
import com.google.common.collect.Range;
import java.time.Instant;

/** Utility methods used for converting time related values used in update.proto */
public final class TimeProtoUtil {

  private TimeProtoUtil() {}

  /** Converts a {@link TimeRange} to a {@link Range<Instant>}. */
  public static Range<Instant> toInstantRange(TimeRange range) {
    return Range.openClosed(toInstant(range.getFrom()), toInstant(range.getTo()));
  }

  /** Converts a {@link Timestamp} to a {@link Instant}. */
  public static Instant toInstant(Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds());
  }

  /** Converts a {@link Range<Instant>} to a {@link TimeRange}. */
  public static TimeRange toTimeRange(Range<Instant> range) {
    return TimeRange.newBuilder()
        .setFrom(toTimestamp(range.lowerEndpoint()))
        .setTo(toTimestamp(range.upperEndpoint()))
        .build();
  }

  /** Converts a {@link Instant} to a {@link Timestamp}. */
  public static Timestamp toTimestamp(Instant instant) {
    return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).build();
  }
}
