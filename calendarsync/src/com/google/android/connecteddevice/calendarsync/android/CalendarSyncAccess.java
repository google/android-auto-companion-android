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

import static com.google.common.base.Preconditions.checkState;

import android.content.ContentResolver;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.connecteddevice.calendarsync.common.BaseCalendarSync;
import com.google.android.connecteddevice.calendarsync.common.BaseComponentBuilder;
import com.google.android.connecteddevice.calendarsync.common.CommonLogger;
import com.google.android.connecteddevice.calendarsync.common.DaggerReplicaCalendarSyncComponent;
import com.google.android.connecteddevice.calendarsync.common.DaggerSourceCalendarSyncComponent;
import com.google.android.connecteddevice.calendarsync.common.RemoteSender;
import com.google.android.connecteddevice.calendarsync.common.ReplicaCalendarSync;
import com.google.android.connecteddevice.calendarsync.common.ReplicaCalendarSyncComponent;
import com.google.android.connecteddevice.calendarsync.common.SourceCalendarSync;
import com.google.android.connecteddevice.calendarsync.common.SourceCalendarSyncComponent;
import com.google.android.connecteddevice.calendarsync.common.TimeWindow;
import java.time.Clock;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Allows access to a {@link BaseCalendarSync} subclass on a background handler thread.
 *
 * <p>Manages starting and stopping the handler thread.
 *
 * <p>This is used from both Android implementations: the phone and the car.
 */
public class CalendarSyncAccess<CalendarSyncT extends BaseCalendarSync> {
  private static final String TAG = "CalendarSyncAccess";

  private final CommonLogger logger;
  private final AndroidCalendarSyncFactory<CalendarSyncT> calendarSyncFactory;

  @Nullable private HandlerThread backgroundHandlerThread;
  @Nullable private Handler handler;
  @Nullable private CalendarSyncT sync;

  @VisibleForTesting
  CalendarSyncAccess(
      CommonLogger.Factory commonLoggerFactory,
      AndroidCalendarSyncFactory<CalendarSyncT> calendarSyncFactory) {
    logger = commonLoggerFactory.create(TAG);
    this.calendarSyncFactory = calendarSyncFactory;
  }

  /**
   * Runs a task with the {@link BaseCalendarSync} by posting on the background handler. Must be
   * called after {@link #start()}
   */
  public void access(Consumer<CalendarSyncT> task) {
    checkState(handler != null, "Must call start() first");
    // Reference the sync locally in case the field is nulled.
    final CalendarSyncT nonNullSync = sync;
    handler.post(
        () -> {
          try {
            task.accept(nonNullSync);
          } catch (RuntimeException e) {
            // Catch and log exceptions without crashing the background thread.
            logger.error("Caught exception running calendar access task", e);
          }
        });
  }

  /** Returns true when the background thread is started after calling {@link #start()}. */
  public boolean isStarted() {
    return sync != null;
  }

  /**
   * Start the background handler thread and creates the {@link BaseCalendarSync}. Must be called
   * before calling {@link #access(Consumer)}. This calls {@link BaseCalendarSync#start()} on the
   * background handler.
   */
  public void start() {
    if (sync == null) {
      backgroundHandlerThread = new HandlerThread(TAG);
      backgroundHandlerThread.start();
      handler = new Handler(backgroundHandlerThread.getLooper());
      sync = calendarSyncFactory.create(handler);
      access(BaseCalendarSync::start);
    } else {
      logger.error("start() was called multiple times without calling stop() first.");
    }
  }

  /**
   * Stops the background handler thread and releases the {@link BaseCalendarSync}. This calls
   * {@link BaseCalendarSync#stop()} on the background handler.
   */
  public void stop() {
    if (sync != null) {
      access(BaseCalendarSync::stop);
      sync = null;
      backgroundHandlerThread.quitSafely();
      backgroundHandlerThread = null;
      handler = null;
    } else {
      logger.error("stop() was called without calling start() first.");
    }
  }

  /** Something that accepts a {@link Handler} and returns a {@link BaseCalendarSync}. */
  @VisibleForTesting
  interface AndroidCalendarSyncFactory<CalendarSyncT extends BaseCalendarSync> {
    CalendarSyncT create(Handler handler);
  }

