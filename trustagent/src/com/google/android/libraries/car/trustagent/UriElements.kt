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
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.loge

/**
 * Companion device data extracted from elements in a URI.
 *
 * @property oobEncryptionKey an encryption key for out-of-band association.
 * @property deviceIdentifier the data that identifies the device during discovery.
 * @property queries list of customized queries which is retrieved from the original uri.
 */
@PublicApi
data class UriElements
private constructor(
  val oobData: OobData?,
  val deviceIdentifier: ByteArray?,
  val queries: Map<String, String?>
) {
  @PublicApi
  companion object {
    private const val TAG = "UriElements"
    /**
     * The key name of the companion device out-of-band data.
     *
     * The value should be a Base64-encoded url-safe string of a serialize protocol buffer message
     * [OutOfBandAssociationData].
     */
    @VisibleForTesting internal const val OOB_DATA_PARAMETER_KEY = "oobData"

    private val RESERVED_KEYS = setOf(OOB_DATA_PARAMETER_KEY)
    private val RESERVED_PREFIXES = setOf("oob", "bat")

    /**
     * Decodes [uri] into an [UriElements].
     *
     * Returns `null` if the URI contains invalid parameter names.
     */
    fun decodeFrom(uri: Uri): UriElements? {
      if (!uri.isParameterNamesValid()) {
        loge(TAG, "$uri parameters cannot start with reserved prefixes.")
        return null
      }

      val queries = uri.queryParameterNames.associateBy({ it }, { uri.getQueryParameter(it) })
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

      return UriElements(oobData, deviceIdentifier, queries)
    }

    internal fun Uri.isParameterNamesValid(): Boolean =
      queryParameterNames.all { name ->
        // Name is okay if it meets any of the following criteria:
        // 1. it's reserved;
        // 2. it doesn't start with any of the reserved prefix.
        name in RESERVED_KEYS || RESERVED_PREFIXES.none { prefix -> name.startsWith(prefix) }
      }
  }
}
