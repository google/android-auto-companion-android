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

package com.google.android.libraries.car.trusteddevice.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private val TEST_CAR_ID1 = UUID.fromString("e0ee1a8c-5f03-4010-97ba-cd4d6a560caa")
private val TEST_CAR_ID2 = UUID.fromString("cd51c0a3-7250-461d-a4bd-9737f9daff09")
private val TEST_HANDLE = "handle".toByteArray(Charsets.UTF_8)
private val TEST_TOKEN = "token".toByteArray(Charsets.UTF_8)
private val TEST_STATE = "state".toByteArray(Charsets.UTF_8)

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class TrustedDeviceManagerStorageTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val mockClock: Clock = mock { on { instant() } doReturn Clock.systemUTC().instant() }
  private lateinit var manager: TrustedDeviceManagerStorage
  private lateinit var database: TrustedDeviceDatabase

  @Before
  fun setUp() {
    // Using directExecutor to ensure that all operations happen on the main thread and allows for
    // tests to wait until the operations are done before continuing. Without this, operations can
    // leak and interfere between tests. See b/153095973 for details.
    database =
      Room.inMemoryDatabaseBuilder(context, TrustedDeviceDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(directExecutor())
        .build()
    manager = TrustedDeviceManagerStorage(context, mockClock, database)
  }

  @After
  fun cleanUp() {
    database.close()
  }

  @Test
  fun containsCredential_noData_returnsFalse() {
    runBlocking { assertThat(manager.containsCredential(TEST_CAR_ID1)).isFalse() }
  }

  @Test
  fun containsCredential_onlyToken_returnsFalse() {
    runBlocking {
      manager.storeToken(TEST_TOKEN, TEST_CAR_ID1)

      assertThat(manager.containsCredential(TEST_CAR_ID1)).isFalse()
    }
  }

  @Test
  fun containsCredential_bothTokenAndHandle_returnsTrue() {
    runBlocking {
      manager.storeToken(TEST_TOKEN, TEST_CAR_ID1)
      manager.storeHandle(TEST_HANDLE, TEST_CAR_ID1)

      assertThat(manager.containsCredential(TEST_CAR_ID1)).isTrue()
    }
  }

  @Test
  fun getCredential_onlyToken_returnsNull() {
    runBlocking {
      manager.storeToken(TEST_TOKEN, TEST_CAR_ID1)

      assertThat(manager.getCredential(TEST_CAR_ID1)).isNull()
    }
  }

  @Test
  fun getCredential_bothTokenAndHandle_returnsCredential() {
    runBlocking {
      manager.storeToken(TEST_TOKEN, TEST_CAR_ID1)
      manager.storeHandle(TEST_HANDLE, TEST_CAR_ID1)

      val credential = manager.getCredential(TEST_CAR_ID1)
      assertThat(credential).isNotNull()

      credential?.let {
        assertThat(it.escrowToken.toByteArray()).isEqualTo(TEST_TOKEN)
        assertThat(it.handle.toByteArray()).isEqualTo(TEST_HANDLE)
      }
    }
  }

  @Test
  fun testStoringUnlockHistory_savesSingleDate() {
    runBlocking {
      val now = Clock.systemUTC().instant()
      whenever(mockClock.instant()).thenReturn(now)

      manager.recordUnlockDate(TEST_CAR_ID1)
      assertThat(manager.getUnlockHistory(TEST_CAR_ID1)).contains(now.inMillis())
    }
  }

  @Test
  fun testStoringUnlockHistory_savesMultipleDates() {
    runBlocking {
      val now = mockClock.instant()
      val earlier = mockClock.instant().minus(Duration.ofMinutes(5))
      whenever(mockClock.instant()).thenReturn(earlier).thenReturn(now)

      manager.recordUnlockDate(TEST_CAR_ID1)
      manager.recordUnlockDate(TEST_CAR_ID1)
      assertThat(manager.getUnlockHistory(TEST_CAR_ID1)).contains(now.inMillis())
      assertThat(manager.getUnlockHistory(TEST_CAR_ID1)).contains(earlier.inMillis())
    }
  }

  @Test
  fun testStoringUnlockHistory_oldDatesAreCleared() {
    runBlocking {
      val now = mockClock.instant()
      val oneMonthAgo = mockClock.instant().minus(Duration.ofDays(30))
      // First return an date for recording; then return now for comparison.
      whenever(mockClock.instant()).thenReturn(oneMonthAgo).thenReturn(now)

      manager.recordUnlockDate(TEST_CAR_ID1)
      assertThat(manager.getUnlockHistory(TEST_CAR_ID1)).isEmpty()
    }
  }

  @Test
  fun testClearAllUnlockHistory() {
    runBlocking {
      val now = Clock.systemUTC().instant()
      whenever(mockClock.instant()).thenReturn(now)

      manager.recordUnlockDate(TEST_CAR_ID1)
      manager.recordUnlockDate(TEST_CAR_ID2)

      manager.clearAllUnlockHistory()

      assertThat(manager.getUnlockHistory(TEST_CAR_ID1)).isEmpty()
      assertThat(manager.getUnlockHistory(TEST_CAR_ID2)).isEmpty()
    }
  }

  @Test
  fun testClearingTokenAndHandle() {
    runBlocking {
      manager.storeToken(TEST_TOKEN, TEST_CAR_ID1)
      manager.storeHandle(TEST_HANDLE, TEST_CAR_ID1)
      manager.recordUnlockDate(TEST_CAR_ID1)
      manager.storeFeatureState(TEST_STATE, TEST_CAR_ID1)

      manager.clearCredentials(TEST_CAR_ID1)

      assertThat(manager.containsCredential(TEST_CAR_ID1)).isFalse()
      assertThat(manager.getUnlockHistory(TEST_CAR_ID1)).isEmpty()
      assertThat(manager.getFeatureState(TEST_CAR_ID1)).isEqualTo(TEST_STATE)
    }
  }

  @Test
  fun testClearAll() {
    runBlocking {
      manager.storeToken(TEST_TOKEN, TEST_CAR_ID1)
      manager.storeHandle(TEST_HANDLE, TEST_CAR_ID1)
      manager.recordUnlockDate(TEST_CAR_ID1)
      manager.storeFeatureState(TEST_STATE, TEST_CAR_ID1)

      manager.clearAll()

      assertThat(manager.containsCredential(TEST_CAR_ID1)).isFalse()
      assertThat(manager.getUnlockHistory(TEST_CAR_ID1)).isEmpty()
      assertThat(manager.getFeatureState(TEST_CAR_ID1)).isNull()
    }
  }

  @Test
  fun testStoreFeatureState() {
    runBlocking {
      manager.storeFeatureState(TEST_STATE, TEST_CAR_ID1)
      assertThat(manager.getFeatureState(TEST_CAR_ID1)).isEqualTo(TEST_STATE)
    }
  }

  @Test
  fun testClearFeatureState() {
    runBlocking {
      manager.storeToken(TEST_TOKEN, TEST_CAR_ID1)
      manager.storeHandle(TEST_HANDLE, TEST_CAR_ID1)

      val now = Clock.systemUTC().instant()
      whenever(mockClock.instant()).thenReturn(now)
      manager.recordUnlockDate(TEST_CAR_ID1)

      manager.storeFeatureState(TEST_STATE, TEST_CAR_ID1)
      manager.storeFeatureState(TEST_STATE, TEST_CAR_ID2)
      manager.clearFeatureState(TEST_CAR_ID1)

      assertThat(manager.getFeatureState(TEST_CAR_ID1)).isNull()
      assertThat(manager.getFeatureState(TEST_CAR_ID2)).isEqualTo(TEST_STATE)

      // Assert no other data has been cleared
      assertThat(manager.containsCredential(TEST_CAR_ID1)).isTrue()
      assertThat(manager.getUnlockHistory(TEST_CAR_ID1)).containsExactly(now.inMillis())
    }
  }

  /**
   * Reduces the precision of an Instant to millis.
   *
   * This reduction matches the precision of an Instant from [Clock] with that of an Instant
   * reconstructed from storage. See b/154390034 for context.
   */
  private fun Instant.inMillis() = Instant.ofEpochMilli(this.toEpochMilli())
}
