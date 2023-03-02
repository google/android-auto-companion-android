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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.GuardedBy
import com.google.android.companionprotos.OutOfBandAssociationToken
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A channel for out-of-band verification, established over RFCOMM Bluetooth socket.
 *
 * This class acts as the Bluetooth socket server. It runs a blocking call to accept connection.
 *
 * @property backgroundContext The coroutine context to launch task with; must not be main thread.
 */
internal class BluetoothRfcommChannel(
  private val isProtoApplied: Boolean,
  private val backgroundContext: CoroutineContext
) : OobChannel {
  private val lock = Any()

  private val isInterrupted = AtomicBoolean(false)
  @GuardedBy("lock") internal var bluetoothSocket: BluetoothSocket? = null

  override var callback: OobChannel.Callback? = null

  /** Launches blocking calls to read OOB data on [backgroundContext]. */
  @SuppressLint("MissingPermission")
  override fun startOobDataExchange(device: BluetoothDevice) {
    CoroutineScope(backgroundContext).launch { oobDataExchange(device) }
  }

  /** This method blocks waiting for socket connection so it must not run in the main thread. */
  internal fun oobDataExchange(device: BluetoothDevice) {
    isInterrupted.set(false)

    // Wait for the remote device to connect through BluetoothSocket.
    val bluetoothSocket = connectToDevice(device)
    if (isInterrupted.get()) {
      logi(TAG, "Stopped after socket connection.")
      return
    }

    if (bluetoothSocket == null) {
      loge(TAG, "Bluetooth socket was not able to accept connection.")
      callback?.onFailure()
      cleanUp()
      return
    }
    synchronized(lock) { this.bluetoothSocket = bluetoothSocket }

    // Attempt to read OOB data out of the BluetoothSocket.
    val oobData = readOobData(bluetoothSocket.inputStream)
    if (isInterrupted.get()) {
      logi(TAG, "Stopped while reading OOB data.")
      return
    }

    if (oobData != null) {
      callback?.onSuccess(oobData)
    } else {
      callback?.onFailure()
    }
    cleanUp()
  }

  /**
   * Establishes a Bluetooth connection with remote [device].
   *
   * This call blocks until a connection is established, was aborted, or failed.
   *
   * Returns the established bluetooth socket, or `null` if the device is not bonded to the [device]
   * or the connection failed.
   */
  private fun connectToDevice(device: BluetoothDevice): BluetoothSocket? {
    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    if (device !in bluetoothAdapter.getBondedDevices()) {
      logi(
        TAG,
        "Device $device is not bonded, so will not attempt to establish Bluetooth connection."
      )
      // No need to clean up because we cannot establish any connection.
      return null
    }

    return try {
      device.createRfcommSocketToServiceRecord(RFCOMM_UUID).apply { connect() }
    } catch (e: IOException) {
      loge(TAG, "Exception when connecting to device $device.", e)
      cleanUp()
      null
    }
  }

  /**
   * Returns the out-of-band data read via [inputStream].
   *
   * Returns `null` if OOB data could not be read fully.
   *
   * Launch this method in [backgroundContext] because it blocks reading the data.
   */
  private fun readOobData(inputStream: InputStream): OobData? {
    val sizeData = readData(inputStream, DATA_SIZE_BYTES)
    if (sizeData == null) {
      loge(TAG, "Could not receive size of out-of-band data.")
      return null
    }

    val messageLength = ByteBuffer.wrap(sizeData).order(ByteOrder.LITTLE_ENDIAN).int
    val tokenBytes = readData(inputStream, messageLength)

    return if (isProtoApplied) {
      tokenBytes?.let { OutOfBandAssociationToken.parseFrom(it) }?.toOobData()
    } else {
      tokenBytes?.toOobData()
    }
  }

  /** Reads [size] bytes from [inputStream]. Returns `null` if reading fails. */
  private fun readData(inputStream: InputStream, size: Int): ByteArray? {
    val buffer = ByteArray(size)
    var bytesRead = 0
    while (bytesRead < size) {
      val read =
        try {
          inputStream.read(buffer)
        } catch (e: IOException) {
          loge(TAG, "Could not read OOB data from input stream.", e)
          break
        }
      if (read == -1) {
        loge(TAG, "The stream is at the end of file. Stopped reading.")
        break
      }
      bytesRead += read
    }

    logi(TAG, "Read $bytesRead; expected $size.")
    return if (bytesRead == size) {
      buffer
    } else {
      null
    }
  }

  override fun stopOobDataExchange() {
    isInterrupted.set(true)
    cleanUp()
  }

  private fun cleanUp() {
    synchronized(lock) {
      logi(TAG, "Closing BluetoothSocket and InputStream.")
      bluetoothSocket?.inputStream?.close()
      bluetoothSocket?.close()
      bluetoothSocket = null
    }
  }

  companion object {
    private const val TAG = "BluetoothRfcommChannel"
    // TODO: Generate a random UUID
    private val RFCOMM_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Number of bytes that represents an int, which will be the size of OOB data.
    private const val DATA_SIZE_BYTES = 4
  }
}
