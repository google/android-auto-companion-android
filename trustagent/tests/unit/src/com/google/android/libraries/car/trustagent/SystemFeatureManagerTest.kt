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
import com.google.android.companionprotos.SystemQuery
import com.google.android.companionprotos.SystemQueryType
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val CAR_ID = UUID.fromString("b9592993-2f53-40a8-8b87-e218e592c165")
private const val DEVICE_NAME = "deviceName"
private const val APP_NAME = "appName"

@RunWith(AndroidJUnit4::class)
class SystemFeatureManagerTest {
  private lateinit var manager: SystemFeatureManager

  @Before
  fun setUp() {
    manager =
      SystemFeatureManager(
        deviceNameProvider = { DEVICE_NAME },
        appNameProvider = { APP_NAME }
      )
  }

  @Test
  fun testOnValidNameQuery_writesNameResponseHandler() {
    val onResponse: (Boolean, ByteArray) -> Unit = mock()
    val request = createSystemQueryProto(SystemQueryType.DEVICE_NAME)
    val query = Query(request, parameters = null)

    manager.onQueryReceived(query, CAR_ID, onResponse)

    verify(onResponse).invoke(true, DEVICE_NAME.toByteArray())
  }

  @Test
  fun testOnValidAppNameQuery_writesNameResponseHandler() {
    val onResponse: (Boolean, ByteArray) -> Unit = mock()
    val request = createSystemQueryProto(SystemQueryType.APP_NAME)
    val query = Query(request, parameters = null)

    manager.onQueryReceived(query, CAR_ID, onResponse)

    verify(onResponse).invoke(true, APP_NAME.toByteArray())
  }

  @Test
  fun testInvalidQueryProto_doesNotWriteToResponseHandler() {
    val onResponse: (Boolean, ByteArray) -> Unit = mock()

    // Request that is not a proto.
    val query = Query("request".toByteArray(), parameters = null)

    manager.onQueryReceived(query, CAR_ID, onResponse)

    verify(onResponse, never()).invoke(any(), any())
  }

  @Test
  fun testWrongOperationType_sendsUnsuccessfulQueryResponse() {
    val onResponse: (Boolean, ByteArray) -> Unit = mock()

    // Request with wrong operation type.
    val request = createSystemQueryProto(SystemQueryType.SYSTEM_QUERY_TYPE_UNKNOWN)
    val query = Query(request, parameters = null)

    manager.onQueryReceived(query, CAR_ID, onResponse)

    verify(onResponse).invoke(false, byteArrayOf())
  }

  @Test
  fun testResponseHandlerException_doesNotCrash() {
    val onResponse: (Boolean, ByteArray) -> Unit = { _, _ ->
      throw IllegalArgumentException("Simulating a write failure")
    }

    val request = createSystemQueryProto(SystemQueryType.DEVICE_NAME)
    val query = Query(request, parameters = null)

    manager.onQueryReceived(query, CAR_ID, onResponse)
  }

  /** Returns a serialized [SystemQuery] with the given operation type. */
  private fun createSystemQueryProto(type: SystemQueryType): ByteArray =
    SystemQuery.newBuilder().setType(type).build().toByteArray()
}
