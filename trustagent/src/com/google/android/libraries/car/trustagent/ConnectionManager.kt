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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattHandle
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.storage.getDeviceId
import com.google.android.libraries.car.trustagent.util.checkPermissionsForBleScanner
import com.google.android.libraries.car.trustagent.util.checkPermissionsForBluetoothConnection
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.common.util.concurrent.MoreExecutors
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch

/**
 * Provides methods to connect to [Car]s that this device has previously associated with.
 *
 * @param context To connect in the background, the context should outlive this object. Consider a
 * service or application context.
 * @param executor Handles the platform callback and executes the methods that return a
 * ListenableFuture. Defaults to the executor in which is object is instantiated.
 */
// TODO: Add unit test when robolectric shadows support BluetoothLeScanner.
open internal class ConnectionManager
@VisibleForTesting
internal constructor(
  private val context: Context,
  private val serviceUuid: UUID,
  private val associatedCarManager: AssociatedCarManager,
  private val executor: Executor,
) {
  private val coroutineDispatcher = executor.asCoroutineDispatcher()
  /** The UUID to use as a key for pulling out values in advertisement data. */
  private val dataUuid = V2_DATA_UUID

  private val bluetoothAdapter: BluetoothAdapter
  private val connectionCallbacks = mutableListOf<ConnectionCallback>()

  // On some Android devices, the BluetoothLeScanner is `null` if Bluetooth is currently
  // turned off but returns correctly once it is turned on. So always retrieve the scanner out of
  // the adapter.
  private val bluetoothLeScanner: BluetoothLeScanner?
    get() = bluetoothAdapter.bluetoothLeScanner

  // The PendingIntent or ScanCallback that was used to start a scan. At most one of these two
  // properties can be set at a time, guarded by `startInternal`. `stop()` resets both to `null`.
  private var startPendingIntent: PendingIntent? = null
  private var startScanCallback: ScanCallback? = null

  init {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter

    logi(TAG, "Always connecting to device with names: ${context.alwaysAllowedDeviceNames}.")
  }

  constructor(
    context: Context,
    serviceUuid: UUID = V2_SERVICE_UUID,
  ) : this(
    context = context.applicationContext,
    serviceUuid = serviceUuid,
    associatedCarManager = AssociatedCarManagerProvider.getInstance(context).manager,
    executor = MoreExecutors.directExecutor(),
  )

  /** Registers the given [callback] to be notified of connection events. */
  open fun registerConnectionCallback(callback: ConnectionCallback) {
    connectionCallbacks.add(callback)
  }

  /** Unregisters the given [callback] from being notified of connection events. */
  open fun unregisterConnectionCallback(callback: ConnectionCallback) {
    connectionCallbacks.remove(callback)
  }

  /**
   * Starts Bluetooth LE scanning for associated devices to connect to.
   *
   * If no car has been associated, this method does not start scanning.
   *
   * The scan results will be delivered via the `PendingIntent`. When delivered, the Intent passed
   * to the receiver or activity will contain one or more of the extras
   * [BluetoothLeScanner.EXTRA_CALLBACK_TYPE], [BluetoothLeScanner.EXTRA_ERROR_CODE], and
   * [BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT] to indicate the result of the scan.
   *
   * If the Intent contains [ScanResult]s, the results should be passed to [connect] to establish
   * connection.
   *
   * Ensure that Bluetooth is enabled before calling this method. If this method is called while
   * Bluetooth is off, then this method will do nothing and return `false`.
   *
   * @return `true` if the start was successful.
   * @see [BluetoothLeScanner.startScan]
   */
  // TODO: Remove lint suppression once false positive lint error has been fixed.
  @SuppressLint("MissingPermission")
  open fun start(pendingIntent: PendingIntent): Boolean =
    startScanForAssociatedCarsInternal(pendingIntent, scanCallback = null)

  /**
   * Starts Bluetooth LE scanning for associated devices to connect to.
   *
   * If no car has been associated, this method does not start scanning.
   *
   * @return `true` if the start was successful.
   */
  internal open fun startScanForAssociatedCars(scanCallback: ScanCallback): Boolean =
    startScanForAssociatedCarsInternal(pendingIntent = null, scanCallback)

  private fun startScanForAssociatedCarsInternal(
    pendingIntent: PendingIntent?,
    scanCallback: ScanCallback?
  ): Boolean {
    logi(TAG, "Scanning associated cars for reconnection.")

    require(pendingIntent == null || scanCallback == null) {
      "At most one of pendingIntent or scanCallback can be set."
    }

    if (!checkPermissionsForBleScanner(context)) {
      loge(TAG, "Missing required permission. No-op.")
      return false
    }

    // Ensure there is only ever one scan happening at a time.
    logi(TAG, "Stopping existing scanning.")
    stop()

    if (!bluetoothAdapter.isEnabled()) {
      loge(TAG, "Request to start(), but Bluetooth is off.")
      return false
    }

    val scanner = bluetoothLeScanner
    if (scanner == null) {
      loge(TAG, "No BluetoothLeScanner to use to start scan. Bluetooth might be off.")
      return false
    }

    CoroutineScope(coroutineDispatcher).launch {
      val isAssociated = associatedCarManager.loadIsAssociated()
      if (!isAssociated) {
        logi(TAG, "No associated car; no-op")
        // Not having associated car is not considered a failure to start reconnection.
        return@launch
      }

      startPendingIntent = pendingIntent
      startScanCallback = scanCallback

      val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
      val settings =
        ScanSettings.Builder()
          .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
          .setCallbackType(SCAN_CALLBACK_TYPE)
          .build()

      if (scanCallback != null) {
        logi(TAG, "Starting discovery with ScanCallback.")
        scanner.startScan(filters, settings, scanCallback)
      }
      if (pendingIntent != null) {
        logi(TAG, "Starting discovery with PendingIntent")
        scanner.startScan(filters, settings, pendingIntent)
      }
    }
    return true
  }

  /**
   * Stops any ongoing scans and returns `true` if this request was successful.
   *
   * If Bluetooth is off when this method is called, this request might not stop correctly on
   * certain Android phones. For a guaranteed stop, ensure Bluetooth is on.
   */
  // TODO: Remove lint suppression once false positive lint error has been fixed.
  @SuppressLint("MissingPermission")
  open fun stop(): Boolean {
    val scanner = bluetoothLeScanner
    if (scanner == null) {
      loge(TAG, "No BluetoothLeScanner to use to stop scan. Bluetooth might be off.")
      return false
    }

    startPendingIntent?.let {
      logi(TAG, "Stopping scan with PendingIntent.")
      scanner.stopScan(it)
    }
    startScanCallback?.let {
      logi(TAG, "Stopping scan with ScanCallback.")
      scanner.stopScan(it)
    }

    startScanCallback = null
    startPendingIntent = null

    return true
  }

  /** Filters a list of [ScanResult]s to connect to. */
  open suspend fun filterForConnectableCars(scanResults: List<ScanResult>): List<ScanResult> {
    val allowedDeviceNames = context.alwaysAllowedDeviceNames
    val associatedCars = associatedCarManager.retrieveAssociatedCars()

    return scanResults.filter {
      allowedDeviceNames.contains(it.scanRecord?.deviceName) || shouldConnect(it, associatedCars)
    }
  }

  /** Returns whether [scanResult] should be connected to, based on [associatedCars]. */
  internal fun shouldConnect(scanResult: ScanResult, associatedCars: List<AssociatedCar>): Boolean {
    if (associatedCars.isEmpty()) {
      logi(TAG, "No car is associated. Ignored scanResult.")
      return false
    }

    val advertisedData = scanResult.getAdvertisedData(dataUuid)
    // If an advertisement does not contain advertised data, the advertisement uses the phone's
    // device ID as service UUID. If the service is waken up, it is implicitly recognized.
    // Always connect.
    if (advertisedData == null) {
      return true
    }

    if (advertisedData.size != PendingCarV2Reconnection.ADVERTISED_DATA_SIZE_BYTES) {
      loge(TAG, "Unexpected advertised data size. Ignored.")
      return false
    }

    val isAssociated = PendingCarV2Reconnection.findMatch(advertisedData, associatedCars) != null
    if (!isAssociated) {
      val size = associatedCars.size
      logi(TAG, "Could not find a match for ScanResult in associated cars of $size.")
    }
    return isAssociated
  }

  /**
   * Establishes connection with a [ScanResult] through BLE.
   *
   * Before attempting to connect to [scanResult], ensure [shouldConnect] returns `true` for it.
   *
   * [scanResult] should be a result delivered to the pending intent from [start]. Passing in
   * arbitrary [ScanResult] is undefined behavior.
   *
   * The result will be notified through [ConnectionCallback]. Specifically, a [Car] will be sent
   * back through [ConnectionCallback.onConnected] for successful connection; and the same
   * [scanResult] will be sent back through [ConnectionCallback.onConnectionFailed] if a connection
   * could not be established.
   *
   * Set [executor] to control the thread that initiates the connection. By default, this is the
   * [executor] from construction. Consider setting a background thread to avoid delaying the
   * connection if that thread is also handling the `ScanResult` callback.
   */
  @JvmOverloads
  open fun connect(
    scanResult: ScanResult,
    // Convert [executor] between executor and dispatcher so that the exposed public API is
    // java-friendly.
    executor: Executor = coroutineDispatcher.asExecutor(),
  ) {
    CoroutineScope(executor.asCoroutineDispatcher()).launch { connectAsync(scanResult) }
  }

  private suspend fun connectAsync(scanResult: ScanResult) {
    if (!checkPermissionsForBluetoothConnection(context)) {
      loge(
        TAG,
        "Missing required permission to connect to bluetooth device, ignore the connect call."
      )
      return
    }
    val gatt = scanResult.toBluetoothGattManager()
    if (gatt == null) {
      loge(TAG, "Could not convert $scanResult as BluetoothGattManager.")
      notifyCallbacksOfFailedConnection(scanResult.device)
      return
    }
    val advertisedData = resolveAdvertisedData(gatt, scanResult)

    connect(gatt, advertisedData)
  }

  private suspend fun connect(manager: BluetoothConnectionManager, advertisedData: ByteArray?) {
    val device = manager.bluetoothDevice

    if (!manager.connectToDevice()) {
      loge(TAG, "Could not establish connection.")
      notifyCallbacksOfFailedConnection(device)
      return
    }

    val resolvedVersion = resolveVersion(manager)
    if (resolvedVersion == null) {
      loge(TAG, "Could not resolve version over $device.")
      notifyCallbacksOfFailedConnection(device)
      return
    }

    val stream = MessageStream.create(resolvedVersion.messageVersion, manager)
    if (stream == null) {
      loge(TAG, "Resolved version is $resolvedVersion but could not create stream.")
      notifyCallbacksOfFailedConnection(device)
      return
    }

    val pendingCar =
      PendingCar.create(
          resolvedVersion.securityVersion,
          context,
          isAssociating = false,
          stream = stream,
          associatedCarManager = associatedCarManager,
          device = device,
          bluetoothManager = manager,
          oobChannelTypes = emptyList(),
          oobData = null
        )
        .apply { callback = pendingCarCallback }

    pendingCar.connect(advertisedData)
  }

  // This method registers then unregisters a connection callback because it owns the connection
  // during the version exchange phase.
  @VisibleForTesting
  internal suspend fun resolveVersion(manager: BluetoothConnectionManager): ResolvedVersion? {
    val device = manager.bluetoothDevice
    val connectionCallback =
      object : BluetoothConnectionManager.ConnectionCallback {
        override fun onConnected() {
          loge(TAG, "Received onConnected() during version exchange. Stopping reconnection.")
          notifyCallbacksOfFailedConnection(device)
        }

        override fun onConnectionFailed() {
          loge(TAG, "Received onConnectionFailed() during version exchange. Stopping reconnection.")
          notifyCallbacksOfFailedConnection(device)
        }

        override fun onDisconnected() {
          loge(TAG, "Disconnected during version exchange.")
          notifyCallbacksOfFailedConnection(device)
        }
      }

    manager.registerConnectionCallback(connectionCallback)
    val resolved = VersionResolver.resolve(manager)
    manager.unregisterConnectionCallback(connectionCallback)

    return resolved
  }

  private fun notifyCallbacksOfFailedConnection(device: BluetoothDevice) {
    for (callback in connectionCallbacks) {
      callback.onConnectionFailed(device)
    }
  }

  /** Converts a [ScanResult] to [BluetoothGattManager]. */
  private fun ScanResult.toBluetoothGattManager(): BluetoothGattManager? {
    if (!containsService(serviceUuid)) {
      // Car does not advertise a fixed service UUID;
      // it must be advertising the device ID of phone as service UUID.
      val deviceId = getDeviceId(context)
      logi(TAG, "ScanResult does not contain fixed service UUID; connecting to service $deviceId")
      return toV2BluetoothGattManager(deviceId)
    }

    val advertisedData = getAdvertisedData(dataUuid)
    val size = advertisedData?.size ?: 0
    return when (size) {
      PendingCarV2Reconnection.ADVERTISED_DATA_SIZE_BYTES -> {
        logi(TAG, "Converting to V2 BluetoothGattManager with service $serviceUuid")
        toV2BluetoothGattManager(serviceUuid)
      }
      0 -> {
        logi(TAG, "No advertise data but service contains $serviceUuid. Treating as BLE proxy.")
        toV2BluetoothGattManager(serviceUuid)
      }
      else -> {
        loge(TAG, "Unrecognized size of advertised data of $this: $size. Ignored.")
        null
      }
    }
  }

  private fun ScanResult.containsService(serviceUuid: UUID): Boolean =
    scanRecord?.serviceUuids?.contains(ParcelUuid(serviceUuid)) ?: false

  /** Converts a [ScanResult] to [BluetoothGattManager] using message stream V2 characteristics. */
  private fun ScanResult.toV2BluetoothGattManager(serviceUuid: UUID) =
    BluetoothGattManager(
      context,
      BluetoothGattHandle(device, context.gattTransport),
      serviceUuid,
      V2_CLIENT_WRITE_CHARACTERISTIC_UUID,
      V2_SERVER_WRITE_CHARACTERISTIC_UUID
    )

  /**
   * Determines the advertise data the Bluetooth connection manager should use.
   *
   * `null` means the connection does not depend on advertise data.
   *
   * This function may make a GATT request to read characteristic for advertised data.
   */
  private suspend fun resolveAdvertisedData(
    manager: BluetoothConnectionManager,
    scanResult: ScanResult
  ): ByteArray? {
    // Car advertises phone's device ID - scan result does not contain advertise data.
    if (!scanResult.containsService(serviceUuid)) {
      return null
    }

    val advertisedData = scanResult.getAdvertisedData(dataUuid)
    return advertisedData ?: (manager as? BluetoothGattManager)?.retrieveAdvertisedData()
  }

  /** Disconnects the given car if it is currently connected. */
  open fun disconnect(car: Car) {
    car.disconnect()
  }

  private suspend fun handleOnConnected(car: Car) {
    if (!associatedCarManager.updateEncryptionKey(car)) {
      loge(
        TAG,
        "Could not store secure session for car ${car.deviceId}. Car needs to be re-associated."
      )
      return
    }
    connectionCallbacks.forEach { it.onConnected(car) }
  }

  private val pendingCarCallback =
    object : PendingCar.Callback {
      override fun onDeviceIdReceived(deviceId: UUID) {
        logw(TAG, "Unexpected callback - onDeviceIdReceived: $deviceId. Ignored.")
      }

      override fun onAuthStringAvailable(authString: String) {
        logw(TAG, "Unexpected callback - onAuthStringAvailable: $authString. Ignored.")
      }

      override fun onConnected(car: Car) {
        CoroutineScope(coroutineDispatcher).launch { handleOnConnected(car) }
      }

      override fun onConnectionFailed(pendingCar: PendingCar) {
        // We should not clear stored secure session for this pending car, in case it's attempting
        // to connect to a car that has not been previously associated.
        logi(TAG, "Could not re-authenticate $pendingCar.")
        notifyCallbacksOfFailedConnection(pendingCar.device)
      }
    }

  private fun ScanResult.getAdvertisedData(serviceUuid: UUID): ByteArray? =
    scanRecord?.getServiceData(ParcelUuid(serviceUuid))

  /** Callback that will be notified for [connect] state change. */
  interface ConnectionCallback {
    /**
     * Invoked when a [Car] has been connected. It can be used to send/receive encrypted messages.
     */
    fun onConnected(car: Car)

    /**
     * Invoked when [connect] fails, e.g. car has removed the association with this device (forget
     * the phone). [device] will be the same [BluetoothDevice] or [ScanResult.getDevice] passed to
     * the [connect] call.
     */
    fun onConnectionFailed(device: BluetoothDevice)
  }

  companion object {
    private const val TAG = "ConnectionManager"

    private val V2_SERVER_WRITE_CHARACTERISTIC_UUID =
      UUID.fromString("5e2a68a5-27be-43f9-8d1e-4546976fabd7")
    private val V2_CLIENT_WRITE_CHARACTERISTIC_UUID =
      UUID.fromString("5e2a68a6-27be-43f9-8d1e-4546976fabd7")

    private const val SCAN_CALLBACK_TYPE = ScanSettings.CALLBACK_TYPE_ALL_MATCHES

    /**
     * The service UUID to scan for if the security version is 2.
     *
     * This UUID is the Google Manufacturer Specific ID.
     */
    val V2_SERVICE_UUID = UUID.fromString("000000e0-0000-1000-8000-00805f9b34fb")

    /**
     * The UUID that serves as the key for data within the advertisement packet in security version
     * 2.
     *
     * This UUID is only valid for security version 2. It is the "Google Manufacturer Data Type".
     */
    val V2_DATA_UUID = UUID.fromString("00000020-0000-1000-8000-00805f9b34fb")

    /**
     * Sets the default GATT MTU.
     *
     * GATT MTU depends on hardware support, and is negotiated as part of establishing connection.
     * This method sets the MTU to request. The value will be persisted and used in future GATT
     * connections.
     *
     * Also this value will be used as the default in case the MTU request callback was not received
     * (a problem for some Android phone models). It should be configured according to the hardware
     * capability of the connected device.
     *
     * @return `true` if the value was persisted successfully.
     */
    @JvmStatic
    fun setDefaultGattMtu(context: Context, mtu: Int): Boolean =
      BluetoothGattManager.setDefaultMtu(context, mtu)
  }
}
