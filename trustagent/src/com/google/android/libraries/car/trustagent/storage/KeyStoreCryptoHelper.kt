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

package com.google.android.libraries.car.trustagent.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.android.libraries.car.trustagent.util.loge
import java.lang.Exception
import java.security.Key
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "KeyStoreCryptoHelper"

private const val KEY_ALIAS = "EncryptionKey"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val IV_SPEC_SEPARATOR = ";"

/**
 * The length of the authentication tag for a cipher in GCM mode. The GCM specification states that
 * this length can only have the values {128, 120, 112, 104, 96}. Using the highest possible value.
 */
private const val GCM_AUTHENTICATION_TAG_LENGTH = 128

/** A [CryptoHelper] that utilizes the Android keystore system to encrypt and decrypt values. */
class KeyStoreCryptoHelper : CryptoHelper {
  override fun encrypt(value: ByteArray): String? {
    return try {
      val key = getKey()
      val cipher = getCipherInstance()
      cipher.init(Cipher.ENCRYPT_MODE, key)
      Base64.encodeToString(cipher.doFinal(value), Base64.DEFAULT) +
        IV_SPEC_SEPARATOR +
        Base64.encodeToString(cipher.iv, Base64.DEFAULT)
    } catch (e: Exception) {
      loge(TAG, "Unable to encrypt value with key", e)
      null
    }
  }

  override fun decrypt(value: String): ByteArray? {
    val values = value.split(IV_SPEC_SEPARATOR)
    if (values.size != 2) {
      loge(TAG, "Unable to extract encrypted value and iv, invalid array size: ${values.size}")
      return null
    }
    val encryptedKey = Base64.decode(values[0], Base64.DEFAULT)
    val ivSpec = Base64.decode(values[1], Base64.DEFAULT)
    try {
      val key = getKey()
      with(getCipherInstance()) {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_AUTHENTICATION_TAG_LENGTH, ivSpec))
        return doFinal(encryptedKey)
      }
    } catch (e: Exception) {
      loge(TAG, "Unable to decrypt value with key", e)
      return null
    }
  }

  private fun getKey(): Key {
    try {
      val keyStore = getKeyStoreInstance()
      if (!keyStore.containsAlias(KEY_ALIAS)) {
        val spec =
          KeyGenParameterSpec.Builder(
              KEY_ALIAS,
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        with(getKeyGeneratorInstance()) {
          init(spec)
          generateKey()
        }
      }
      return keyStore.getKey(KEY_ALIAS, null)
    } catch (e: Exception) {
      loge(TAG, "Unable to retrieve key", e)
      throw e
    }
  }

  private fun getKeyGeneratorInstance(): KeyGenerator {
    try {
      return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
    } catch (e: NoSuchAlgorithmException) {
      loge(TAG, "Unable to get KeyGenerator instance.", e)
      throw e
    }
  }

  private fun getKeyStoreInstance(): KeyStore {
    try {
      val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
      keyStore.load(/* loadStoreParameter= */ null)
      return keyStore
    } catch (e: Exception) {
      loge(TAG, "Unable to get KeyStore instance.", e)
      throw e
    }
  }

  private fun getCipherInstance(): Cipher {
    try {
      return Cipher.getInstance("AES/GCM/NoPadding")
    } catch (e: Exception) {
      loge(TAG, "Unable to get Cipher instance.", e)
      throw e
    }
  }
}
