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

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.encryptionrunner.EncryptionRunner
import com.google.android.encryptionrunner.EncryptionRunnerFactory
import com.google.android.encryptionrunner.EncryptionRunnerFactory.EncryptionRunnerType
import com.google.android.encryptionrunner.HandshakeMessage.HandshakeState
import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.blemessagestream.FakeBluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.testutils.Base64CryptoHelper
import com.google.android.libraries.car.trustagent.util.uuidToBytes
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import java.lang.IllegalStateException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow

private val DEVICE_ID = UUID.randomUUID()
private val TEST_VERIFICATION_TOKEN = "testVerificationToken".toByteArray()

private const val INIT_MESSAGE_ID = 1
private const val CONT_MESSAGE_ID = 2
private const val DEVICE_AND_SECRET_KEY_MESSAGE_ID = 3

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class PendingCarV3AssociationTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val fakeBluetoothConnectionManager = spy(FakeBluetoothConnectionManager())
  private val mockPendingCarCallback: PendingCar.Callback = mock()
  private val mockStream: MessageStream = mock()

  private lateinit var pendingCar: PendingCarV3Association

  private lateinit var streamCallbacks: CopyOnWriteArrayList<MessageStream.Callback>

  private lateinit var serverRunner: EncryptionRunner
  private lateinit var associatedCarManager: AssociatedCarManager
  private lateinit var database: ConnectedCarDatabase
  private lateinit var bluetoothDevice: BluetoothDevice

  private var authString: String? = null
  private var serverKey: Key? = null

  @Before
  fun setUp() {
    bluetoothDevice = Shadow.newInstanceOf(BluetoothDevice::class.java)

    // Using directExecutor to ensure that all operations happen on the main thread and allows for
    // tests to wait until the operations are done before continuing. Without this, operations can
    // leak and interfere between tests. See b/153095973 for details.
    database =
      Room.inMemoryDatabaseBuilder(context, ConnectedCarDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(directExecutor())
        .build()

    associatedCarManager = AssociatedCarManager(context, database, Base64CryptoHelper())

    serverRunner = EncryptionRunnerFactory.newRunner(EncryptionRunnerType.UKEY2)

    streamCallbacks = CopyOnWriteArrayList()
    // Keep track of the stream callbacks because encryption runner also registers one.
    whenever(mockStream.registerMessageEventCallback(any<MessageStream.Callback>())).thenAnswer {
      val callback = it.getArgument(0) as MessageStream.Callback
      streamCallbacks.add(callback)
    }

    whenever(mockStream.unregisterMessageEventCallback(any<MessageStream.Callback>())).thenAnswer {
      val callback = it.getArgument(0) as MessageStream.Callback
      streamCallbacks.remove(callback)
    }

    setUpMockStreamMessageId()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun createdBySecurityVersion() {
    assertThat(
        PendingCar.create(
          securityVersion = 3,
          context = context,
          isAssociating = true,
          stream = mockStream,
          associatedCarManager = associatedCarManager,
          device = bluetoothDevice,
          bluetoothManager = fakeBluetoothConnectionManager,
          oobChannelTypes = emptyList(),
          oobData = null
        )
      )
      .isInstanceOf(PendingCarV3Association::class.java)
  }

  @Test
  fun connect_onAuthStringAvailable() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()
    respondToContMessage()

    verify(mockPendingCarCallback).onAuthStringAvailable(checkNotNull(authString))
  }

  @Test
  fun connect_encryptedDeviceIdConfirmsAuthString() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()

    // IHU responds with encrypted device ID as auth string confirmation.
    respondToContMessage()

    assertThat(pendingCar.deviceId).isEqualTo(DEVICE_ID)
    verify(mockPendingCarCallback).onDeviceIdReceived(DEVICE_ID)
  }

  @Test
  fun connect_onConnected() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()
    respondToContMessage()
    // Message containing device Id and secret key is delivered.
    for (callback in streamCallbacks) {
      callback.onMessageSent(DEVICE_AND_SECRET_KEY_MESSAGE_ID)
    }

    verify(mockPendingCarCallback).onConnected(any())
  }

  @Test
  fun disconnect_GattIsDisconnected() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()
    respondToContMessage()

    pendingCar.disconnect()

    verify(fakeBluetoothConnectionManager).disconnect()
  }

  @Test
  fun gattDisconnection_connectionFails() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()

    for (callback in fakeBluetoothConnectionManager.connectionCallbacks) {
      callback.onDisconnected()
    }

    verify(mockPendingCarCallback).onConnectionFailed(pendingCar)
  }

  @Test
  fun gattConnectedCallback_connectionFails() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()

    // Unexpected callback also fails connection.
    for (callback in fakeBluetoothConnectionManager.connectionCallbacks) {
      callback.onConnected()
    }

    verify(mockPendingCarCallback).onConnectionFailed(pendingCar)
  }

  @Test
  fun connect_noVerificationCodeEncryptionFailure_connectionFails() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()

    pendingCar.encryptionCallback.onEncryptionFailure(
      EncryptionRunnerManager.FailureReason.NO_VERIFICATION_CODE
    )

    verify(mockPendingCarCallback).onConnectionFailed(pendingCar)
  }

  @Test
  fun connect_ukey2KeyMismatchEncryptionFailure_failureIgnored(): Unit = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()

    assertFailsWith<IllegalStateException> {
      pendingCar.encryptionCallback.onEncryptionFailure(
        EncryptionRunnerManager.FailureReason.UKEY2_KEY_MISMATCH
      )
    }
  }

  /**
   * Sets up the returned message ID.
   *
   * Conection flow waits for certain message delivery. These message IDs can be used to trigger the
   * next state.
   */
  private fun setUpMockStreamMessageId() {
    whenever(mockStream.sendMessage(any()))
      .thenReturn(INIT_MESSAGE_ID)
      .thenReturn(CONT_MESSAGE_ID)
      .thenReturn(DEVICE_AND_SECRET_KEY_MESSAGE_ID)
  }

  private fun respondToInitMessage() {
    argumentCaptor<StreamMessage>().apply {
      // First message is encryption init.
      verify(mockStream).sendMessage(capture())

      val message = serverRunner.respondToInitRequest(lastValue.payload)
      for (callback in streamCallbacks) {
        callback.onMessageReceived(createStreamMessage(message.nextMessage!!))
      }

      // Check the auth string is accepted as soon as it becomes available.
      verify(mockStream).encryptionKey = any()
    }
  }

  private fun respondToContMessage() {
    argumentCaptor<StreamMessage>().apply {
      // Second message is encryption cont. message.
      verify(mockStream, times(2)).sendMessage(capture())

      val contMessage =
        serverRunner.continueHandshake(lastValue.payload).also {
          assertThat(it.handshakeState).isEqualTo(HandshakeState.VERIFICATION_NEEDED)
        }
      authString = contMessage.verificationCode

      // Confirm PIN on server side; retrieve the encryption key.
      val lastMessage = serverRunner.notifyPinVerified()
      serverKey = lastMessage.key

      // Send IHU device ID as confirmation.
      for (callback in streamCallbacks) {
        callback.onMessageReceived(createStreamMessage(uuidToBytes(DEVICE_ID)))
      }
    }
  }

  private fun createStreamMessage(payload: ByteArray) =
    StreamMessage(
      payload,
      operation = OperationType.ENCRYPTION_HANDSHAKE,
      isPayloadEncrypted = false,
      originalMessageSize = 0,
      recipient = null
    )

  private fun createPendingCar() =
    PendingCarV3Association(
        context,
        messageStream = mockStream,
        associatedCarManager = associatedCarManager,
        device = bluetoothDevice,
        bluetoothManager = fakeBluetoothConnectionManager,
        resolvedOobChannelTypes = emptyList(),
        oobData = null
      )
      .apply { callback = mockPendingCarCallback }
}
