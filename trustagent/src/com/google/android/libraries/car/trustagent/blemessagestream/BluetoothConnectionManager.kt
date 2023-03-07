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

package com.google.android.libraries.car.trustagent.blemessagestream

import android.bluetooth.BluetoothDevice
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A generic class which manages common logics shared among different connection managers e.g.
 * callbacks registration.
 */
abstract class BluetoothConnectionManager() {
  internal open val connectionCallbacks = mutableListOf<ConnectionCallback>()
  internal open val messageCallbacks = mutableListOf<MessageCallback>()

  abstract val maxWriteSize: Int
  abstract val bluetoothDevice: BluetoothDevice

  abstract val deviceName: String?

  /**
   * Connects to remote device.
   *
   * A successful connection should invoke [ConnectionCallback.onConnected].
   *
   * Connection enables [sendMessage].
   */
  abstract fun connect()

  /**
   * Connects to remote device.
   *
   * This method functions the same as [connect] except it returns `true` if connection was
   * established, instead of [ConnectionCallback].
   *
   * Use this method, rather than [connect], if you are not interested in the disconnection
   * callback, or will register a callback separately.
   */
  open suspend fun connectToDevice(): Boolean =
    suspendCancellableCoroutine<Boolean> { cont ->
      val callback =
        object : ConnectionCallback {
          override fun onConnected() {
            logd(TAG, "Device connected!")
            unregisterConnectionCallback(this)
            cont.resume(true)
          }

          override fun onConnectionFailed() {
            loge(TAG, "Bluetooth could not establish connection.")
            unregisterConnectionCallback(this)
            cont.resume(false)
          }

          override fun onDisconnected() {
            loge(TAG, "Disconnected while attempting to establish connection.")
            unregisterConnectionCallback(this)
            cont.resume(false)
          }
        }
      registerConnectionCallback(callback)
      logd(TAG, "Connecting to device")
      connect()
    }

  abstract fun disconnect()

  /**
   * Sends [message] to a connected device.
   *
   * The delivery of data is notified through [MessageCallback.onMessageSent]. This method should
   * not be invoked again before the callback; otherwise the behavior is undefined.
   *
   * @return `true` if the request to send data was initiated successfully; `false` otherwise.
   */
  abstract fun sendMessage(message: ByteArray): Boolean

  open fun registerConnectionCallback(callback: ConnectionCallback) {
    check(connectionCallbacks.add(callback)) { "Could not add connection callback." }
  }

  open fun unregisterConnectionCallback(callback: ConnectionCallback) {
    if (!connectionCallbacks.remove(callback)) {
      logi(TAG, "Did not remove callback from existing ones.")
    }
  }

  open fun registerMessageCallback(callback: MessageCallback) {
    check(messageCallbacks.add(callback)) { "Could not add message callback." }
  }

  open fun unregisterMessageCallback(callback: MessageCallback) {
    if (!messageCallbacks.remove(callback)) {
      logi(TAG, "Did not remove callback from existing ones.")
    }
  }

  /** Callback that will be notified for [connect] result. */
  interface ConnectionCallback {
    /** Invoked when GATT has been connected. */
    fun onConnected()

    /** Invoked when [connect] could not be completed. */
    fun onConnectionFailed()

    /** Invoked when GATT has been disconnected. */
    fun onDisconnected()
  }

  /** Callback that will be notified for [sendMessage] result and incoming messages. */
  interface MessageCallback {
    /** Invoked when a message has been received. */
    fun onMessageReceived(data: ByteArray)

    /** Invoked when [message] has been sent successfully. */
    fun onMessageSent(message: ByteArray) {
      // Defaults to no-op. Only override if acknowledgement for sending a message is required.
      // For example, sending chunked message through `BleMessageStreamV2`.
    }
  }

  companion object {
    private const val TAG = "BluetoothConnectionManager"
  }
}
