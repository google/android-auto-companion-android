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
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarTest {
  private val carId = UUID.fromString("3e6a5c94-e1d4-4d10-99e1-75e8af4ee463")
  private val carName = "testCarName"
  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var car: Car
  private lateinit var bluetoothGattManager: BluetoothGattManager
  private lateinit var bleMessageStream: MessageStream
  private lateinit var identificationKey: SecretKey

  @Before
  fun setUp() {
    bluetoothGattManager = mock()
    bleMessageStream = mock()
    identificationKey = mock()

    car =
      Car(bluetoothGattManager, bleMessageStream, identificationKey, carId, carName, testDispatcher)
  }

  @Test
  fun testSetCallback_forSameRecipient_throwsError() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")

    car.setCallback(mock(), recipient)

    assertFailsWith<IllegalStateException> { car.setCallback(mock(), recipient) }
  }

  @Test
  fun testSetCallback_sameRecipient_sameCallback_doesNotThrowError() {
    val callback: Car.Callback = mock()
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")

    car.setCallback(callback, recipient)
    car.setCallback(callback, recipient)
  }

  @Test
  fun testSetCallback_forDifferentRecipient_doesNotThrowError() {
    val recipient1 = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val recipient2 = UUID.fromString("12c93241-c200-49d7-ad26-ad4042369eb3")

    car.setCallback(mock(), recipient1)
    car.setCallback(mock(), recipient2)
  }

  @Test
  fun testClearCallback() {
    val callback: Car.Callback = mock()
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")

    car.setCallback(callback, recipient)
    car.clearCallback(callback, recipient)

    // This set should not throw an error since the callback was cleared now.
    car.setCallback(callback, recipient)
  }

  @Test
  fun testClearCallback_forDifferentCallback_doesNotClear() {
    val callback: Car.Callback = mock()
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")

    car.setCallback(callback, recipient)

    val otherCallback: Car.Callback = mock()
    car.clearCallback(otherCallback, recipient)

    // The clear was called for a different callback, so it won't succeed and an error should
    // still be thrown.
    assertFailsWith<IllegalStateException> { car.setCallback(otherCallback, recipient) }
  }

  @Test
  fun testMessageReceived_notifiesCallback() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")

    val callback: Car.Callback = mock()
    car.setCallback(callback, recipient)

    val message = "message".toByteArray()

    val streamMessage =
      StreamMessage(
        payload = message,
        operation = OperationType.CLIENT_MESSAGE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )

    car.streamForwardingMessageCallback.onMessageReceived(streamMessage)

    verify(callback).onMessageReceived(message)
  }

  @Test
  fun testUnclaimedMessages_deliveredWhenCallbackRegistered() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val message = "message".toByteArray()
    val streamMessage =
      StreamMessage(
        payload = message,
        operation = OperationType.CLIENT_MESSAGE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )
    car.streamForwardingMessageCallback.onMessageReceived(streamMessage)

    val callback = mock<Car.Callback>()
    car.setCallback(callback, recipient)

    verify(callback).onMessageReceived(message)
  }

  @Test
  fun testUnclaimedMessages_deliveredInFifoOrder() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val message1 = "message1".toByteArray()
    car.streamForwardingMessageCallback.onMessageReceived(
      StreamMessage(
        payload = message1,
        operation = OperationType.CLIENT_MESSAGE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )
    )
    val message2 = "message2".toByteArray()
    car.streamForwardingMessageCallback.onMessageReceived(
      StreamMessage(
        payload = message2,
        operation = OperationType.CLIENT_MESSAGE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )
    )

    val callback = mock<Car.Callback>()
    car.setCallback(callback, recipient)

    inOrder(callback).apply {
      verify(callback).onMessageReceived(message1)
      verify(callback).onMessageReceived(message2)
    }
  }

  @Test
  fun testSendQuery_writesToMessageStream() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val query = Query("request".toByteArray(), parameters = null)
    val queryId = 13
    car.queryId.set(queryId)

    car.sendQuery(query, recipient, onResponse = {})

    val captor = argumentCaptor<StreamMessage>()
    verify(bleMessageStream).sendMessage(captor.capture())

    val expectedMessage =
      StreamMessage(
        payload = query.toProtoByteArray(queryId = queryId, recipient = recipient),
        operation = OperationType.QUERY,
        isPayloadEncrypted = true,
        originalMessageSize = 0,
        recipient = recipient
      )

    assertThat(captor.firstValue).isEqualTo(expectedMessage)
  }

  @Test
  fun testSendMultipleQueries_incrementsQueryId() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val query = Query("request".toByteArray(), parameters = null)
    val queryId = 13
    car.queryId.set(queryId)

    car.sendQuery(query, recipient, onResponse = {})
    car.sendQuery(query, recipient, onResponse = {})

    val captor = argumentCaptor<StreamMessage>()
    verify(bleMessageStream, times(2)).sendMessage(captor.capture())

    var expectedQuery = query.toProtoByteArray(queryId = queryId, recipient = recipient)
    assertThat(captor.firstValue.payload).isEqualTo(expectedQuery)

    // Second query should have an incremented id.
    expectedQuery = query.toProtoByteArray(queryId = queryId + 1, recipient = recipient)
    assertThat(captor.secondValue.payload).isEqualTo(expectedQuery)
  }

  @Test
  fun testQueryResponseReceived_notifiesCorrectHandler() {
    val semaphore = Semaphore(0)
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val query = Query("request".toByteArray(), parameters = null)
    val queryId = 13
    car.queryId.set(queryId)

    val expectedQueryResponse =
      QueryResponse(queryId, isSuccessful = true, "response".toByteArray())

    car.sendQuery(query, recipient) { queryResponse ->
      semaphore.release()
      assertThat(queryResponse).isEqualTo(expectedQueryResponse)
    }

    val streamMessage =
      StreamMessage(
        payload = expectedQueryResponse.toProtoByteArray(),
        operation = OperationType.QUERY_RESPONSE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )

    car.streamForwardingMessageCallback.onMessageReceived(streamMessage)

    // If the callback is not called, then the semaphore will never be acquired and will fail the
    // test. Wait for 100ms, since it should be invoked immediately.
    assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue()
  }

  @Test
  fun testQueryResponseReceived_wrongOperationType_doesNotNotifyHandler() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val query = Query("request".toByteArray(), parameters = null)
    val queryId = 13
    car.queryId.set(queryId)

    val queryResponse = QueryResponse(queryId, isSuccessful = true, "response".toByteArray())

    car.sendQuery(query, recipient) { _ -> fail("Handler called") }

    // StreamMessage with the wrong OperationType.
    val streamMessage =
      StreamMessage(
        payload = queryResponse.toProtoByteArray(),
        operation = OperationType.QUERY,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )

    car.streamForwardingMessageCallback.onMessageReceived(streamMessage)
  }

  @Test
  fun testQueryResponseReceived_wrongQueryId_doesNotifyHandler() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val query = Query("request".toByteArray(), parameters = null)
    val queryId = 13
    val otherQueryId = 26
    car.queryId.set(queryId)

    val queryResponse = QueryResponse(otherQueryId, isSuccessful = true, "response".toByteArray())

    car.sendQuery(query, recipient) { _ -> fail("Handler invoked") }

    val streamMessage =
      StreamMessage(
        payload = queryResponse.toProtoByteArray(),
        operation = OperationType.QUERY_RESPONSE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )

    car.streamForwardingMessageCallback.onMessageReceived(streamMessage)
  }

  @Test
  fun testQueryRecieved_invokesCallback() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val sender = UUID.fromString("64ed652b-b706-4684-a8bc-0dbc17ee452d")
    val callback: Car.Callback = mock()
    car.setCallback(callback, recipient)

    val query = Query("request".toByteArray(), "parameters".toByteArray())
    val queryId = 13

    val streamMessage =
      StreamMessage(
        payload = query.toProtoByteArray(queryId, sender),
        operation = OperationType.QUERY,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )

    car.streamForwardingMessageCallback.onMessageReceived(streamMessage)

    verify(callback).onQueryReceived(queryId, sender, query)
  }

  @Test
  fun testQueryRecieved_withWrongOperationCallback_doesNotInvokeCallback() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")

    val callback: Car.Callback = mock()
    car.setCallback(callback, recipient)

    val query = Query("request".toByteArray(), "parameters".toByteArray())

    // Message with the wrong operation type.
    val streamMessage =
      StreamMessage(
        payload = query.toProtoByteArray(queryId = 13, recipient),
        operation = OperationType.QUERY_RESPONSE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )

    car.streamForwardingMessageCallback.onMessageReceived(streamMessage)

    verify(callback, never()).onQueryReceived(any(), any(), any())
  }

  @Test
  fun testQueryReceived_deliveredWhenCallbackRegistered() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val sender = UUID.fromString("64ed652b-b706-4684-a8bc-0dbc17ee452d")
    val query = Query("request".toByteArray(), "parameters".toByteArray())
    val queryId = 13
    val streamMessage =
      StreamMessage(
        payload = query.toProtoByteArray(queryId, sender),
        operation = OperationType.QUERY,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )
    car.streamForwardingMessageCallback.onMessageReceived(streamMessage)

    val callback: Car.Callback = mock()
    car.setCallback(callback, recipient)

    verify(callback).onQueryReceived(queryId, sender, query)
  }

  @Test
  fun testQueryReceived_deliveredInFifoOrder() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")

    val sender = UUID.fromString("81a0763d-f7f5-4b7d-ab01-09a4e45257c3")
    val query1 = Query("request1".toByteArray(), "parameters".toByteArray())
    val queryId1 = 13
    car.streamForwardingMessageCallback.onMessageReceived(
      StreamMessage(
        payload = query1.toProtoByteArray(queryId1, sender),
        operation = OperationType.QUERY,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )
    )

    val sender2 = UUID.fromString("20a4af97-5647-4176-9e4f-dfc0dc5b93a9")
    val query2 = Query("request2".toByteArray(), "parameters".toByteArray())
    val queryId2 = 14
    car.streamForwardingMessageCallback.onMessageReceived(
      StreamMessage(
        payload = query2.toProtoByteArray(queryId2, sender2),
        operation = OperationType.QUERY,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = recipient
      )
    )

    val callback: Car.Callback = mock()
    car.setCallback(callback, recipient)

    inOrder(callback).apply {
      verify(callback).onQueryReceived(queryId1, sender, query1)
      verify(callback).onQueryReceived(queryId2, sender2, query2)
    }
  }

  @Test
  fun testIsFeatureSupported_supportedFeature() {
    val recipient = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")
    val callback = mock<Car.Callback>()
    car.setCallback(callback, recipient)

    assertThat(car.isFeatureSupported(recipient)).isTrue()
  }

  @Test
  fun testIsFeatureSupported_unsupportedFeature() {
    // The recipient UUID is not registered.
    val featureId = UUID.fromString("e284f45d-666f-479f-bd48-b8be0283977e")

    assertThat(car.isFeatureSupported(featureId)).isFalse()
  }
}
