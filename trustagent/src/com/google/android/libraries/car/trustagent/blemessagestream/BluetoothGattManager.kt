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

package com.google.android.libraries.car.trustagent.blemessagestream

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logw
import com.google.android.libraries.car.trustagent.util.logwtf
import com.google.android.libraries.car.trustagent.util.toHexString
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Extracts the interface for sending/receiving data from [BluetoothGatt].
 *
 * @property serviceUuid The GATT service that will be configured for sending/receiving data.
 */
open class BluetoothGattManager(
  private val context: Context,
  private val gatt: GattHandle,
  private val serviceUuid: UUID,
  private val clientWriteCharacteristicUuid: UUID,
  private val serverWriteCharacteristicUuid: UUID,
  private val advertiseDataCharacteristicUuid: UUID = ADVERTISE_DATA_CHARACTERITIC_UUID,
) : BluetoothConnectionManager() {
  private val defaultMtu = getDefaultMtu(context)
  private val bluetoothAdapter: BluetoothAdapter

  override val bluetoothDevice = gatt.device

  private var serverWriteCharacteristic: BluetoothGattCharacteristic? = null
  private var clientWriteCharacteristic: BluetoothGattCharacteristic? = null

  private var retrieveAdvertisedDataContinuation: CancellableContinuation<ByteArray?>? = null

  // Before going through GATT operations to set up the connection, we should make sure the BT stack
  // is idle, i.e. not in BONDING state for classic BT. If the state is BONDING, we should pause
  // GATT operations and restart after BT state update (through the broadcast receiver).
  @VisibleForTesting internal val isConnectionPaused = AtomicBoolean(false)
  @VisibleForTesting
  internal val bluetoothBondStateBroadcastReceiver: BroadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BOND_STATE_CHANGED) {
          loge(
            TAG,
            "Bond state broadcast receiver received invalid action: ${intent.action}. Ignored.",
          )
          return
        }

        val device: BluetoothDevice? =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
          } else {
            intent.getParcelableExtra(EXTRA_DEVICE) as? BluetoothDevice
          }
        if (device == null) {
          loge(TAG, "Intent does not contain valid bluetooth device. Ignored.")
          return
        }

        val prevBondState = intent.getIntExtra(EXTRA_PREVIOUS_BOND_STATE, -1)
        val bondState = intent.getIntExtra(EXTRA_BOND_STATE, -1)
        logi(TAG, "$device bonding state changed from $prevBondState to $bondState.")

        when (bondState) {
          BOND_BONDING -> {
            logi(TAG, "Device in BOND_BONDING state. Pausing GATT connection at $gattState.")
            isConnectionPaused.set(true)
          }
          BOND_NONE,
          BOND_BONDED -> {
            if (isConnectionPaused.getAndSet(false)) {
              logi(TAG, "Classic BT is no longer bonding. Restarting GATT connection.")
              handler.removeCallbacksAndMessages(null)
              connect()
            }
          }
        }
      }
    }

  /**
   * The updated name of the [bluetoothDevice] managed by this class.
   *
   * This name is not necessarily the same as the one that would be returned by the
   * [bluetoothDevice] field. For example, the device might have changed its name during its
   * lifecycle, but the `BluetoothDevice` itself will not update its internally cached name.
   *
   * This field will instead return that up-to-date name.
   */
  final override var deviceName = bluetoothDevice.name
    private set

  /** Retry count for each step in the connection flow. */
  private val retryCount = mutableMapOf<Int, Int>()

  private enum class GattState {
    DISCONNECTED,
    CONNECTED,
    RETRIEVING_NAME,
    DISCOVERY_COMPLETED,
  }

  private var gattState = GattState.DISCONNECTED

  // Helps to resume the connection flow in case a GATT onMtuChanged() callback was not triggered.
  // See b/149106658 for context.
  private val handler =
    object : Handler(Looper.getMainLooper()) {
      override fun handleMessage(message: Message) {
        when (message.what) {
          MSG_CONNECT_GATT -> handleConnectGatt()
          MSG_ON_CONNECTION_STATE_CHANGE -> handleOnConnectionStateChange(message)
          MSG_REQUEST_MTU -> handleRequestMtu(message)
          MSG_ON_MTU_CHANGED -> handleOnMtuChanged(message)
          MSG_DISCOVER_SERVICES -> handleDiscoverServices()
          MSG_ON_SERVICES_DISCOVERED -> handleOnServicesDiscovered(message)
          MSG_SKIP_MTU_CALLBACK -> handleSkipMtuCallback()
          MSG_READ_CHARACTERISTIC -> handleReadCharacteristic(message)
          else -> loge(TAG, "Received $message of type ${message.what}. Ignoring.")
        }
      }
    }

  /**
   * The maximum amount of bytes that can be written over BLE.
   *
   * This initial value is 20 because BLE has a default write of 23 bytes. However, 3 bytes are
   * subtracted due to bytes being reserved for the command type and attribute ID.
   *
   * @see [ATT_PAYLOAD_RESERVED_BYTES]
   */
  override var maxWriteSize = 20
    internal set

  private val notifyDiscoveryCompleteRunnable = Runnable(::notifyDiscoveryComplete)

  init {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter

    registerForBondStateBroadcast()
  }

  @SuppressLint("UnprotectedReceiver") // System broadcast so this is safe.
  // Create an individual method so that it can be annotated (we can't annotate init).
  private fun registerForBondStateBroadcast() {
    val filter = IntentFilter(ACTION_BOND_STATE_CHANGED)
    context.registerReceiver(bluetoothBondStateBroadcastReceiver, filter)
  }

  /**
   * Connects to bluetooth GATT.
   *
   * A success connection will invoke [ConnectionCallback.onConnected], meaning we have successfully
   * modifies MTU size and enabled server write characteristic for GATT service.
   *
   * Connection enables [sendMessage].
   */
  override fun connect() {
    retryCount.clear()
    val message = handler.obtainMessage(MSG_CONNECT_GATT)
    sendHandlerMessage(message)
  }

  override fun disconnect() {
    if (bluetoothAdapter.isEnabled) {
      logi(TAG, "Disconnecting GATT.")
      gatt.disconnect()
      return
    }

    logi(TAG, "Disconnect called when Bluetooth is off. Handling disconnection manually.")

    // When Bluetooth is turned off, the call to `disconnect` does not trigger an appropriate
    // callback. Notify any callbacks manually while closing the GATT to ensure any lingering
    // connections are severed.
    closeGatt()
    notifyDisconnection()
  }

  private fun closeGatt() {
    gatt.close()
    gatt.callback = null
  }

  /**
   * Sends [message] to a connected device.
   *
   * The delivery of data is notified through [MessageCallback.onMessageSent]. This method should
   * not be invoked again before the callback; otherwise the behavior is undefined.
   *
   * @return `true` if the request to send data was initiated successfully; `false` otherwise.
   */
  override fun sendMessage(message: ByteArray): Boolean {
    logi(TAG, "Sending message of ${message.size} bytes.")
    val gatt =
      checkNotNull(gatt) {
        "Connection not established yet. Call connect() and wait for onConnect() callback."
      }

    val characteristic = clientWriteCharacteristic
    if (characteristic == null) {
      loge(
        TAG,
        "Missing GATT service $serviceUuid or characteristic $clientWriteCharacteristicUuid",
      )
      return false
    }
    characteristic.value = message
    return gatt.writeCharacteristic(characteristic).also { success ->
      if (!success) {
        loge(
          TAG,
          "Could not write characteristic ${characteristic.uuid} in GATT service $serviceUuid.",
        )
        disconnect()
      }
    }
  }

  /**
   * Retrieves the advertised data from GATT service characteristic.
   *
   * Returns `null` if GATT service does not contain such characteristic, or its value is null.
   */
  suspend fun retrieveAdvertisedData(): ByteArray? {
    return suspendCancellableCoroutine<ByteArray?> { cont ->
      retrieveAdvertisedDataContinuation = cont
      logi(TAG, "Issuing request to read advertise data from GATT characteristic.")

      val message = handler.obtainMessage(MSG_READ_CHARACTERISTIC, advertiseDataCharacteristicUuid)
      sendHandlerMessage(message)
    }
  }

  /** Initiates GATT connection. */
  private fun handleConnectGatt() {
    logi(TAG, "Initiating GATT connection.")
    gatt.callback = gattCallback
    gatt.connect(context)
  }

  /** Attempts to queue or retry a handler message with [delay]. */
  private fun sendHandlerMessage(message: Message, delay: Duration = Duration.ZERO) {
    if (isConnectionPaused.get()) {
      logi(TAG, "GATT connection is paused; current message is ${message.what}. Ignored.")
      return
    }

    fun GattState.handleError() {
      loge(TAG, "Error at GATT state $this.")
      when (this) {
        GattState.DISCONNECTED,
        GattState.CONNECTED,
        GattState.RETRIEVING_NAME -> {
          notifyConnectionFailed()
        }
        // When GATT state is DISCOVERY_COMPLETED, we have notified the client of a successful
        // connection, so we should initiate a disconnect().
        GattState.DISCOVERY_COMPLETED -> {
          disconnect()
        }
      }
    }

    if (!message.shouldRetry()) {
      loge(TAG, "Exceeded max retry for $message.")
      gattState.handleError()
      return
    }

    if (!handler.sendMessageDelayed(message, delay.plus(message.retryDelay()).toMillis())) {
      loge(TAG, "Could not send $message to handler.")
      gattState.handleError()
    }
  }

  private fun Message.shouldRetry(): Boolean {
    val what = this.what

    // No retry to handle GATT callback messages.
    when (what) {
      MSG_ON_CONNECTION_STATE_CHANGE,
      MSG_ON_MTU_CHANGED,
      MSG_ON_SERVICES_DISCOVERED -> return true
    }

    val count = retryCount.getOrPut(what) { 0 }
    val shouldRetry = count < MAX_RETRY_COUNT

    if (shouldRetry) {
      if (count > 0) {
        logi(TAG, "Retrying message $this for $count time.")
      }
      retryCount[what] = count + 1
    }
    return shouldRetry
  }

  private fun Message.retryDelay(): Duration {
    val count = retryCount.getOrPut(this.what) { 0 }
    return RETRY_DELAY.multipliedBy(count.toLong())
  }

  /**
   * Handles [BluetoothGattCallback.onConnectionStateChange].
   *
   * Expects [Message.obj] to be a [OnConnectionStateChangeInput].
   */
  private fun handleOnConnectionStateChange(message: Message) {
    val (status, newState) = message.obj as OnConnectionStateChangeInput

    logi(
      TAG,
      "handleOnConnectionStateChange: internal $gattState; status: $status; newState: $newState.",
    )

    if (status == BluetoothGatt.GATT_SUCCESS) {
      // Happy path.
      handleConnectionState(newState)
      return
    }

    if (newState != BluetoothProfile.STATE_DISCONNECTED) {
      // This is an unexpected case. Log; then disconnect to ensure a clean state.
      logwtf(TAG, "Connection $status was not success but did not disconnect: $newState.")
      disconnect()
      return
    }

    // Status is not SUCCESS, and newState is DISCONNECTED.
    logi(TAG, "GATT disconnected with non-success status $status; closing gatt.")
    // Order of handling matters here: we need to close the current gatt before retrying connection.
    closeGatt()

    when (gattState) {
      GattState.DISCONNECTED -> {
        // We haven't establish a connection - retry.
        sendHandlerMessage(handler.obtainMessage(MSG_CONNECT_GATT))
      }
      GattState.CONNECTED,
      GattState.RETRIEVING_NAME -> {
        // We established the GATT connection but have not completed service discovery.
        notifyConnectionFailed()
      }
      GattState.DISCOVERY_COMPLETED -> {
        // If GATT has been configured, we've notified clients about established connection.
        // Now notify them about the disconnection.
        notifyDisconnection()
      }
    }
    gattState = GattState.DISCONNECTED
  }

  private fun notifyConnectionFailed() {
    connectionCallbacks.forEach { it.onConnectionFailed() }
  }

  private fun notifyDisconnection() {
    connectionCallbacks.forEach { it.onDisconnected() }
  }

  private fun handleConnectionState(newState: Int) {
    when (newState) {
      BluetoothProfile.STATE_DISCONNECTED -> {
        logi(TAG, "Gatt status success; new state disconnected; closing gatt.")
        gattState = GattState.DISCONNECTED
        closeGatt()
        notifyDisconnection()
      }
      BluetoothProfile.STATE_CONNECTED -> {
        logi(TAG, "GATT connected. Requesting MTU.")
        gattState = GattState.CONNECTED
        requestMtu()
      }
      else -> logi(TAG, "Bluetooth GATT connection state changed to $newState.")
    }
  }

  private fun requestMtu(mtu: Int = defaultMtu) {
    val message = handler.obtainMessage(MSG_REQUEST_MTU, /* arg1= */ mtu, /* arg2=no-op */ 0)
    sendHandlerMessage(message)
  }

  /**
   * Initiates request for MTU.
   *
   * Expects [message.arg1] to be the MTU size to request.
   */
  private fun handleRequestMtu(message: Message) {
    val mtuSize = message.arg1
    logi(TAG, "Requesting MTU of size $mtuSize")

    // [requestMtu] will eventually result in an [onMtuChanged] callback.
    if (!gatt.requestMtu(mtuSize)) {
      logw(TAG, "Request to change MTU to $mtuSize could not be initiated. Retrying.")
      requestMtu(mtuSize)
      return
    }

    // Requesting MTU size sometimes does not trigger onMtuChanged() callback,
    // thus breaking the connection flow. Use a delayed message to recover from this issue.
    // See b/149106658.
    scheduleSkipOnMtuChangedCallback()
  }

  private fun scheduleSkipOnMtuChangedCallback() {
    logi(TAG, "Posting delayed message to skip MTU callback.")

    val message = handler.obtainMessage(MSG_SKIP_MTU_CALLBACK)
    sendHandlerMessage(message, DISCOVER_SERVICES_DELAY)
  }

  /**
   * Handles [BluetoothGattCallback.onMtuChanged].
   *
   * Expects [Message.obj] to be a [OnMtuChangedInput].
   */
  private fun handleOnMtuChanged(message: Message) {
    val (mtu, status) = message.obj as OnMtuChangedInput

    logi(TAG, "handleOnMtuChanged: mtu: $mtu, status: $status")

    if (status != BluetoothGatt.GATT_SUCCESS) {
      loge(TAG, "handleOnMtuChanged: status is $status.")
      requestMtu()
      return
    }

    // Clear the delayed message for skipping mtu callback.
    handler.removeMessages(MSG_SKIP_MTU_CALLBACK).also {
      logi(TAG, "Received handleOnMtuChanged; removing MSG_SKIP_MTU_CALLBACK from handler.")
    }

    maxWriteSize = mtu - ATT_PAYLOAD_RESERVED_BYTES

    requestDiscoverServices()
  }

  /**
   * Sends a [Message] for [BluetoothGatt.discoverServices].
   *
   * Message may be sent with a delay based on device bond state and device build version.
   */
  private fun requestDiscoverServices(delay: Duration = Duration.ZERO) {
    val message = handler.obtainMessage(MSG_DISCOVER_SERVICES)
    sendHandlerMessage(message, delay = delay)
  }

  /**
   * Initiates GATT service discovery.
   *
   * Expects [Message.obj] to be a [BluetoothGatt].
   */
  private fun handleDiscoverServices() {
    if (!gatt.discoverServices()) {
      logi(TAG, "Could not request GATT services discovery. Retrying.")
      requestDiscoverServices()
    }
  }

  /**
   * Handles [BluetoothGattCallback.onServicesDiscovered].
   *
   * Expects [Message.obj] to be a [OnServicesDiscoveredInput].
   */
  private fun handleOnServicesDiscovered(message: Message) {
    val status = message.arg1

    logi(TAG, "handleOnServicesDiscovered: status: $status")

    if (gattState == GattState.DISCOVERY_COMPLETED) {
      logi(TAG, "handleOnServicesDiscovered: service discovery already completed. Ignoring.")
      return
    }

    if (status != BluetoothGatt.GATT_SUCCESS) {
      loge(TAG, "handleOnServicesDiscovered: status is $status.")
      refreshAndDiscoverServices()
      return
    }

    val service = gatt.getService(serviceUuid)
    if (service == null) {
      loge(TAG, "GATT does not contain service $serviceUuid.")
      refreshAndDiscoverServices()
      return
    }

    serverWriteCharacteristic = service.getCharacteristic(serverWriteCharacteristicUuid)
    clientWriteCharacteristic = service.getCharacteristic(clientWriteCharacteristicUuid)
    if (serverWriteCharacteristic == null || clientWriteCharacteristic == null) {
      loge(
        TAG,
        "handleOnServicesDiscovered: GATT service $serviceUuid missing write " +
          "characteristic. Server characteristic: $serverWriteCharacteristic; " +
          "client characteristic: $clientWriteCharacteristic.",
      )
      refreshAndDiscoverServices()
      return
    }

    clientWriteCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    // Enable write notification.
    serverWriteCharacteristic?.let { characteristic ->
      if (!gatt.setCharacteristicNotification(characteristic, isEnabled = true)) {
        loge(TAG, "Could not enable notification for server write $characteristic in $serviceUuid.")
        refreshAndDiscoverServices()
        return
      }

      // TODO: always write descriptor; retrieve device name in onDescriptorWrite().
      // The default IHU apk does not set this descriptor on write characteristic. We need to update
      // the apk in gerrit before changing phone behavior to stay compatible.
      val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR)
      if (descriptor == null) {
        logi(
          TAG,
          "Characteristic ${characteristic.uuid} does not have descriptor. Retrieving device name.",
        )
        retrieveDeviceName()
      } else {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        logi(TAG, "Enabling notification of $characteristic by writing $descriptor.")
        if (!gatt.writeDescriptor(descriptor)) {
          loge(
            TAG,
            "Could not write to descriptor of characteristic ${characteristic.uuid}. " +
              "Retrieving device name.",
          )
          retrieveDeviceName()
        }
      }
    }

    if (!gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)) {
      loge(TAG, "Could not request connection priority. Continuing.")
    }
  }

  /**
   * Issues a request to read the remote device name out of the GAP service.
   *
   * This is needed because Android will cache the name of the [BluetoothDevice]. This name is the
   * one at the time of advertising, but the device might have changed its name afterwards. So an
   * explicit read is required to obtain the name.
   *
   * If there is any error with device name retrieval, then this method ensures that the connection
   * process continues without it. There is always a fallback name in [deviceName].
   */
  private fun retrieveDeviceName() {
    gattState = GattState.RETRIEVING_NAME

    val gapService = gatt.getService(GENERIC_ACCESS_PROFILE_UUID)
    if (gapService == null) {
      loge(TAG, "Generic Access Service is null. Cannot retrieve device name.")
      notifyDiscoveryComplete()
      return
    }

    val deviceNameCharacteristic = gapService.getCharacteristic(DEVICE_NAME_UUID)
    if (deviceNameCharacteristic == null) {
      loge(TAG, "Device Name Characteristic is null. Cannot retrieve device name")
      notifyDiscoveryComplete()
      return
    }

    logi(TAG, "Issuing request to read device name.")
    gatt.readCharacteristic(deviceNameCharacteristic)

    // Set up a timeout for the read request so that the connection is not stopped by a failure
    // to retrieve the name.
    handler.postDelayed(notifyDiscoveryCompleteRunnable, RETRIEVE_NAME_DELAY.toMillis())
  }

  private fun notifyDiscoveryComplete() {
    if (gattState == GattState.DISCOVERY_COMPLETED) {
      logw(TAG, "Call to notify service discovery complete, but has happened already. Ignoring.")
      return
    }
    gattState = GattState.DISCOVERY_COMPLETED

    logi(TAG, "Service discovery has completed. Notifying callbacks.")
    connectionCallbacks.forEach { it.onConnected() }
  }

  private fun refreshAndDiscoverServices() {
    loge(TAG, "Service discovery prerequisite not met. Refreshing cache then retrying.")
    gatt.refresh()
    requestDiscoverServices(REFRESH_DELAY)
  }

  private fun handleSkipMtuCallback() {
    logw(
      TAG,
      "Did not receive onMtuChanged() callback in allotted time; setting maxWriteSize based " +
        "on requested $defaultMtu.",
    )
    maxWriteSize = defaultMtu - ATT_PAYLOAD_RESERVED_BYTES

    requestDiscoverServices()
  }

  private fun handleReadCharacteristic(message: Message) {
    val characteristicUuid = message.obj as? UUID
    if (characteristicUuid == null) {
      loge(TAG, "$message must contain characteristic UUID. Ignored.")
      retrieveAdvertisedDataContinuation?.resume(null)
      return
    }

    val service = gatt.getService(serviceUuid)
    if (service == null) {
      loge(TAG, "Could not read characteristic; service $serviceUuid is null. Ignored.")
      retrieveAdvertisedDataContinuation?.resume(null)
      return
    }

    val characteristic = service.getCharacteristic(characteristicUuid)
    if (characteristic == null) {
      loge(TAG, "Could not read characteristic; $characteristicUuid is null. Ignored.")
      retrieveAdvertisedDataContinuation?.resume(null)
      return
    }

    gatt.readCharacteristic(characteristic)
  }

  internal val gattCallback: GattHandleCallback =
    object : GattHandleCallback {
      override fun onConnectionStateChange(status: Int, newState: Int) {
        val input = OnConnectionStateChangeInput(status, newState)
        val message = handler.obtainMessage(MSG_ON_CONNECTION_STATE_CHANGE, input)
        sendHandlerMessage(message)
      }

      override fun onMtuChanged(mtu: Int, status: Int) {
        val input = OnMtuChangedInput(mtu, status)
        val message = handler.obtainMessage(MSG_ON_MTU_CHANGED, input)
        sendHandlerMessage(message)
      }

      override fun onServicesDiscovered(status: Int) {
        val message = handler.obtainMessage(MSG_ON_SERVICES_DISCOVERED, /* obj= */ status)
        sendHandlerMessage(message)
      }

      override fun onCharacteristicChanged(characteristic: BluetoothGattCharacteristic) {
        // Prevent a race condition with future notifications by copying the value out of the
        // characteristic prior to notifying callbacks.
        val value = characteristic.value.copyOf()
        logi(TAG, "Received data from service $serviceUuid")

        handler.post { messageCallbacks.forEach { it.onMessageReceived(value) } }
      }

      override fun onCharacteristicWrite(characteristic: BluetoothGattCharacteristic, status: Int) {
        if (characteristic.uuid != clientWriteCharacteristicUuid) {
          loge(TAG, "onCharacteristicWrite: characteristic is ${characteristic.uuid}. Ignoring.")
          return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
          loge(TAG, "onCharacteristicWrite failed with status $status.")
          disconnect()
          return
        }

        logi(TAG, "onCharacteristicWrite: sent ${characteristic.value.size} bytes")

        messageCallbacks.forEach { it.onMessageSent(characteristic.value) }
      }

      override fun onCharacteristicRead(characteristic: BluetoothGattCharacteristic, status: Int) {
        logi(TAG, "onCharacteristicRead: ${characteristic.uuid}; status is $status.")
        when (characteristic.service.uuid) {
          GENERIC_ACCESS_PROFILE_UUID -> readDeviceName(characteristic, status)
          serviceUuid -> readRetrievingAdvertisedData(characteristic, status)
          else -> {
            logw(
              TAG,
              "OnCharacteristicRead of an unknown service ${characteristic.service.uuid}. Ignored.",
            )
          }
        }
      }

      private fun readDeviceName(characteristic: BluetoothGattCharacteristic, status: Int) {
        if (characteristic.uuid != DEVICE_NAME_UUID) {
          logw(
            TAG,
            """
          |Received characteristic read for ${characteristic.uuid}
          |while expecting $DEVICE_NAME_UUID. Ignored.
          """
              .trimMargin(),
          )
          return
        }

        handler.removeCallbacks(notifyDiscoveryCompleteRunnable)

        if (status == BluetoothGatt.GATT_SUCCESS) {
          val deviceName = characteristic.getStringValue(0)
          logi(TAG, "Retrieved remote device name: $deviceName")

          this@BluetoothGattManager.deviceName = deviceName
        } else {
          loge(TAG, "Reading GAP for device name failed. Status: $status. Continuing.")
        }

        if (gattState != GattState.DISCOVERY_COMPLETED) {
          notifyDiscoveryComplete()
        }
      }

      private fun readRetrievingAdvertisedData(
        characteristic: BluetoothGattCharacteristic,
        status: Int,
      ) {
        if (characteristic.uuid != advertiseDataCharacteristicUuid) {
          logw(
            TAG,
            """
          |Received characteristic read for ${characteristic.uuid}
          |while expecting $advertiseDataCharacteristicUuid. Ignored.
          """
              .trimMargin(),
          )
          return
        }

        val advertiseData =
          if (status == BluetoothGatt.GATT_SUCCESS) {
            characteristic.value.also { logi(TAG, "Retrieved advertise data: ${it.toHexString()}") }
          } else {
            loge(TAG, "Retrieveing advertise data failed with $status. Continuing.")
            null
          }
        retrieveAdvertisedDataContinuation?.resume(advertiseData)
      }

      override fun onDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int) {
        // Only expect a callback for enabling server write characteristic notification by writing
        // its descriptor.
        if (descriptor.characteristic.uuid != serverWriteCharacteristicUuid) {
          loge(
            TAG,
            "onDescriptorWrite: unexpected callback for ${descriptor.characteristic.uuid}. Ignored.",
          )
          return
        }

        // Failing to write descriptor means GATT couldn't enable characteristic notification.
        // But GATT was able to receive callback without notification so this failure should not
        // affect exchanging messages.
        if (status != BluetoothGatt.GATT_SUCCESS) {
          loge(TAG, "onDescriptorWrite: ${descriptor.characteristic.uuid} is $status. Continuing.")
        }

        retrieveDeviceName()
      }

      override fun onServiceChanged() {
        loge(TAG, "onServiceChanged: current GATT state is $gattState")
        when (gattState) {
          GattState.CONNECTED,
          GattState.RETRIEVING_NAME -> {
            // We may receive this callback if GATT connection triggers a classic BT pairing
            // attempt. If the service changes during connection, re-discover services.
            logi(
              TAG,
              "Received onServiceChanged during GATT connection. Requesting service discovery.",
            )
            requestDiscoverServices()
          }
          GattState.DISCONNECTED,
          GattState.DISCOVERY_COMPLETED -> {
            loge(TAG, "Received onServiceChanged callback at $gattState. Disconnecting.")
            // The suggested behavior by Android is to re-discover services.
            // But we don't expect the GATT services to change. We'd get this callback when IHU
            // disconnects (see b/241451594), so disconnect instead.
            disconnect()
          }
        }
      }
    }

  companion object {
    private const val TAG = "BluetoothGattManager"

    private const val MSG_CONNECT_GATT = 1
    private const val MSG_ON_CONNECTION_STATE_CHANGE = 2
    private const val MSG_REQUEST_MTU = 3
    private const val MSG_ON_MTU_CHANGED = 4
    private const val MSG_DISCOVER_SERVICES = 5
    private const val MSG_ON_SERVICES_DISCOVERED = 6
    private const val MSG_SKIP_MTU_CALLBACK = 7
    // The message should also contain an UUID of the characteristic to be read.
    private const val MSG_READ_CHARACTERISTIC = 8

    // Arbitrary retry count.
    internal const val MAX_RETRY_COUNT = 3

    // Measured average time to request MTU is around 0.7 seconds.
    private val DISCOVER_SERVICES_DELAY = Duration.ofSeconds(2)
    private val RETRIEVE_NAME_DELAY = Duration.ofSeconds(2)
    private val REFRESH_DELAY = Duration.ofMillis(3)
    private val RETRY_DELAY = Duration.ofSeconds(1)

    /**
     * Reserved bytes for an ATT write request payload.
     *
     * The attribute protocol uses 3 bytes to encode the command type and attribute ID. These bytes
     * need to be subtracted from the reported MTU size and the resulting value will represent the
     * total amount of bytes that can be sent in a write.
     *
     * On Android 14 (U) the default MTU size is 517 bytes. For GATT writes that are longer than 255
     * bytes, we need to reserve 5 bytes. See:
     * https://developer.android.com/about/versions/14/behavior-changes-all#mtu-set-to-517
     */
    private const val ATT_PAYLOAD_RESERVED_BYTES = 5

    /**
     * The UUID of the Generic Access Profile, which contains services common to all BLE
     * connections.
     *
     * This UUID was obtained from Bluetooth specifications
     * [here](https://www.bluetooth.com/specifications/gatt/services/).
     */
    internal val GENERIC_ACCESS_PROFILE_UUID =
      UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")

    /**
     * The UUID of the standard characteristic on a BLE device that contains its name.
     *
     * This UUID was obtained from the Bluetooth specifications
     * [here](https://www.bluetooth.com/specifications/gatt/characteristics/).
     */
    internal val DEVICE_NAME_UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    /**
     * The UUID of client characteristic configuration descriptor, CCCD.
     *
     * This UUID was obtained from the Bluetooth specifications
     * https://www.bluetooth.com/specifications/gatt/descriptors/
     */
    internal val CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR =
      UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * The UUID of characteristic that contains advertise data.
     *
     * This fixed UUID is companion device specific. It must match the value used by IHU.
     */
    internal val ADVERTISE_DATA_CHARACTERITIC_UUID =
      UUID.fromString("24289b40-af40-4149-a5f4-878ccff87566")

    /**
     * The maximum MTU to use.
     *
     * This value is arbitrarily picked, and does not guarantee success on a particular hardware
     * platform.
     *
     * Use [setDefaultMtu] to set a suitable value based on your hardware and bluetooth
     * implementation.
     *
     * On Android 14 (U) the default MTU size will be 517 bytes.
     * https://developer.android.com/about/versions/14/behavior-changes-all#mtu-set-to-517
     */
    internal const val MAXIMUM_MTU = 512

    internal const val SHARED_PREF = "com.google.android.libraries.car.trustagent.ConnectionManager"
    private const val KEY_DEFAULT_MTU = "default_mtu"

    /**
     * Sets the default MTU size.
     *
     * The value [BluetoothGattManager] defaults to when it didn't receive onMtuChanged() callback.
     */
    fun setDefaultMtu(context: Context, mtu: Int): Boolean {
      val sharedPref =
        context.getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE)
          ?: run {
            loge(
              TAG,
              "Set default MTU: could not retrieve shared preferences of name $SHARED_PREF.",
            )
            return false
          }
      sharedPref.edit().putInt(KEY_DEFAULT_MTU, mtu).apply()
      return true
    }

    /** Retrieves the MTU to be used for GATT connection. */
    private fun getDefaultMtu(context: Context): Int {
      val sharedPref =
        context.getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE)
          ?: run {
            loge(
              TAG,
              "Could not retrieve shared preference of name $SHARED_PREF. Using $MAXIMUM_MTU",
            )
            return MAXIMUM_MTU
          }
      return sharedPref.getInt(KEY_DEFAULT_MTU, MAXIMUM_MTU)
    }
  }
}

/** Values should be set based on [BluetoothGattCallback.onConnectionStateChange]. */
private data class OnConnectionStateChangeInput(val status: Int, val newState: Int)

/** Values should be set based on [BluetoothGattCallback.OnMtuChanged]. */
private data class OnMtuChangedInput(val mtu: Int, val status: Int)
