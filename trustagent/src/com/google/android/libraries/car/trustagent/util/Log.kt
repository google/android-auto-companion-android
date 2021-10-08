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

import android.util.Log

/** Methods that log to android system log buffer and app log buffer. */
fun logwtf(tag: String, message: String) {
  Log.wtf(tag, message)
  Logger.logwtf(tag, message)
}

fun loge(tag: String, message: String, e: Throwable? = null) {
  Log.e(tag, message, e)
  Logger.loge(tag, message, e)
}

fun logw(tag: String, message: String) {
  Log.w(tag, message)
  Logger.logw(tag, message)
}

fun logi(tag: String, message: String) {
  Log.i(tag, message)
  Logger.logi(tag, message)
}

fun logd(tag: String, message: String) {
  if (Log.isLoggable(tag, Log.DEBUG)) {
    Log.d(tag, message)
    Logger.logd(tag, message)
  }
}

fun logv(tag: String, message: String) {
  if (Log.isLoggable(tag, Log.VERBOSE)) {
    Log.v(tag, message)
    Logger.logv(tag, message)
  }
}
