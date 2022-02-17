// Copyright 2022 Google LLC
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

import android.Manifest.permission
import android.app.Activity
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.libraries.car.trustagent.testutils.Base64CryptoHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collection of unit tests for [AssociationManager] where Bluetooth permissions are not granted.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class AssociationManagerMissingBluetoothPermissionsTest {
  // Grant only non-Bluetooth permissions.
  @get:Rule
  val grantPermissionRule: GrantPermissionRule =
    GrantPermissionRule.grant(
      permission.ACCESS_FINE_LOCATION,
      permission.ACCESS_BACKGROUND_LOCATION,
    )

  private val testDispatcher = TestCoroutineDispatcher()
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var database: ConnectedCarDatabase
  private lateinit var bleManager: FakeBleManager
  private lateinit var associationManager: AssociationManager
  private lateinit var associationCallback: AssociationManager.AssociationCallback

  @Before
  fun setUp() {
    // Using directExecutor to ensure that all operations happen on the main thread and allows for
    // tests to wait until the operations are done before continuing. Without this, operations can
    // leak and interfere between tests. See b/153095973 for details.
    database =
      Room.inMemoryDatabaseBuilder(context, ConnectedCarDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(directExecutor())
        .build()

    val associatedCarManager = AssociatedCarManager(context, database, Base64CryptoHelper())

    bleManager = spy(FakeBleManager())
    associationCallback = mock()

    associationManager =
      AssociationManager(context, associatedCarManager, bleManager).apply {
        registerAssociationCallback(associationCallback)
        coroutineContext = testDispatcher
      }
  }

  @After
  fun after() {
    database.close()
    testDispatcher.cleanupTestCoroutines()
  }

  @Test
  fun startSppDiscovery_returnsFalse() {
    bleManager.isEnabled = true

    assertThat(associationManager.startSppDiscovery()).isFalse()
    assertThat(associationManager.isSppDiscoveryStarted).isFalse()
  }

  @Test
  fun startCdmDiscovery_returnsFalse() {
    associationManager.isSppEnabled = false

    val discoveryRequest = DiscoveryRequest.Builder(Activity()).build()
    assertThat(associationManager.startCdmDiscovery(discoveryRequest, mock())).isFalse()
  }

  @Test
  fun associate_notifiesCallbackOfFailure() {
    associationManager.associate(mock<DiscoveredCar>())
    verify(associationCallback).onAssociationFailed()
  }
}
