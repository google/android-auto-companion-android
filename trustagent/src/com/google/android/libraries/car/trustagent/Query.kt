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

import com.google.android.companionprotos.Query as QueryProto
import com.google.android.companionprotos.QueryResponse as QueryResponseProto
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.uuidToBytes
import com.google.protobuf.ByteString
import java.util.UUID

/**
 * Represents a query that can be sent to a remote car.
 *
 * The format of the [request] and [parameters] are feature-defined and will be sent as-is to the
 * car.
 */
@PublicApi
data class Query(val request: ByteArray, val parameters: ByteArray?) {
  internal fun toProtoByteArray(queryId: Int, recipient: UUID): ByteArray =
    QueryProto.newBuilder()
      .setId(queryId)
      .setSender(ByteString.copyFrom(uuidToBytes(recipient)))
      .setRequest(ByteString.copyFrom(request))
      .setParameters(ByteString.copyFrom(parameters ?: byteArrayOf()))
      .build()
      .toByteArray()

  // Equals method needs to be defined manually since Kotlin compares arrays by reference and not
  // content.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Query) return false

    return request contentEquals other.request && parameters contentEquals other.parameters
  }
}

/**
 * Response to a response to a query by a remote car.
 *
 * The format of the [response] and whether a query is successful are feature-defined and will be
 * sent as-is to car.
 */
@PublicApi
data class QueryResponse(internal val id: Int, val isSuccessful: Boolean, val response: ByteArray) {
  internal fun toProtoByteArray(): ByteArray =
    QueryResponseProto.newBuilder()
      .setQueryId(id)
      .setSuccess(isSuccessful)
      .setResponse(ByteString.copyFrom(response))
      .build()
      .toByteArray()

  // Equals method needs to be defined manually since Kotlin compares arrays by reference and not
  // content.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is QueryResponse) return false

    return id == other.id &&
      isSuccessful == other.isSuccessful &&
      response contentEquals other.response
  }
}
