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
