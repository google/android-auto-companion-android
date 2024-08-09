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

import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logwtf
import java.util.UUID

/** Interface for a feature that exchanges data with a car that runs Android Automotive OS. */
@PublicApi
abstract class FeatureManager {
  /**
   * Map of car device ID to the [Car] object and its registered callback. Keeps track of the
   * registered callback for clean up.
   */
  private val carsAndCallbacks = mutableMapOf<UUID, Pair<Car, Car.Callback>>()

  /** Uniquely identifies this feature; should remain consistent across connection sessions. */
  abstract val featureId: UUID

  /** Invoked when a car has been connected. */
  abstract fun onCarConnected(deviceId: UUID)

  /** Invoked when data intended for [featureId] has been received from car with [deviceId]. */
  abstract fun onMessageReceived(message: ByteArray, deviceId: UUID)

  /** Invoked when message with [messageId] has been sent to car with [deviceId]. */
  abstract fun onMessageSent(messageId: Int, deviceId: UUID)

  /**
   * Invoked when car has disconnected.
   *
   * For [deviceId] there will no longer be any incoming message; [sendMessage] will fail.
   */
  abstract fun onCarDisconnected(deviceId: UUID)

  /**
   * Invoked when a car with the given [deviceId] has disassociated.
   *
   * Feature managers should use this event to wipe any data that related to the given car. The car
   * will need to go through the association process again before messages can be sent to and
   * received from it.
   *
   * A disassociation event can occur even if the given car is not currently connected.
   */
  abstract fun onCarDisassociated(deviceId: UUID)

  /**
   * Invoked when all associated cars have been disassociated.
   *
   * Feature managers can use this event as a signal to wipe all data pertaining to associated cars.
   * After this method is called, any car that wishes to send and receive messages will need to go
   * through the association progress again.
   */
  abstract fun onAllCarsDisassociated()

  /**
   * Invoked when a car with the given [deviceId] has associated.
   *
   * [onCarConnected] will be invoked after association as well. Only implement this method if the
   * feature needs to differentiated the first connection and the subsequent connections. Otherwise,
   * use [onCarConnected] instead.
   */
  open fun onCarAssociated(deviceId: UUID) {}

  internal fun getFeatureSupportStatusProvider(deviceId: UUID): FeatureSupportStatusProvider? {
    return carsAndCallbacks[deviceId]?.first
  }

  /**
   * Invoked when a query has been sent by the car with the given [deviceId].
   *
   * The format of the query and response are feature defined and will be sent and received as-is to
   * and from the connected car.
   *
   * Utilize the given [responseHandler] to respond to the query. Before responding, the feature
   * should check that the car is still connected via a call to [isCarConnected]. The handler takes
   * two parameters: a `ByteArray` that represents the response to send to the car and a `Boolean`
   * that indicates whether the query was successful or not. The format of the `ByteArray` passed to
   * this method will be sent as-is to the remote car.
   */
  open fun onQueryReceived(
    query: Query,
    deviceId: UUID,
    responseHandler: (Boolean, ByteArray) -> Unit,
  ) {}

  /**
   * Sends the given query to the car with the given [deviceId].
   *
   * When a response is received from the remote car, the given [onResponse] function will be
   * invoked with a [QueryResponse] that was sent by the car. The format of the `Query` and
   * `QueryResponse` are feature-defined and will be sent and received as-is to and from the car.
   *
   * After a car is disconnected, i.e. after [onCarDisconnected] is invoked, calling this method
   * with its device id will result in [onResponse] being invoked with a [QueryResponse] indicating
   * the query was not successful. The `response` within the [QueryResponse] will be an empty
   * [ByteArray] and the id will be [INVALID_QUERY_ID].
   */
  open fun sendQuery(query: Query, deviceId: UUID, onResponse: (QueryResponse) -> Unit) {
    carsAndCallbacks[deviceId]?.let { (car, _) ->
      car.sendQuery(query, featureId, onResponse)
      return
    }

    loge(TAG, "sendQuery: $deviceId does not exist. Query cannot be sent.")
    onResponse(QueryResponse(id = INVALID_QUERY_ID, isSuccessful = false, response = byteArrayOf()))
  }

