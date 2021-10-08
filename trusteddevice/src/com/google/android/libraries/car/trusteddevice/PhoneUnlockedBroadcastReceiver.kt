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

package com.google.android.libraries.car.trusteddevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.libraries.car.trustagent.api.PublicApi

/**
 * Receives broadcast when phone is unlocked and calls [onPhoneUnlocked] method passed from the
 * constructor.
 */
@PublicApi
class PhoneUnlockedBroadcastReceiver(
  private val onPhoneUnlocked: () -> Unit
) : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (isDeviceSecure(context)) {
      onPhoneUnlocked()
    }
  }
}
