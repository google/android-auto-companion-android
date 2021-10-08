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

/** The max size in bytes for an int32 field in a protobuf. */
internal const val MAX_INT32_ENCODING_BYTES = 5

/**
 * Computes and returns the number of bytes that would be needed to store a 32-bit [variant].
 *
 * Negative value is not considered because all proto values should be positive. The methods in this
 * section are taken from
 * google3/third_party/swift/swift_protobuf/Sources/SwiftProtobuf/Varint.swift. It should be kept in
 * sync as long as the proto version remains the same.
 */
internal fun getEncodedSize(variant: Int): Int {
  return when {
    variant < 0 -> 10
    variant and (0.inv() shl 7) == 0 -> 1
    variant and (0.inv() shl 14) == 0 -> 2
    variant and (0.inv() shl 21) == 0 -> 3
    variant and (0.inv() shl 28) == 0 -> 4
    else -> MAX_INT32_ENCODING_BYTES
  }
}
