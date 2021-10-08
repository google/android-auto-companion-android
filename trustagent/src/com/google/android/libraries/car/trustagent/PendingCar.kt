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
import android.content.Context
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import java.util.UUID

/** Represents a car that is ready to be associated (first time) or re-connected with. */
internal interface PendingCar {
  /**
   * The bluetooth device that this [PendingCar] is connecting to.
   *
   * Keeping the scan result because it's returned by ConnectionManager callback.
   */
  val device: BluetoothDevice

  /**
   * Whether this pending car is performing association handshake.
   *
   * If false, this object will attempt to reconnect.
   */
  val isAssociating: Boolean

  var callback: Callback?

  /**
   * Initiates connection to connect to a remote device.
   *
   * Successful connection will invoke [Callback.onConnected].
   *
   * If [isAssociating], [Callback.onAuthStringAvailable] will be invoked for out-of-band
   * verification. If not, i.e. reconnection, [Callback.onConnectionFailed] will be invoked if the
   * previous session could not be found, and connection will be stopped.
   */
  fun connect(advertisedData: ByteArray? = null)

  /**
   * Stops current connection.
   *
   * After disconnection, this object should be discarded.
   */
  fun disconnect()

  /** Propagates callbacks from internal objects to class that exposes external API. */
  interface Callback {
    /** Invoked when the remote device ID has been received. */
    fun onDeviceIdReceived(deviceId: UUID)

    /** Invoked for a first-time connection as part of out-of-band user verification. */
    fun onAuthStringAvailable(authString: String)

    /** Invoked when encryption is established. [car] is ready for exchanging messages. */
    fun onConnected(car: Car)

    /**
     * Invoked when encryption could not be established.
     *
     * Device will automatically be disconnected. [pendingCar] should be discarded.
     */
    fun onConnectionFailed(pendingCar: PendingCar)
  }

  companion object {
    /** Creates a [PendingCar] to connect to. */
    internal fun create(
      securityVersion: Int,
      context: Context,
      isAssociating: Boolean,
      stream: MessageStream,
      associatedCarManager: AssociatedCarManager,
      device: BluetoothDevice,
      bluetoothManager: BluetoothConnectionManager,
      oobConnectionManager: OobConnectionManager? = null
    ): PendingCar =
      when (securityVersion) {
        // Version 2 and 3 go through the same handshake. The difference being version 3 added
        // capability exchange, which has already happened before this PendingCar is created.
        2,
        3 ->
          if (isAssociating && oobConnectionManager != null) {
            PendingCarV2OobAssociation(
              context,
              stream,
              associatedCarManager,
              device,
              bluetoothManager,
              oobConnectionManager
            )
          } else if (isAssociating && oobConnectionManager == null) {
            PendingCarV2Association(context, stream, associatedCarManager, device, bluetoothManager)
          } else {
            PendingCarV2Reconnection(stream, associatedCarManager, device, bluetoothManager)
          }
        // Version 1 is no longer supported.
        else -> {
          throw IllegalArgumentException("Unsupported security version: $securityVersion.")
        }
      }
  }
}
