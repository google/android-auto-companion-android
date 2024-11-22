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

import android.os.Build.VERSION
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.DeviceOS
import com.google.android.companionprotos.DeviceVersionsResponse
import com.google.android.companionprotos.FeatureSupportResponse
import com.google.android.companionprotos.SystemQuery
import com.google.android.companionprotos.SystemQueryType
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

private val CAR_ID = UUID.fromString("b9592993-2f53-40a8-8b87-e218e592c165")
private const val DEVICE_NAME = "deviceName"
private const val APP_NAME = "appName"
private const val COMPANION_SDK_VERSION = "1.0.0"

@RunWith(AndroidJUnit4::class)
class SystemFeatureManagerTest {
  private lateinit var manager: SystemFeatureManager

  @Before
  fun setUp() {
    manager =
      SystemFeatureManager(
        deviceNameProvider = { DEVICE_NAME },
        appNameProvider = { APP_NAME },
        companionSdkVersion = COMPANION_SDK_VERSION,
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
  fun onValidOsQuery_writesOsResponseHandler() {
    val onResponse: (Boolean, ByteArray) -> Unit = mock()
    val request = createSystemQueryProto(SystemQueryType.DEVICE_OS)
    val query = Query(request, parameters = null)

    manager.onQueryReceived(query, CAR_ID, onResponse)

    val deviceVersionsResponse =
      DeviceVersionsResponse.newBuilder()
        .setOs(DeviceOS.ANDROID)
        .setOsVersion(VERSION.SDK_INT.toString())
        .setCompanionSdkVersion(COMPANION_SDK_VERSION)
        .build()
        .toByteArray()

    verify(onResponse).invoke(true, deviceVersionsResponse)
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
  fun testIsFeatureSupported_disconnectedCar_featureNotSupported() {
    val queriedFeatureId = UUID.randomUUID()
    val request =
      createSystemQueryProto(
        SystemQueryType.IS_FEATURE_SUPPORTED,
        payloads = listOf(queriedFeatureId.toString().toByteArray()),
      )
    val query = Query(request, parameters = null)
    val onResponse: (Boolean, ByteArray) -> Unit = mock()

    // CAR_ID is not notified as connected.
    manager.onQueryReceived(query, CAR_ID, onResponse)

    val response =
      argumentCaptor<ByteArray>().run {
        verify(onResponse).invoke(eq(true), capture())
        firstValue
      }
    val featureSupportResponse = FeatureSupportResponse.parseFrom(response)
    val status = featureSupportResponse.statusesList.first()

    assertThat(UUID.fromString(status.featureId)).isEqualTo(queriedFeatureId)
    assertThat(status.isSupported).isFalse()
  }

  @Test
  fun testIsFeatureSupported_featureIsSupported_returnsSupported() {
    val queriedFeatureId = UUID.randomUUID()
    val request =
      createSystemQueryProto(
        SystemQueryType.IS_FEATURE_SUPPORTED,
        payloads = listOf(queriedFeatureId.toString().toByteArray()),
      )
    val query = Query(request, parameters = null)
    val onResponse: (Boolean, ByteArray) -> Unit = mock()

    // Connect CAR_ID and registere the feature.
    val mockCar: Car = mock {
      on { deviceId } doReturn CAR_ID
      on { isFeatureSupported(queriedFeatureId) } doReturn true
    }
    manager.notifyCarConnected(mockCar)

    manager.onQueryReceived(query, CAR_ID, onResponse)

    val response =
      argumentCaptor<ByteArray>().run {
        verify(onResponse).invoke(eq(true), capture())
        firstValue
      }
    val featureSupportResponse = FeatureSupportResponse.parseFrom(response)
    val status = featureSupportResponse.statusesList.first()

    assertThat(UUID.fromString(status.featureId)).isEqualTo(queriedFeatureId)
    assertThat(status.isSupported).isTrue()
  }

  @Test
  fun testIsFeatureSupported_featureIsNotSupported_returnsNotSupported() {
    val queriedFeatureId = UUID.randomUUID()
    val request =
      createSystemQueryProto(
        SystemQueryType.IS_FEATURE_SUPPORTED,
        payloads = listOf(queriedFeatureId.toString().toByteArray()),
      )
    val query = Query(request, parameters = null)
    val onResponse: (Boolean, ByteArray) -> Unit = mock()

    // Connect CAR_ID and registere the feature.
    val mockCar: Car = mock {
      on { deviceId } doReturn CAR_ID
      on { isFeatureSupported(queriedFeatureId) } doReturn false
    }
    manager.notifyCarConnected(mockCar)

    manager.onQueryReceived(query, CAR_ID, onResponse)

    val response =
      argumentCaptor<ByteArray>().run {
        verify(onResponse).invoke(eq(true), capture())
        firstValue
      }
    val featureSupportResponse = FeatureSupportResponse.parseFrom(response)
    val status = featureSupportResponse.statusesList.first()

    assertThat(UUID.fromString(status.featureId)).isEqualTo(queriedFeatureId)
    assertThat(status.isSupported).isFalse()
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
  private fun createSystemQueryProto(
    type: SystemQueryType,
    payloads: List<ByteArray> = emptyList(),
  ): ByteArray =
    SystemQuery.newBuilder()
      .run {
        setType(type)
        addAllPayloads(payloads.map { ByteString.copyFrom(it) })
        build()
      }
      .toByteArray()
}
