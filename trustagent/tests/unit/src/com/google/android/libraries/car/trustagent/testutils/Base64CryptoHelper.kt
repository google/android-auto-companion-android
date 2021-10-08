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

package com.google.android.libraries.car.trustagent.testutils

import android.util.Base64
import com.google.android.libraries.car.trustagent.storage.CryptoHelper

/**
 * An implementation of [CryptoHelper] that does base64 de/encoding, instead of de/encryption.
 *
 * DO NOT USE outside a test.
 */
class Base64CryptoHelper : CryptoHelper {
  override fun encrypt(value: ByteArray): String? = Base64.encodeToString(value, Base64.DEFAULT)

  override fun decrypt(value: String): ByteArray? = Base64.decode(value, Base64.DEFAULT)
}
