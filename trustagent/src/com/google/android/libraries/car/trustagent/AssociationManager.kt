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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationRequest as CmdAssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.Parcelable
import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.util.checkPermissionsForBleScanner
import com.google.android.libraries.car.trustagent.util.checkPermissionsForBluetoothConnection
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.common.util.concurrent.ListenableFuture
import java.nio.ByteBuffer
import java.nio.ByteOrder.BIG_ENDIAN
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.experimental.or
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Manages the process of associating the current device with a car, including
 * - discovering a car that is ready to be associated with;
 * - initiating the association and notifying callbacks that require user interaction.
 *
 * @param associationHandler A representative of [CompanionDeviceManager], which will be used to
 *   handle associate and disassociate requests.
 */
open internal class AssociationManager
@VisibleForTesting
internal constructor(
  private val context: Context,
  private val associatedCarManager: AssociatedCarManager,
  private val bleManager: BleManager,
  private val associationHandler: AssociationHandler,
  private val coroutineDispatcher: CoroutineDispatcher,
) {
  private val bluetoothAdapter: BluetoothAdapter

  /**
   * The context for executing any [ListenableFuture]s and database related actions.
   *
   * This context should be capable of running in the background since it will be responsible for
   * accessing the database.
   */
  private var backgroundContext: CoroutineDispatcher =
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()

  /** The UUID to filter for when scanning for cars to associate with. */
  private lateinit var associationServiceUuid: UUID

  private var currentPendingCar: PendingCar? = null
  private var currentPendingCdmDevice: BluetoothDevice? = null

  private var namePrefix: String? = null

  private val discoveryCallbacks = mutableListOf<DiscoveryCallback>()
  private val associationCallbacks = mutableListOf<AssociationCallback>()
  private val disassociationCallbacks = mutableListOf<DisassociationCallback>()

  // This field should be re-initiliazed every time it's used; it being non-null has no implication.
  private var oobChannel: OobChannel? = null

  private val versionExchangeConnectionCallback =
    object : BluetoothConnectionManager.ConnectionCallback {
      override fun onConnected() {
        loge(TAG, "Received onConnected() during version exchange. Stopping association.")
        notifyCallbacksOfFailedAssociation()
      }

      override fun onConnectionFailed() {
        loge(TAG, "Received onConnectionFailed() during version exchange. Stopping association.")
        notifyCallbacksOfFailedAssociation()
      }

      override fun onDisconnected() {
        loge(TAG, "Disconnected during version exchange.")
        notifyCallbacksOfFailedAssociation()
      }
    }

  /**
   * `true` if Bluetooth is currently enabled.
   *
   * Starting discovery of devices to associate can only occur if this value is `true`.
   */
  open val isBluetoothEnabled
    get() = bleManager.isEnabled

  init {
    associationServiceUuid =
      UUID.fromString(context.getString(R.string.car_association_service_uuid))

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter

    logi(TAG, "Resources specified association serivce UUID: $associationServiceUuid.")
  }

  constructor(
    context: Context,
  ) : this(
    context = context.applicationContext,
    associatedCarManager = AssociatedCarManagerProvider.getInstance(context).manager,
    bleManager =
      BluetoothManagerWrapper(
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
      ),
    associationHandler = CompanionDeviceManagerHandler(context),
    coroutineDispatcher = Dispatchers.Main,
  )

  /**
   * Loads if this phone is currently associated with at least one car.
   *
   * `true` will be passed through the returned [ListenableFuture] if this is the case.
   */
  open fun loadIsAssociated(): ListenableFuture<Boolean> =
    CoroutineScope(backgroundContext).future { associatedCarManager.loadIsAssociated() }

  /**
   * Loads if this phone is associated with the car with the [macAddress].
   *
   * `true` will be passed through the returned [ListenableFuture] if this is the case.
   */
  open fun loadIsAssociated(macAddress: String): ListenableFuture<Boolean> =
    CoroutineScope(backgroundContext).future { associatedCarManager.loadIsAssociated(macAddress) }

  /**
   * Loads a list of all cars that are currently associated with this phone.
   *
   * The returned [ListenableFuture] will be invoked with the list when loading is successful. An
   * empty list will be returned if there are no associated cars.
   */
  open fun retrieveAssociatedCars(): ListenableFuture<List<AssociatedCar>> =
    CoroutineScope(backgroundContext).future { associatedCarManager.retrieveAssociatedCars() }

  /** Registers the given [callback] to be notified of discovery events. */
  fun registerDiscoveryCallback(callback: DiscoveryCallback) {
    discoveryCallbacks.add(callback)
  }

  /** Unregisters the given [callback] from being notified of discovery events. */
  fun unregisterDiscoveryCallback(callback: DiscoveryCallback) {
    discoveryCallbacks.remove(callback)
  }

  /** Registers the given [callback] to be notified of association events. */
  open fun registerAssociationCallback(callback: AssociationCallback) {
    associationCallbacks.add(callback)
  }

  /** Unregisters the given [callback] from being notified of association events. */
  open fun unregisterAssociationCallback(callback: AssociationCallback) {
    associationCallbacks.remove(callback)
  }

  /** Registers the given [callback] to be notified of disassociation events. */
  open fun registerDisassociationCallback(callback: DisassociationCallback) {
    disassociationCallbacks.add(callback)
  }

  /** Unregisters the given [callback] from being notified of disassociation events. */
  open fun unregisterDisassociationCallback(callback: DisassociationCallback) {
    disassociationCallbacks.remove(callback)
  }

  /**
   * Starts the discovery for association and returns `true` if the start was successful.
   *
   * Results are notified by [DiscoveryCallback].
   *
   * Ensure that Bluetooth is enabled before calling this method. If this method is called while
   * Bluetooth is off, then this method will do nothing and return `false`. [isBluetoothEnabled] can
   * be used as a shortcut for checking this state.
   *
   * @param namePrefix May be added to the beginning of the advertised names of cars that are found.
   *   This will be added only when doing so matches the advertisement name the car is currently
   *   displaying.
   */
  fun startDiscovery(namePrefix: String = ""): Boolean =
    startDiscovery(associationServiceUuid, namePrefix)

  /**
   * Starts the discovery for association and returns `true` if the start was successful.
   *
   * Results are notified by [DiscoveryCallback].
   *
   * Ensure that Bluetooth is enabled before calling this method. If this method is called while
   * Bluetooth is off, then this method will do nothing and return `false`. [isBluetoothEnabled] can
   * be used as a shortcut for checking this state.
   *
   * @param filterService UUID used for discovery. This parameter takes precedence over the value
   *   that is specified via resource overlay as R.string.car_association_service_uuid.
   * @param namePrefix May be added to the beginning of the advertised names of cars that are found.
   *   This will be added only when doing so matches the advertisement name the car is currently
   *   displaying.
   */
  // TODO: Remove lint suppression once false positive lint error has been fixed. Same
  //  for all the [MissingPermission] lint suppression in this class.
  @SuppressLint("MissingPermission")
  fun startDiscovery(filterService: UUID, namePrefix: String = ""): Boolean {
    if (!isBluetoothEnabled) {
      loge(TAG, "Request to start discovery to associate, but Bluetooth is not enabled.")
      return false
    }
    if (!checkPermissionsForBleScanner(context)) {
      loge(TAG, "Missing required permission. No-op.")
      return false
    }

    // Ensure only one scan happens at a time.
    stopDiscovery()

    associationServiceUuid = filterService
    logi(TAG, "Setting association service UUID to $filterService.")

    this.namePrefix = namePrefix

    val filters =
      listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(associationServiceUuid)).build())
    val settings =
      ScanSettings.Builder()
        .setCallbackType(SCAN_CALLBACK_TYPE)
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    logi(TAG, "Starting discovery for $associationServiceUuid.")

    if (bleManager.startScan(filters, settings, scanCallback)) {
      return true
    }

    loge(TAG, "startScan failed. Bluetooth might be off.")
    return false
  }

  /**
   * Starts the [CompanionDeviceManager] discovery for association.
   *
   * Results are notified by [DiscoveryCallback] for discovery failure and [OnDeviceFoundListener]
   * for device found successfully.
   *
   * Ensure that Bluetooth is enabled before calling this method. If this method is called while
   * Bluetooth is off, then this method will do nothing and return `false`. [isBluetoothEnabled] can
   * be used as a shortcut for checking this state.
   *
   * This method also requires that the proper scanning permissions be granted. If they are not,
   * then this method will fail and return `false`.
   *
   * @param request Parameters that modify this discovery call.
   * @param callback Callback to be notified of success and failure events.
   */
  internal open fun startCdmDiscovery(
    request: DiscoveryRequest,
    callback: CompanionDeviceManager.Callback,
  ): Boolean {
    logi(TAG, "Starting CDM discovery with $request.")

    if (!isBluetoothEnabled) {
      loge(TAG, "Request to start discovery to associate, but Bluetooth is not enabled.")
      return false
    }

    if (!checkPermissionsForBleScanner(context)) {
      loge(TAG, "Request to start discovery to association, but missing required permissions.")
      return false
    }

    request.associationUuid?.let {
      logi(TAG, "Starting discovery with association UUID specified by request: $it")
      associationServiceUuid = it
    }

    // Create CDM association request
    val pairingRequest =
      CmdAssociationRequest.Builder().run {
        addDeviceFilter(
          createBleDeviceFilter(
            request.namePrefix,
            associationServiceUuid,
            request.deviceIdentifier
          )
        )
        // A device identifier ensures only a single device can be found.
        setSingleDevice(request.deviceIdentifier != null)

        build()
      }

    // Use CDM to start discovery - CDM.associate() would return discovery result in callback.
    associationHandler.associate(request.activity, pairingRequest, callback)
    return true
  }

  private fun createBleDeviceFilter(
    namePrefix: String,
    filterService: UUID,
    serviceData: ByteArray?
  ): BluetoothLeDeviceFilter {
    val bleDeviceFilterBuilder =
      BluetoothLeDeviceFilter.Builder()
        .setRenameFromBytes(
          namePrefix,
          "",
          DEVICE_NAME_START_INDEX,
          ADVERTISED_NAME_DATA_LENGTH_SHORT,
          BIG_ENDIAN
        )

    // Filter raw data instead of service uuid because of the filter bug b/158243042
    // which is fixed in Android R.
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
      val filter = createRawDataFilter(filterService, serviceData)
      val filterMask = createRawDataFilterMask(serviceData)
      bleDeviceFilterBuilder.setRawDataFilter(filter, filterMask)
    } else {
      val bleScanFilter =
        ScanFilter.Builder().run {
          setServiceUuid(ParcelUuid(filterService))
          serviceData?.let { setServiceData(ParcelUuid(DEVICE_NAME_DATA_UUID), it) }
          build()
        }
      bleDeviceFilterBuilder.setScanFilter(bleScanFilter)
    }

    return bleDeviceFilterBuilder.build()
  }

  /**
   * Stops any ongoing discovery scans and returns `true` if this request was successful.
   *
   * If Bluetooth is off when this method is called, this request might not stop correctly on
   * certain Android phones. For a guaranteed stop, ensure Bluetooth is on.
   */
  @SuppressLint("MissingPermission")
  open fun stopDiscovery(): Boolean {
    if (bleManager.stopScan(scanCallback)) {
      return true
    }

    loge(TAG, "stopScan failed. Bluetooth might be off.")
    return false
  }

  /**
   * Begins association process with [discoveredCar].
   *
   * Progress is notified by [AssociationCallback].
   */
  fun associate(discoveredCar: DiscoveredCar) {
    associateInternal(discoveredCar, oobData = null)
  }

  /**
   * Begins an association using the configuration in the given [request].
   *
   * Progress is notified by [AssociationCallback].
   */
  internal open fun associate(request: AssociationRequest) {
    logi(TAG, "Starting association with $request.")
    val deviceToPair: Parcelable? =
      request.intent.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
    val discoveredCar =
      when (deviceToPair) {
        is BluetoothDevice ->
          DiscoveredCar(
            device = deviceToPair,
            name = deviceToPair.name,
            gattServiceUuid = associationServiceUuid,
          )
        is ScanResult -> deviceToPair.toDiscoveredCar()
        else -> null
      }
    if (discoveredCar == null) {
      loge(TAG, "Found unrecogonized device, ignore.")
      return
    }
    currentPendingCdmDevice = discoveredCar.device
    associateInternal(discoveredCar, request.oobData)
  }

  private fun associateInternal(discoveredCar: DiscoveredCar, oobData: OobData?) {
    logi(TAG, "Starting association with $discoveredCar and $oobData.")
    if (!checkPermissionsForBluetoothConnection(context)) {
      loge(TAG, "Missing required permissions to start association. Notifying callbacks of error.")

      notifyCallbacksOfFailedAssociation()
      return
    }

    logi(TAG, "Stopping discovery for association.")
    stopDiscovery()

    CoroutineScope(coroutineDispatcher).launch { startConnection(discoveredCar, oobData) }
  }

  private suspend fun startConnection(discoveredCar: DiscoveredCar, oobData: OobData?) {
    logi(TAG, "Connecting to $discoveredCar.")

    val bluetoothManager = connectBluetooth(discoveredCar)
    if (bluetoothManager == null) {
      loge(TAG, "Could not establish connection.")
      notifyCallbacksOfFailedAssociation()
      return
    }

    for (callback in associationCallbacks) {
      callback.onAssociationStart()
    }

    // Resolve message and security version; also exchange capability based on security version.
    logi(TAG, "Resolving connection with $bluetoothManager.")
    val resolvedConnection = resolveConnection(bluetoothManager, oobData)
    if (resolvedConnection == null) {
      loge(TAG, "Could not resolve connection over $bluetoothManager.")
      notifyCallbacksOfFailedAssociation()
      return
    }

    // Locally construct a message stream based on message version.
    val stream = MessageStream.create(resolvedConnection.messageVersion, bluetoothManager)
    if (stream == null) {
      loge(TAG, "Resolved connection is $resolvedConnection but could not create stream.")
      notifyCallbacksOfFailedAssociation()
      return
    }

    currentPendingCar =
      createPendingCar(
        resolvedConnection.securityVersion,
        stream,
        discoveredCar.device,
        bluetoothManager,
        resolvedConnection.oobChannels,
        oobData
      )
    currentPendingCar?.connect()
  }

  // This method registers then unregisters a connection callback because it owns the connection
  // during the version exchange phase.
  @VisibleForTesting
  internal suspend fun resolveConnection(
    bluetoothManager: BluetoothConnectionManager,
    oobData: OobData?
  ): ResolvedConnection? {
    bluetoothManager.registerConnectionCallback(versionExchangeConnectionCallback)
    val resolved = ConnectionResolver.resolve(bluetoothManager, oobData, isAssociating = true)
    bluetoothManager.unregisterConnectionCallback(versionExchangeConnectionCallback)

    return resolved
  }

  private fun notifyCallbacksOfFailedAssociation() {
    for (callback in associationCallbacks) {
      callback.onAssociationFailed()
    }
  }

  /** Establishes connection with discovered car over GATT. */
  private suspend fun connectBluetooth(discoveredCar: DiscoveredCar): BluetoothConnectionManager? {
    val bluetoothManagers = discoveredCar.toBluetoothConnectionManagers(context)
    return bluetoothManagers.firstOrNull { manager ->
      val connectionResult = manager.connectToDevice()
      logi(TAG, "The result of the connection attempt with $manager is $connectionResult.")
      connectionResult
    }
  }

  /** Asynchronously clears all cars that are currently associated with this device. */
  fun clearAllAssociatedCars() {
    logi(TAG, "Request to clear all associated cars.")
    CoroutineScope(coroutineDispatcher).launch {
      associatedCarManager.clearAll()
      disassociationCallbacks.forEach { it.onAllCarsDisassociated() }
    }
  }

  /**
   * Clears all cars that are currently associated with this device and [CompanionDeviceManager]
   * associations, returns `true` if the operation succeeded.
   */
  internal suspend fun clearAllCdmAssociatedCars(): Boolean {
    associationHandler.associations.forEach { associationHandler.disassociate(it) }
    return associatedCarManager.clearAll()
  }

  /** Asynchronously clears the association status for the car with the given [deviceId]. */
  fun clearAssociatedCar(deviceId: UUID) {
    logi(TAG, "Request to clear association for car with device id $deviceId")

    CoroutineScope(coroutineDispatcher).launch {
      associatedCarManager.clear(deviceId)
      disassociationCallbacks.forEach { it.onCarDisassociated(deviceId) }
    }
  }

  /**
   * Clears the association status for the car with the given [deviceId] and
   * [CompanionDeviceManager] association with the device, returns `true` if the operation
   * succeeded.
   */
  internal open suspend fun clearCdmAssociatedCar(deviceId: UUID): Boolean {
    val macAddress = associatedCarManager.loadMacAddress(deviceId)
    if (!associationHandler.disassociate(macAddress)) {
      loge(TAG, "clearCdmAssociatedCar called with un-associated device $macAddress")
    }
    return associatedCarManager.clear(deviceId)
  }

  /** Clears current incomplete association. */
  fun clearCurrentAssociation() {
    stopDiscovery()
    currentPendingCar?.disconnect()
    currentPendingCar = null
  }

  /** Clears current incomplete association and [CompanionDeviceManager] association. */
  internal fun clearCurrentCdmAssociation() {
    clearCurrentAssociation()
    currentPendingCdmDevice?.let {
      if (!associationHandler.disassociate(it.address)) {
        logw(TAG, "clearCurrentCdmAssociation called with un-associated device ${it.address}")
      }
    }
    currentPendingCdmDevice = null
  }

  /**
   * Renames an associated car and returns `true` if the operation was successful
   *
   * Renaming requires that the given [name] be non-empty.
   */
  open fun renameCar(deviceId: UUID, name: String): ListenableFuture<Boolean> =
    CoroutineScope(backgroundContext).future { associatedCarManager.rename(deviceId, name) }

  private fun createPendingCar(
    securityVersion: Int,
    stream: MessageStream,
    device: BluetoothDevice,
    bluetoothManager: BluetoothConnectionManager,
    oobChannelTypes: List<OobChannelType>,
    oobData: OobData?
  ): PendingCar {
    return PendingCar.create(
        securityVersion,
        context,
        isAssociating = true,
        stream = stream,
        associatedCarManager = associatedCarManager,
        device = device,
        bluetoothManager = bluetoothManager,
        oobChannelTypes = oobChannelTypes,
        oobData = oobData
      )
      .apply { callback = pendingCarCallback }
  }

  private val scanCallback =
    object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult?) {
        super.onScanResult(callbackType, result)

        logi(TAG, "onScanResult: $result")
        result?.toDiscoveredCar()?.let { discoveredCar ->
          discoveryCallbacks.forEach { it.onDiscovered(discoveredCar) }
        }
      }

      override fun onScanFailed(errorCode: Int) {
        super.onScanFailed(errorCode)

        loge(TAG, "onScanFailed: error $errorCode")
        discoveryCallbacks.forEach { it.onDiscoveryFailed(errorCode) }
      }

      override fun onBatchScanResults(results: MutableList<ScanResult>?) {
        super.onBatchScanResults(results)
        logw(TAG, "Received unexpected onBatchScanResults callback; forwarding to onScanResult.")
        results?.forEach { onScanResult(SCAN_CALLBACK_TYPE, it) }
      }
    }

  private fun ScanResult.toDiscoveredCar(): DiscoveredCar? {
    val scanRecord = scanRecord
    if (scanRecord == null) {
      loge(TAG, "ScanResult for association scan has null 'scanRecord'. Ignoring.")
      return null
    }

    val name = retrieveDeviceName(scanRecord)
    if (name == null) {
      loge(TAG, "Failed to retrieve a non-empty device name from the scan: $scanRecord")
      return null
    }

    val serviceUuid = scanRecord.serviceUuids?.takeIf { it.size == 1 }?.let { it.first().uuid }

    // Validity check to ensure that the returned result corresponds to the UUID that was used for
    // scanning.
    if (serviceUuid == null || associationServiceUuid != serviceUuid) {
      loge(
        TAG,
        "Invalid services found. Expected $associationServiceUuid, " +
          "available service UUIDs: ${scanRecord.serviceUuids}"
      )
      return null
    }

    return DiscoveredCar(device, name, associationServiceUuid)
  }

  /**
   * Attempts to retrieve a non-empty String from the given [ScanRecord] that can be used as the
   * device name to display to the user.
   *
   * The service data on the `ScanRecord` is checked first, with the [UUID] used to scan as the key
   * into the data. If this value is empty, then [ScanRecord.deviceName] is returned.
   */
  private fun retrieveDeviceName(scanRecord: ScanRecord): String? {
    // Fall back to the device name, which is where the legacy name for association can possibly
    // be stored.
    val serviceData = scanRecord.getServiceData(ParcelUuid(DEVICE_NAME_DATA_UUID))
    if (serviceData == null) {
      return scanRecord.deviceName
    }

    val shownName =
      if (serviceData.size == ADVERTISED_NAME_DATA_LENGTH_LONG) {
        serviceData.toString(Charsets.UTF_8)
      } else {
        serviceData.toHexString()
      }
    return "$namePrefix$shownName"
  }

  private fun ByteArray.toHexString() = joinToString("") { String.format("%02X", it) }

  private suspend fun notifyCallbacksOfDeviceId(deviceId: UUID) {
    // Check if the user is associating an already associated car. In this case, issue a
    // disassociation first so that features can clear data if necessary.
    if (associatedCarManager.loadIsAssociated(deviceId)) {
      logi(TAG, "Associating an already associated car. Issuing disassociation callback first.")

      for (callback in disassociationCallbacks) {
        callback.onCarDisassociated(deviceId)
      }
    }

    for (callback in associationCallbacks) {
      callback.onDeviceIdReceived(deviceId)
    }
  }

  @VisibleForTesting
  internal val pendingCarCallback =
    object : PendingCar.Callback {
      override fun onDeviceIdReceived(deviceId: UUID) {
        logi(TAG, "Received device ID from car: $deviceId. Notifying callbacks")

        CoroutineScope(coroutineDispatcher).launch { notifyCallbacksOfDeviceId(deviceId) }
      }

      override fun onAuthStringAvailable(authString: String) {
        associationCallbacks.forEach { it.onAuthStringAvailable(authString) }
      }

      override fun onConnected(car: Car) {
        currentPendingCar = null
        currentPendingCdmDevice = null

        CoroutineScope(coroutineDispatcher).launch {
          if (associatedCarManager.add(car)) {
            associationCallbacks.forEach { it.onAssociated(car) }
          } else {
            loge(TAG, "Could not add $car as an associated car. Notifying callback")
            notifyCallbacksOfFailedAssociation()
          }
        }
      }

      override fun onConnectionFailed(pendingCar: PendingCar) {
        notifyCallbacksOfFailedAssociation()
      }
    }

  /** Callback that will be notified for [startDiscovery] result. */
  interface DiscoveryCallback {
    /** Invoked when a [DiscoveredCar] has be found. */
    fun onDiscovered(discoveredCar: DiscoveredCar)

    /**
     * Invoked when [startDiscovery] failed. [errorCode] are `SCAN_FAILED_*` constants in
     * [ScanCallback].
     */
    fun onDiscoveryFailed(errorCode: Int)
  }

  /** Callback that will be notified for [associate] result. */
  interface AssociationCallback {
    /** Invoked when association has been started. */
    fun onAssociationStart()

    /** Invoked when remote device ID has been received. */
    fun onDeviceIdReceived(deviceId: UUID)

    /** Invoked when [authString] should be displayed to user for out-of-band verification. */
    fun onAuthStringAvailable(authString: String)

    /** Invoked when this device has been successfully associated with [car]. */
    fun onAssociated(car: Car)

    /** Invoked when the association process has failed. */
    // TODO: Define error code when encryption error is exposed.
    fun onAssociationFailed()
  }

  /** Listener for when a car has been disassociated. */
  interface DisassociationCallback {
    /**
     * Invoked when the car with the given [deviceId] has been disassociated.
     *
     * Once disassociated, this phone will no longer be able to send or receive messages from the
     * given car until it goes through the association process again.
     *
     * This method is called when when the [onCarDisassociated] is called, as opposed to
     * [clearAllAssociatedCars], which clears all associated cars. The latter will instead trigger
     * the [onAllCarsDisassociated] callback.
     */
    fun onCarDisassociated(deviceId: UUID)

    /**
     * Invoked when all associated cars have been disassociated.
     *
     * This will indicate that it is safe to clear all data pertaining to any associated car.
     *
     * This method is only invoked when the [clearAllAssociatedCars] method is called, as opposed to
     * [clearAssociatedCar]. The latter will instead trigger the [onCarDisassociated] callback.
     */
    fun onAllCarsDisassociated()
  }

  companion object {
    private const val TAG = "AssociationManager"
    private const val SCAN_CALLBACK_TYPE = ScanSettings.CALLBACK_TYPE_ALL_MATCHES
    private const val ADVERTISED_NAME_DATA_LENGTH_LONG = 8
    private const val ADVERTISED_NAME_DATA_LENGTH_SHORT = 2

    /**
     * Decided by the maximum advertised data size(31) defined by [BluetoothLeAdvertiser]. The total
     * advertisement data is consist of advertised data and scan response so has a maximum length
     * of 62.
     */
    private const val ADVERTISEMENT_LENGTH = 62

    /**
     * The index of service UUID in a car's BLE advertisement.
     *
     * The service UUID is the second element in the advertisement. The first element is flags that
     * take 3 bytes.
     *
     * In the second element, the first 2 bytes are split as:
     * - 1 byte of length;
     * - 1 byte of type;
     *
     * The advertisement format should be kept in sync with the advertising schema on the car side.
     * Details calculating the index is describe in b/187241458.
     */
    private const val ADVERTISED_DATA_SERVICE_UUID_START_INDEX = 5
    private const val UUID_LENGTH_BYTES = 16
    /**
     * The index of service data of [DEVICE_NAME_DATA_UUID] in a car's BLE advertisement.
     *
     * The service data element is packed after the service UUID (start index + UUID length).
     *
     * In the service data element, the first 4 bytes are split as:
     * - 1 byte of length;
     * - 1 byte of type;
     * - 2 byte of short UUID [DEVICE_NAME_DATA_UUID];
     *
     * The advertisement format should be kept in sync with the advertising schema on the car side.
     * Details calculating the index is describe in b/187241458.
     */
    private const val DEVICE_NAME_START_INDEX =
      ADVERTISED_DATA_SERVICE_UUID_START_INDEX + UUID_LENGTH_BYTES + 4

    // Filter for non-null name.
    private const val BLUETOOTH_DEVICE_NAME_PATTERN_REGEX = ".+"

    private val SERVICE_UUID_FILTER_MASK =
      ByteArray(ADVERTISEMENT_LENGTH).apply {
        fill(
          0xff.toByte(),
          ADVERTISED_DATA_SERVICE_UUID_START_INDEX,
          ADVERTISED_DATA_SERVICE_UUID_START_INDEX + UUID_LENGTH_BYTES
        )
      }
    private val DEVICE_NAME_FILTER_MASK =
      ByteArray(ADVERTISEMENT_LENGTH).apply {
        fill(
          0xff.toByte(),
          DEVICE_NAME_START_INDEX,
          DEVICE_NAME_START_INDEX + ADVERTISED_NAME_DATA_LENGTH_SHORT
        )
      }

    /**
     * The [UUID] that serves as the key for data within the association advertisement packet which
     * contains the device name.
     */
    // TODO: Allow this value to be configurable.
    internal val DEVICE_NAME_DATA_UUID = UUID.fromString("00000020-0000-1000-8000-00805f9b34fb")

    /**
     * Generates the raw data as filter to scan for the device.
     *
     * Only filter for devices are that are advertising [associationUuid], and optionally contains
     * [advertisedData] under GATT service of [DEVICE_NAME_DATA_UUID].
     *
     * The filter is decided by the structure of the advertisement data from the car side. It looks
     * for the given [associationUuid] bytes from the whole advertisement data beginning on a
     * certain index.
     */
    @VisibleForTesting
    internal fun createRawDataFilter(associationUuid: UUID, advertisedData: ByteArray?): ByteArray {
      val rawDataFilter = ByteArray(ADVERTISEMENT_LENGTH)
      val uuidBytes = associationUuid.toByteArray()
      // Copy the UUID as bytes into filter byte array with offset.
      System.arraycopy(
        uuidBytes,
        0,
        rawDataFilter,
        ADVERTISED_DATA_SERVICE_UUID_START_INDEX,
        uuidBytes.size
      )
      advertisedData?.let {
        // Also copy the advertised data into filter, only with supported length.
        when (it.size) {
          ADVERTISED_NAME_DATA_LENGTH_LONG,
          ADVERTISED_NAME_DATA_LENGTH_SHORT -> {
            System.arraycopy(it, 0, rawDataFilter, DEVICE_NAME_START_INDEX, it.size)
          }
          else -> {
            logw(TAG, "Advertised data is null, or does not have expected size. Ignored.")
          }
        }
      }
      return rawDataFilter
    }

    /**
     * Generates the mask of raw data filter to scan for the device.
     *
     * @param serviceData The advertised data; to be interpreted as the device name of advertiser.
     */
    private fun createRawDataFilterMask(serviceData: ByteArray?): ByteArray {
      return if (serviceData == null) {
        SERVICE_UUID_FILTER_MASK
      } else {
        // Create a byte array of (SERVICE_UUID_FILTER_MASK | DEVICE_NAME_FILTER_MASK).
        SERVICE_UUID_FILTER_MASK.zip(DEVICE_NAME_FILTER_MASK) { a, b -> a or b }.toByteArray()
      }
    }

    private fun UUID.toByteArray() =
      ByteBuffer.allocate(UUID_LENGTH_BYTES)
        .order(LITTLE_ENDIAN)
        .putLong(leastSignificantBits)
        .putLong(mostSignificantBits)
        .array()
  }
}
