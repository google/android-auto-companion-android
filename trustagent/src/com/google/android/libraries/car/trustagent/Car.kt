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

import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.companionprotos.Query as QueryProto
import com.google.android.companionprotos.QueryResponse as QueryResponseProto
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.util.bytesToUuid
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.protobuf.InvalidProtocolBufferException
import java.util.ArrayDeque
import java.util.Queue
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.SecretKey
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Represents a connected remote device that runs Android Automotive OS.
 *
 * This class can be used to exchange messages with a Car.
 */
open class Car(
  private val bluetoothManager: BluetoothConnectionManager,
  internal open val messageStream: MessageStream,
  @GuardedBy("lock")
  @get:VisibleForTesting
  // Only keep this var in this class so it can be passed through callback.
  internal open val identificationKey: SecretKey,
  open val deviceId: UUID,
  open var name: String? = bluetoothManager.deviceName,
  private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
  /** The BluetoothDevice that this object represents (and is being connected to). */
  open val bluetoothDevice = bluetoothManager.bluetoothDevice

  private val coroutineScope = CoroutineScope(coroutineDispatcher)

  private val lock = ReentrantLock()
  /** Maps a recipient UUID to the callback to notify for events. */
  @GuardedBy("lock") private val callbacks = mutableMapOf<UUID, Callback>()

  /**
   * Stores messages/queries for recipients that do not have registered callback.
   *
   * When a callback is registered, these messages/queries are dispatched in FIFO order.
   */
  @GuardedBy("lock") private val unclaimedMessages = mutableMapOf<UUID, Queue<ByteArray>>()
  @GuardedBy("lock") private val unclaimedQueries = mutableMapOf<UUID, Queue<QueryProto>>()

  /** Maps a message Id to a recipient UUID. */
  @GuardedBy("lock") private val messageIdMap = mutableMapOf<Int, UUID>()

  /**
   * The current id to use when sending a query. Utilize the [nextQueryId] method to retrieve the
   * value. This ensures the id is correctly incremented.
   */
  @VisibleForTesting internal val queryId = AtomicInteger(0)

  /**
   * Maps a query id to handlers that should be invoked when a response for that query has come back
   * from the car.
   */
  @GuardedBy("lock")
  private val queryResponseHandlers = mutableMapOf<Int, (QueryResponse) -> Unit>()

  @VisibleForTesting
  internal val streamForwardingMessageCallback =
    object : MessageStream.Callback {
      override fun onMessageReceived(streamMessage: StreamMessage) {
        when (streamMessage.operation) {
          OperationType.CLIENT_MESSAGE -> handleMessage(streamMessage)
          OperationType.QUERY -> handleQuery(streamMessage)
          OperationType.QUERY_RESPONSE -> handleQueryResponse(streamMessage)
          else -> loge(TAG, "Received non-client message: ${streamMessage.operation}. Ignored.")
        }
      }

      override fun onMessageSent(messageId: Int) {
        lock.withLock {
          // Notify default recipient if incoming message doesn't specify one.
          // This is to be backward compatible with v1 stream, which doesn't support recipient.
          val recipient = messageIdMap.remove(messageId) ?: DEFAULT_FEATURE_ID
          callbacks[recipient]?.onMessageSent(messageId)
        }
      }
    }

  private val gattCallback =
    object : BluetoothConnectionManager.ConnectionCallback {
      override fun onConnected() {
        loge(TAG, "Unexpected BluetoothGattManager callback: onConnected.")
        throw IllegalStateException()
      }

      override fun onConnectionFailed() {
        loge(TAG, "Unexpected BluetoothGattManager callback: onConnectionFailed.")
        throw IllegalStateException()
      }

      override fun onDisconnected() {
        lock.withLock {
          coroutineScope.cancel()

          // Using `toList` to create a copy of the callbacks to notify in case the callbacks remove
          // themselves when `onDisconnected` is triggered.
          callbacks.values.toList().forEach { it.onDisconnected() }
        }
      }
    }

  init {
    bluetoothManager.registerConnectionCallback(gattCallback)
    messageStream.registerMessageEventCallback(streamForwardingMessageCallback)
  }

  override fun toString() = "[ID: $deviceId; name: $name; device: $bluetoothDevice]"

  override fun equals(other: Any?): Boolean {
    return if (other is Car) {
      this.deviceId == other.deviceId
    } else {
      false
    }
  }

  override fun hashCode(): Int = deviceId.hashCode()

  /**
   * Sends message to remote device Car and returns the id generated for the message.
   *
   * @param message The bytes to be sent to remote device.
   * @param recipient Identifies the recipient of [data] on remote device.
   */
  open fun sendMessage(message: ByteArray, recipient: UUID): Int {
    val messageId =
      messageStream.sendMessage(
        StreamMessage(
          payload = message,
          operation = OperationType.CLIENT_MESSAGE,
          isPayloadEncrypted = true,
          originalMessageSize = 0,
          recipient = recipient
        )
      )
    lock.withLock { messageIdMap[messageId] = recipient }
    return messageId
  }

  /**
   * Sends a query to the given [recipient] on a connected car.
   *
   * The format of the provided [Query] is feature defined and will be sent as-is to the car. The
   * provided [onResponse] function will be invoked with a [QueryResponse] that was sent by the car.
   */
  open fun sendQuery(query: Query, recipient: UUID, onResponse: (QueryResponse) -> Unit) {
    val queryId = nextQueryId()
    messageStream.sendMessage(
      StreamMessage(
        payload = query.toProtoByteArray(queryId = queryId, recipient = recipient),
        operation = OperationType.QUERY,
        isPayloadEncrypted = true,
        originalMessageSize = 0,
        recipient = recipient
      )
    )

    lock.withLock { queryResponseHandlers[queryId] = onResponse }
  }

  /**
   * Sends the [QueryResponse] to the specified [recipient] on the remote car.
   *
   * The `id` of the `QueryResponse` should match a previous query that was received via
   * [Callback.onQueryReceived]. The contents of the response is feature defined and will be sent
   * without modification to the car.
   */
  open fun sendQueryResponse(queryResponse: QueryResponse, recipient: UUID) {
    messageStream.sendMessage(
      StreamMessage(
        payload = queryResponse.toProtoByteArray(),
        operation = OperationType.QUERY_RESPONSE,
        isPayloadEncrypted = true,
        originalMessageSize = 0,
        recipient = recipient
      )
    )
  }

  internal open fun disconnect() {
    bluetoothManager.disconnect()
  }

  /**
   * Registers [Callback] for car updates.
   *
   * Only one callback can be registered per recipient. This is to prevent malicious features from
   * listening on messages from other features. Registering more than one callback will throw an
   * `IllegalStateException`.
   */
  open fun setCallback(callback: Callback, recipient: UUID) {
    lock.withLock {
      if (callbacks.containsKey(recipient) && callbacks[recipient] != callback) {
        throw IllegalStateException(
          "Callback is already registered for recipient $recipient. Only one callback can be " +
            "registered per recipient. This is to prevent malicious features from listening on " +
            "your messages."
        )
      }

      callbacks[recipient] = callback
    }

    coroutineScope.launch {
      dispatchUnclaimedMessages(recipient, callback)
      dispatchUnclaimedQueries(recipient, callback)
    }
  }

  /** Dispatches unclaimed messages for [recipient] to [callback]. */
  private fun dispatchUnclaimedMessages(recipient: UUID, callback: Callback) {
    lock.withLock {
      unclaimedMessages.remove(recipient)?.let { payloads ->
        while (payloads.isNotEmpty()) {
          payloads.poll()?.let {
            logi(TAG, "Dispatching stored message to $recipient.")
            callback.onMessageReceived(it)
          }
        }
      }
    }
  }

  private fun dispatchUnclaimedQueries(recipient: UUID, callback: Callback) {
    lock.withLock {
      unclaimedQueries.remove(recipient)?.let { queryProtos ->
        while (queryProtos.isNotEmpty()) {
          queryProtos.poll()?.let {
            logi(TAG, "Dispatching stored query ${it.id} to $recipient.")
            callback.onQueryReceived(it.id, bytesToUuid(it.sender.toByteArray()), it.toQuery())
          }
        }
      }
    }
  }

  /**
   * Clears the callback registered for the given [recipient].
   *
   * The callback is only cleared if it matches the specified [callback]. This is to prevent
   * malicious features from clearing a callback and registering themselves to listen on messages.
   */
  open fun clearCallback(callback: Callback, recipient: UUID) {
    lock.withLock {
      callbacks[recipient]?.takeIf { it == callback }?.run { callbacks.remove(recipient) }
    }
  }

  internal open fun toAssociatedCar() =
    lock.withLock { AssociatedCar(deviceId, name, bluetoothDevice.address, identificationKey) }

  private fun handleMessage(streamMessage: StreamMessage) {
    lock.withLock {
      // Notify default recipient if incoming message doesn't specify one.
      // This is to be backward compatible with v1 stream, which doesn't support recipient.
      val recipient = streamMessage.recipient ?: DEFAULT_FEATURE_ID
      if (recipient in callbacks) {
        callbacks[recipient]?.onMessageReceived(streamMessage.payload)
      } else {
        logi(TAG, "Received message for $recipient but no registered callback. Saving message.")
        storeUnclaimedMessage(recipient, streamMessage.payload)
      }
    }
  }

  private fun storeUnclaimedMessage(recipient: UUID, payload: ByteArray) {
    lock.withLock { unclaimedMessages.getOrPut(recipient) { ArrayDeque<ByteArray>() }.add(payload) }
  }

  private fun handleQuery(streamMessage: StreamMessage) {
    val queryProto =
      try {
        QueryProto.parseFrom(streamMessage.payload)
      } catch (e: InvalidProtocolBufferException) {
        loge(TAG, "Received a query but unable to parse. Ignoring", e)
        return
      }

    val recipient = streamMessage.recipient
    if (recipient == null) {
      loge(TAG, "Received query for null recipient. Ignoring.")
      return
    }
    logi(TAG, "Received query for recipient: $recipient. Invoking callback")

    lock.withLock {
      if (recipient in callbacks) {
        callbacks[recipient]?.onQueryReceived(
          queryProto.id,
          bytesToUuid(queryProto.sender.toByteArray()),
          queryProto.toQuery()
        )
      } else {
        logi(TAG, "Received query for $recipient but no registered callback. Saving query.")
        storeUnclaimedQuery(recipient, queryProto)
      }
    }
  }

  private fun storeUnclaimedQuery(recipient: UUID, queryProto: QueryProto) {
    lock.withLock {
      unclaimedQueries.getOrPut(recipient) { ArrayDeque<QueryProto>() }.add(queryProto)
    }
  }

  private fun handleQueryResponse(streamMessage: StreamMessage) {
    val queryResponseProto =
      try {
        QueryResponseProto.parseFrom(streamMessage.payload)
      } catch (e: InvalidProtocolBufferException) {
        loge(TAG, "Received a query response but unable to parse. Ignoring", e)
        return
      }

    val handler = lock.withLock { queryResponseHandlers.remove(queryResponseProto.queryId) }
    if (handler == null) {
      loge(
        TAG,
        "Received query response for query id ${queryResponseProto.queryId}, " +
          "but no registered handler. Ignoring."
      )
      return
    }

    logi(
      TAG,
      "Received a query response for recipient: ${streamMessage.recipient}. Invoking callback"
    )

    handler(queryResponseProto.toQueryResponse())
  }

  /**
   * Returns the id that should be used to construct a new
   * [com.google.android.companionprotos.Query].
   *
   * Each call to this method will return an increment from a previous call up to the maximum
   * integer value. When the maximum value is reached, the id wraps back to 0.
   */
  private fun nextQueryId(): Int = queryId.getAndUpdate { if (it == Int.MAX_VALUE) 0 else it + 1 }

  /** Converts this proto to its [Query] representation. */
  private fun QueryProto.toQuery(): Query = Query(request.toByteArray(), parameters.toByteArray())

  /** Converts this proto to its [QueryResponse] representation. */
  private fun QueryResponseProto.toQueryResponse(): QueryResponse =
    QueryResponse(queryId, success, response.toByteArray())

  /** Callback for car interaction. */
  interface Callback {
    /** Invoked when a message has been received from car. */
    fun onMessageReceived(data: ByteArray)

    /** Invoked when the message with the specified [messageId] has been sent to the car. */
    fun onMessageSent(messageId: Int)

    /**
     * Invoked when a query has been received from a car.
     *
     * The given [queryId] should be later used to respond to query. The formatting of the query
     * `request` and `parameters` are feature defined and sent as-is from the car. The response
     * should be sent to the specified [sender].
     */
    fun onQueryReceived(queryId: Int, sender: UUID, query: Query)

    /** Invoked when car has disconnected. This object should now be discarded. */
    fun onDisconnected()
  }

  companion object {
    private const val TAG = "Car"

    // Default to recipient ID of trusted device manager.
    // LINT.IfChange(DEFAULT_FEATURE_ID)
    private val DEFAULT_FEATURE_ID: UUID = UUID.fromString("baf7b76f-d419-40ce-8aeb-2b80c6510123")
    // LINT.ThenChange(//depot/google3/third_party/java_src/android_libs/automotive_trusteddevice/java/com/google/android/libraries/car/trusteddevice/TrustedDeviceManager.kt:FEATURE_ID)
  }
}
