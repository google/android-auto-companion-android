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

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.common.util.concurrent.ListenableFuture
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Manages connected devices.
 *
 * @param[lifecycle] Lifecycle controls the connections. Reconnection will be started in
 * lifecycle#onCreate(), and cut-off in lifecycle#onDestroy().
 *
 * @param[features] [FeatureManager]s that would be held by this manager. These features would be
 * notified of connection events.
 */
@PublicApi
class ConnectedDeviceManager(
  private val context: Context,
  private val lifecycle: Lifecycle,
  private val associationManager: AssociationManager,
  private val connectionManager: ConnectionManager,
  private val features: List<FeatureManager>,
  private val coroutineDispatcher: CoroutineDispatcher
) : DefaultLifecycleObserver {
  private val coroutineScope = CoroutineScope(coroutineDispatcher)
  private val callbacks = mutableListOf<Callback>()

  /** Tracks the ongoing connection and connected devices. */
  private var ongoingAssociation: AssociationRequest? = null
  private val ongoingReconnections = mutableSetOf<BluetoothDevice>()

  private val retryHandler = Handler(Looper.getMainLooper())
  private var sppConnectionAttempted = AtomicBoolean(false)

  private val _connectedCars = mutableSetOf<Car>()
  /** Returns the currently connected cars. */
  val connectedCars: List<AssociatedCar>
    get() = _connectedCars.map { it.toAssociatedCar() }

  /** Indicates if [start] has been called. */
  private var isStarted = false

  /** The coroutine scope which is executed in a background thread. */
  private var backgroundScope: CoroutineScope =
    CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

  private val associationManagerCallback =
    object : AssociationManager.AssociationCallback, AssociationManager.DisassociationCallback {
      override fun onAssociationStart() {
        callbacks.forEach { it.onAssociationStart() }
      }

      override fun onDeviceIdReceived(deviceId: UUID) {
        // No-operation.
        // In v2 connection (v1 has been removed), device ID is received as the confirmation of
        // UKEY2 encryption. Skip forwarding this callback as it has no impact on the UI.
      }

      override fun onAuthStringAvailable(authString: String) {
        callbacks.forEach { it.onAuthStringAvailable(authString) }
      }

      override fun onAssociated(car: Car) {
        ongoingAssociation = null
        handleConnection(car)

        // Start reconnection attempt when a car has been associated so that when this car
        // disconnects we can resume connection.
        //
        // There is no matching stop() call because reconnection is automatically stopped when
        // there are no associated cars.
        start()

        callbacks.forEach { it.onAssociated(car.toAssociatedCar()) }
      }

      override fun onAssociationFailed() {
        ongoingAssociation = null
        loge(TAG, "onAssociationFailed.")

        callbacks.forEach { it.onAssociationFailed() }
      }

      override fun onCarDisassociated(deviceId: UUID) {
        coroutineScope.launch { handleCarDisassociated(deviceId) }
      }

      override fun onAllCarsDisassociated() {
        logi(TAG, "onAllCarsDisassociated: stopping.")
        stop()

        features.forEach { it.onAllCarsDisassociated() }
      }
    }

  private val reconnectionScanCallback =
    object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult?) {
        super.onScanResult(callbackType, result)

        if (result == null) {
          loge(TAG, "onScanResult received null result. Ignored.")
          return
        }

        // When Bluetooth is turned off, we stop scanning. But occasionally the BluetoothAdapter
        // would return a null scanner since Bluetooth is turned off, thus the BLE scanning could
        // not be stopped.
        // Ignore the scan results so the experience is consistent.
        if (!isBluetoothEnabled) {
          logi(TAG, "Received ScanResult when Bluetooth is off. Ignored.")
          return
        }

        coroutineScope.launch {
          logi(TAG, "onScanResult: checking whether $result should be connected to.")
          // Specifiy the filteredScanResult type to eliminate ambiguity in calling
          // ConnectionManager#connect().
          val filteredScanResult: ScanResult? =
            connectionManager.filterForConnectableCars(listOf(result)).firstOrNull()

          if (filteredScanResult == null) {
            loge(TAG, "$result does not meet condition for reconnection. Ignored.")
            return@launch
          }

          val device = filteredScanResult.device
          if (device !in ongoingReconnections) {
            ongoingReconnections.add(device)

            logi(TAG, "Connecting to $result.")
            connectionManager.connect(filteredScanResult)
          }
        }
      }

      override fun onScanFailed(errorCode: Int) {
        super.onScanFailed(errorCode)
        loge(TAG, "onScanFailed: error $errorCode")
      }

      override fun onBatchScanResults(results: MutableList<ScanResult>?) {
        super.onBatchScanResults(results)
        logw(TAG, "Received unexpected onBatchScanResults callback. Ignored.")
      }
    }

  private val connectionManagerCallback =
    object : ConnectionManager.ConnectionCallback {
      override fun onConnected(car: Car) {
        val device = car.bluetoothDevice
        if (!ongoingReconnections.remove(device)) {
          logw(TAG, "onConnected: $device does not exist in $ongoingReconnections.")
        }
        handleConnection(car)

        callbacks.forEach { it.onConnected(car.toAssociatedCar()) }
      }

      override fun onConnectionFailed(device: BluetoothDevice) {
        if (!ongoingReconnections.remove(device)) {
          logw(TAG, "onConnectionFailed: $device does not exist in $ongoingReconnections.")
        }

        loge(TAG, "onConnectionFailed: could not reconnect to $device.")

        // Check the connection channel type first. If current connection is a SPP connection then a
        // retry logic is needed to make sure the remote device can be reconnected successfully.
        // A retry under BLE connection is not needed because BLE will do the background scanning
        // all the time to make sure the reconnection worked properly.
        if (sppConnectionAttempted.get()) {
          reconnectIfBluetoothConnected(device)
        }
      }
    }

  @VisibleForTesting
  internal val companionDeviceManagerCallback =
    object : CompanionDeviceManager.Callback() {
      override fun onDeviceFound(chooserLauncher: IntentSender) {
        callbacks.forEach { it.onDeviceDiscovered(chooserLauncher) }
      }

      override fun onFailure(error: CharSequence) {
        loge(TAG, "Received onFailure() from CompanionDeviceManager: $error.")
        callbacks.forEach { it.onDiscoveryFailed() }
      }
    }

  @VisibleForTesting
  internal val startSppBroadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
          loge(TAG, "Received Intent with incorrect action: ${intent.action}. Ignored.")
          return
        }

        if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED
        ) {
          logi(TAG, "Bluetooth connection status is not STATE_CONNECTED. Ignored")
          return
        }

        val device: BluetoothDevice =
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!associationManager.loadIsAssociated(device.address).get()) {
          logw(TAG, "Discovered device (${device.address}) that is not associated. Ignored.")
          return
        }
        attemptSppConnection(device)
      }
    }

  private val bluetoothStateChangeReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
          loge(TAG, "Received intent is not ${BluetoothAdapter.ACTION_STATE_CHANGED}. Ignored.")
          return
        }

        val bluetoothState =
          intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        when (bluetoothState) {
          BluetoothAdapter.STATE_ON -> start()
          BluetoothAdapter.STATE_OFF -> stop()
          else -> logd(TAG, "Received bluetooth state $bluetoothState. Ignored.")
        }
      }
    }

  /**
   * `true` if Bluetooth is currently enabled.
   *
   * Starting discovery of devices to associate can only occur if this value is `true`.
   */
  val isBluetoothEnabled: Boolean
    get() = associationManager.isBluetoothEnabled

  init {
    lifecycle.addObserver(this)
  }

  override fun onCreate(owner: LifecycleOwner) {
    associationManager.registerAssociationCallback(associationManagerCallback)
    associationManager.registerDisassociationCallback(associationManagerCallback)

    connectionManager.registerConnectionCallback(connectionManagerCallback)

    context.registerReceiver(
      bluetoothStateChangeReceiver,
      IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    )

    // Start reconnection.
    start()
  }

  override fun onDestroy(owner: LifecycleOwner) {
    associationManager.unregisterAssociationCallback(associationManagerCallback)
    associationManager.unregisterDisassociationCallback(associationManagerCallback)

    connectionManager.unregisterConnectionCallback(connectionManagerCallback)

    coroutineScope.cancel()

    context.unregisterReceiver(bluetoothStateChangeReceiver)

    // Stop reconnection, which also stops all current connections.
    stop()
  }

  // TODO(b/182827383): Remove default overrides when b/138957824 is resolved.
  // Thses empty overrides are currently required by granular.
  override fun onResume(owner: LifecycleOwner) {}
  override fun onPause(owner: LifecycleOwner) {}
  override fun onStart(owner: LifecycleOwner) {}
  override fun onStop(owner: LifecycleOwner) {}

  /**
   * Starts the [CompanionDeviceManager] discovery for association.
   *
   * Ensure that Bluetooth is enabled before calling this method. If this method is called while
   * Bluetooth is off, then this method will do nothing and return `false`.
   *
   * @param[request] Parameters that modify this discovery call.
   */
  fun startDiscovery(request: DiscoveryRequest): Boolean {
    return associationManager.startCdmDiscovery(request, companionDeviceManagerCallback)
  }

  /**
   * Begins an association using the configuration in the given [request].
   *
   * This [request] contains the intent that is returned through [Activity.onActivityResult]
   * callback after calling [Activity.startIntentSenderForResult] on the intent sender returned by
   * [onDeviceDiscovered].
   *
   * Association progress is notified by [Callback].
   *
   * There can only be one association at a time; subsequent calls are ignored until the association
   * completes, i.e. [Callback.onAssociated] or [Callback.onAssociationFailed].
   */
  fun associate(request: AssociationRequest) {
    if (ongoingAssociation != null) {
      logw(TAG, "Currently attempting to associate with $ongoingAssociation. Ignored.")
      return
    }
    ongoingAssociation = request
    associationManager.associate(request)
  }

  /**
   * Clears the association status for the car with the given [deviceId] and returns `true` if the
   * operation succeeded.
   */
  fun disassociate(deviceId: UUID): ListenableFuture<Boolean> =
    backgroundScope.future {
      val success = associationManager.clearCdmAssociatedCar(deviceId)
      if (success) {
        coroutineScope.launch { handleCarDisassociated(deviceId) }
      }
      success
    }

  private suspend fun handleCarDisassociated(deviceId: UUID) {
    _connectedCars.firstOrNull { it.deviceId == deviceId }?.disconnect()
    if (!associationManager.loadIsAssociated().await()) {
      logi(TAG, "onCarDisassociated: no more associated cars; stopping.")
      stop()
    }
    features.forEach { it.onCarDisassociated(deviceId) }
  }

  /**
   * Clears all cars that are currently associated with this device and returns `true` if the
   * operation succeeded.
   */
  fun disassociateAllCars(): ListenableFuture<Boolean> =
    backgroundScope.future {
      val success = associationManager.clearAllCdmAssociatedCars()
      if (success) {
        logi(TAG, "onAllCarsDisassociated: stopping.")
        stop()
        coroutineScope.launch { features.forEach { it.onAllCarsDisassociated() } }
      }
      success
    }

  /** Clears current incomplete association. */
  fun clearCurrentAssociation() {
    associationManager.clearCurrentCdmAssociation()
  }
  /**
   * Attempts to start reconnection.
   *
   * If there is no associated device, this method will be a no-op.
   *
   * Normally caller does not need to explicitly invoked this method, as it is automatically invoked
   * by [lifecycle] onCreate().
   */
  fun start() {
    if (!isStarted) {
      // No need to register receiver for multiple times.
      context.registerReceiver(
        startSppBroadcastReceiver,
        IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
      )
      isStarted = true
    }
    // The following logic will be executed as long as a reconnection attempt is needed.
    coroutineScope.launch {
      val devices = connectionManager.fetchConnectedBluetoothDevices().await()
      for (device in devices) {
        attemptSppConnection(device)
      }
    }
    connectionManager.startScanForAssociatedCars(reconnectionScanCallback)
  }

  /**
   * Stops reconnection.
   *
   * Stops any reconnections and disconnects all devices that are currently connected.
   *
   * Normally caller does not need to explicitly invoked this method, as it is automatically invoked
   * by [lifecycle] onDestroy().
   */
  fun stop() {
    connectionManager.stop()
    if (isStarted) {
      context.unregisterReceiver(startSppBroadcastReceiver)
      isStarted = false
    }
    _connectedCars.forEach { it.disconnect() }
  }

  /** Registers the given [callback] to be notified of connection events. */
  fun registerCallback(callback: Callback) {
    callbacks.add(callback)
  }

  /** Unregisters the given [callback] from being notified of connection events. */
  fun unregisterCallback(callback: Callback) {
    callbacks.remove(callback)
  }

  /**
   * Renames an associated car and returns `true` if the operation was successful.
   *
   * If the given car is not associated, `false` is also returned.
   */
  fun renameCar(deviceId: UUID, name: String): ListenableFuture<Boolean> =
    backgroundScope.future {
      val isSuccessful = associationManager.renameCar(deviceId, name).await()
      if (isSuccessful) {
        _connectedCars.firstOrNull { it.deviceId == deviceId }?.name = name
      }
      isSuccessful
    }

  private fun handleConnection(car: Car) {
    // Validity check.
    if (_connectedCars.contains(car)) {
      logw(TAG, "onAssociated: ${car.deviceId} is already connected. Replaced.")
    }
    _connectedCars.add(car)
    registerDisconnectionCallback(car)

    features.forEach { it.notifyCarConnected(car) }
  }

  /**
   * Establishes SPP connection to [device] if the [device] is an associated device and currently
   * connected over bluetooth. This method retries continually with [SPP_RETRY_THROTTLE] seconds
   * delay.
   */
  private fun reconnectIfBluetoothConnected(device: BluetoothDevice) {
    coroutineScope.launch {
      val connectedDevices = connectionManager.fetchConnectedBluetoothDevices().await()
      // Only retry connection if the device is still connected over Bluetooth. Otherwise, the
      // reconnection will occur when the device reconnects over Bluetooth.
      if (device in connectedDevices) {
        logi(
          TAG,
          "Associated device $device connection failed while in range. Retrying connection."
        )
        retryHandler.postDelayed({ attemptSppConnection(device) }, SPP_RETRY_THROTTLE.toMillis())
      }
    }
  }

  /**
   * Establishes SPP connection to [device] if the device is not currently connected or in the
   * middle of a connection.
   */
  private fun attemptSppConnection(device: BluetoothDevice) {
    if (ongoingReconnections.contains(device)) {
      logi(TAG, "Passed Bluetooth device ${device.address} is already connecting. Ignored.")
      return
    }

    if (_connectedCars.any { it.bluetoothDevice == device }) {
      logi(TAG, "Passed Bluetooth device ${device.address} is already connected. Ignored.")
      return
    }

    logi(TAG, "Starting SPP connection to ${device.address}")
    sppConnectionAttempted.set(true)
    connectionManager.connect(device)
    ongoingReconnections.add(device)
  }

  /**
   * Loads a list of all cars that are currently associated with this phone.
   *
   * The returned [ListenableFuture] will be invoked with the list when loading is successful. An
   * empty list will be returned if there are no associated cars.
   */
  fun retrieveAssociatedCars(): ListenableFuture<List<AssociatedCar>> =
    associationManager.retrieveAssociatedCars()

  private fun registerDisconnectionCallback(car: Car) {
    val callback =
      object : Car.Callback {
        override fun onDisconnected() {
          val deviceId = car.deviceId
          logi(TAG, "$deviceId has disconnected.")
          if (!_connectedCars.remove(car)) {
            logw(TAG, "Attempted to remove $deviceId but it does not exist in $_connectedCars.")
          }

          car.clearCallback(this, RECIPIENT_ID)

          callbacks.forEach { it.onDisconnected(car.toAssociatedCar()) }

          if (car.isSppDevice()) {
            reconnectIfBluetoothConnected(car.bluetoothDevice)
          }
        }

        // The following callbacks are irrelevant to connection status; ignored.
        override fun onMessageSent(messageId: Int) {}

        override fun onMessageReceived(data: ByteArray) {}

        override fun onQueryReceived(queryId: Int, query: Query) {}
      }

    car.setCallback(callback, RECIPIENT_ID)
  }

  @PublicApi
  interface Callback {
    /**
     * Invoked when [startDiscovery] has found a device that can be associated with.
     *
     * The [chooserLauncher] should be launched to allow user to select the device. The selected
     * device will be returned as an Intent to [Activity.onActivityResult]. This Intent should be
     * passed to [associateDevice] to start association.
     */
    fun onDeviceDiscovered(chooserLauncher: IntentSender)

    /** Invoked if there was an error looking for devices. */
    fun onDiscoveryFailed()

    /** Invoked when association has been started. */
    fun onAssociationStart()

    /** Invoked when [authString] should be displayed to user for out-of-band verification. */
    fun onAuthStringAvailable(authString: String)

    /** Invoked when this device has been successfully associated. */
    fun onAssociated(associatedCar: AssociatedCar)

    /** Invoked when association process failed. */
    // TODO(b/166381202): define error enum.
    fun onAssociationFailed()

    /** Invoked when an already associated device has reconnected. */
    fun onConnected(associatedCar: AssociatedCar)

    /** Invoked when a connected device has disconnected. */
    fun onDisconnected(associatedCar: AssociatedCar)
  }

  @PublicApi
  companion object {
    private const val TAG = "ConnectedDeviceManager"
    /**
     * Retry SPP connection under a certain arbitrary interval to establish connection as soon as
     * the remote device is available and at the same time do not spam connection calls to the
     * Bluetooth.
     */
    @VisibleForTesting internal val SPP_RETRY_THROTTLE = Duration.ofSeconds(2)
    internal val RECIPIENT_ID = UUID.fromString("5efd8b16-21d6-4fb1-b00a-a904720d1320")
  }
}
