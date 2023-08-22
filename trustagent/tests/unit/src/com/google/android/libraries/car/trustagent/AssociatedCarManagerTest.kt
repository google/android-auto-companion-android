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
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.testutils.Base64CryptoHelper
import com.google.android.libraries.car.trustagent.testutils.FakeMessageStream
import com.google.android.libraries.car.trustagent.testutils.FakeSecretKey
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

// Randomly generated UUID.
private val TEST_ID_1 = UUID.fromString("8a16e891-d4ad-455d-8194-cbc2dfbaebdf")
private val TEST_ID_2 = UUID.fromString("8a16e892-d4ad-455d-8194-cbc2dfbaebdf")
private val TEST_ID_3 = UUID.fromString("8a16e893-d4ad-455d-8194-cbc2dfbaebdf")
private val ADAPTER = BluetoothAdapter.getDefaultAdapter()
private val TEST_MAC_ADDRESS_1 = "11:22:33:44:55:66"
private val TEST_MAC_ADDRESS_2 = "AA:BB:CC:DD:EE:FF"
private val TEST_BLUETOOTH_DEVICE_1 = ADAPTER.getRemoteDevice(TEST_MAC_ADDRESS_1)
private val TEST_BLUETOOTH_DEVICE_2 = ADAPTER.getRemoteDevice(TEST_MAC_ADDRESS_2)
private const val TEST_NAME_1 = "test_name_1"
private const val TEST_NAME_2 = "test_name_2"
private val TEST_IDENTIFICATION_KEY = FakeSecretKey()
private val TEST_STREAM = FakeMessageStream()

