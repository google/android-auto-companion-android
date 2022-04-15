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

package com.google.android.libraries.car.trustagent

import android.bluetooth.BluetoothDevice

/**
 * A channel that can handle out-of-band verification for establishing a secure connection with a
 * remote vehicle.
 */
interface OobChannel {

  var callback: Callback?

  /**
   * Attempts to connect to an out of band-capable [device] and initiate data exchange.
   *
   * The phone should receive the out of band data sent by the remote [device]. If it successfully
   * receives the data, [Callback.onSuccess] will be invoked.
   */
  fun startOobDataExchange(device: BluetoothDevice)

  /**
   * Cancels the out of band discovery and data exchange, typically due to a user action.
   *
   * Does not trigger [Callback.onFailure].
   */
  fun stopOobDataExchange()

  /**
   * Callbacks that will be invoked for OOB exchange events.
   *
   * A channel should only make a single callback of either success or failure.
   */
  interface Callback {
    /** Invoked when the OOB channel has successfully received the data. */
    fun onSuccess(oobData: OobData)

    /**
     * Invoked when the OOB channel failed to receive data.
     *
     * For example when a connection could not be established, or OOB data was not read in full.
     */
    fun onFailure()
  }
}
