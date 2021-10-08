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

package com.google.android.libraries.car.connectionservice

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.connectionservice.PhoneSyncBaseService.Companion.EXTRA_FOREGROUND_NOTIFICATION
import com.google.android.libraries.car.trustagent.AssociationManager
import com.google.android.libraries.car.trustagent.Car
import com.google.android.libraries.car.trustagent.ConnectionManager
import com.google.android.libraries.car.trustagent.Query
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * A service that is responsible for managing the [Car] connections, as well as the feature managers
 * who want to receive and send data during an active connection.
 *
 * # Running in the foreground
 *
 * To maintain a long-lasting connection, this service should run in the foreground on API 26+. When
 * this service is created, it will look for a notification as intent extra specified by
 * [EXTRA_FOREGROUND_NOTIFICATION]. The caller is responsible for creating the notification channel
 * for which the notification is pushed in.
 *
 * When this service is started in foreground, i.e. [Context.startForegroundService], the
 * notification extra should be set. If the notification is not set as intent extra, the service
 * runs under platform default configuration.
 *
 * # Connection
 *
 * This service maintains an internal list of cars that are currently connected.
 *
 * After association, an application should use [ConnectionManager.start] to automatically connect
 * to the associated cars. Any discovered cars will be delivered to this service and connected to.
 */
@PublicApi
abstract class PhoneSyncBaseService : FeatureManagerService() {

  var connectionStatusCallback: ConnectionStatusCallback? = null

  private var sppConnectionAttempted = false

  private val retryHandler = Handler(Looper.getMainLooper())

  /** Executes the battery monitoring task in a fixed interval. */
  private val batteryMonitorExecutor = ScheduledThreadPoolExecutor(1)

  private lateinit var scheduleFuture: ScheduledFuture<*>

  protected lateinit var coroutineScope: CoroutineScope

  /**
   * Recipient ID that uniquely identifies this service.
   *
   * Could be any arbitrary value.
   */
  abstract val serviceRecipient: UUID

  lateinit var connectionManager: ConnectionManager
  lateinit var associationManager: AssociationManager

  @VisibleForTesting
  internal val connectionCallback: ConnectionManager.ConnectionCallback =
    object : ConnectionManager.ConnectionCallback {
      override fun onConnected(car: Car) {
        addConnectedCar(car)

        val device = car.bluetoothDevice
        if (connectingCars.remove(device)) {
          logi(
            TAG,
            "onConnected: $car connected; removed $device; remaining connecting cars: " +
              "$connectingCars."
          )
        } else {
          loge(TAG, "onConnected: could not remove $device of connected $car from $connectingCars.")
        }
      }

      override fun onConnectionFailed(device: BluetoothDevice) {
        if (connectingCars.remove(device)) {
          logi(
            TAG,
            "onConnectionFailed: removed $device; remaining connecting cars: " + "$connectingCars."
          )
        } else {
          loge(TAG, "onConnectionFailed: could not remove $device from $connectingCars")
        }

        // Check the connection channel type first. If current connection is a SPP connection then a
        // retry logic is needed to make sure the remote device can be reconnected successfully.
        // A retry under BLE connection is not needed because BLE will do the background scanning
        // all the time to make sure the reconnection worked properly.
        if (sppConnectionAttempted) {
          reconnectSpp(device)
          return
        }
        stopIfNoOngoingConnection()
      }
    }

  @VisibleForTesting
  internal val disassociationCallback: AssociationManager.DisassociationCallback =
    object : AssociationManager.DisassociationCallback {
      override fun onCarDisassociated(deviceId: UUID) {
        logi(TAG, "$deviceId has been disassociated. Notifying feature managers and disconnecting")
        featureManagers.forEach { it.onCarDisassociated(deviceId) }
        connectedCars.filter { it.deviceId == deviceId }.forEach {
          connectionManager.disconnect(it)
        }
      }

      override fun onAllCarsDisassociated() {
        logi(
          TAG,
          "All cars has been disassociated. Notifying feature managers and disconnecting all cars."
        )

        featureManagers.forEach { it.onAllCarsDisassociated() }
        connectedCars.forEach { connectionManager.disconnect(it) }
      }
    }

  @VisibleForTesting
  internal val associationCallback: AssociationManager.AssociationCallback =
    object : AssociationManager.AssociationCallback {
      override fun onAssociationStart() {}

      override fun onDeviceIdReceived(deviceId: UUID) {
        // Reset the feature state if [deviceId] is already associated.
        val existingCar =
          associationManager.retrieveAssociatedCars().get().firstOrNull { it.deviceId == deviceId }
        existingCar?.let { featureManagers.forEach { it.onCarDisassociated(deviceId) } }
      }

      override fun onAssociated(car: Car) {
        addConnectedCar(car)
      }

