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

package com.google.android.libraries.car.trustagent.experimental.remoteintents

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.car.trustagent.Car
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val CAR_ID = UUID.fromString("b9592993-2f53-40a8-8b87-e218e592c165")
private val OTHER_CAR_ID = UUID.fromString("829466cd-3321-4af5-ac6b-9d7e175d76dc")

@RunWith(AndroidJUnit4::class)
class IntentFeatureTest {
  private lateinit var intentFeature: IntentFeature
  private lateinit var car: Car
  private lateinit var otherCar: Car
  private lateinit var testIntent: CompanionIntent

  @Before
  fun setUp() {
    car = mock { on { deviceId } doReturn CAR_ID }
    otherCar = mock { on { deviceId } doReturn OTHER_CAR_ID }
    intentFeature = spy(IntentFeature())

    testIntent = CompanionIntent("title", "content", "action", "uri", "package.name", flags = 0)
  }
  @Test
  fun testSendToCar_doNotSendMessageWhenNoCarConnected() {
    intentFeature.sendToCar(testIntent)
    verify(intentFeature, never()).sendMessage(any(), any())
  }

  @Test
  fun testSendToCar_sendCorrectMessageToConnectedCars() {
    intentFeature.notifyCarConnected(car)
    intentFeature.notifyCarConnected(otherCar)
    intentFeature.sendToCar(testIntent)
    verify(intentFeature).sendMessage(testIntent.toByteArray(), CAR_ID)
    verify(intentFeature).sendMessage(testIntent.toByteArray(), OTHER_CAR_ID)
  }

  @Test
  fun testSendToCar_sendPendingMessageWhenCarConnected() {
    intentFeature.sendToCar(testIntent)
    verify(intentFeature, never()).sendMessage(testIntent.toByteArray(), CAR_ID)

    intentFeature.notifyCarConnected(car)
    verify(intentFeature).sendMessage(testIntent.toByteArray(), CAR_ID)
  }
}
