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

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.AssociationManager
import com.google.android.libraries.car.trustagent.ConnectionManager
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.testutils.createScanRecord
import com.google.android.libraries.car.trustagent.testutils.createScanResult
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verifyBlocking
import com.nhaarman.mockitokotlin2.whenever
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextWrapper

@RunWith(AndroidJUnit4::class)
class PhoneSyncReceiverTest {

  private lateinit var receiver: PhoneSyncReceiver

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val mockConnectionManager: ConnectionManager = mock()
  private val mockAssociationManager: AssociationManager = mock()

  @Before
  fun setUp() {
    whenever(mockAssociationManager.isBluetoothEnabled).doReturn(true)
    mockConnectionManager.stub {
      onBlocking { filterForConnectableCars(any()) } doReturn listOf(SCAN_RESULT)
    }

    AssociationManager.instance = mockAssociationManager
    ConnectionManager.instance = mockConnectionManager

    receiver = PhoneSyncReceiver()
  }

  @Test
  fun passScanResultToStartedService() {
    val intent = createIntent(scanResult = SCAN_RESULT)

    receiver.onReceive(context, intent)

    val shadowContextWrapper = Shadow.extract<ShadowContextWrapper>(context)
    val startedServiceIntent = shadowContextWrapper.nextStartedService
    assertThat(startedServiceIntent).isNotNull()
    val results =
      startedServiceIntent.getParcelableArrayListExtra<ScanResult>(
        PhoneSyncBaseService.EXTRA_SCAN_DEVICES
      )
    assertThat(results).containsExactly(SCAN_RESULT)
  }

  @Test
  fun missingReceiverIntent_ignore() {
    val intent = createIntent(receiverIntent = null)
    receiver.onReceive(context, intent)

    verifyBlocking(mockConnectionManager, never()) { filterForConnectableCars(any()) }
  }

  @Test
  fun scanError_ignore() {
    val intent = createIntent(errorCode = ScanCallback.SCAN_FAILED_ALREADY_STARTED)

    receiver.onReceive(context, intent)

    verifyBlocking(mockConnectionManager, never()) { filterForConnectableCars(any()) }
  }

  @Test
  fun missingScanResult_ignore() {
    val intent = createIntent(scanResult = null)

    receiver.onReceive(context, intent)

    verifyBlocking(mockConnectionManager, never()) { filterForConnectableCars(any()) }
  }

  @Test
  fun bluetoothOff_ignore() {
    whenever(mockAssociationManager.isBluetoothEnabled).doReturn(false)
    val intent = createIntent()

    receiver.onReceive(context, intent)

    verifyBlocking(mockConnectionManager, never()) { filterForConnectableCars(any()) }
  }

  @Test
  fun scanResultFilteredOut_ignore() {
    mockConnectionManager.stub {
      onBlocking { filterForConnectableCars(any()) } doReturn emptyList()
    }
    val intent = createIntent()

    receiver.onReceive(context, intent)

    val shadowContextWrapper = Shadow.extract<ShadowContextWrapper>(context)
    assertThat(shadowContextWrapper.nextStartedService).isNull()
  }

  private fun createIntent(
    errorCode: Int = 0,
    scanResult: ScanResult? = SCAN_RESULT,
    receiverIntent: Intent? = Intent(context, FakeService::class.java),
  ): Intent {
    val intent = Intent().apply { putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, errorCode) }

    scanResult?.let { intent.putExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, arrayListOf(it)) }
    receiverIntent?.let { intent.putExtra(PhoneSyncReceiver.EXTRA_RECEIVER_INTENT, it) }

    return intent
  }

  class FakeService : PhoneSyncBaseService() {
    override val serviceRecipient = UUID.randomUUID()
    override fun createFeatureManagers() = emptyList<FeatureManager>()
    override fun onBind(intent: Intent): Binder = mock()
  }

  companion object {
    private val SCAN_RECORD =
      createScanRecord(
        name = "deviceName",
        serviceUuids = listOf(UUID.randomUUID()),
        serviceData = emptyMap()
      )

    private val SCAN_RESULT = createScanResult(SCAN_RECORD)
  }
}
