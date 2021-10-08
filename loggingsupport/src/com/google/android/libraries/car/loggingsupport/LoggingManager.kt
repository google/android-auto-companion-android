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
import android.os.Build
import android.os.Environment
import com.google.android.companionprotos.LoggingMessageProto.LoggingMessage
import com.google.android.companionprotos.LoggingMessageProto.LoggingMessage.MessageType
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.util.Logger
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.protobuf.InvalidProtocolBufferException
import java.io.File
import java.io.IOException
import java.util.UUID

/** Feature for collecting local logs and logs from connected devices. */
class LoggingManager(private val context: Context) : FeatureManager() {
  internal var util = FileUtil()

  /** Notify about the log files updates. */
  var onLogFilesUpdatedListener: OnLogFilesUpdatedListener? = null

  override val featureId = FEATURE_ID

  override fun onCarConnected(carId: UUID) {
  }

  override fun onMessageReceived(message: ByteArray, carId: UUID) =
    processLoggingMessage(message, carId)

  override fun onMessageSent(messageId: Int, carId: UUID) {
  }

  override fun onCarDisconnected(carId: UUID) {
  }

  override fun onCarDisassociated(carId: UUID) {
  }

  override fun onAllCarsDisassociated() {
  }

  /** Loads all log files from LOG_FILE_DIR. */
  fun loadLogFiles(): List<File> =
    util.readFiles(getLogFileDirectory())
      .filter { it.isFile }
      .sortedWith(compareBy { it.lastModified() })

  /** Loads names of all log files. */
  fun loadLogFileNames(): List<String> = loadLogFiles().map { it.name }

  /** Generates a new log file. */
  fun generateLogFile() {
    writeLogFile(getPhoneLogFileName(), Logger.toByteArray())
  }

  /** Sends request for log file to connected devices. */
  fun sendLogRequest() {
    connectedCars.forEach {
      logi(TAG, "Sending log request to car ${getConnectedCarNameById(it)}")
      sendMessage(LogMessageUtil.createRequestMessage(LOGGING_MESSAGE_VERSION), it)
    }
  }

  private fun processLoggingMessage(message: ByteArray, carId: UUID) {
    val loggingMessage = try {
      LoggingMessage.parseFrom(message)
    } catch (e: InvalidProtocolBufferException) {
      loge(TAG, "Can not parse received logging message.", e)
      return
    }
    if (loggingMessage.version != LOGGING_MESSAGE_VERSION) {
      loge(TAG, "Received logging message version not supported.")
      return
    }
    when (loggingMessage.type) {
      MessageType.START_SENDING -> sendMessage(
        LogMessageUtil.createMessage(Logger.toByteArray(), LOGGING_MESSAGE_VERSION),
        carId
      )
      MessageType.LOG -> onCarLogsRetrieved(loggingMessage.payload.toByteArray(), carId)
      MessageType.ERROR -> onErrorMessageReceived(carId)
      else -> loge(
        TAG,
        "Received a logging message with invalid message type ${loggingMessage.type} from " +
          "car $carId."
      )
    }
  }

  private fun onCarLogsRetrieved(log: ByteArray, carId: UUID) {
    logi(TAG, "Received log from car ${getConnectedCarNameById(carId)}")
    writeLogFile(getCarLogFileName(carId), log)
  }

  private fun onErrorMessageReceived(carId: UUID) {
    loge(TAG, "Received error logging message from car $carId.")
  }

  private fun writeLogFile(fileName: String, log: ByteArray) {
    try {
      util.writeToFile(log, getLogFileDirectory(), fileName)
      logi(TAG, "Generated file ${getLogFileDirectory() + File.separator + fileName}")
    } catch (exception: IOException) {
      loge(TAG, "Failed to generate log file $fileName", exception)
    }
    removeOldLogFiles()

    logi(TAG, "Notifying file update to listsner $onLogFilesUpdatedListener.")
    onLogFilesUpdatedListener?.onLogFilesUpdated()
  }

  private fun removeOldLogFiles() {
    val files = loadLogFiles()
    if (files.size <= LOG_FILES_MAX_NUM) {
      return
    }
    repeat(files.size - LOG_FILES_MAX_NUM) {
      files[it].delete()
    }
  }

  private fun getPhoneLogFileName(): String =
    Build.MODEL + FILE_NAME_SEPARATOR +
      LogMessageUtil.getCurrentLocalTimeStamp(TIME_STAMP_FORMAT_PATTERN) + FILE_EXTENSION

  private fun getCarLogFileName(carId: UUID): String =
    getConnectedCarNameById(carId) + FILE_NAME_SEPARATOR +
      LogMessageUtil.getCurrentLocalTimeStamp(TIME_STAMP_FORMAT_PATTERN) + FILE_EXTENSION

  private fun getLogFileDirectory(): String =
    getExternalStorageDirectory() + File.separator + LOG_FILE_DIR

  private fun getExternalStorageDirectory(): String =
    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString()

  companion object {
    private const val TAG = "LoggingManager"
    private const val TIME_STAMP_FORMAT_PATTERN = "yyyyMMddHHmmss"
    private const val LOG_FILE_DIR = "Logs"
    private const val FILE_NAME_SEPARATOR = "-"
    private const val FILE_EXTENSION = ".aalog"
    internal val FEATURE_ID: UUID = UUID.fromString("675836fb-18ed-4c60-94cd-131352e8a5b7")
    internal const val LOGGING_MESSAGE_VERSION = 1
    internal const val LOG_FILES_MAX_NUM = 10
  }

  /** Listener that will be notified for log files updates. */
  interface OnLogFilesUpdatedListener {
    /** Called when log files have updates. */
    fun onLogFilesUpdated()
  }
}
