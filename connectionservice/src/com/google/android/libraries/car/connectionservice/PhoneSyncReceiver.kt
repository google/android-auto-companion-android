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

import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.trustagent.AssociationManager
import com.google.android.libraries.car.trustagent.ConnectionManager
import com.google.android.libraries.car.trustagent.api.PublicApi
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import kotlinx.coroutines.runBlocking

/** A receiver that is notified for scan results matching the filters. */
@PublicApi
open class PhoneSyncReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    logi(TAG, "New devices found")

    // Ignore the results if there is nothing to forward to.
    val receiverIntent = intent.getParcelableExtra(EXTRA_RECEIVER_INTENT) as? Intent
    if (receiverIntent == null) {
      loge(TAG, "Intent extra $EXTRA_RECEIVER_INTENT not set. Ignoring scan results.")
      return
    }

    // default value is 0 because BluetoothScanner errors are all positive.
    val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
    if (errorCode != 0) {
      loge(TAG, "Received scanner $errorCode from $intent.")
      return
    }

    val results =
      intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
    if (results == null) {
      loge(TAG, "Received null results in intent. Ignoring.")
      return
    }
    logi(TAG, "Received ${results.size} scan results from $intent.")

    // When Bluetooth is turned off, we stop scanning. But occasionally the BluetoothAdapter
    // would return a null scanner since Bluetooth is turned off, thus the BLE scanning could not be
    // stopped.
    // Ignore the scan results so the experience is consistent.
    if (!AssociationManager.getInstance(context).isBluetoothEnabled) {
      logi(TAG, "Received ScanResult when Bluetooth is off. Ignored.")
      return
    }

    val filteredResults =
      ArrayList(
        runBlocking { ConnectionManager.getInstance(context).filterForConnectableCars(results) }
      )
    if (filteredResults.isEmpty()) {
      logi(TAG, "No suitable devices in results.")
      return
    }

    context.startForegroundService(
      receiverIntent
        .putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, errorCode)
        .putParcelableArrayListExtra(PhoneSyncBaseService.EXTRA_SCAN_DEVICES, filteredResults)
    )
  }

  @PublicApi
  companion object {
    const val TAG = "PhoneSyncReceiver"

    const val SERVICE_REQUEST_CODE = 1

    const val ACTION_DEVICE_FOUND =
      "com.google.android.libraries.car.connectionservice.DEVICE_FOUND"

    @VisibleForTesting
    internal const val EXTRA_RECEIVER_INTENT =
      "com.google.android.libraries.car.connectionservice.RECEIVER_INTENT"

    /**
     * Creates a PendingIntent to this broadcast receiver.
     *
     * [receiverIntent] will be used to start the service when filtered result is available. The
     * service will be passed a list of devices as an `ArrayList` extra that is retrievable via the
     * name [PhoneSyncBaseService.EXTRA_SCAN_DEVICES].
     *
     * If there was a scanner error, results will be ignored. Otherwise the code is retrievable via
     * the name [BluetoothLeScanner.EXTRA_ERROR_CODE], with a value that matches the constant in
     * [BluetoothLeScanner].
     */
    // Specify return type because
    // - kotlin cannot infer type properly from framework API call;
    // - getBroadcast() only returns null when FLAG_NO_CREATE is supplied (not nullable here).
    @JvmStatic
    fun createPendingIntent(context: Context, receiverIntent: Intent): PendingIntent =
      PendingIntent.getBroadcast(
        context,
        SERVICE_REQUEST_CODE,
        createBroadcastIntent(context, receiverIntent),
        PendingIntent.FLAG_UPDATE_CURRENT
      )

    @JvmStatic
    fun createBroadcastIntent(context: Context, receiverIntent: Intent) =
      Intent(context, PhoneSyncReceiver::class.java)
        .setAction(ACTION_DEVICE_FOUND)
        .putExtra(EXTRA_RECEIVER_INTENT, receiverIntent)
  }
}
