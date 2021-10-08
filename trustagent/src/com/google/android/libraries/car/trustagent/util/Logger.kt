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

package com.google.android.libraries.car.trustagent.util

import com.google.android.libraries.car.trustagent.util.LogRecord.Level
import com.google.common.collect.EvictingQueue
import com.google.gson.Gson
import java.util.Queue

/** Singleton class that keeps latest internal log records. */
object Logger {
  private const val MAX_LOG_SIZE = 500
  private val logRecordQueue: Queue<LogRecord> = EvictingQueue.create(MAX_LOG_SIZE)

  fun logwtf(tag: String, message: String) = addLogRecord(LogRecord(Level.ASSERT, tag, message))

  fun loge(tag: String, message: String, throwable: Throwable? = null) =
    addLogRecord(LogRecord(Level.ERROR, tag, message, throwable))

  fun logw(tag: String, message: String) = addLogRecord(LogRecord(Level.WARN, tag, message))

  fun logi(tag: String, message: String) = addLogRecord(LogRecord(Level.INFO, tag, message))

  fun logd(tag: String, message: String) = addLogRecord(LogRecord(Level.DEBUG, tag, message))

  fun logv(tag: String, message: String) = addLogRecord(LogRecord(Level.VERBOSE, tag, message))

  /** Get log records of this Logger in byte array. */
  fun toByteArray(): ByteArray {
    val currentRecords = ArrayList<LogRecord>(logRecordQueue)
    return Gson().toJson(currentRecords).toByteArray()
  }

  private fun addLogRecord(logRecord: LogRecord) = logRecordQueue.add(logRecord)
}
