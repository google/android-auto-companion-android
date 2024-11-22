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
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Handles reading and receiving data from an Spp connection. Will start new thread to handle
 * [ConnectTask] and [ReadMessageTask].
 *
 * @property serviceUuid Service record UUID of the RFCOMM socket.
 */
class SppManager(
  context: Context,
  override val bluetoothDevice: BluetoothDevice,
  private val serviceUuid: UUID,
) : BluetoothConnectionManager() {
  internal var connectTask: ConnectTask? = null
  @VisibleForTesting internal var writeExecutor: Executor = Executors.newSingleThreadExecutor()
  @VisibleForTesting internal var readMessageTask: ReadMessageTask? = null

  private val connectionExecutor = Executors.newSingleThreadExecutor()
  private val taskCallbackExecutor = ContextCompat.getMainExecutor(context)
  private var connectedSocket: BluetoothSocket? = null

  @SuppressLint("MissingPermission")
  override var deviceName = bluetoothDevice.name
    private set

  @VisibleForTesting
  internal val connectTaskCallback =
    object : ConnectTask.Callback {
      override fun onConnectionSuccess(socket: BluetoothSocket) {
        logi(TAG, "Connected, remote device is ${bluetoothDevice.address}")
        connectedSocket = socket

        readMessageTask?.cancel()
        // Start the task to manage the connection and perform transmissions
        readMessageTask =
          ReadMessageTask(socket.inputStream, readMessageTaskCallback, taskCallbackExecutor)
        connectionExecutor.execute(readMessageTask)
        connectionCallbacks.forEach { it.onConnected() }
      }

      override fun onConnectionAttemptFailed() {
        connectionCallbacks.forEach { it.onConnectionFailed() }
      }
    }

  @VisibleForTesting
  internal val readMessageTaskCallback =
    object : ReadMessageTask.Callback {
      override fun onMessageReceived(message: ByteArray) {
        messageCallbacks.forEach { it.onMessageReceived(message) }
      }

      override fun onMessageReadError() {
        disconnect()
      }
    }

  override val maxWriteSize: Int = MAX_WRITE_SIZE

  /** Start the [ConnectTask] to initiate a connection to a remote device. */
  override fun connect() {
    // TODO: add retry logic for SPP
    logi(TAG, "Attempt connect to remote device.")
    // Cancel any thread attempting to make a connection
    connectTask?.cancel()

    // Start the task to connect with the given device
    connectTask =
      ConnectTask(bluetoothDevice, true, serviceUuid, connectTaskCallback, taskCallbackExecutor)
    connectionExecutor.execute(connectTask)
  }

  override fun disconnect() {
    logi(TAG, "Disconnecting SPP.")
    // Cancel the task that attempts to establish the connection
    connectTask?.cancel()
    connectTask = null

    // Cancel any task currently running a connection
    readMessageTask?.cancel()
    readMessageTask = null

    connectedSocket?.close()
    connectionCallbacks.forEach { it.onDisconnected() }
  }

  /**
   * Sends [message] to a connected device.
   *
   * The delivery of data is notified through [MessageCallback.onMessageSent]. This method should
   * not be invoked again before the callback; otherwise the behavior is undefined.
   *
   * @return `true` if the request to send data was initiated successfully; `false` otherwise.
   */
  override fun sendMessage(message: ByteArray): Boolean {
    val dataReadyToSend = wrapWithArrayLength(message)
    if (connectedSocket == null) {
      loge(TAG, "Attempted to send data when device is disconnected. Ignoring.")
      return false
    }
    writeExecutor.execute {
      try {
        connectedSocket?.outputStream?.let {
          it.write(dataReadyToSend)
          logi(TAG, "Sent message to remote device with length: " + dataReadyToSend.size)
          messageCallbacks.forEach { it.onMessageSent(message) }
        }
      } catch (e: IOException) {
        loge(TAG, "Exception during write", e)
        disconnect()
      }
    }
    return true
  }

  companion object {
    const val LENGTH_BYTES_SIZE = 4

    private const val TAG = "SppManager"
    // TODO: Currently its an arbitrary number, should update after getting more
    //  testing data.
    private const val MAX_WRITE_SIZE = 700

    /**
     * Wrap the raw byte array with array length.
     *
     * Should be called every time when server wants to send a message to client.
     *
     * @param rawData Original data
     * @return The wrapped data
     * @throws IOException If there are some errors writing data
     */
    @VisibleForTesting
    internal fun wrapWithArrayLength(rawData: ByteArray): ByteArray {
      val length = rawData.size
      val lengthBytes =
        ByteBuffer.allocate(LENGTH_BYTES_SIZE).order(ByteOrder.LITTLE_ENDIAN).putInt(length).array()
      val outputStream = ByteArrayOutputStream()
      outputStream.write(lengthBytes + rawData)
      return outputStream.toByteArray()
    }
  }
}
