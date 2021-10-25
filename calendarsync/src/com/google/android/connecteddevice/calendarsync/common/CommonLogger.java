package com.google.android.connecteddevice.calendarsync.common;

import com.google.errorprone.annotations.FormatMethod;
import java.util.Locale;

/**
 * An interface that allows platform specific logging implementations to be used in the common code.
 */
public interface CommonLogger {
  /** Logs a DEBUG level message. */
  void debug(String message);

  /** Logs a DEBUG level message with {@link String#format(String, Object...)} args. */
  @FormatMethod
  default void debug(String message, Object... args) {
    debug(format(message, args));
  }

  /** Logs an INFO level message. */
  void info(String message);

  /** Logs a INFO level message with {@link String#format(String, Object...)} args. */
  @FormatMethod
  default void info(String message, Object... args) {
    info(format(message, args));
  }

  /** Logs a WARN level message. */
  void warn(String message);

  /** Logs a WARN level message with {@link String#format(String, Object...)} args. */
  @FormatMethod
  default void warn(String message, Object... args) {
    warn(format(message, args));
  }

  /** Logs an ERROR level message. */
  void error(String message);

  /** Logs a ERROR level message with {@link String#format(String, Object...)} args. */
  @FormatMethod
  default void error(String message, Object... args) {
    error(format(message, args));
  }

  /** Logs an ERROR level message with an exception. */
  void error(String message, Exception e);

  default String format(String message, Object[] args) {
    // Ensure consistent formatting across devices by explicitly defining the locale.
    return String.format(Locale.US, message, args);
  }

  /** Creates a {@link CommonLogger} with the given name. */
  interface Factory {
    CommonLogger create(String name);
  }

  /** A no-op implementation for testing that does not log anything. */
  class NoOpLoggerFactory implements CommonLogger.Factory {
    @Override
    public CommonLogger create(String name) {
      return new CommonLogger() {
        @Override
        public void debug(String message) {}

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void error(String message) {}

        @Override
        public void error(String message, Exception e) {}
      };
    }
  }
}
