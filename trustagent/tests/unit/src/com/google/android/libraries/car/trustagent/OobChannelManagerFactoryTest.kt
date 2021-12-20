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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class OobChannelManagerFactoryTest {
  private lateinit var factory: OobChannelManagerFactory

  @Before
  fun setUp() {
    factory = OobChannelManagerFactoryImpl()
  }

  @Test
  fun createsRfcommChannel() {
    val manager =
      factory.create(listOf(OobChannelType.BT_RFCOMM), oobData = null, securityVersion = 3)

    assertThat(manager.oobChannels).hasSize(1)
    assertThat(manager.oobChannels.first()).isInstanceOf(BluetoothRfcommChannel::class.java)
  }

  @Test
  fun createsPassThroughChannel_oobDataIsNull_emptyList() {
    val manager =
      factory.create(listOf(OobChannelType.PRE_ASSOCIATION), oobData = null, securityVersion = 3)

    assertThat(manager.oobChannels).isEmpty()
  }

  @Test
  fun createsPassThroughChannel() {
    val manager =
      factory.create(
        listOf(OobChannelType.PRE_ASSOCIATION),
        oobData =
          OobData(
            encryptionKey = byteArrayOf(0x11, 0x22),
            ihuIv = byteArrayOf(0x11, 0x22),
            mobileIv = byteArrayOf(0x11, 0x22),
          ),
        securityVersion = 3
      )

    assertThat(manager.oobChannels).hasSize(1)
    assertThat(manager.oobChannels.first()).isInstanceOf(PassThroughOobChannel::class.java)
  }

  @Test
  fun ignoresUnkownAndUnrecognizedChannels() {
    val manager =
      factory.create(
        listOf(OobChannelType.OOB_CHANNEL_UNKNOWN, OobChannelType.UNRECOGNIZED),
        oobData = null,
        securityVersion = 3
      )

    assertThat(manager.oobChannels).isEmpty()
  }
}
