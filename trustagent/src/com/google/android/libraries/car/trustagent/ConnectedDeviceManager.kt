@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * Manages connected devices.
 *
 * @param[lifecycle] Lifecycle that controls the connections. Reconnection will be started in
 *   lifecycle#onCreate(), and cut-off in lifecycle#onDestroy().
 * @param[features] [FeatureManager]s that would be held by this manager. These features would be
 *   notified of connection events.
 * @param[coroutineDispatcher] Dispatcher for coroutines. Callbacks are made by this dispatcher.
 * @param[backgroundDispatcher] Dispatcher for background tasks. Must not be the main thread.
 */
// This class is the entry point of Companion API but the class itself is not annotated as
// @PublicApi. Instead, individual fields are annotated, thus publicized. The reason is that
// this class exposes some fields/methods as `internal` for unit testing. These `internal` members
// will generate `public` methods in Java, and may accidentally expose internal classes.
//
// NOTE: public API in the SDK needs to be annotated as @PublicApi so it's not obfuscated by
// proguard during granular release. See go/aae-batmobile-lib-dev#exposing-public-api.
class ConnectedDeviceManager
@VisibleForTesting
internal constructor(
  private val context: Context,
  private val lifecycle: Lifecycle,
  private val associationManager: AssociationManager,
  private val connectionManager: ConnectionManager,
  private val features: List<FeatureManager>,
  coroutineDispatcher: CoroutineDispatcher,
  private val backgroundDispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {
  private val callbacks = mutableListOf<Callback>()
  private var coroutineScope = CoroutineScope(coroutineDispatcher)

  /** Tracks the ongoing connection and connected devices. */
  private var ongoingAssociation: AssociationRequest? = null
  private val ongoingReconnections = mutableSetOf<BluetoothDevice>()

  private val retryHandler = Handler(Looper.getMainLooper())

  private val _connectedCars = mutableMapOf<UUID, Car>()
  /** Returns the currently connected cars. */
  @get:PublicApi
  val connectedCars: List<AssociatedCar>
    get() = _connectedCars.values.map { it.toAssociatedCar() }

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

        coroutineScope.launch(backgroundDispatcher) {
          logi(TAG, "onScanResult: checking whether we should connect to ${result.device}.")
          // Specifiy the filteredScanResult type to eliminate ambiguity in calling
          // ConnectionManager#connect().
          val filteredScanResult: ScanResult? =
            connectionManager.filterForConnectableCars(listOf(result)).firstOrNull()
          if (filteredScanResult == null) {
            loge(TAG, "$result does not meet condition for reconnection. Ignored.")
            return@launch
          }

          val device = filteredScanResult.device
          if (device in ongoingReconnections) {
            loge(TAG, "$device has an ongoing connection. Ignored.")
            return@launch
          }
          ongoingReconnections.add(device)

          logi(TAG, "Connecting to $result.")
          connectionManager.connect(filteredScanResult, backgroundDispatcher.asExecutor())
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
      }
    }

  // Tracks whether we have already requested a discovery through system service
  // CompanionDeviceManager.
  // This checking is necessary because the platform throws exception when the discovered result
  // is presented twice (IntentSender started twice), which could be caused by startDiscovery()
  // being invoked multiple times.
  private val hasOngoingCdmDiscovery = AtomicBoolean(false)

  @VisibleForTesting
  internal val companionDeviceManagerCallback =
    object : CompanionDeviceManager.Callback() {
      override fun onDeviceFound(chooserLauncher: IntentSender) {
        logi(TAG, "Received $chooserLauncher from CompanionDeviceManager.")
        hasOngoingCdmDiscovery.set(false)
        callbacks.forEach { it.onDeviceDiscovered(chooserLauncher) }
      }

      override fun onFailure(error: CharSequence?) {
        loge(TAG, "Received onFailure() from CompanionDeviceManager: $error.")
        hasOngoingCdmDiscovery.set(false)
        callbacks.forEach { it.onDiscoveryFailed() }
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
  @get:PublicApi
  val isBluetoothEnabled: Boolean
    get() = associationManager.isBluetoothEnabled

  init {
    lifecycle.addObserver(this)
  }

  @PublicApi
  constructor(
    context: Context,
    lifecycle: Lifecycle,
    features: List<FeatureManager>,
  ) : this(
    context = context,
    lifecycle = lifecycle,
    features = features,
    associationManager = AssociationManager(context),
    connectionManager = ConnectionManager(context),
    coroutineDispatcher = Dispatchers.Main,
    backgroundDispatcher = newSingleThreadContext(name = "backgroundDispatcher"),
  )

  override fun onCreate(owner: LifecycleOwner) {
    associationManager.registerAssociationCallback(associationManagerCallback)
    associationManager.registerDisassociationCallback(associationManagerCallback)

    connectionManager.registerConnectionCallback(connectionManagerCallback)

    context.registerReceiver(
      bluetoothStateChangeReceiver,
      IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
      BluetoothAdapter.ACTION_STATE_CHANGED,
      /* handler= */ null,
    )

    // Start reconnection.
    start()
  }

  override fun onDestroy(owner: LifecycleOwner) {
    associationManager.unregisterAssociationCallback(associationManagerCallback)
    associationManager.unregisterDisassociationCallback(associationManagerCallback)

    connectionManager.unregisterConnectionCallback(connectionManagerCallback)

    context.unregisterReceiver(bluetoothStateChangeReceiver)

    // Stop reconnection, which also stops all current connections.
    stop()

    coroutineScope.cancel()
  }

  // TODO: Remove default overrides when b/138957824 is resolved.
  // These empty overrides are currently required by granular.
  override fun onResume(owner: LifecycleOwner) {}

  override fun onPause(owner: LifecycleOwner) {}

  override fun onStart(owner: LifecycleOwner) {}

  override fun onStop(owner: LifecycleOwner) {}

  /**
   * Starts the [CompanionDeviceManager] discovery for association.
   *
   * Ensure that Bluetooth is enabled before calling this method. If this method is called while
   * Bluetooth is off, then this method will do nothing and return `false`. This method will also
   * fail if the proper scanning permissions are not granted. In this case, `false` will also be
   * returned.
   *
   * This method should only be invoked once per request. Otherwise launching the IntentSender from
   * [onDeviceDiscovered] might cause exception from the platform.
   *
   * @param[request] Parameters that modify this discovery call.
   */
  @PublicApi
  fun startDiscovery(request: DiscoveryRequest): Boolean {
    if (!hasOngoingCdmDiscovery.compareAndSet(false, true)) {
      logi(TAG, "Discovery already started. Ignored.")
      return false
    }
    logi(TAG, "StartDiscovery with $request.")

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
  @PublicApi
  fun associate(request: AssociationRequest) {
    logi(TAG, "Associate with $request.")
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
  @PublicApi
  fun disassociate(deviceId: UUID): ListenableFuture<Boolean> =
    coroutineScope.future(backgroundDispatcher) {
      val success = associationManager.clearCdmAssociatedCar(deviceId)
      if (success) {
        coroutineScope.launch { handleCarDisassociated(deviceId) }
      }
      success
    }

  private suspend fun handleCarDisassociated(deviceId: UUID) {
    _connectedCars[deviceId]?.disconnect()
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
  @PublicApi
  fun disassociateAllCars(): ListenableFuture<Boolean> =
    coroutineScope.future(backgroundDispatcher) {
      val success = associationManager.clearAllCdmAssociatedCars()
      if (success) {
        logi(TAG, "onAllCarsDisassociated: stopping.")
        stop()
        coroutineScope.launch { features.forEach { it.onAllCarsDisassociated() } }
      }
      success
    }

  /** Clears current incomplete association. */
  @PublicApi
  fun clearCurrentAssociation() {
    ongoingAssociation = null
    associationManager.clearCurrentCdmAssociation()
  }

  /**
   * Attempts to start reconnection.
   *
   * If there is no associated device, this method will be a no-op.
   *
   * Normally caller does not need to explicitly invoked this method, as it is automatically invoked
   * by `onCreate()` of [lifecycle].
   */
  @PublicApi
  fun start() {
    logi(TAG, "Starting reconnection.")
    connectionManager.startScanForAssociatedCars(reconnectionScanCallback)
  }

  /**
   * Stops reconnection.
   *
   * Stops any reconnections and disconnects all devices that are currently connected.
   *
   * Normally caller does not need to explicitly invoked this method, as it is automatically invoked
   * by `onDestroy()` of [lifecycle].
   */
  @PublicApi
  fun stop() {
    connectionManager.stop()
    for (car in _connectedCars.values) {
      car.disconnect()
    }
  }

  internal fun getConnectedCar(deviceId: UUID): Car? = _connectedCars[deviceId]

  /** Registers the given [callback] to be notified of connection events. */
  @PublicApi
  fun registerCallback(callback: Callback) {
    callbacks.add(callback)
  }

  /** Unregisters the given [callback] from being notified of connection events. */
  @PublicApi
  fun unregisterCallback(callback: Callback) {
    callbacks.remove(callback)
  }

  /**
   * Renames an associated car and returns `true` if the operation was successful.
   *
   * If the given car is not associated, `false` is also returned.
   */
  @PublicApi
  fun renameCar(deviceId: UUID, name: String): ListenableFuture<Boolean> =
    coroutineScope.future(backgroundDispatcher) {
      val isSuccessful = associationManager.renameCar(deviceId, name).await()
      if (isSuccessful) {
        _connectedCars[deviceId]?.name = name
      }
      isSuccessful
    }

  private fun handleConnection(car: Car) {
    // Validity check.
    if (car.deviceId in _connectedCars) {
      logw(TAG, "onAssociated: ${car.deviceId} is already connected. Replaced.")
    }
    _connectedCars[car.deviceId] = car
    registerDisconnectionCallback(car)

    features.forEach { it.notifyCarConnected(car) }
  }

  /**
   * Loads a list of all cars that are currently associated with this phone.
   *
   * The returned [ListenableFuture] will be invoked with the list when loading is successful. An
   * empty list will be returned if there are no associated cars.
   */
  @PublicApi
  fun retrieveAssociatedCars(): ListenableFuture<List<AssociatedCar>> =
    associationManager.retrieveAssociatedCars()

  private fun registerDisconnectionCallback(car: Car) {
    val callback =
      object : Car.Callback {
        override fun onDisconnected() {
          val deviceId = car.deviceId
          logi(TAG, "$deviceId has disconnected.")
          if (_connectedCars.remove(deviceId) == null) {
            logw(TAG, "Attempted to remove $deviceId but it is not connected.")
          }

          car.clearCallback(this, RECIPIENT_ID)

          callbacks.forEach { it.onDisconnected(car.toAssociatedCar()) }
        }

        // The following callbacks are irrelevant to connection status; ignored.
        override fun onMessageSent(messageId: Int) {}

        override fun onMessageReceived(data: ByteArray) {}

        override fun onQueryReceived(queryId: Int, sender: UUID, query: Query) {}
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
    // TODO: define error enum.
    fun onAssociationFailed()

    /** Invoked when an already associated device has reconnected. */
    fun onConnected(associatedCar: AssociatedCar)

    /** Invoked when a connected device has disconnected. */
    fun onDisconnected(associatedCar: AssociatedCar)
  }

  companion object {
    private const val TAG = "ConnectedDeviceManager"
    internal val RECIPIENT_ID = UUID.fromString("5efd8b16-21d6-4fb1-b00a-a904720d1320")
  }
}
