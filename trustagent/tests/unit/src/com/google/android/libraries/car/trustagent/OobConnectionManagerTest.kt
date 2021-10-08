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
import java.security.InvalidKeyException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OobConnectionManagerTest {
  private lateinit var oobConnectionManager: OobConnectionManager

  @Before
  fun setUp() {
    oobConnectionManager = OobConnectionManager()
  }

  @Test
  fun testServer_setOobData_setsKeyAndNonce_returnTrue() {
    assertThat(oobConnectionManager.setOobData(TEST_OOB_DATA)).isTrue()

    // The decryption IV for one device is the encryption IV for the other and vice versa
    assertThat(oobConnectionManager.decryptionIv).isEqualTo(TEST_ENCRYPTION_IV)
    assertThat(oobConnectionManager.encryptionIv).isEqualTo(TEST_DECRYPTION_IV)
    assertThat(oobConnectionManager.encryptionKey).isEqualTo(TEST_KEY)
  }

  @Test
  fun testServer_setOobData_setsSameEncryptAndDecryptNonce_returnFalse() {
    assertThat(oobConnectionManager.setOobData(TEST_INVALID_OOB_DATA)).isFalse()
  }

  @Test
  fun testSuccessfulEncryptAndServerDecrypt() {
    oobConnectionManager.setOobData(TEST_OOB_DATA)
    val otherOobConnectionManager = OobConnectionManager()
    // The decryption IV for one device is the encryption IV for the other and vice versa
    otherOobConnectionManager.setOobData(TEST_ENCRYPTION_IV + TEST_DECRYPTION_IV + TEST_KEY.encoded)

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
    oobConnectionManager.setOobData(TEST_OOB_DATA)
    oobConnectionManager.encryptVerificationCode(TEST_MESSAGE)
    assertThrows(AEADBadTagException::class.java) {
      oobConnectionManager.decryptVerificationCode(TEST_MESSAGE)
    }
  }

  @Test
  fun testMultipleCallsToEncrypt_throwsInvalidParameterException() {
    oobConnectionManager.setOobData(TEST_OOB_DATA)
    oobConnectionManager.encryptVerificationCode(TEST_MESSAGE)
    assertThrows(InvalidAlgorithmParameterException::class.java) {
      oobConnectionManager.encryptVerificationCode(TEST_MESSAGE)
    }
  }

  @Test
  fun testDecryptWithShortMessage_throwsAEADBadTagException() {
    oobConnectionManager.setOobData(TEST_OOB_DATA)

    // An exception will be thrown if the message to decrypt is shorter than the IV
    assertThrows(AEADBadTagException::class.java) {
      oobConnectionManager.decryptVerificationCode("short".toByteArray())
    }
  }

  @Test
  fun testEncryptWithNullKey_throwsInvalidKeyException() {
    assertThrows(InvalidKeyException::class.java) {
      oobConnectionManager.encryptVerificationCode(TEST_MESSAGE)
    }
  }

  @Test
  fun testDecryptWithNullKey_throwsInvalidKeyException() {
    assertThrows(InvalidKeyException::class.java) {
      oobConnectionManager.decryptVerificationCode(TEST_MESSAGE)
    }
  }

  companion object {
    private val TEST_KEY = KeyGenerator.getInstance("AES").generateKey()
    private val TEST_ENCRYPTION_IV =
      ByteArray(OobConnectionManager.NONCE_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
    private val TEST_DECRYPTION_IV =
      ByteArray(OobConnectionManager.NONCE_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
    private val TEST_OOB_DATA = TEST_DECRYPTION_IV + TEST_ENCRYPTION_IV + TEST_KEY.encoded
    private val TEST_INVALID_OOB_DATA = TEST_ENCRYPTION_IV + TEST_ENCRYPTION_IV + TEST_KEY.encoded
    private val TEST_MESSAGE: ByteArray = "testMessage".toByteArray()
  }
}
