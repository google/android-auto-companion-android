package com.google.android.connecteddevice.calendarsync.common;

import dagger.BindsInstance;
import dagger.Component;
import java.util.function.Supplier;

/** A component to create a {@link SourceCalendarSync}. */
@Component
public interface SourceCalendarSyncComponent {

  /** Creates a {@link SourceCalendarSync}. */
  SourceCalendarSync createSourceCalendarSync();

  /** Defines the required external dependencies. */
  @Component.Builder
  interface Builder extends BaseComponentBuilder<Builder> {
    @BindsInstance
    Builder timeWindowSupplier(Supplier<TimeWindow> instance);

    SourceCalendarSyncComponent build();
  }
}
