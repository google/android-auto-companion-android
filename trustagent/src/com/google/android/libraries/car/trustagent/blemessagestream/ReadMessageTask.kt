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

import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.trustagent.blemessagestream.SppManager.Companion.LENGTH_BYTES_SIZE
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** This task runs during a connection with a remote device. It handles all incoming data. */
internal class ReadMessageTask
internal constructor(
  private val inputStream: InputStream,
  private val callback: Callback,
  private val callbackExecutor: Executor
) : Runnable {
  private val sizeBuffer = ByteArray(LENGTH_BYTES_SIZE)
  private val isCanceled = AtomicBoolean(false)

  /** Read data from [stream] until the end of the [buffer]. */
  @VisibleForTesting
  internal fun readData(stream: InputStream, buffer: ByteArray): Boolean {
    var offset = 0
    var bytesToRead = buffer.size
    var bytesRead: Int
    while (bytesToRead > 0) {
      try {
        bytesRead = stream.read(buffer, offset, bytesToRead)
      } catch (e: IOException) {
        logw(TAG, "Encountered an exception when listening for incoming message.")
        return false
      }
      if (bytesRead == -1) {
        loge(TAG, "EOF when reading data from input stream.")
        return false
      }
      offset += bytesRead
      bytesToRead -= bytesRead
    }
    return true
  }

  override fun run() {
    logi(TAG, "Begin ReadMessageTask: started to listen to incoming messages.")
    // Keep listening to the InputStream when task started.
    while (!isCanceled.get()) {
      if (!readData(inputStream, sizeBuffer)) {
        cancel()
        callbackExecutor.execute { callback.onMessageReadError() }
        break
      }

      val messageLength = ByteBuffer.wrap(sizeBuffer).order(ByteOrder.LITTLE_ENDIAN).int
      val dataBuffer = ByteArray(messageLength)
      if (!readData(inputStream, dataBuffer)) {
        cancel()
        callbackExecutor.execute { callback.onMessageReadError() }
        break
      }

      logd(TAG, "Received raw bytes from remote device of length: $messageLength")
      callbackExecutor.execute { callback.onMessageReceived(dataBuffer) }
    }
  }

  /** Stop listening to messages from remote device. */
  fun cancel() {
    logd(TAG, "cancel ReadMessageTask: stop listening to message.")
    isCanceled.set(true)
  }

  /** Interface to be called when there are [ReadMessageTask] related events. */
  interface Callback {
    /**
     * Triggered when a completed message is received from input stream.
     *
     * @param message Message received from remote device.
     */
    fun onMessageReceived(message: ByteArray)

    /** Triggered when failed to read message from remote device. */
    fun onMessageReadError()
  }

  companion object {
    private const val TAG = "ReadMessageTask"
  }
}
