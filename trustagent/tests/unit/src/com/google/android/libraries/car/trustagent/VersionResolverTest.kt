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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class VersionResolverTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val mockGatt: BluetoothGattManager = mock() { on { sendMessage(any()) } doReturn true }

  private lateinit var resolver: VersionResolver

  @Before
  fun setUp() {
    resolver = VersionResolver(mockGatt)
  }

  @Test
  fun makeBleVersionExchange_validVersion() {
    val versionExchange = VersionResolver.makeVersionExchange()

    assertThat(versionExchange.maxSupportedMessagingVersion)
      .isAtLeast(versionExchange.minSupportedMessagingVersion)
    assertThat(versionExchange.maxSupportedSecurityVersion)
      .isAtLeast(versionExchange.minSupportedSecurityVersion)
  }

  @Test
  fun exchangeVersion_sendsLocalVersion() =
    runTest(UnconfinedTestDispatcher()) {
      val localVersion = VersionResolver.makeVersionExchange()
      whenever(mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>()))
        .thenAnswer {
          val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
          // Okay to callback with anything. This test only cares about what is sent out.
          callback.onMessageReceived(localVersion.toByteArray())
        }

      resolver.exchangeVersion()

      verify(mockGatt).sendMessage(localVersion.toByteArray())
    }

  @Test
  fun exchangeVersion_returnsRemoteVersion() =
    runTest(UnconfinedTestDispatcher()) {
      val remoteVersion = VersionResolver.makeVersionExchange(maxMessagingVersion = 10)
      // ArgumentCaptor does not work with suspendCoroutine to capture callback.
      // Configure the mock ahead of invoking the suspend fun.
      // See https://github.com/Kotlin/kotlinx.coroutines/issues/514
      whenever(mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>()))
        .thenAnswer {
          val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
          callback.onMessageReceived(remoteVersion.toByteArray())
        }

      val result = resolver.exchangeVersion()

      assertThat(result).isEqualTo(remoteVersion)
    }

  @Test
  fun exchangeVersion_returnsNullIfCouldNotSendMessage() =
    runTest(UnconfinedTestDispatcher()) {
      whenever(mockGatt.sendMessage(any())).thenReturn(false)

      assertThat(resolver.exchangeVersion()).isNull()
    }

  @Test
  fun resolveVersion_returnsMaxSupportedVersion() {
    val supported =
      VersionResolver.makeVersionExchange(
        minMessagingVersion = 1,
        maxMessagingVersion = 10,
        minSecurityVersion = 1,
        maxSecurityVersion = 10
      )

    val resolved = resolver.resolveVersion(supported)

    assertThat(resolved?.messageVersion).isEqualTo(VersionResolver.MAX_MESSAGING_VERSION)
    assertThat(resolved?.securityVersion).isEqualTo(VersionResolver.MAX_SECURITY_VERSION)
  }

  @Test
  fun resolveVersion_returnsNullForUnsupportedSecurityVersion() {
    val unsupported = VersionResolver.makeVersionExchange(minSecurityVersion = 10)

    assertThat(resolver.resolveVersion(unsupported)).isNull()
  }

  @Test
  fun resolveVersion_returnsNullForUnsupportedMessageVersion() {
    val unsupported =
      VersionResolver.makeVersionExchange(minMessagingVersion = 10, maxMessagingVersion = 20)

    assertThat(resolver.resolveVersion(unsupported)).isNull()
  }
}
