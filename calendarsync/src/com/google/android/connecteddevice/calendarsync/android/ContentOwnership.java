package com.google.android.connecteddevice.calendarsync.android;

/**
 * Represents if this device is the source the calendar data or if it will hold a replica.
 *
 * <p>The mobile device will be configured as the source, while the car will be configured to be a
 * replica.
 */
public enum ContentOwnership {
  SOURCE,
  REPLICA
}
