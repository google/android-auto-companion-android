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

package com.google.android.libraries.car.connectionservice

import com.google.android.libraries.car.trustagent.util.logi
import java.util.UUID

// LINT.IfChange
/** Logs events that are expected by automated test. */
object EventLog {
  private const val TAG = "ConnectionEvent"

  fun onServiceStarted() {
    logi(TAG, "Service has been started.")
  }

  fun onServiceStopped() {
    logi(TAG, "Service has been stopped.")
  }

  fun onCarConnected(deviceId: UUID) {
    logi(TAG, "Car of $deviceId has connected.")
  }

  fun onStartForeground() {
    logi(TAG, "Service has started running in the foreground.")
  }

  fun onStopForeground() {
    logi(TAG, "Service has stopped running in the foreground.")
  }
}
// LINT.ThenChange(//depot/google3/java/com/google/android/libraries/automotive/multidevice/testing/python_aae/tests/companion_android_longevity_test.py)
