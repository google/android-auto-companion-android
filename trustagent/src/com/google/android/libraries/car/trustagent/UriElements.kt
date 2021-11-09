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

import android.net.Uri
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.OutOfBandAssociationData

/**
 * Companion device data extracted from elements in a URI.
 *
 * @property oobEncryptionKey an encryption key for out-of-band association.
 * @property deviceIdentifier the data that identifies the device during discovery.
 */
data class UriElements
private constructor(val oobData: OobData?, val deviceIdentifier: ByteArray?) {
  companion object {
    /**
     * The key name of the companion device out-of-band data.
     *
     * The value should be a Base64-encoded url-safe string of a serialize protocol buffer message
     * [OutOfBandAssociationData].
     */
    @VisibleForTesting internal const val OOB_DATA_PARAMETER_KEY = "oobData"

    private val RESERVED_KEYS = setOf(OOB_DATA_PARAMETER_KEY)
    private val RESERVED_PREFIXES = setOf("oob", "bat")

    fun decodeFrom(uri: Uri): UriElements {
      // Filter for parameter name that is not reserved but starts with a reserved prefix.
      val containsViolation =
        uri.getQueryParameterNames().any { name ->
          name !in RESERVED_KEYS && RESERVED_PREFIXES.any { prefix -> name.startsWith(prefix) }
        }
      check(!containsViolation) { "URI parameters cannot start with reserved prefixes" }

      val proto =
        uri.getQueryParameter(OOB_DATA_PARAMETER_KEY)?.let {
          // Decode String parameter to ByteArray.
          // Parse ByteArray as proto message.
          OutOfBandAssociationData.parseFrom(Base64.decode(it, Base64.URL_SAFE))
        }

      // Directly accessing the proto field always returns a non-null message.
      // If the field is not set, the message will be empty.
      val oobData =
        if (proto?.hasToken() == true) {
          proto?.token?.toOobData()
        } else {
          null
        }
      // The default value of byte array is empty.
      val deviceIdentifier =
        if (proto?.deviceIdentifier?.isEmpty() == false) {
          proto?.deviceIdentifier?.toByteArray()
        } else {
          null
        }

      return UriElements(oobData, deviceIdentifier)
    }
  }
}
