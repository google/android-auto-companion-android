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
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val SENDER_ID = UUID.fromString("ac3be440-c193-4960-a154-ed10c51045f0")
private val FEATURE_ID = UUID.fromString("c673cad8-59b9-4f86-bb5a-13d115e2ce26")
private val CAR_ID = UUID.fromString("b9592993-2f53-40a8-8b87-e218e592c165")
private val OTHER_CAR_ID = UUID.fromString("829466cd-3321-4af5-ac6b-9d7e175d76dc")

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class FeatureManagerTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var featureManger: FeatureManagerFake
  private lateinit var car: Car
  private lateinit var otherCar: Car

  @Before
  fun setUp() {
    car =
      spy(
        Car(
          bluetoothManager = mock(),
          messageStream = mock(),
          identificationKey = mock(),
          CAR_ID,
          coroutineDispatcher = testDispatcher
        )
      )
    otherCar =
      spy(
        Car(
          bluetoothManager = mock(),
          messageStream = mock(),
          identificationKey = mock(),
          OTHER_CAR_ID,
          coroutineDispatcher = testDispatcher
        )
      )
    featureManger = spy(FeatureManagerFake())
  }

  @Test
  fun sendMessage_carNotConnected_returnsInvalidMessageId() {
    val message = "message".toByteArray()
    val messageId = featureManger.sendMessage(message, CAR_ID)
    assertThat(messageId).isEqualTo(FeatureManager.INVALID_MESSAGE_ID)
  }

  @Test
  fun sendMessage_writesToCorrectCar() {
    val message = "message".toByteArray()

    featureManger.notifyCarConnected(car)
    featureManger.notifyCarConnected(otherCar)
    featureManger.sendMessage(message, CAR_ID)

    verify(car).sendMessage(message, FEATURE_ID)
    verify(otherCar, never()).sendMessage(any(), any())
  }

  @Test
  fun sendMessage_returnsCorrectMessageId() {
    val expectedMessageId = 33
    doReturn(expectedMessageId).whenever(car).sendMessage(any(), any())

    featureManger.notifyCarConnected(car)

    val message = "message".toByteArray()
    val messageId = featureManger.sendMessage(message, CAR_ID)

    assertThat(messageId).isEqualTo(expectedMessageId)
  }

  @Test
  fun testSendQuery_carConnected_doesNotInvokeOnResponseWithFailure() {
    val query = Query("request".toByteArray(), parameters = null)

    featureManger.notifyCarConnected(car)

    featureManger.sendQuery(query, CAR_ID) { fail("OnResponse invoked") }
  }

  @Test
  fun testSendQuery_carNotConnected_invokesOnResponseWithFailure() {
    val semaphore = Semaphore(0)
    val query = Query("request".toByteArray(), parameters = null)

    val expectedQueryResponse =
      QueryResponse(
        id = FeatureManager.INVALID_QUERY_ID,
        isSuccessful = false,
        response = byteArrayOf()
      )

    featureManger.sendQuery(query, CAR_ID) { queryResponse ->
      semaphore.release()
      assertThat(queryResponse).isEqualTo(expectedQueryResponse)
    }

    // `onResponse` should be invoked immediately, so only waiting for 100ms
    assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue()
  }

  @Test
  fun testSendQuery_writesToCorrectCar() {
    val onResponse: (QueryResponse) -> Unit = {}
    val query = Query("request".toByteArray(), parameters = null)

    featureManger.notifyCarConnected(car)
    featureManger.notifyCarConnected(otherCar)
    featureManger.sendQuery(query, CAR_ID, onResponse)

    verify(car).sendQuery(query, FEATURE_ID, onResponse)
    verify(otherCar, never()).sendQuery(any(), any(), any())
  }

  @Test
  fun testOnQueryReceived_callsCallback() {
    val query = Query("request".toByteArray(), parameters = null)
    featureManger.notifyCarConnected(car)

    val callback = captureCallback(car)
    callback.onQueryReceived(queryId = 13, sender = SENDER_ID, query)

    verify(featureManger).onQueryReceived(eq(query), eq(CAR_ID), any())
  }

  @Test
  fun testQueryResponseHandler_writesToCorrectCar() {
    featureManger.notifyCarConnected(car)
    featureManger.notifyCarConnected(otherCar)

    val queryId = 13
    val query = Query("request".toByteArray(), parameters = null)
    val callback = captureCallback(car)
    callback.onQueryReceived(queryId, SENDER_ID, query)

    val handlerCaptor = argumentCaptor<(Boolean, ByteArray) -> Unit>()
    verify(featureManger).onQueryReceived(eq(query), eq(CAR_ID), handlerCaptor.capture())

    val responseHandler = handlerCaptor.firstValue

    val isSuccessful = true
    val response = "response".toByteArray()
    responseHandler(isSuccessful, response)

    val expectedQueryResponse = QueryResponse(queryId, isSuccessful, response)
    verify(car).sendQueryResponse(expectedQueryResponse, SENDER_ID)
    verify(otherCar, never()).sendQueryResponse(any(), any())
  }

  /** Verifies that a callback was set on the provided [car] and returns that callback. */
  private fun captureCallback(car: Car): Car.Callback =
    argumentCaptor<Car.Callback>().run {
      verify(car).setCallback(capture(), eq(FEATURE_ID))
      firstValue
    }
}

/** A concrete implementation of [FeatureManager] so that it can be tested. */
internal open class FeatureManagerFake : FeatureManager() {
  override val featureId = FEATURE_ID

  override fun onCarConnected(deviceId: UUID) {}

  override fun onMessageReceived(message: ByteArray, deviceId: UUID) {}

  override fun onMessageSent(messageId: Int, deviceId: UUID) {}

  override fun onCarDisconnected(deviceId: UUID) {}

  override fun onCarDisassociated(deviceId: UUID) {}

  override fun onAllCarsDisassociated() {}

  // Explicit override to expose the following methods as public for testing. This preserves the
  // existing `FeatureManager`'s methods as `protected`.

  public override fun sendQuery(query: Query, deviceId: UUID, onResponse: (QueryResponse) -> Unit) {
    super.sendQuery(query, deviceId, onResponse)
  }
}
