package com.google.android.connecteddevice.calendarsync.common;

/**
 * A simple observable interface similar to {@link java.util.Observable} but without passing any
 * value to the observers.
 *
 * <p>When calendar changes are detected to calendars on Android and iOS, no details of the change
 * are reported. The observer needs to re-read all relevant data and decide what action to take.
 */
public interface CalendarsObservable {
  ObservationHandle observe(CalendarsObserver observer);

  /** A handle that allows the observer to be unregistered. */
  interface ObservationHandle {
    void unregister();
  }

  /** An object that receives callbacks when there was a change to calendar data on the platform. */
  interface CalendarsObserver {

    /**
     * Called when a change was made to calendar data.
     *
     * <p>This change could be to any calendar at any time and may not be relevant to the observer.
     */
    void onCalendarsChanged();
  }
}
