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

import com.google.android.connecteddevice.calendarsync.UpdateCalendars;
import javax.inject.Inject;

/**
 * Receives calendar data from a remote device and sends back changes in the given time range.
 *
 * <p>This class will only be used on the car, as the iOS phone and Android phone will use {@link
 * SourceCalendarSync}.
 *
 * <p>If the remote device is not a recent version that supports receiving updates then no changes
 * will be sent back and this device will only receive data.
 *
 * <p>{@inheritDoc}
 */
public class ReplicaCalendarSync extends BaseCalendarSync {
  private static final String TAG = "ReplicaCalendarSync";

  private final ContentCleanerDelegate contentCleanerDelegate;

  @Inject
  ReplicaCalendarSync(
      CommonLogger.Factory commonLoggerFactory,
      RemoteSender sender,
      CalendarStore store,
      Scheduler scheduler,
      CalendarsObservable calendarsObservable,
      ContentCleanerDelegate contentCleanerDelegate,
      CalendarManagerFactory calendarManagerFactory) {
    super(
        commonLoggerFactory.create(TAG),
        sender,
        store,
        scheduler,
        calendarsObservable,
        calendarManagerFactory);
    this.contentCleanerDelegate = contentCleanerDelegate;
  }

  @Override
  protected void sendDeviceChangeUpdate(DeviceState state) {
    if (isUpdatable(state)) {
      super.sendDeviceChangeUpdate(state);
    } else {
      logger.info("Not processing changes for non-updatable remote device %s", state.getDeviceId());
    }
  }

  @Override
  protected void send(UpdateCalendars update, String deviceId) {
    // A legacy source device that is not updatable cannot receive any messages.
    DeviceState state = getOrCreateState(deviceId);
    if (isUpdatable(state)) {
      super.send(update, deviceId);
    } else {
      logger.info(
          "Not sending %s to non-updatable remote device %s",
          update.getType().name(), state.getDeviceId());
    }
  }

  @Override
  protected void handleDisableMessage(String deviceId) {
    clear(deviceId);
  }

  @Override
  public void clear(String deviceId) {
    if (deviceIdToState.containsKey(deviceId)) {
      CalendarManager manager = getCalendarManager(deviceId);
      manager.deleteAll(deviceId);
    } else {
      logger.warn("Clear was called but there was no state for device %s", deviceId);
    }

    // Must clear the in-memory state after getting the manager for the device.
    super.clear(deviceId);
  }

  @Override
  public void start() {
    // Content should have been deleted in stop() but could remain if the process was stopped.
    if (contentCleanerDelegate.clean()) {
      logger.warn("Content was present at start");
    }

    // Must start listening for changes after cleaning remaining content.
    super.start();
  }

  @Override
  public void stop() {
    super.stop();
    contentCleanerDelegate.clean();
  }
}
