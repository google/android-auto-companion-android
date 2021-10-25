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
