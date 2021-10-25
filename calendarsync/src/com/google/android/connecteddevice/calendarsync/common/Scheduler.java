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
