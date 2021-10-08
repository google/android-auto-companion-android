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

import com.google.android.companionprotos.LoggingMessageProto.LoggingMessage
import com.google.android.companionprotos.LoggingMessageProto.LoggingMessage.MessageType
import com.google.protobuf.ByteString
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Util methods for reading and writing log files. */
internal open class FileUtil {

  /** Writes values to a file with given file name in the given directory. */
  internal open fun writeToFile(value: ByteArray, dir: String, fileName: String) {
    val fileDirectory = File(dir)
    if (!fileDirectory.exists()) {
      check(fileDirectory.mkdirs()) {
        "Directory creation failed"
      }
    }
    val path = dir + File.separator + fileName
    val file = File(path)
    val stream = FileOutputStream(file, /* append= */ true)
    val streamWriter = OutputStreamWriter(stream)
    BufferedWriter(streamWriter).append(String(value)).close()
  }

  /** Gets files in the given directory. */
  internal open fun readFiles(directoryPath: String): List<File> {
    val filesInDirectory = File(directoryPath).listFiles()
    return filesInDirectory?.toList() ?: emptyList()
  }
}

/** Util methods for creating log messages. */
internal object LogMessageUtil {
  /** Creates log request message with given version number. */
  internal fun createRequestMessage(version: Int): ByteArray = LoggingMessage.newBuilder()
    .setVersion(version)
    .setType(MessageType.START_SENDING)
    .build()
    .toByteArray()

  /** Creates log message with given log and version number. */
  internal fun createMessage(log: ByteArray, version: Int): ByteArray =
    LoggingMessage.newBuilder()
      .setVersion(version)
      .setType(MessageType.LOG)
      .setPayload(ByteString.copyFrom(log))
      .build()
      .toByteArray()

  /** Gets current time stamp of given pattern. */
  internal fun getCurrentLocalTimeStamp(pattern: String): String {
    val formatter = DateTimeFormatter.ofPattern(pattern)
    return formatter.format(LocalDateTime.now())
  }
}
