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

package com.google.android.libraries.car.trustagent

import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.protobuf.InvalidProtocolBufferException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Handles capabilities exchange.
 *
 * @property manager provides the bluetooth connection with the remote device.
 */
internal class CapabilityResolver(private val manager: BluetoothConnectionManager) {
  /**
   * Exchanges capabilities with [manager].
   *
   * @param oobChannelTypes Types of the supported OOB channels.
   * @return The received version; `null` if there was an error during exchange.
   */
  suspend fun exchangeCapabilities(oobChannelTypes: List<OobChannelType>): CapabilitiesExchange? =
    suspendCoroutine<CapabilitiesExchange?> { continuation ->
      val messageCallback =
        object : BluetoothConnectionManager.MessageCallback {
          override fun onMessageReceived(data: ByteArray) {
            manager.unregisterMessageCallback(this)
            val remoteCapabilities =
              try {
                CapabilitiesExchange.parseFrom(data)
              } catch (e: InvalidProtocolBufferException) {
                loge(TAG, "Could not parse remote data as CapabilitiesExchange.", e)
                null
              }
            logi(TAG, "Remote channels are ${remoteCapabilities?.supportedChannelsToString()}")
            continuation.resume(remoteCapabilities)
          }
        }
      manager.registerMessageCallback(messageCallback)

      val capabilities =
        CapabilitiesExchange.newBuilder().run {
          addAllSupportedOobChannels(oobChannelTypes)
          build()
        }
      logi(TAG, "Sending capabilities with channels: ${capabilities.supportedChannelsToString()}")
      // After sending capabilities exchange, gattMessageCallback will be triggered.
      if (!manager.sendMessage(capabilities.toByteArray())) {
        logw(TAG, "Could not send message to init capabilities exchange. Disconnecting.")
        manager.unregisterMessageCallback(messageCallback)
        manager.disconnect()
        continuation.resume(null)
      }
    }

  companion object {
    private const val TAG = "CapabilityResolver"

    private const val SECURITY_VERSION_FOR_CAPABILITIES_EXCHANGE = 3

    /**
     * Resolves capability via [manager].
     *
     * Returns the supported OOB channels with the remote device; empty list if local and remote
     * device are incompatible or an error occurred.
     */
    suspend fun resolve(
      manager: BluetoothConnectionManager,
      oobChannelTypes: List<OobChannelType>
    ): List<OobChannelType> {
      val resolver = CapabilityResolver(manager)
      val remoteCapabilitiesExchange = resolver.exchangeCapabilities(oobChannelTypes)

      return remoteCapabilitiesExchange?.supportedOobChannelsList ?: emptyList()
    }

    fun shouldExchangeCapabilities(isAssociating: Boolean, securityVersion: Int): Boolean =
      isAssociating && securityVersion == SECURITY_VERSION_FOR_CAPABILITIES_EXCHANGE
  }
}

private fun CapabilitiesExchange.supportedChannelsToString() =
  this.supportedOobChannelsList.joinToString { it.name }
