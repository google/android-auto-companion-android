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

import android.os.Process
import com.google.gson.Gson

/** Contains basic info of a log record. */
data class LogRecord(
  val level: Level,
  val tag: String,
  val message: String,
  val stackTrace: String?
) {
  constructor(
    level: Level,
    tag: String,
    message: String,
    throwable: Throwable? = null
  ) : this(level, tag, message, throwable?.stackTrace?.joinToString(STACK_TRACE_SEPARATOR))

  /** Priority level constant for a log record. */
  enum class Level {
    ASSERT,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    VERBOSE
  }

  val time = TimeProvider.createCurrentTimeStamp()
  val processId = Process.myPid()
  val threadId = Process.myTid()

  /** Return serialization of log record. */
  fun toJson(): String = Gson().toJson(this)

  companion object {
    private const val STACK_TRACE_SEPARATOR = "\t"
  }
}
