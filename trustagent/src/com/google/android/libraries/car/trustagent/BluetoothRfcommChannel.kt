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
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import androidx.annotation.GuardedBy
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * A channel for out-of-band verification, established over RFCOMM Bluetooth socket.
 *
 * This class acts as the Bluetooth socket server. It runs a blocking call to accept connection.
 *
 * @property backgroundContext The coroutine context to launch task with; must not be main thread.
 */
internal class BluetoothRfcommChannel(
  private val acceptBluetoothSocketTimeout: Duration,
  private val backgroundContext: CoroutineContext,
  // TODO(b/179171369): remove this val once the experimental page is removed.
  //   Currently rfcomm needs to konw about GATT because this object will be converted to a
  //   `DiscoveredCar` that is showned to user for OOB association. The actual process now depends
  //   on resolved capability, so after the experimental page is removed we should remove this val.
  private val gattServiceUuid: UUID,
  private val sppServiceUuid: UUID
) : OobChannel {
  private val lock = Any()

  private val isInterrupted = AtomicBoolean(false)
  @GuardedBy("lock") internal var bluetoothServerSocket: BluetoothServerSocket? = null
  @GuardedBy("lock") internal var bluetoothSocket: BluetoothSocket? = null

  /** Launches blocking calls to read OOB data on [backgroundContext]. */
  @SuppressLint("MissingPermission")
  override suspend fun startOobDataExchange(
    oobConnectionManager: OobConnectionManager,
    callback: OobChannel.Callback?
  ) {
    isInterrupted.set(false)

    // Wait for the remote device to connect through BluetoothSocket.
    val bluetoothSocket = withContext(backgroundContext) { waitForBluetoothSocket() }
    if (isInterrupted.get()) {
      return
    }
    if (bluetoothSocket == null) {
      loge(TAG, "Bluetooth socket was not able to accept connection.")
      callback?.onOobExchangeFailure()
      return
    }
    synchronized(lock) { this.bluetoothSocket = bluetoothSocket }

    // Attempt to read OOB data out of the BluetoothSocket.
    val success =
      withContext(backgroundContext) { readOobData(bluetoothSocket, oobConnectionManager) }

    bluetoothServerSocket?.close()
    synchronized(lock) { bluetoothServerSocket = null }

    if (isInterrupted.get()) {
      return
    }

    if (success) {
      callback?.onOobExchangeSuccess(
        bluetoothSocket.remoteDevice.toDiscoveredCar(oobConnectionManager)
      )
    } else {
      callback?.onOobExchangeFailure()
    }
    bluetoothSocket.close()
    synchronized(lock) { this.bluetoothSocket = null }
  }

  /**
   * Waits for a bluetooth socket connection.
   *
   * This call blocks until a connection is established, was aborted, or timed out.
   *
   * Returns the established bluetooth socket, or `null` if the device is not bonded to any other
   * devices, or if the connection fails.
   */
  private fun waitForBluetoothSocket(): BluetoothSocket? {
    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    if (bluetoothAdapter.getBondedDevices().isEmpty()) {
      logd(TAG, "No Bluetooth devices are bonded, so will not attempt to accept Bluetooth socket.")
      return null
    }

    val bluetoothServerSocket =
      bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, RFCOMM_UUID)

    synchronized(lock) { this.bluetoothServerSocket = bluetoothServerSocket }

    val bluetoothSocket =
      try {
        bluetoothServerSocket.accept(acceptBluetoothSocketTimeout.toMillis().toInt())
      } catch (e: IOException) {
        loge(TAG, "Accepting bluetooth socket was aborted or timed out.", e)
        return null
      }

    return bluetoothSocket
  }

  /**
   * Reads out-of-band data through [bluetoothSocket] to set to [oobConnectionManager].
   *
   * This call potentially blocks reading data.
   *
   * Returns `true` if OOB data was read successfully.
   */
  private fun readOobData(
    bluetoothSocket: BluetoothSocket,
    oobConnectionManager: OobConnectionManager
  ): Boolean {
    val sizeData = ByteArray(4)
    if (!readData(bluetoothSocket, sizeData)) {
      loge(TAG, "Bluetooth socket was not able to receive size of out of band data.")
      return false
    }
    val messageLength = ByteBuffer.wrap(sizeData).order(ByteOrder.LITTLE_ENDIAN).int

    if (messageLength != OobConnectionManager.DATA_LENGTH_BYTES) {
      loge(TAG, "The size of the incoming out of band data is incorrect")
      return false
    }
    val oobData = ByteArray(messageLength)
    if (!readData(bluetoothSocket, oobData)) {
      loge(TAG, "Bluetooth socket was not able to receive out of band data.")
      return false
    }

    return oobConnectionManager.setOobData(oobData)
  }

  private fun readData(bluetoothSocket: BluetoothSocket, data: ByteArray): Boolean {
    val bytesRead =
      try {
        bluetoothSocket.inputStream?.read(data) ?: 0
      } catch (e: IOException) {
        loge(TAG, "Could not read OOB data from bluetooth socket input stream.", e)
        0
      }

    return bytesRead > 0
  }

  private fun BluetoothDevice.toDiscoveredCar(oobConnectionManager: OobConnectionManager) =
    DiscoveredCar(
      this,
      this.name,
      gattServiceUuid,
      sppServiceUuid,
      oobConnectionManager = oobConnectionManager
    )

  override fun stopOobDataExchange() {
    isInterrupted.set(true)
    synchronized(lock) {
      logd(TAG, "Closing BluetoothServerSocket, InputStream, and BluetoothSocket.")
      bluetoothServerSocket?.close()
      bluetoothSocket?.inputStream?.close()
      bluetoothSocket?.close()

      bluetoothServerSocket = null
      bluetoothSocket = null
    }
  }

  companion object {
    private const val TAG = "BluetoothRfcommChannel"
    private const val SERVICE_NAME = "batmobile_oob"
    // TODO(b/159500330): Generate a random UUID
    private val RFCOMM_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
  }
}
