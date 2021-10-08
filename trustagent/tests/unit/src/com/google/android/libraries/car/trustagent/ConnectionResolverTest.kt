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
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattManager
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class ConnectionResolverTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val mockGatt: BluetoothGattManager = mock() {
    on { sendMessage(any()) } doReturn true
  }

  private val EMPTY_CAPABILITIES_EXCHANGE =
    CapabilitiesExchange.newBuilder()
      .addAllSupportedOobChannels(emptyList())
      .build()

  private val RFCOMM_CAPABILITIES_EXCHANGE =
    CapabilitiesExchange.newBuilder()
      .addAllSupportedOobChannels(listOf(OobChannelType.BT_RFCOMM))
      .build()

  private lateinit var resolver: ConnectionResolver

  @Before
  fun setUp() {
    resolver = ConnectionResolver(mockGatt)
  }

  @Test
  fun makeBleVersionExchange_validVersion() {
    val versionExchange = ConnectionResolver.makeVersionExchange()

    assertThat(
      versionExchange.maxSupportedMessagingVersion
    ).isAtLeast(
      versionExchange.minSupportedMessagingVersion
    )
    assertThat(
      versionExchange.maxSupportedSecurityVersion
    ).isAtLeast(
      versionExchange.minSupportedSecurityVersion
    )
  }

  @Test
  fun exchangeVersion_sendsLocalVersion() = runBlockingTest {
    val localVersion = ConnectionResolver.makeVersionExchange()
    whenever(
      mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>())
    ).thenAnswer {
      val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
      // Okay to callback with anything. This test only cares about what is sent out.
      callback.onMessageReceived(localVersion.toByteArray())
    }

    resolver.exchangeVersion()

    verify(mockGatt).sendMessage(localVersion.toByteArray())
  }

  @Test
  fun exchangeVersion_returnsRemoteVersion() = runBlockingTest {
    val remoteVersion = ConnectionResolver.makeVersionExchange(maxMessagingVersion = 10)
    // ArgumentCaptor does not work with suspendCoroutine to capture callback.
    // Configure the mock ahead of invoking the suspend fun.
    // See https://github.com/Kotlin/kotlinx.coroutines/issues/514
    whenever(
      mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>())
    ).thenAnswer {
      val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
      callback.onMessageReceived(remoteVersion.toByteArray())
    }

    val result = resolver.exchangeVersion()

    assertThat(result).isEqualTo(remoteVersion)
  }

  @Test
  fun exchangeVersion_returnsNullIfCouldNotSendMessage() = runBlockingTest {
    whenever(mockGatt.sendMessage(any())).thenReturn(false)

    assertThat(resolver.exchangeVersion()).isNull()
  }

  @Test
  fun exchangeCapabilities_sendsLocalCapabilities() = runBlockingTest {
    val localCapabilities = RFCOMM_CAPABILITIES_EXCHANGE
    whenever(
      mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>())
    ).thenAnswer {
      val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
      // Okay to callback with anything. This test only cares about what is sent out.
      callback.onMessageReceived(localCapabilities.toByteArray())
    }

    resolver.exchangeCapabilities()
    verify(mockGatt).sendMessage(localCapabilities.toByteArray())
  }

  @Test
  fun exchangeCapabilities_remoteAndLocalOobChannelsAreTheSame_returnsSharedOobChannels() =
    runBlockingTest {
      val remoteCapabilities = RFCOMM_CAPABILITIES_EXCHANGE
      whenever(
        mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>())
      ).thenAnswer {
        val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
        callback.onMessageReceived(remoteCapabilities.toByteArray())
      }

      val result = resolver.exchangeCapabilities()
      assertThat(result).isEqualTo(remoteCapabilities)
    }

  @Test
  fun exchangeCapabilities_noRemoteOobChannels_returnsEmptyList() = runBlockingTest {
    val remoteCapabilities = EMPTY_CAPABILITIES_EXCHANGE
    whenever(
      mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>())
    ).thenAnswer {
      val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
      callback.onMessageReceived(remoteCapabilities.toByteArray())
    }

    val result = resolver.exchangeCapabilities()
    assertThat(result).isEqualTo(remoteCapabilities)
  }

  @Test
  fun resolveVersion_returnsMaxSupportedVersion() {
    val supported = ConnectionResolver.makeVersionExchange(
      minMessagingVersion = 1,
      maxMessagingVersion = 10,
      minSecurityVersion = 1,
      maxSecurityVersion = 10
    )

    val resolved = resolver.resolveVersion(supported)

    assertThat(resolved?.messageVersion).isEqualTo(ConnectionResolver.MAX_MESSAGING_VERSION)
    assertThat(resolved?.securityVersion).isEqualTo(ConnectionResolver.MAX_SECURITY_VERSION)
  }

  @Test
  fun resolveVersion_returnsNullForUnsupportedSecurityVersion() {
    val unsupported = ConnectionResolver.makeVersionExchange(minSecurityVersion = 10)

    assertThat(resolver.resolveVersion(unsupported)).isNull()
  }

  @Test
  fun resolveVersion_returnsNullForUnsupportedMessageVersion() {
    val unsupported = ConnectionResolver.makeVersionExchange(
      minMessagingVersion = 10, maxMessagingVersion = 20
    )

    assertThat(resolver.resolveVersion(unsupported)).isNull()
  }
}
