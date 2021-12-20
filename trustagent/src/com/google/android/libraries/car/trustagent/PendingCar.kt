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
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.uuidToBytes
import java.util.UUID
import javax.crypto.SecretKey

/** Represents a car that is ready to be associated (first time) or re-connected with. */
internal interface PendingCar {
  /**
   * The bluetooth device that this [PendingCar] is connecting to.
   *
   * Keeping the scan result because it's returned by ConnectionManager callback.
   */
  val device: BluetoothDevice

  var callback: Callback?

  /**
   * Initiates connection to connect to a remote device.
   *
   * Successful connection will invoke [Callback.onConnected].
   *
   * If the implmentation is performing association, [Callback.onAuthStringAvailable] should be
   * invoked for out-of-band verification. If not, i.e. reconnection, [Callback.onConnectionFailed]
   * should be invoked if the previous session could not be found, and connection will be stopped.
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
    // Internal to expose to the extension methods in this file.
    internal const val TAG = "PendingCar"

    /** Creates a [PendingCar] to connect to. */
    internal fun create(
      securityVersion: Int,
      context: Context,
      isAssociating: Boolean,
      stream: MessageStream,
      associatedCarManager: AssociatedCarManager,
      device: BluetoothDevice,
      bluetoothManager: BluetoothConnectionManager,
      oobChannelTypes: List<OobChannelType>,
      oobData: OobData?
    ): PendingCar {
      if (!isAssociating) {
        return when (securityVersion) {
          // Version 2/3/4 share the same flow for reconnection.
          in 2..4 ->
            PendingCarV2Reconnection(stream, associatedCarManager, device, bluetoothManager)
          else -> {
            // Version 1 is no longer supported.
            throw IllegalArgumentException("Unsupported security version: $securityVersion.")
          }
        }
      }

      return when (securityVersion) {
        2 ->
          PendingCarV2Association(context, stream, associatedCarManager, device, bluetoothManager)
        3 ->
          PendingCarV3Association(
            context,
            stream,
            associatedCarManager,
            device,
            bluetoothManager,
            oobChannelTypes,
            oobData
          )
        4 -> {
          PendingCarV4Association(
            context,
            stream,
            associatedCarManager,
            device,
            bluetoothManager,
            oobData
          )
        }
        else -> {
          // Version 1 is no longer supported.
          throw IllegalArgumentException("Unsupported security version: $securityVersion.")
        }
      }
    }

    /**
     * Creates an implementation of [BluetoothConnectionManager.ConnectionCallback].
     *
     * The created callback is intended for PendingCar implementations to listen to connection
     * events.
     *
     * For all connection events, i.e. connected/disconnected/connection failure, the created
     * callback always disconnects the PendingCar and invokes
     * [PendingCar.Callback.onConnectionFailed], because during connection phase the connection is
     * expected to remain unchanged.
     */
    internal fun createBluetoothConnectionManagerCallback(
      pendingCar: PendingCar
    ): BluetoothConnectionManager.ConnectionCallback {
      return object : BluetoothConnectionManager.ConnectionCallback {
        override fun onConnected() {
          loge(TAG, "Unexpected gatt callback: onConnected. Disconnecting.")
          pendingCar.disconnect()
          pendingCar.callback?.onConnectionFailed(pendingCar)
        }

        override fun onConnectionFailed() {
          loge(TAG, "Unexpected gatt callback: onConnectionFailed. Disconnecting.")
          pendingCar.disconnect()
          pendingCar.callback?.onConnectionFailed(pendingCar)
        }

        override fun onDisconnected() {
          // Mainly rely on disconnect() to clean up the state.
          loge(TAG, "Disconnected while attempting to establish connection.")

          pendingCar.disconnect()
          pendingCar.callback?.onConnectionFailed(pendingCar)
        }
      }
    }
  }
}

/**
 * Sends device ID and secret key as a message over [messageStream].
 *
 * Returns the message ID of sent message.
 */
internal fun MessageStream.send(
  deviceId: UUID,
  secretKey: SecretKey,
): Int {
  val payload = uuidToBytes(deviceId) + secretKey.encoded
  val messageId =
    sendMessage(
      StreamMessage(
        payload,
        operation = OperationType.ENCRYPTION_HANDSHAKE,
        isPayloadEncrypted = true,
        originalMessageSize = 0,
        recipient = null
      )
    )
  logi(PendingCar.TAG, "Sent deviceId and secret key. Message Id: $messageId")
  return messageId
}
