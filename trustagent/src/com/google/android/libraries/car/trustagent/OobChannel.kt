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

/**
 * A channel that can handle out-of-band verification for establishing a secure connection with a
 * remote vehicle.
 */
internal interface OobChannel {

  /**
   * Attempts to connect to an out of band-capable device and initiate data exchange.
   *
   * The phone should receive the out of band data sent by the IHU. If it successfully receives the
   * data, it should call [oobConnectionManager.setOobData], and then
   * [callback.onOobExchangeSuccess]. Otherwise, it should call [callback.onOobExchangeFailure].
   */
  suspend fun startOobDataExchange(oobConnectionManager: OobConnectionManager, callback: Callback?)

  /** Cancels the out of band discovery and data exchange, typically due to a user action. */
  fun stopOobDataExchange()

  /** Callbacks that will be invoked for OOB discovery events. */
  interface Callback {
    fun onOobExchangeSuccess(discoveredCar: DiscoveredCar)
    fun onOobExchangeFailure()
  }
}