/** Tests for [AssociatedCarManager]. */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class AssociatedCarManagerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun isEqualAssociatedCar(actualValue: AssociatedCar?, expected: AssociatedCar?): Boolean {
    if (actualValue == null || expected == null) {
      return false
    }
    return actualValue.deviceId == expected.deviceId &&
      actualValue.name == expected.name &&
      actualValue.identificationKey.encoded.contentEquals(expected.identificationKey.encoded)
  }
  private val associatedCarCorrespondence =
    Correspondence.from(::isEqualAssociatedCar, "is equivalent to")

  private lateinit var database: ConnectedCarDatabase
  private lateinit var associatedCarManager: AssociatedCarManager

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

    associatedCarManager = AssociatedCarManager(context, database, Base64CryptoHelper())
  }

  @After
  fun after() {
    database.close()
  }

  @Test
  fun testAddCar_retrievesSuccessfully() {
    runBlocking {
      val car = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      associatedCarManager.add(car)

      val expectedCar =
        AssociatedCar(car.deviceId, car.name, TEST_MAC_ADDRESS_1, TEST_IDENTIFICATION_KEY)
      assertThat(associatedCarManager.retrieveAssociatedCars())
        .comparingElementsUsing(associatedCarCorrespondence)
        .containsExactly(expectedCar)
    }
  }

  @Test
  fun testAddCar_replacesExistingCar() {
    runBlocking {
      val car = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      associatedCarManager.add(car)

      val newName = "newName"
      val replacementCar = mockCar(TEST_ID_1, newName, TEST_BLUETOOTH_DEVICE_1)
      associatedCarManager.add(replacementCar)

      val expectedCar =
        AssociatedCar(
          replacementCar.deviceId,
          replacementCar.name,
          TEST_MAC_ADDRESS_1,
          TEST_IDENTIFICATION_KEY
        )
      assertThat(associatedCarManager.retrieveAssociatedCars())
        .comparingElementsUsing(associatedCarCorrespondence)
        .containsExactly(expectedCar)
    }
  }

  @Test
  fun testAddCar_updatesIsAssociated() {
    runBlocking {
      val car = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      associatedCarManager.add(car)

      assertThat(associatedCarManager.loadIsAssociated()).isTrue()
    }
  }

  @Test
  fun testAddCar_updatesIsAssociated_perCar() {
    runBlocking {
      val car = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      associatedCarManager.add(car)

      assertThat(associatedCarManager.loadIsAssociated(TEST_ID_1)).isTrue()
    }
  }

  @Test
  fun testAddMultipleCars_retrievesSuccessfully() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)

      val expectedCar1 =
        AssociatedCar(car1.deviceId, car1.name, TEST_MAC_ADDRESS_1, TEST_IDENTIFICATION_KEY)
      val expectedCar2 =
        AssociatedCar(car2.deviceId, car2.name, TEST_MAC_ADDRESS_2, TEST_IDENTIFICATION_KEY)
      assertThat(associatedCarManager.retrieveAssociatedCars())
        .comparingElementsUsing(associatedCarCorrespondence)
        .containsExactly(expectedCar1, expectedCar2)
    }
  }

  @Test
  fun testAddMultipleCars_updatesIsAssociated() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)

      assertThat(associatedCarManager.loadIsAssociated()).isTrue()
    }
  }

  @Test
  fun testClear_removesUnrecognizedCar_returnFalse() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      assertThat(associatedCarManager.clear(car2.deviceId)).isFalse()
    }
  }

  @Test
  fun testClear_removesSingleCar() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)
      assertThat(associatedCarManager.clear(car2.deviceId)).isTrue()

      // Only car 1 should remain
      val expectedCar1 =
        AssociatedCar(car1.deviceId, car1.name, TEST_MAC_ADDRESS_1, TEST_IDENTIFICATION_KEY)
      assertThat(associatedCarManager.retrieveAssociatedCars())
        .comparingElementsUsing(associatedCarCorrespondence)
        .containsExactly(expectedCar1)
    }
  }

  @Test
  fun testClear_updatesIsAssociated() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)
      assertThat(associatedCarManager.clear(car2.deviceId)).isTrue()

      // Only one car removed, so should still be associated.
      assertThat(associatedCarManager.loadIsAssociated()).isTrue()
    }
  }

  @Test
  fun testClear_updatesIsAssociated_forSingleCar() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)
      assertThat(associatedCarManager.clear(car2.deviceId)).isTrue()

      assertThat(associatedCarManager.loadIsAssociated(TEST_ID_1)).isTrue()
      assertThat(associatedCarManager.loadIsAssociated(TEST_ID_2)).isFalse()
    }
  }

  @Test
  fun testClearAll_removesAllCars() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)
      associatedCarManager.clearAll()

      assertThat(associatedCarManager.retrieveAssociatedCars()).isEmpty()
    }
  }

  @Test
  fun testClearAll_updatesIsAssociated() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)
      associatedCarManager.clearAll()

      assertThat(associatedCarManager.loadIsAssociated()).isFalse()
    }
  }

  @Test
  fun testClearAll_updatesIsAssociated_perCar() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)
      associatedCarManager.clearAll()

      assertThat(associatedCarManager.loadIsAssociated(TEST_ID_1)).isFalse()
      assertThat(associatedCarManager.loadIsAssociated(TEST_ID_2)).isFalse()
    }
  }

  @Test
  fun testRename_renamesCorrectCar() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)

      val newName = "newName"
      assertThat(associatedCarManager.rename(car1.deviceId, newName)).isTrue()

      val expectedCar1 =
        AssociatedCar(car1.deviceId, newName, TEST_MAC_ADDRESS_1, TEST_IDENTIFICATION_KEY)
      val expectedCar2 =
        AssociatedCar(car2.deviceId, car2.name, TEST_MAC_ADDRESS_2, TEST_IDENTIFICATION_KEY)
      assertThat(associatedCarManager.retrieveAssociatedCars())
        .comparingElementsUsing(associatedCarCorrespondence)
        .containsExactly(expectedCar1, expectedCar2)
    }
  }

  @Test
  fun testRename_ignoresNonExistentCar() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)

      val newName = "newName"
      assertThat(associatedCarManager.rename(TEST_ID_3, newName)).isFalse()

      // Both cars should still retain their names.
      val expectedCar1 =
        AssociatedCar(car1.deviceId, car1.name, TEST_MAC_ADDRESS_1, TEST_IDENTIFICATION_KEY)
      val expectedCar2 =
        AssociatedCar(car2.deviceId, car2.name, TEST_MAC_ADDRESS_2, TEST_IDENTIFICATION_KEY)
      assertThat(associatedCarManager.retrieveAssociatedCars())
        .comparingElementsUsing(associatedCarCorrespondence)
        .containsExactly(expectedCar1, expectedCar2)
    }
  }

  @Test
  fun testRename_ignoresEmptyName() {
    runBlocking {
      val car1 = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      val car2 = mockCar(TEST_ID_2, TEST_NAME_2, TEST_BLUETOOTH_DEVICE_2)
      associatedCarManager.add(car1)
      associatedCarManager.add(car2)

      val emptyName = ""
      assertThat(associatedCarManager.rename(car1.deviceId, emptyName)).isFalse()

      // Both cars should still retain their names.
      val expectedCar1 =
        AssociatedCar(car1.deviceId, car1.name, TEST_MAC_ADDRESS_1, TEST_IDENTIFICATION_KEY)
      val expectedCar2 =
        AssociatedCar(car2.deviceId, car2.name, TEST_MAC_ADDRESS_2, TEST_IDENTIFICATION_KEY)
      assertThat(associatedCarManager.retrieveAssociatedCars())
        .comparingElementsUsing(associatedCarCorrespondence)
        .containsExactly(expectedCar1, expectedCar2)
    }
  }

  @Test
  fun testGenerateKey_happyPath() {
    associatedCarManager.generateKey()
  }

  @Test
  fun testLoadName_loadCorrectCarName() {
    runBlocking {
      val car = mockCar(TEST_ID_1, TEST_NAME_1, TEST_BLUETOOTH_DEVICE_1)
      associatedCarManager.add(car)

      assertThat(associatedCarManager.loadName(TEST_ID_1)).isEqualTo(TEST_NAME_1)
    }
  }

  private fun mockCar(id: UUID, mockName: String, device: BluetoothDevice): Car = mock {
    on { deviceId } doReturn id
    on { bluetoothDevice } doReturn device
    on { name } doReturn mockName
    on { identificationKey } doReturn TEST_IDENTIFICATION_KEY
    on { messageStream } doReturn TEST_STREAM
  }
}
