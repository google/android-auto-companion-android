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

package com.google.android.libraries.car.loggingsupport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.LoggingMessageProto.LoggingMessage
import com.google.android.companionprotos.LoggingMessageProto.LoggingMessage.MessageType
import com.google.android.libraries.car.trustagent.Car
import com.google.android.libraries.car.trustagent.util.LogRecord
import com.google.gson.Gson
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import java.io.File
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [LoggingManager] */
@RunWith(AndroidJUnit4::class)
class LoggingManagerTest {
  private val testConnectedCar = UUID.randomUUID()
  private val mockCar: Car = mock()
  private val mockFileUtils: FileUtil = mock()
  private val mockListener: LoggingManager.OnLogFilesUpdatedListener = mock()
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private lateinit var loggingManager: LoggingManager
  private val util = FileUtil()

  @Before
  fun setUp() {
    loggingManager = LoggingManager(context)
    loggingManager.util = mockFileUtils
    loggingManager.onLogFilesUpdatedListener = mockListener
    whenever(mockCar.deviceId).thenAnswer { testConnectedCar }
    loggingManager.notifyCarConnected(mockCar)
  }

  @Test
  fun onMessageReceived_sendRequest() {
    val mockLogRequestMessage = LogMessageUtil
      .createRequestMessage(LoggingManager.LOGGING_MESSAGE_VERSION)
    loggingManager.onMessageReceived(mockLogRequestMessage, testConnectedCar)
    argumentCaptor<ByteArray>().apply {
      verify(mockCar).sendMessage(capture(), eq(LoggingManager.FEATURE_ID))
      val message = LoggingMessage.parseFrom(lastValue)
      assert(message.type == MessageType.LOG)
    }
  }

  @Test
  fun onMessageReceived_logMessage() {
    val receivedLogs = mockLogMessageReceived(5)
    verify(mockFileUtils).writeToFile(eq(receivedLogs), any(), any())
    verify(mockListener).onLogFilesUpdated()
  }

  @Test
  fun getLogFiles() {
    loggingManager.loadLogFiles()
    verify(mockFileUtils).readFiles(any())
  }

  @Test
  fun generateLogFile() {
    loggingManager.generateLogFile()
    verify(mockListener).onLogFilesUpdated()
  }

  @Test
  fun sendLogRequest() {
    loggingManager.sendLogRequest()
    argumentCaptor<ByteArray>().apply {
      verify(mockCar).sendMessage(capture(), eq(LoggingManager.FEATURE_ID))
      val message = LoggingMessage.parseFrom(lastValue)
      assert(message.type == MessageType.START_SENDING)
    }
  }

  @Test
  fun removeOldLogFiles() {
    val logFiles = createRandomLogFiles(LoggingManager.LOG_FILES_MAX_NUM + 1)
    whenever(mockFileUtils.readFiles(any())).thenAnswer { logFiles }
    val receivedLogs = mockLogMessageReceived(5)
    verify(mockFileUtils).writeToFile(eq(receivedLogs), any(), any())
    verify(logFiles[0]).delete()
  }

  private fun mockLogMessageReceived(size: Int): ByteArray {
    val mockReceivedLogs = createRandomLogRecords(size)
    val mockLogMessage = LogMessageUtil.createMessage(
      mockReceivedLogs,
      LoggingManager.LOGGING_MESSAGE_VERSION
    )
    loggingManager.onMessageReceived(mockLogMessage, testConnectedCar)
    return mockReceivedLogs
  }

  private fun createRandomLogFiles(size: Int): List<File> {
    val logFiles = mutableListOf<File>()
    for (i in 1..size) {
      val file: File = mock()
      whenever(file.isFile).thenAnswer { true }
      whenever(file.lastModified()).thenAnswer { i.toLong() }
      logFiles.add(file)
    }
    return logFiles
  }

  private fun createRandomLogRecords(size: Int): ByteArray {
    val logRecords = mutableListOf<LogRecord>()
    var count = size
    for (i in 1..count) {
      logRecords.add(LogRecord(LogRecord.Level.INFO, "TEST_TAG", "TEST_MESSAGE"))
    }
    return Gson().toJson(logRecords).toByteArray()
  }
}
