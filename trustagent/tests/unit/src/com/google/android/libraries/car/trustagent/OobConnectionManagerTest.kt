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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.security.InvalidAlgorithmParameterException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OobConnectionManagerTest {
  private lateinit var oobConnectionManager: OobConnectionManager

  @Test
  fun testServer_validOobData_setsKeyAndNonce() {
    val oobConnectionManager = OobConnectionManager.create(TEST_OOB_DATA)!!

    assertThat(oobConnectionManager.ihuIv).isEqualTo(TEST_IHU_IV)
    assertThat(oobConnectionManager.mobileIv).isEqualTo(TEST_MOBILE_IV)
    assertThat(oobConnectionManager.encryptionKey).isEqualTo(TEST_KEY)
  }

  @Test
  fun testServer_create_sameEncryptAndDecryptNonce_returnsNull() {
    assertThat(OobConnectionManager.create(TEST_INVALID_OOB_DATA)).isNull()
  }

  @Test
  fun testSuccessfulEncryptAndServerDecrypt() {
    val oobConnectionManager = OobConnectionManager.create(TEST_OOB_DATA)!!
    // The decryption IV for one device is the encryption IV for the other and vice versa
    val otherOobConnectionManager =
      OobConnectionManager.create(
        OobData(encryptionKey = TEST_KEY.encoded, ihuIv = TEST_MOBILE_IV, mobileIv = TEST_IHU_IV)
      )!!

    val encryptedTestMessage = oobConnectionManager.encryptVerificationCode(TEST_MESSAGE)
    val decryptedTestMessage =
      otherOobConnectionManager.decryptVerificationCode(encryptedTestMessage)

    assertThat(decryptedTestMessage).isEqualTo(TEST_MESSAGE)
  }

  @Test
  fun testEncryptAndDecryptWithDifferentNonces_throwsAEADBadTagException() {
    // The OobConnectionManager stores a different nonce for encryption and decryption, so it can't
    // decrypt messages that it encrypted itself. It can only send encrypted messages to an
    // OobConnectionManager on another device that share its nonces and encryption key.
    val oobConnectionManager = OobConnectionManager.create(TEST_OOB_DATA)!!
    oobConnectionManager.encryptVerificationCode(TEST_MESSAGE)
    assertThrows(AEADBadTagException::class.java) {
      oobConnectionManager.decryptVerificationCode(TEST_MESSAGE)
    }
  }

  @Test
  fun testMultipleCallsToEncrypt_throwsInvalidParameterException() {
    val oobConnectionManager = OobConnectionManager.create(TEST_OOB_DATA)!!
    oobConnectionManager.encryptVerificationCode(TEST_MESSAGE)
    assertThrows(InvalidAlgorithmParameterException::class.java) {
      oobConnectionManager.encryptVerificationCode(TEST_MESSAGE)
    }
  }

  @Test
  fun testDecryptWithShortMessage_throwsAEADBadTagException() {
    val oobConnectionManager = OobConnectionManager.create(TEST_OOB_DATA)!!

    // An exception will be thrown if the message to decrypt is shorter than the IV
    assertThrows(AEADBadTagException::class.java) {
      oobConnectionManager.decryptVerificationCode("short".toByteArray())
    }
  }

  companion object {
    // 12 is what IHU uses but its value does not affect the logic.
    private const val NONCE_LENGTH_BYTES = 12

    private val TEST_KEY = KeyGenerator.getInstance("AES").generateKey()
    private val TEST_MOBILE_IV =
      ByteArray(NONCE_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
    private val TEST_IHU_IV = ByteArray(NONCE_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }

    private val TEST_OOB_DATA =
      OobData(
        encryptionKey = TEST_KEY.encoded,
        ihuIv = TEST_IHU_IV,
        mobileIv = TEST_MOBILE_IV,
      )
    private val TEST_INVALID_OOB_DATA =
      OobData(
        encryptionKey = TEST_KEY.encoded,
        // IHU IV is the same as mobile IV.
        ihuIv = TEST_MOBILE_IV,
        mobileIv = TEST_MOBILE_IV,
      )
    private val TEST_MESSAGE: ByteArray = "testMessage".toByteArray()
  }
}
