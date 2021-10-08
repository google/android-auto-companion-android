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

import static com.google.common.base.Preconditions.checkArgument;

import android.os.Handler;
import android.os.SystemClock;
import com.google.android.connecteddevice.calendarsync.common.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Android implementation of {@link Scheduler} that posts tasks on a {@link Handler}. */
public final class HandlerScheduler implements Scheduler {
  private final Handler handler;
  private final Clock clock;

  public HandlerScheduler(Handler handler, Clock clock) {
    this.handler = handler;
    this.clock = clock;
  }

  @Override
  public ScheduledTaskHandle schedule(Instant when, Runnable task) {
    checkArgument(when.isAfter(clock.instant()), "Must schedule in the future.");
    return delay(Duration.between(clock.instant(), when), task);
  }

  @Override
  public ScheduledTaskHandle delay(Duration delay, Runnable task) {
    long uptimeMillis = SystemClock.uptimeMillis() + delay.toMillis();
    handler.postAtTime(task, uptimeMillis);
    return () -> handler.removeCallbacks(task);
  }
}
