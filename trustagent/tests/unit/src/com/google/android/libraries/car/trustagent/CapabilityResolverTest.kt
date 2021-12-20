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
import com.google.android.companionprotos.capabilitiesExchange
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
class CapabilityResolverTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val mockGatt: BluetoothGattManager = mock() { on { sendMessage(any()) } doReturn true }

  private val EMPTY_CAPABILITIES_EXCHANGE = capabilitiesExchange {
    supportedOobChannels += emptyList()
  }

  private val RFCOMM_CAPABILITIES_EXCHANGE = capabilitiesExchange {
    supportedOobChannels += OobChannelType.BT_RFCOMM
  }

  private val ALL_CAPABILITIES_EXCHANGE = capabilitiesExchange {
    supportedOobChannels += OobChannelType.BT_RFCOMM
    supportedOobChannels += OobChannelType.PRE_ASSOCIATION
  }

  private lateinit var resolver: CapabilityResolver

  @Before
  fun setUp() {
    resolver = CapabilityResolver(mockGatt)
  }

  @Test
  fun exchangeCapabilities_returnsNullIfCouldNotSendMessage() = runBlockingTest {
    whenever(mockGatt.sendMessage(any())).thenReturn(false)

    assertThat(resolver.exchangeCapabilities(emptyList())).isNull()
  }

  @Test
  fun exchangeCapabilities_sendsLocalCapabilities() = runBlockingTest {
    whenever(mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>()))
      .thenAnswer {
        val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
        // Okay to callback with anything. This test only cares about what is sent out.
        callback.onMessageReceived(RFCOMM_CAPABILITIES_EXCHANGE.toByteArray())
      }

    resolver.exchangeCapabilities(listOf(OobChannelType.BT_RFCOMM))

    // Local capability is the same as remote, containing only BT_RFCOMM.
    verify(mockGatt).sendMessage(RFCOMM_CAPABILITIES_EXCHANGE.toByteArray())
  }

  @Test
  fun exchangeCapabilities_remoteAndLocalOobChannelsAreTheSame_returnsSharedOobChannels() =
      runBlockingTest {
    val capabilities = RFCOMM_CAPABILITIES_EXCHANGE
    whenever(mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>()))
      .thenAnswer {
        val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
        callback.onMessageReceived(capabilities.toByteArray())
      }

    val result: CapabilitiesExchange? =
      resolver.exchangeCapabilities(listOf(OobChannelType.BT_RFCOMM))

    assertThat(result).isEqualTo(capabilities)
  }

  @Test
  fun exchangeCapabilities_noRemoteOobChannels_returnsEmptyList() = runBlockingTest {
    val remoteCapabilities = EMPTY_CAPABILITIES_EXCHANGE
    whenever(mockGatt.registerMessageCallback(any<BluetoothConnectionManager.MessageCallback>()))
      .thenAnswer {
        val callback = it.getArgument(0) as BluetoothConnectionManager.MessageCallback
        callback.onMessageReceived(remoteCapabilities.toByteArray())
      }

    // Local is capable of OOB channel, but resolved result is empty.
    val result: CapabilitiesExchange? =
      resolver.exchangeCapabilities(listOf(OobChannelType.BT_RFCOMM))

    assertThat(result).isEqualTo(EMPTY_CAPABILITIES_EXCHANGE)
  }
}
