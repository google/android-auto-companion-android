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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor

/** This task runs when sending a connection request to remote device. */
// TODO: Remove lint suppression once false positive lint error has been fixed.
@SuppressLint("MissingPermission")
internal class ConnectTask(
  device: BluetoothDevice,
  isSecure: Boolean,
  serviceUuid: UUID,
  private val callback: Callback,
  private val callbackExecutor: Executor
) : Runnable {
  private var socket: BluetoothSocket? = null

  init {
    try {
      socket =
        if (isSecure) {
          device.createRfcommSocketToServiceRecord(serviceUuid)
        } else {
          device.createInsecureRfcommSocketToServiceRecord(serviceUuid)
        }
    } catch (e: IOException) {
      loge(TAG, "Socket create() failed", e)
    }
  }

  @SuppressLint("MissingPermission")
  override fun run() {
    if (socket == null) {
      loge(TAG, "Socket is null, can not begin ConnectTask")
      callbackExecutor.execute { callback.onConnectionAttemptFailed() }
      return
    }
    logi(TAG, "Begin ConnectTask.")
    // Always cancel discovery because it will slow down a connection
    BluetoothAdapter.getDefaultAdapter().cancelDiscovery()
    try {
      // This is a blocking call and will only return on a successful connection or an exception
      socket?.connect()
    } catch (e: IOException) {
      loge(TAG, "Exception when connecting to device.", e)
      cancel()
      callbackExecutor.execute { callback.onConnectionAttemptFailed() }
      return
    }
    socket?.let { callbackExecutor.execute { callback.onConnectionSuccess(it) } }
  }

  fun cancel() {
    try {
      socket?.close()
    } catch (e: IOException) {
      loge(TAG, "close() of connect socket failed", e)
    }
  }

  /** Callbacks which informs the result of this task. */
  interface Callback {

    /** Will be called when the [ConnectTask] completed successfully. */
    fun onConnectionSuccess(socket: BluetoothSocket)

    /**
     * Called when connection failed at any stage. Further call on the current [ConnectTask] object
     * should be avoid.
     */
    fun onConnectionAttemptFailed()
  }

  companion object {
    private const val TAG = "ConnectTask"
  }
}
