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
 * Handles version exchange.
 *
 * @property manager provides the bluetooth connection with the remote device.
 */
internal class VersionResolver(private val manager: BluetoothConnectionManager) {
  /**
   * Exchanges supported version with [manager].
   *
   * @return The received version; `null` if there was an error during exchange.
   */
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

  /** Returns the resolved version between local and [remoteVersion]; `null` if not compatible. */
  fun resolveVersion(remoteVersion: VersionExchange): ResolvedVersion? {
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
    return ResolvedVersion(
      maxMessageVersion,
      maxSecurityVersion,
    )
  }

  companion object {
    private const val TAG = "VersionResolver"
    const val MIN_MESSAGING_VERSION = 2
    const val MAX_MESSAGING_VERSION = 3
    const val MIN_SECURITY_VERSION = 2
    const val MAX_SECURITY_VERSION = 4

    /**
     * Resolves version via [manager].
     *
     * Returns [ResolvedVersion] based on the message version of IHU; `null` if local and remote
     * versions are incompatible or an error occured.
     */
    suspend fun resolve(manager: BluetoothConnectionManager): ResolvedVersion? {
      val resolver = VersionResolver(manager)
      val remoteVersion = resolver.exchangeVersion()

      return remoteVersion?.let { resolver.resolveVersion(it) }
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

internal data class ResolvedVersion(
  val messageVersion: Int,
  val securityVersion: Int,
)
