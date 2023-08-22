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

package com.google.android.libraries.car.trustagent.blemessagestream

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.libraries.car.trustagent.blemessagestream.version2.BluetoothMessageStreamV2
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class MessageStreamTest {

  private val mockBluetothManager: BluetoothConnectionManager = mock()
  private lateinit var stream: BluetoothMessageStreamV2

  @Before
  fun setUp() {
    stream = BluetoothMessageStreamV2(mockBluetothManager, isCompressionEnabled = false)
  }

  @Test
  fun testCompressData_canNotCompress_returnsNull() {
    val payload = ByteArray(1)
    val compressed = MessageStream.compressData(payload)
    assertThat(compressed).isNull()
  }

  @Test
  fun testCompressData_canCompress_returnsSmallerData() {
    val payload = ByteArray(1000)
    val compressed = MessageStream.compressData(payload)
    assertThat(payload.size).isGreaterThan(compressed?.size)
  }

  @Test
  fun testCompressDecompress() {
    val size = 1000
    val payload = ByteArray(size)
    val compressed = MessageStream.compressData(payload)
    val decompressed = MessageStream.decompressData(compressed!!, size)

    assertThat(decompressed!!.contentEquals(payload)).isTrue()
  }

  @Test
  fun testDecompressData_originalSizeIsZero_returnsOriginal() {
    val payload = ByteArray(10)
    val decompressed = MessageStream.decompressData(payload, originalSize = 0)
    assertThat(decompressed).isEqualTo(payload)
  }

  @Test
  fun testToEncrypted_payloadNotEncrypted_throwsException() {
    val message =
      StreamMessage(
        ByteArray(1),
        OperationType.CLIENT_MESSAGE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = null
      )
    assertFailsWith<IllegalStateException> {
      // We cannot create a mocked instance of interface here,
      // likely because the extension methods live in the scope provided by the concrete class.
      with(stream) { message.toEncrypted() }
    }
  }

  @Test
  fun testToDecrypted_payloadNotEncrypted_throwsException() {
    val message =
      StreamMessage(
        ByteArray(1),
        OperationType.CLIENT_MESSAGE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = null
      )
    assertFailsWith<IllegalStateException> { with(stream) { message.toDecrypted() } }
  }
}
