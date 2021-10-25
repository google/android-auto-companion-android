package com.google.android.connecteddevice.calendarsync.common;

import dagger.BindsInstance;
import dagger.Component;

/** A component to create a {@link ReplicaCalendarSync}. */
@Component
public interface ReplicaCalendarSyncComponent {

  /** Creates a {@link SourceCalendarSync}. */
  ReplicaCalendarSync createReplicaCalendarSync();

  /** Defines the required external dependencies. */
  @Component.Builder
  interface Builder extends BaseComponentBuilder<Builder> {

    @BindsInstance
    Builder contentCleanerDelegate(ContentCleanerDelegate instance);

    ReplicaCalendarSyncComponent build();
  }
}
