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

import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.trustagent.util.loge
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * This is a class that manages a token--[encryptionKey]-- passed via an out of band [Channel] that
 * is distinct from the channel that is currently being secured.
 *
 * Intended usage:
 *
 * 1. on `oobData` received from `OobChannel`
 * 1. Set the data: `oobConnectionManager.setOobData(oobData)`
 * 1. `encryptedMessage = encryptVerificationCode(...)`
 * 1. sendMessage
 * 1. When a message is received:
 * 1. `verificationCode = decryptVerificationCode(...)`
 * 1. Check that verification code is valid
 * 1. If code is valid, verify handshake; otherwise, fail.
 */
internal class OobConnectionManager {
  private val cipher = Cipher.getInstance(ALGORITHM)
  @VisibleForTesting internal var encryptionKey: SecretKey? = null
  @VisibleForTesting internal var encryptionIv = ByteArray(NONCE_LENGTH_BYTES)
  @VisibleForTesting internal var decryptionIv = ByteArray(NONCE_LENGTH_BYTES)

  /** Returns true if the OOB data is set successfully, otherwise returns false. */
  fun setOobData(oobData: ByteArray): Boolean {
    encryptionIv = oobData.copyOfRange(0, NONCE_LENGTH_BYTES)
    decryptionIv = oobData.copyOfRange(NONCE_LENGTH_BYTES, NONCE_LENGTH_BYTES * 2)
    if (encryptionIv.contentEquals(decryptionIv)) {
      loge(TAG, "Invalid OOB data. IVs values are not different. Failed to set OOB data.")
      return false
    }
    encryptionKey =
      SecretKeySpec(
        oobData.copyOfRange(NONCE_LENGTH_BYTES * 2, oobData.size),
        KeyProperties.KEY_ALGORITHM_AES
      )
    return true
  }

  fun encryptVerificationCode(verificationCode: ByteArray): ByteArray {
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, IvParameterSpec(encryptionIv))
    return cipher.doFinal(verificationCode)
  }

  fun decryptVerificationCode(encryptedMessage: ByteArray): ByteArray {
    cipher.init(Cipher.DECRYPT_MODE, encryptionKey, IvParameterSpec(decryptionIv))

    return cipher.doFinal(encryptedMessage)
  }

  companion object {
    private const val TAG = "OobConnectionManager"
    // The nonce length is chosen to be consistent with the standard specification:
    // Section 8.2 of https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf
    @VisibleForTesting internal const val NONCE_LENGTH_BYTES = 12
    // The total data size is the size of the two nonces plus the secret key
    const val DATA_LENGTH_BYTES = NONCE_LENGTH_BYTES * 2 + 16
    private const val ALGORITHM = "AES/GCM/NoPadding"
  }
}
