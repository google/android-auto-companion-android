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

import com.google.android.connecteddevice.calendarsync.Attendee;
import com.google.android.connecteddevice.calendarsync.Calendar;
import com.google.android.connecteddevice.calendarsync.common.PlatformContentDelegate.EventContentDelegateFactory;
import dagger.BindsInstance;

/** Common dependencies for both {@link SourceCalendarSync} and {@link ReplicaCalendarSync}. */
public interface BaseComponentBuilder<BuilderT> {

  @BindsInstance
  BuilderT commonLoggerFactory(CommonLogger.Factory instance);

  @BindsInstance
  BuilderT remoteSender(RemoteSender instance);

  @BindsInstance
  BuilderT scheduler(Scheduler instance);

  @BindsInstance
  BuilderT calendarContentDelegate(PlatformContentDelegate<Calendar> instance);

  @BindsInstance
  BuilderT eventContentDelegateFactory(EventContentDelegateFactory instance);

  @BindsInstance
  BuilderT attendeeContentDelegate(PlatformContentDelegate<Attendee> instance);

  @BindsInstance
  BuilderT calendarsObservable(CalendarsObservable instance);
}
