// Copyright 2023 Google LLC
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

import com.google.android.companionprotos.PeriodicPingProto.PeriodicPingMessage
import com.google.android.companionprotos.PeriodicPingProto.PeriodicPingMessage.MessageType
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.protobuf.InvalidProtocolBufferException
import java.util.UUID

open class PeriodicPingManager : FeatureManager() {
  override val featureId = FEATURE_ID

  internal var connectedCar: UUID? = null

  private val ackMessage = PeriodicPingMessage.newBuilder().setMessageType(MessageType.ACK).build()

  override fun onCarConnected(deviceId: UUID) {
    logi(TAG, "onCarConnected: $deviceId.")
    connectedCar = deviceId
  }

  override fun onCarDisconnected(deviceId: UUID) {
    logi(TAG, "onCarDisconnected: $deviceId.")
    if (deviceId != connectedCar) {
      logi(TAG, "A different car has disconnected. Ignore.")
      return
    }
    connectedCar = null
  }

  override fun onMessageReceived(message: ByteArray, deviceId: UUID) {
    val pingMessage =
      try {
        PeriodicPingMessage.parseFrom(message)
      } catch (e: InvalidProtocolBufferException) {
        loge(TAG, "Failed to parse message.", e)
        return
      }

    when (pingMessage.messageType) {
      MessageType.PING -> handlePing()
      else -> {
        loge(TAG, "Expecting ping message but received ${pingMessage.messageType}. Ignore.")
      }
    }
  }

  private fun handlePing() {
    if (connectedCar == null) {
      loge(TAG, "No car is connected. Cannot send ack message.")
      return
    }
    sendMessage(ackMessage.toByteArray(), connectedCar!!)
  }

  // Lifecycle methods. These are not used for this class as it only responds to pings, but must
  // be implemented as they are abstract.

  override fun onMessageSent(messageId: Int, deviceId: UUID) {}

  override fun onCarDisassociated(deviceId: UUID) {}

  override fun onAllCarsDisassociated() {}

  companion object {
    private const val TAG = "PeriodicPingManager"
    val FEATURE_ID: UUID = UUID.fromString("9eb6528d-bb65-4239-b196-6789196cf2a9")
  }
}
