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
import java.util.concurrent.CopyOnWriteArrayList
import org.robolectric.shadow.api.Shadow

/** A fake implementation. */
// Open to be spied on by tests.
open class FakeBluetoothConnectionManager() : BluetoothConnectionManager() {
  public override val connectionCallbacks = CopyOnWriteArrayList<ConnectionCallback>()
  public override val messageCallbacks = CopyOnWriteArrayList<MessageCallback>()

  override val maxWriteSize: Int = 512
  override val bluetoothDevice: BluetoothDevice = Shadow.newInstanceOf(BluetoothDevice::class.java)

  override val deviceName: String? = "FakeBluetoothConnectionManager"

  /** Stores messages by [sendMessage]. */
  val sentMessages = mutableListOf<ByteArray>()

  /**
   * Determines the callback of [connect].
   *
   * Invokes [ConnectionCallback.onConnected] if this is true;
   * [ConnectionCallback.onConnectionFailed] otherwise.
   */
  var isConnectionSuccessful: Boolean = true

  /**
   * Connects to remote device.
   *
   * Makes connection callback depending on [isConnectionSuccessful].
   */
  override fun connect() {
    for (callback in connectionCallbacks) {
      if (isConnectionSuccessful) {
        callback.onConnected()
      } else {
        callback.onConnectionFailed()
      }
    }
  }

  /**
   * Connects to remote device.
   *
   * Always succeeds.
   */
  override suspend fun connectToDevice(): Boolean = true

  /** No-op. */
  override fun disconnect() {}

  /**
   * Sends [message] to a connected device.
   *
   * Always returns `true`. Out-going messages can be viewed by [sendMessages].
   *
   * To fake a successful message delivery, manually invoke [MessageCallback.onMessageSent].
   */
  override fun sendMessage(message: ByteArray): Boolean {
    sentMessages.add(message)
    return true
  }

  override fun registerConnectionCallback(callback: ConnectionCallback) {
    check(connectionCallbacks.add(callback)) { "Could not add connection callback." }
  }

  override fun unregisterConnectionCallback(callback: ConnectionCallback) {
    check(connectionCallbacks.remove(callback)) { "Did not remove callback." }
  }

  override fun registerMessageCallback(callback: MessageCallback) {
    check(messageCallbacks.add(callback)) { "Could not add message callback." }
  }

  override fun unregisterMessageCallback(callback: MessageCallback) {
    check(messageCallbacks.remove(callback)) { "Did not remove callback." }
  }
}
