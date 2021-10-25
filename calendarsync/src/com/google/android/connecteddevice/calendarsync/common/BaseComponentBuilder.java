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
