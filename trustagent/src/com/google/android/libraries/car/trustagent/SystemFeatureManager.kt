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

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build.VERSION
import com.google.android.companionprotos.DeviceOS
import com.google.android.companionprotos.DeviceVersionsResponse
import com.google.android.companionprotos.FeatureSupportResponse
import com.google.android.companionprotos.FeatureSupportStatus
import com.google.android.companionprotos.SystemQuery
import com.google.android.companionprotos.SystemQueryType
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.protobuf.InvalidProtocolBufferException
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Feature that is responsible for responding to system-level queries, such as providing the current
 * device name.
 */
class SystemFeatureManager
internal constructor(
  private val deviceNameProvider: () -> String,
  private val appNameProvider: () -> String,
  private val companionSdkVersion: String,
) : FeatureManager() {
  override val featureId = FEATURE_ID

  /**
   * Creates an instance of this feature manager that will utilize the [BluetoothAdapter] and
   * current [Context] for application name look-up.
   */
  constructor(
    context: Context
  ) : this(
    deviceNameProvider = BluetoothAdapter.getDefaultAdapter()::getName,
    appNameProvider = context::getAppName,
    companionSdkVersion = context.getString(R.string.android_companion_sdk_version),
  )

  override fun onQueryReceived(
    query: Query,
    deviceId: UUID,
    responseHandler: (Boolean, ByteArray) -> Unit,
  ) {
    val queryProto =
      try {
        SystemQuery.parseFrom(query.request)
      } catch (e: InvalidProtocolBufferException) {
        loge(TAG, "Received a query from car $deviceId but unable to parse. Ignoring.")
        return
      }

    logi(TAG, "Received a query from $deviceId")

    try {
      respondToQuery(deviceId, queryProto, responseHandler)
    } catch (e: IllegalArgumentException) {
      loge(TAG, "Unable to send query response to car", e)
    }
  }

  /**
   * Sends a response to the given [queryProto] with the given [responseHandler] from [deviceId].
   *
   * Throws an [IllegalArgumentException] if the car is not connected at the time of send.
   */
  private fun respondToQuery(
    deviceId: UUID,
    queryProto: SystemQuery,
    responseHandler: (Boolean, ByteArray) -> Unit,
  ) {
    when (queryProto.type) {
      SystemQueryType.DEVICE_NAME -> {
        val deviceName = deviceNameProvider()
        logi(TAG, "Received device name query. Responding with $deviceName")
        responseHandler(/* isSuccessful= */ true, deviceName.toByteArray())
      }
      SystemQueryType.DEVICE_OS -> {
        val deviceOS = DeviceOS.ANDROID
        logi(TAG, "Received device OS query. Responding with $deviceOS")
        responseHandler(
          /* isSuccessful= */ true,
          DeviceVersionsResponse.newBuilder()
            .setOs(deviceOS)
            .setOsVersion(VERSION.SDK_INT.toString())
            .setCompanionSdkVersion(companionSdkVersion)
            .build()
            .toByteArray(),
        )
      }
      SystemQueryType.APP_NAME -> {
        val appName = appNameProvider()
        logi(TAG, "Received app name query. Responding with $appName")
        responseHandler(/* isSuccessful= */ true, appName.toByteArray())
      }
      SystemQueryType.IS_FEATURE_SUPPORTED -> {
        logi(TAG, "Received feature support query.")
        val queriedFeatureIds =
          queryProto.payloadsList.map {
            val uuidString = it.toByteArray().toString(StandardCharsets.UTF_8)
            UUID.fromString(uuidString)
          }
        val response = generateFeatureSupportStatus(deviceId, queriedFeatureIds)
        responseHandler(/* isSuccessful= */ true, response.toByteArray())
      }
      else -> {
        loge(
          TAG,
          "Received a query from of unknown type: ${queryProto.type}. Responding with " +
            "unsuccessful query.",
        )
        responseHandler(/* isSuccessful= */ false, byteArrayOf())
      }
    }
  }

  private fun generateFeatureSupportStatus(
    deviceId: UUID,
    queriedFeatureIds: List<UUID>,
  ): FeatureSupportResponse {
    val provider = getFeatureSupportStatusProvider(deviceId)
    if (provider == null) {
      val featureIds = queriedFeatureIds.joinToString { it.toString() }
      logw(TAG, "No provider for $deviceId. Responding unsupported for all: $featureIds.")
    }

    val statuses =
      queriedFeatureIds.map {
        FeatureSupportStatus.newBuilder().run {
          this.featureId = it.toString()
          this.isSupported = provider?.isFeatureSupported(it) ?: false
          build()
        }
      }

    return FeatureSupportResponse.newBuilder().run {
      addAllStatuses(statuses)
      build()
    }
  }

  // Lifecycle methods. These are not used for this class as it only responds to queries, but must
  // be implemented as they are abstract.

  override fun onCarConnected(deviceId: UUID) {}

  override fun onMessageReceived(message: ByteArray, deviceId: UUID) {}

  override fun onMessageSent(messageId: Int, deviceId: UUID) {}

  override fun onCarDisconnected(deviceId: UUID) {}

  override fun onCarDisassociated(deviceId: UUID) {}

  override fun onAllCarsDisassociated() {}

  companion object {
    private const val TAG = "SystemFeatureManager"
    private val FEATURE_ID = UUID.fromString("892ac5d9-e9a5-48dc-874a-c01e3cb00d5d")
  }
}

/** Returns the current application name based off the result of [ApplicationInfo.loadLabel]. */
private fun Context.getAppName(): String = applicationInfo.loadLabel(packageManager).toString()
