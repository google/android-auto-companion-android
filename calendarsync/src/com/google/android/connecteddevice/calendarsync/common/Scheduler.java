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

import java.time.Duration;
import java.time.Instant;

/**
 * Manages scheduling and cancellation of {@link Runnable} tasks.
 *
 * <p>This is a common interface to be implemented for each platform.
 */
public interface Scheduler {

  /** A handle returned when a task is scheduled that allows the task to be cancelled. */
  interface ScheduledTaskHandle {
    void cancel();
  }

  /**
   * Schedules the {@code task} to be executed at time {@code when}.
   *
   * @return A handle that allows the task execution to be cancelled.
   */
  ScheduledTaskHandle schedule(Instant when, Runnable task);

  /**
   * Schedules the {@code task} to be executed after duration {@code del}.
   *
   * @return A handle that allows the task execution to be cancelled.
   */
  ScheduledTaskHandle delay(Duration delay, Runnable task);
}
