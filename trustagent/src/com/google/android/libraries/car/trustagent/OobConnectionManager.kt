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
 * Manages a token--[encryptionKey]-- passed via an out-of-band channel that is distinct from the
 * channel that is currently being secured.
 *
 * This class is not to be directly constructed. Instead, use `OobChannelManager`, which returns an
 * instance of this class when OOB token is received.
 *
 * @throws IllegalStateException if [oobData] is not valid.
 */
internal class OobConnectionManager
private constructor(
  @get:VisibleForTesting internal val encryptionKey: SecretKey,
  @get:VisibleForTesting internal val mobileIv: ByteArray,
  @get:VisibleForTesting internal val ihuIv: ByteArray
) {
  private val cipher = Cipher.getInstance(ALGORITHM)

  fun encryptVerificationCode(verificationCode: ByteArray): ByteArray {
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, IvParameterSpec(mobileIv))
    return cipher.doFinal(verificationCode)
  }

  fun decryptVerificationCode(encryptedMessage: ByteArray): ByteArray {
    cipher.init(Cipher.DECRYPT_MODE, encryptionKey, IvParameterSpec(ihuIv))
    return cipher.doFinal(encryptedMessage)
  }

  companion object {
    private const val TAG = "OobConnectionManager"
    private const val ALGORITHM = "AES/GCM/NoPadding"

    /**
     * Creates an OobConnectionManager with [oobData].
     *
     * Returns `null` if data is invalid.
     */
    fun create(oobData: OobData): OobConnectionManager? {
      if (oobData.mobileIv contentEquals oobData.ihuIv) {
        loge(TAG, "Invalid OOB data. IVs must not be the same. Cannot create OobConnectionManager.")
        return null
      }
      val key = SecretKeySpec(oobData.encryptionKey, KeyProperties.KEY_ALGORITHM_AES)
      return OobConnectionManager(key, oobData.mobileIv, oobData.ihuIv)
    }
  }
}
