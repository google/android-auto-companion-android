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

import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.util.loge

/** Handles version and capabilities exchange. */
internal class ConnectionResolver() {
  companion object {
    private const val TAG = "ConnectionResolver"

    /**
     * Resolves version by exchanging version with [manager].
     *
     * Returns [ResolvedConnection] based on the message version of IHU; `null` if local and remote
     * versions are incompatible or an error occurred.
     */
    suspend fun resolve(
      manager: BluetoothConnectionManager,
      oobData: OobData? = null,
      isAssociating: Boolean,
    ): ResolvedConnection? {
      val resolvedVersion = VersionResolver.resolve(manager)
      if (resolvedVersion == null) {
        loge(TAG, "Could not exchange version.")
        return null
      }

      val resolvedChannelTypes =
        if (CapabilityResolver.shouldExchangeCapabilities(
            isAssociating = true,
            resolvedVersion.securityVersion
          )
        ) {
          // Attempt to use OOB association over RFCOMM when oobData is not available. Otherwise,
          // skip RFCOMM because before security V4, IHU will stuck at establishing RFCOMM channel
          // while mobile sends the encryption init message, which will be ignored by IHU.
          val supportedChannelTypes =
            if (oobData == null) {
              listOf(OobChannelType.BT_RFCOMM)
            } else {
              listOf(OobChannelType.PRE_ASSOCIATION)
            }
          CapabilityResolver.resolve(manager, supportedChannelTypes)
        } else {
          emptyList()
        }

      return ResolvedConnection(
        resolvedVersion.messageVersion,
        resolvedVersion.securityVersion,
        resolvedChannelTypes
      )
    }
  }
}

data class ResolvedConnection(
  val messageVersion: Int,
  val securityVersion: Int,
  val oobChannels: List<OobChannelType>
)
