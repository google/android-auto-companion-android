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

import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
import com.google.android.companionprotos.VersionExchangeProto.VersionExchange
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.protobuf.InvalidProtocolBufferException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.min

/**
 * Handles the bluetooth connection and version and capabilities exchange in the connected device
 * message exchange flow.
 *
 * @property manager The remote device manager that to be connected with.
 */
internal class ConnectionResolver(private val manager: BluetoothConnectionManager) {
  /**
   * Exchanges supported version with [manager].
   *
   * @return The received version; `null` if there was an error during exchange.
   */
  @VisibleForTesting
  suspend fun exchangeVersion(): VersionExchange? =
    suspendCoroutine<VersionExchange?> { continuation ->
      val messageCallback =
        object : BluetoothConnectionManager.MessageCallback {
          override fun onMessageReceived(data: ByteArray) {
            manager.unregisterMessageCallback(this)
            val remoteVersion =
              try {
                VersionExchange.parseFrom(data)
              } catch (e: InvalidProtocolBufferException) {
                loge(TAG, "Could not parse message from car as BLEVersionExchange.", e)
                null
              }
            logi(TAG, "Remote BleVersionExchange is ${remoteVersion?.asString()}")
            continuation.resume(remoteVersion)
          }
        }
      manager.registerMessageCallback(messageCallback)

      val versionExchange =
        makeVersionExchange().also { logi(TAG, "Sending version exchange: ${it.asString()}") }
      // After sending version exchange, gattMessageCallback will be triggered.
      if (!manager.sendMessage(versionExchange.toByteArray())) {
        logw(TAG, "Could not send message to init version exchange. Disconnecting.")
        manager.unregisterMessageCallback(messageCallback)
        manager.disconnect()
        continuation.resume(null)
      }
    }

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
                loge(TAG, "Could not parse $data as CapabilitiesExchange.", e)
                null
              }
            logi(TAG, "Remote CapabilitiesExchange is ${remoteCapabilities?.asString()}")
            continuation.resume(remoteCapabilities)
          }
        }
      manager.registerMessageCallback(messageCallback)

      val capabilities = CapabilitiesExchange.newBuilder().run {
        addAllSupportedOobChannels(oobChannelTypes)
        build()
      }
      logi(TAG, "Sending capabilities exchange: ${capabilities.asString()}")
      // After sending capabilities exchange, gattMessageCallback will be triggered.
      if (!manager.sendMessage(capabilities.toByteArray())) {
        logw(TAG, "Could not send message to init capabilities exchange. Disconnecting.")
        manager.unregisterMessageCallback(messageCallback)
        manager.disconnect()
        continuation.resume(null)
      }
    }

  /** Returns the resolved version between local and [remoteVersion]; `null` if not compatible. */
  @VisibleForTesting
  fun resolveVersion(
    remoteVersion: VersionExchange,
    remoteCapabilities: CapabilitiesExchange? = null
  ): ResolvedConnection? {
    val maxSecurityVersion = min(remoteVersion.maxSupportedSecurityVersion, MAX_SECURITY_VERSION)
    val minSecurityVersion = max(remoteVersion.minSupportedSecurityVersion, MIN_SECURITY_VERSION)
    val maxMessageVersion = min(remoteVersion.maxSupportedMessagingVersion, MAX_MESSAGING_VERSION)
    val minMessageVersion = max(remoteVersion.minSupportedMessagingVersion, MIN_MESSAGING_VERSION)

    if (minSecurityVersion > maxSecurityVersion || minMessageVersion > maxMessageVersion) {
      loge(
        TAG,
        """
        |Local version is ${makeVersionExchange().asString()};
        |remote version is ${remoteVersion.asString()}.
        """.trimMargin()
      )
      return null
    }

    logi(TAG, "Resolved message version $maxMessageVersion; security version $maxSecurityVersion.")
    return ResolvedConnection(
      maxMessageVersion,
      maxSecurityVersion,
      remoteCapabilities?.supportedOobChannelsList ?: emptyList()
    )
  }

  companion object {
    private const val TAG = "ConnectionResolver"
    const val MIN_MESSAGING_VERSION = 2
    const val MAX_MESSAGING_VERSION = 3
    const val MIN_SECURITY_VERSION = 2
    const val MAX_SECURITY_VERSION = 3
    const val MIN_SECURITY_VERSION_FOR_CAPABILITIES_EXCHANGE = 3

    /**
     * Resolves version by exchanging version with [manager].
     *
     * Returns [ResolvedConnection] based on the message version of IHU; `null` if local and remote
     * versions are incompatible or an error occured.
     */
    suspend fun resolve(
      manager: BluetoothConnectionManager,
      isAssociating: Boolean,
      oobChannelTypes: List<OobChannelType>
    ): ResolvedConnection? {
      val resolver = ConnectionResolver(manager)
      val remoteVersion = resolver.exchangeVersion()
      if (remoteVersion == null) {
        return null
      }
      // Capability exchange is only necessary during association,
      // and only available starting at security version 3.
      val remoteCapabilities =
        if (isAssociating &&
            remoteVersion.maxSupportedSecurityVersion >=
              MIN_SECURITY_VERSION_FOR_CAPABILITIES_EXCHANGE
        ) {
          resolver.exchangeCapabilities(oobChannelTypes)
        } else {
          null
        }

      return resolver.resolveVersion(remoteVersion, remoteCapabilities)
    }

    /** Creates a proto that represents the locally supported version. */
    @VisibleForTesting
    internal fun makeVersionExchange(
      minMessagingVersion: Int = MIN_MESSAGING_VERSION,
      maxMessagingVersion: Int = MAX_MESSAGING_VERSION,
      minSecurityVersion: Int = MIN_SECURITY_VERSION,
      maxSecurityVersion: Int = MAX_SECURITY_VERSION
    ) =
      VersionExchange.newBuilder()
        .setMinSupportedMessagingVersion(minMessagingVersion)
        .setMaxSupportedMessagingVersion(maxMessagingVersion)
        .setMinSupportedSecurityVersion(minSecurityVersion)
        .setMaxSupportedSecurityVersion(maxSecurityVersion)
        .build()
  }
}

// Proto toString() is not available in proto lite.
private fun VersionExchange.asString() =
  """
  |min security version: ${this.minSupportedSecurityVersion};
  |max security version: ${this.maxSupportedSecurityVersion};
  |min message version: ${this.minSupportedMessagingVersion};
  |max message version: ${this.maxSupportedMessagingVersion}.
  """.trimMargin()

private fun CapabilitiesExchange.asString() = this.supportedOobChannelsList.joinToString { it.name }

data class ResolvedConnection(
  val messageVersion: Int,
  val securityVersion: Int,
  val oobChannels: List<OobChannelType>
)
