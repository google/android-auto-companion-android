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

/**
 * A helper that can encrypt and decrypt arbitrary values.
 *
 * The nature of encryption should be such that if data is extracted off the device, they cannot be
 * read.
 */
interface CryptoHelper {
  /** Encrypts the given [value] and returns the encrypted value. */
  fun encrypt(value: ByteArray): String?

  /**
   * Decrypts the given [value] and returns the decrypted value or `null` if decryption cannot be
   * performed.
   */
  fun decrypt(value: String): ByteArray?
}
