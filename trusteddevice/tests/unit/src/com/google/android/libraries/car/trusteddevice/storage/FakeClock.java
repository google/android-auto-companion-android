package com.google.android.libraries.car.trusteddevice.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A {@link Clock} that returns a settable {@link Instant} value as the current time. */
final class FakeClock extends Clock {

  private Instant now = Instant.now();
  private ZoneId zoneId = ZoneOffset.UTC;

  /** Sets the return value of {@link #instant()}. */
  void setNow(Instant now) {
    this.now = now;
  }

  /**
   * Advances the clock time by the given duration.
   *
   * <p>Note: In the exceptional case the clock has to go back in time, this method can be called
   * with a negative duration.
   *
   * @param increment the duration to advance the clock time by
   */
  void advance(Duration increment) {
    now = now.plus(increment);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return zoneId;
  }

  @Override
  public Clock withZone(ZoneId zoneId) {
    this.zoneId = zoneId;
    return this;
  }
}
