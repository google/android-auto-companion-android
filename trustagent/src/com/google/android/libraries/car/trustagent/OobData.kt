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

import com.google.android.companionprotos.OutOfBandAssociationToken

private const val NONCE_LENGTH_BYTES = 12
internal const val DATA_LENGTH_BYTES = NONCE_LENGTH_BYTES * 2 + 16

/** Attempts to extract [OobData] out of the proto. */
internal fun OutOfBandAssociationToken.toOobData(): OobData? {
  if (encryptionKey == null || ihuIv == null || mobileIv == null) {
    return null
  }
  return OobData(encryptionKey.toByteArray(), ihuIv.toByteArray(), mobileIv.toByteArray())
}

/** Attempts to extract [OobData] out of byte array. */
internal fun ByteArray.toOobData(): OobData? {
  if (size != DATA_LENGTH_BYTES) {
    return null
  }
  return OobData(
    copyOfRange(NONCE_LENGTH_BYTES * 2, size),
    copyOfRange(NONCE_LENGTH_BYTES, NONCE_LENGTH_BYTES * 2),
    copyOfRange(0, NONCE_LENGTH_BYTES)
  )
}

/**
 * Data for out-of-band association.
 *
 * @property encryptionKey the key used to encrypt message to establish association.
 * @property ihuIv the initialization vector (IV) for messages sent by IHU.
 * @property mobileIv the initialization vector (IV) for messages sent by mobile.
 */
data class OobData(
  val encryptionKey: ByteArray,
  val ihuIv: ByteArray,
  val mobileIv: ByteArray,
)
