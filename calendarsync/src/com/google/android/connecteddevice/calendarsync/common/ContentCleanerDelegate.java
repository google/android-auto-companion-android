package com.google.android.connecteddevice.calendarsync.common;

/**
 * A platform delegate that can be used to ensure all remote personal calendar content is removed
 * from this device.
 */
public interface ContentCleanerDelegate {

  /**
   * Cleans all calendar content that was created by this package.
   *
   * <p>This is similar to calling {@link PlatformContentDelegate#deleteAll(Object)} but does not
   * require the device ids.
   *
   * @return {@code true} if content was present and cleaned.
   */
  boolean clean();
}
