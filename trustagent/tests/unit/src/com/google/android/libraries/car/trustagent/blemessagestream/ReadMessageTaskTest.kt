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

import android.bluetooth.BluetoothSocket
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow

@RunWith(AndroidJUnit4::class)
class ReadMessageTaskTest {
  private lateinit var readMessageTask: ReadMessageTask
  private lateinit var shadowBluetoothSocket: BluetoothSocket
  private val testData1 = "data1".toByteArray()
  private val testData2 = "testData2".toByteArray()
  private val completedEmptyTestMessage = ByteArray(18)
  private val testedMessageSplits = 3
  private val messageSplitLength = completedEmptyTestMessage.size / testedMessageSplits
  private val callbackExecutor = directExecutor()
  private var executor = Executors.newSingleThreadExecutor()
  private lateinit var semaphore: Semaphore
  private lateinit var callback: ReadMessageTask.Callback

  @Before
  fun setUp() {
    semaphore = Semaphore(0)
    callback = spy(ReadMessageTaskCallback(semaphore))
    shadowBluetoothSocket = Shadow.newInstanceOf(BluetoothSocket::class.java)
    shadowBluetoothSocket.connect()
    readMessageTask = ReadMessageTask(shadowBluetoothSocket.inputStream, callback, callbackExecutor)
  }

  @Test
  fun testRun_oneMessage_informCallback() {
    shadowOf(shadowBluetoothSocket)
      .inputStreamFeeder
      .write(SppManager.wrapWithArrayLength(testData1))

    executor.execute(readMessageTask)

    assertThat(tryAcquire(semaphore, 1000)).isTrue()
    verify(callback).onMessageReceived(testData1)
  }

  @Test
  fun testRun_twoCombinedMessage_informCallback() {
    shadowOf(shadowBluetoothSocket)
      .inputStreamFeeder
      .write(SppManager.wrapWithArrayLength(testData1))
    shadowOf(shadowBluetoothSocket)
      .inputStreamFeeder
      .write(SppManager.wrapWithArrayLength(testData2))
    executor.execute(readMessageTask)

    assertThat(tryAcquire(semaphore, 1000)).isTrue()
    verify(callback).onMessageReceived(testData1)
    assertThat(tryAcquire(semaphore, 1000)).isTrue()
    verify(callback).onMessageReceived(testData2)
  }

  @Test
  fun testReadData_splittedMessages_readSuccessfully() {
    val fakeInputStream = FakeInputStream(messageSplitLength)

    assertThat(readMessageTask.readData(fakeInputStream, completedEmptyTestMessage)).isTrue()

    assertThat(fakeInputStream.methodCalls).isEqualTo(testedMessageSplits)
  }

  private fun tryAcquire(semaphore: Semaphore, timeout: Long): Boolean {
    return semaphore.tryAcquire(timeout, TimeUnit.MILLISECONDS)
  }
  /**
   * Add the thread control logic into [ReadMessageTask.Callback]. Only for spy purpose.
   *
   * The callback will release the semaphore which hold by one test when this callback is called,
   * telling the test that it can verify certain behaviors which will only occurred after the
   * callback is notified. This is needed mainly because of the [ReadMessageTask] is running in a
   * different thread.
   */
  open class ReadMessageTaskCallback(private val semaphore: Semaphore) : ReadMessageTask.Callback {
    override fun onMessageReceived(message: ByteArray) {
      semaphore.release()
    }

    override fun onMessageReadError() {
      semaphore.release()
    }
  }

  /**
   * Fake input stream that can track the number of [read] method calls and should only be used in
   * test.
   */
  class FakeInputStream(private val messageSplitLength: Int, var methodCalls: Int = 0) :
    InputStream() {
    override fun read(): Int {
      return 0
    }

    override fun read(b: ByteArray?, off: Int, len: Int): Int {
      methodCalls++
      return messageSplitLength
    }
  }
}