  /**
   * Responsible for assembling the Android specific dependencies to create a {@link
   * CalendarSyncAccess}.
   */
  public abstract static class Factory<CalendarSyncT extends BaseCalendarSync> {

    /**
     * Creates a factory for {@link CalendarSyncAccess} that holds a {@link ReplicaCalendarSync}.
     *
     * <p>This is for use on the car which will have a replica of the calendars on the phone.
     */
    public static Factory<ReplicaCalendarSync> createReplicaFactory(
        CommonLogger.Factory commonLoggerFactory, ContentResolver resolver) {
      return new Factory<ReplicaCalendarSync>(
          commonLoggerFactory, resolver, ContentOwnership.REPLICA) {
        @Override
        public ReplicaCalendarSync createCalendarSync(RemoteSender sender, Handler handler) {
          ReplicaCalendarSyncComponent.Builder builder =
              DaggerReplicaCalendarSyncComponent.builder();
          CalendarContentDelegate calendarContentDelegate =
              new CalendarContentDelegate(commonLoggerFactory, resolver, ownership);
          setCommonDependencies(builder, sender, handler, calendarContentDelegate);
          builder.contentCleanerDelegate(calendarContentDelegate);
          return builder.build().createReplicaCalendarSync();
        }
      };
    }

    /**
     * Creates a factory for {@link CalendarSyncAccess} that holds a {@link SourceCalendarSync}.
     *
     * <p>This is for use on the phone which will send the source calendars to the car.
     */
    public static Factory<SourceCalendarSync> createSourceFactory(
        CommonLogger.Factory commonLoggerFactory,
        ContentResolver resolver,
        Supplier<TimeWindow> timeWindowSupplier) {
      return new Factory<SourceCalendarSync>(
          commonLoggerFactory, resolver, ContentOwnership.SOURCE) {
        @Override
        public SourceCalendarSync createCalendarSync(RemoteSender sender, Handler handler) {
          SourceCalendarSyncComponent.Builder builder = DaggerSourceCalendarSyncComponent.builder();
          setCommonDependencies(
              builder,
              sender,
              handler,
              new CalendarContentDelegate(commonLoggerFactory, resolver, ownership));
          builder.timeWindowSupplier(timeWindowSupplier);
          return builder.build().createSourceCalendarSync();
        }
      };
    }

    private final CommonLogger.Factory commonLoggerFactory;
    private final ContentResolver resolver;
    protected final ContentOwnership ownership;

    private Factory(
        CommonLogger.Factory commonLoggerFactory,
        ContentResolver resolver,
        ContentOwnership ownership) {
      this.commonLoggerFactory = commonLoggerFactory;
      this.resolver = resolver;
      this.ownership = ownership;
    }

    /** Creates a {@link CalendarSyncAccess} which sends messages with the given {@code sender}. */
    public CalendarSyncAccess<CalendarSyncT> create(RemoteSender sender) {
      return new CalendarSyncAccess<>(
          commonLoggerFactory, handler -> createCalendarSync(sender, handler));
    }

    protected abstract CalendarSyncT createCalendarSync(RemoteSender sender, Handler handler);

    /** Creates and sets dependencies common to both source and replica. */
    protected <BuilderT extends BaseComponentBuilder<BuilderT>> void setCommonDependencies(
        BuilderT builder,
        RemoteSender sender,
        Handler handler,
        CalendarContentDelegate calendarContentDelegate) {
      builder
          .commonLoggerFactory(commonLoggerFactory)
          .remoteSender(sender)
          .scheduler(new HandlerScheduler(handler, Clock.system(ZoneId.systemDefault())))
          .calendarContentDelegate(calendarContentDelegate)
          .eventContentDelegateFactory(
              new EventContentDelegate.Factory(commonLoggerFactory, resolver, ownership))
          .attendeeContentDelegate(new AttendeeContentDelegate(commonLoggerFactory, resolver))
          .calendarsObservable(new ResolverCalendarsObservable(handler, resolver));
    }
  }
}