  /**
   * Sends [data] to remote device with [deviceId] and returns an unique id for this transaction.
   *
   * Multiple requests will be queued up and delivered sequentially. The message id that is returned
   * will be passed to [onMessageSent] so that the caller can verify the delivery of the message to
   * the remote vehicle.
   *
   * After a car is disconnected, i.e. after [onCarDisconnected] is invoked, calling this method
   * with its device id will return an [INVALID_MESSAGE_ID]. The [isCarConnected] method can be used
   * to that a vehicle is connected before invoking this method.
   */
  open fun sendMessage(data: ByteArray, deviceId: UUID): Int {
    val carAndCallback = carsAndCallbacks[deviceId]
    if (carAndCallback == null) {
      loge(TAG, "sendMessage: $deviceId does not exist. Message cannot be sent.")
      return INVALID_MESSAGE_ID
    }

    return carAndCallback.first.sendMessage(data, featureId)
  }

  /** Returns whether the car of [deviceId] is currently connected. */
  fun isCarConnected(deviceId: UUID) = carsAndCallbacks.containsKey(deviceId)

  /** Returns the ids of the currently connected cars. */
  val connectedCars: List<UUID>
    get() = carsAndCallbacks.values.map { it.first.deviceId }

  /**
   * Should be invoked when a [car] has been connected.
   *
   * A feature implementation probably can ignore this method; instead, they should implement the
   * `abstract` methods.
   *
   * Adding a car will trigger [onCarConnected]. This method also automatically forwards the
   * callbacks of [car] to methods in this class, namely [onMessageReceived] and [onCarDisconnected]
   * .
   */
  open fun notifyCarConnected(car: Car) {
    val deviceId = car.deviceId
    if (deviceId in carsAndCallbacks) {
      logwtf(TAG, "onCarConnected: car with ID $deviceId already exists; Overriding existing one.")
      // Continue to override existing car,
      // but clear its callback to avoid double callback.
      clearCar(deviceId)
    }

    val callback = createCarCallback(car)
    car.setCallback(callback, featureId)
    carsAndCallbacks[deviceId] = Pair(car, callback)

    onCarConnected(deviceId)
  }

  /**
   * Returns the name of the connected car with id [deviceId].
   *
   * After a car is disconnected, calling this method with corresponding [deviceId] will return
   * `null`.
   */
  fun getConnectedCarNameById(deviceId: UUID): String? {
    val (car, _) =
      carsAndCallbacks.getOrElse(deviceId) {
        loge(TAG, "getConnectedCarNameById: $deviceId does not exist.")
        return null
      }
    return car.name
  }

  private fun clearCar(deviceId: UUID) {
    val (car, callback) =
      carsAndCallbacks.getOrElse(deviceId) {
        loge(TAG, "clearCar: $deviceId does not exist")
        return
      }
    car.clearCallback(callback, featureId)

    carsAndCallbacks.remove(deviceId)
  }

  private fun createCarCallback(car: Car): Car.Callback =
    object : Car.Callback {
      override fun onMessageReceived(data: ByteArray) {
        this@FeatureManager.onMessageReceived(data, car.deviceId)
      }

      override fun onMessageSent(messageId: Int) {
        this@FeatureManager.onMessageSent(messageId, car.deviceId)
      }

      override fun onQueryReceived(queryId: Int, sender: UUID, query: Query) {
        this@FeatureManager.onQueryReceived(query, car.deviceId) { isSuccessful, response ->
          car.sendQueryResponse(QueryResponse(queryId, isSuccessful, response), sender)
        }
      }

      override fun onDisconnected() {
        val deviceId = car.deviceId
        clearCar(deviceId)
        onCarDisconnected(deviceId)
      }
    }

  companion object {
    private const val TAG = "FeatureManager"

    /** A value that indicates that a message failed to be delivered to a remote car. */
    const val INVALID_MESSAGE_ID = -1

    /** A value that indicates that a query failed to be delivered to a remote car. */
    const val INVALID_QUERY_ID = -1
  }
}
