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

package com.google.android.libraries.car.trustagent.testutils

import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage

/**
 * A fake implementation of a [BleMessageStream].
 */
class FakeMessageStream() : MessageStream {
  val callbacks = mutableListOf<MessageStream.Callback>()

  override var encryptionKey: Key? = FakeKey()

  override fun sendMessage(streamMessage: StreamMessage) = 1

  override fun registerMessageEventCallback(callback: MessageStream.Callback) {
    callbacks.add(callback)
  }

  override fun unregisterMessageEventCallback(
    callback: MessageStream.Callback
  ) {
    callbacks.remove(callback)
  }
}
