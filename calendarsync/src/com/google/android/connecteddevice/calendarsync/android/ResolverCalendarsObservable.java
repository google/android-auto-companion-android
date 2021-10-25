package com.google.android.connecteddevice.calendarsync.android;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.CalendarContract;
import com.google.android.connecteddevice.calendarsync.common.CalendarsObservable;

/**
 * Observes calendar changes using {@link ContentResolver#registerContentObserver(Uri, boolean,
 * ContentObserver)}
 */
public final class ResolverCalendarsObservable implements CalendarsObservable {
  private final Handler handler;
  private final ContentResolver resolver;

  /** @param handler The handler to use to callback to observers. */
  public ResolverCalendarsObservable(Handler handler, ContentResolver resolver) {
    this.handler = handler;
    this.resolver = resolver;
  }

  @Override
  public ObservationHandle observe(CalendarsObserver calendarsObserver) {
    ContentObserver contentObserver =
        new ContentObserver(handler) {
          @Override
          public void onChange(boolean selfChange) {
            calendarsObserver.onCalendarsChanged();
          }
        };
    resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, contentObserver);
    return () -> resolver.unregisterContentObserver(contentObserver);
  }
}