      override fun onAuthStringAvailable(authString: String) {}

      override fun onAssociationFailed() {}
    }

  /**
   * Receiver that listens for changes in the Bluetooth adapter state and disconnects connected cars
   * if Bluetooth has been turned off.
   */
  private val bluetoothStateChangeReceiver: BroadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

        val bluetoothState =
          intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

        onBluetoothStateChange(bluetoothState)
      }
    }

  /** Cars that are currently connected. */
  val connectedCars: Collection<Car>
    get() = connectedCarsByDevice.values

  /** Currently connected cars, indexed by their BluetoothDevice. */
  private val connectedCarsByDevice = mutableMapOf<BluetoothDevice, Car>()

  /**
   * Cars pending connection.
   *
   * This set is maintained to avoid re-issuing a connect request.
   */
  @VisibleForTesting internal val connectingCars = mutableSetOf<BluetoothDevice>()

  @CallSuper
  override fun onCreate() {
    super.onCreate()

    coroutineScope = MainScope()

    connectionManager =
      ConnectionManager.getInstance(this).apply { registerConnectionCallback(connectionCallback) }
    associationManager =
      AssociationManager.getInstance(this).apply {
        registerAssociationCallback(associationCallback)
        registerDisassociationCallback(disassociationCallback)
      }
    scheduleFuture =
      batteryMonitorExecutor.scheduleAtFixedRate(
        ::logBatteryLevel,
        /* initialDelay= */ 0,
        BATTERY_MONITOR_INTERVAL.toMinutes(),
        TimeUnit.MINUTES
      )

    registerReceiver(
      bluetoothStateChangeReceiver,
      IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    )
  }

  @CallSuper
  override fun onDestroy() {
    super.onDestroy()

    coroutineScope.cancel()

    unregisterReceiver(bluetoothStateChangeReceiver)

    connectionManager.unregisterConnectionCallback(connectionCallback)
    associationManager.unregisterAssociationCallback(associationCallback)
    associationManager.unregisterDisassociationCallback(disassociationCallback)

    scheduleFuture.cancel(true)
    batteryMonitorExecutor.shutdown()
  }

  @CallSuper
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)

    if (intent == null) {
      loge(TAG, "onStartCommand received null intent. Ignored.")
      return START_NOT_STICKY
    }
    // The notification extras should be set to run in foreground for API 26+.
    intent.getParcelableExtra<Notification?>(EXTRA_FOREGROUND_NOTIFICATION)?.let { notification ->
      logi(TAG, "Retrieved foreground notification from intent: $notification.")

      val id = intent.getIntExtra(EXTRA_FOREGROUND_NOTIFICATION_ID, -1)
      if (id < 0) {
        loge(TAG, "Missing notification ID as intent extra. See EXTRA_FOREGROUND_NOTIFICATION_ID.")
        stopSelf()
        return START_NOT_STICKY
      }
      EventLog.onServiceStarted()

      startForeground(id, notification)
    }

    intent.getParcelableExtra<BluetoothDevice>(EXTRA_SPP_BLUETOOTH_DEVICE)?.let {
      attemptSppConnection(it)
    }

    intent.getParcelableArrayListExtra<ScanResult>(EXTRA_SCAN_DEVICES)?.forEach { result ->
      val device = result.device
      if (device !in connectingCars && device !in connectedCarsByDevice) {
        logi(TAG, "Initiating connection to $device of $result; current $connectingCars.")
        connectionManager.connect(result)
        connectingCars.add(device)
      }
    }
    stopIfNoOngoingConnection()
    return START_NOT_STICKY
  }

  private fun stopServiceInForeground() {
    EventLog.onServiceStopped()

    stopForeground(true)
    stopSelf()
  }

  private fun reconnectSpp(device: BluetoothDevice) {
    coroutineScope.launch {
      val devices = connectionManager.fetchConnectedBluetoothDevices().await()
      if (device in devices) {
        logi(
          TAG,
          "Associated device $device connection failed while in range. Retrying in 2 seconds."
        )
        retryHandler.postDelayed({ attemptSppConnection(device) }, SPP_RETRY_THROTTLE.toMillis())
        return@launch
      }
      stopIfNoOngoingConnection()
    }
  }

  /** Stores a connected car internally to maintain an active connection. */
  @VisibleForTesting
  fun addConnectedCar(car: Car) {
    EventLog.onCarConnected(car.deviceId)

    connectionStatusCallback?.onCarConnected(car.deviceId)
    car.setCallback(createCarCallback(car), serviceRecipient)
    logi(TAG, "Adding device ${car.bluetoothDevice} to current list $connectedCarsByDevice")

    connectedCarsByDevice[car.bluetoothDevice] = car
    featureManagers.forEach { it.notifyCarConnected(car) }
  }

  @VisibleForTesting
  internal fun onBluetoothStateChange(bluetoothState: Int) {
    logi(TAG, "Bluetooth state has changed to $bluetoothState.")

    if (bluetoothState != BluetoothAdapter.STATE_OFF) return

    logi(
      TAG,
      "Bluetooth has been turned off. Stopping scanning and disconnecting connected cars. " +
        "Number of connected cars: ${connectedCars.size}"
    )

    connectionManager.stop()
    connectedCars.forEach { connectionManager.disconnect(it) }
  }

  private fun stopIfNoOngoingConnection() {
    if (connectingCars.isEmpty() && connectedCars.isEmpty()) {
      logi(TAG, "No ongoing nor established connections. Stopping.")
      stopServiceInForeground()
    } else {
      logi(TAG, "Did not stop service because of remaining $connectingCars; $connectedCarsByDevice")
    }
  }

  private fun attemptSppConnection(device: BluetoothDevice) {
    sppConnectionAttempted = true
    if (connectingCars.contains(device)) {
      logi(TAG, "Passed Bluetooth device ${device.address} is already connecting. Ignoring.")
      return
    }

    if (connectedCarsByDevice.contains(device)) {
      logi(TAG, "Passed Bluetooth device ${device.address} is already connected. Ignoring.")
      return
    }

    logi(TAG, "Starting SPP connection to ${device.address}")

    connectionManager.connect(device)
    connectingCars.add(device)
  }

  private fun logBatteryLevel() {
    val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    if (batteryStatus == null) {
      loge(TAG, "Error getting battery status data. Ignore.")
      return
    }
    val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val batteryPct = level * 100 / scale.toFloat()
    logi(TAG, "Battery level: $level out of $scale, percentage: $batteryPct%.")
  }

  private fun createCarCallback(car: Car): Car.Callback =
    object : Car.Callback {
      override fun onMessageReceived(data: ByteArray) {}

      override fun onMessageSent(messageId: Int) {}

      override fun onQueryReceived(queryId: Int, query: Query) {}

      override fun onDisconnected() {
        val deviceId = car.deviceId
        connectionStatusCallback?.onCarDisconnected(deviceId)

        logi(TAG, "Removing disconnected ${car.bluetoothDevice} from $connectedCarsByDevice")

        if (connectedCarsByDevice.remove(car.bluetoothDevice) == null) {
          loge(TAG, "onDisconnected: $car not found in $connectedCarsByDevice")
        }

        if (car.isSppDevice()) {
          reconnectSpp(car.bluetoothDevice)
          return
        }
        stopIfNoOngoingConnection()
      }
    }

  /** Get the connected car with ID [deviceId]. */
  fun getConnectedCar(deviceId: UUID) = connectedCars.find { it.deviceId == deviceId }

  @PublicApi
  companion object {
    private const val TAG = "PhoneSyncBaseService"

    private val SPP_RETRY_THROTTLE = Duration.ofSeconds(2)

    private val BATTERY_MONITOR_INTERVAL = Duration.ofMinutes(30)

    /**
     * [Notification] that will be pushed via [Service.startForeground] when this service starts.
     *
     * Must be used in conjunction with [EXTRA_FOREGROUND_NOTIFICATION_ID].
     *
     * In API level 26 and above, a service that wishes to maintain connection should push a
     * foreground notification to stay active. This extra provides the notification, which will be
     * pushed with notification ID by EXTRA_FOREGROUND_NOTIFICATION_ID.
     */
    const val EXTRA_FOREGROUND_NOTIFICATION =
      "com.google.android.libraries.car.connectionservice.EXTRA_FOREGROUND_NOTIFICATION"

    /**
     * A non-negative Int as notification ID.
     *
     * This ID will be used to push the notification set by [EXTRA_FOREGROUND_NOTIFICATION]. Missing
     * this extra or negative value will lead to not posting notification.
     */
    const val EXTRA_FOREGROUND_NOTIFICATION_ID =
      "com.google.android.libraries.car.connectionservice.EXTRA_FOREGROUND_NOTIFICATION_ID"

    /**
     * Extra name of the device which will be found during SPP discovery and passed to
     * [onStartCommand].
     */
    const val EXTRA_SPP_BLUETOOTH_DEVICE = "spp_bluetooth_device"

    /** Array of [ScanDevice]s. This service will attempt to connect to each device in the array. */
    const val EXTRA_SCAN_DEVICES = "com.google.android.libraries.car.connectionservice.SCAN_DEVICES"
  }

  /** Callback that will be notified of a car's connection status. */
  @PublicApi
  interface ConnectionStatusCallback {

    /** Invoked when car with [deviceId] is connected. */
    fun onCarConnected(deviceId: UUID)

    /** Invoked when car with [deviceId] has disconnected. */
    fun onCarDisconnected(deviceId: UUID)
  }
}
