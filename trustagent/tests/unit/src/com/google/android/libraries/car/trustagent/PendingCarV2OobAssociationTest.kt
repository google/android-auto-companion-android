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
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.testutils.Base64CryptoHelper
import com.google.android.libraries.car.trustagent.util.uuidToBytes
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyZeroInteractions
import com.nhaarman.mockitokotlin2.whenever
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
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
class PendingCarV2OobAssociationTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testDispatcher = TestCoroutineDispatcher()

  private val mockGattManager: BluetoothGattManager = mock()
  private val mockPendingCarCallback: PendingCar.Callback = mock()
  private val mockStream: MessageStream = mock()

  private lateinit var pendingCar: PendingCarV2OobAssociation

  private lateinit var streamCallbacks: CopyOnWriteArrayList<MessageStream.Callback>
  private lateinit var gattCallbacks:
    CopyOnWriteArrayList<BluetoothConnectionManager.ConnectionCallback>

  private lateinit var serverRunner: EncryptionRunner
  private lateinit var associatedCarManager: AssociatedCarManager
  private lateinit var database: ConnectedCarDatabase
  private lateinit var bluetoothDevice: BluetoothDevice
  private lateinit var oobConnectionManager: OobConnectionManager
  private lateinit var serverOobConnectionManager: OobConnectionManager
  private lateinit var oobAuthCode: ByteArray

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

    gattCallbacks = CopyOnWriteArrayList()
    whenever(
      mockGattManager.registerConnectionCallback(
        any<BluetoothConnectionManager.ConnectionCallback>()
      )
    )
      .thenAnswer {
        val callback = it.getArgument(0) as BluetoothConnectionManager.ConnectionCallback
        gattCallbacks.add(callback)
      }

    whenever(
      mockGattManager.unregisterConnectionCallback(
        any<BluetoothConnectionManager.ConnectionCallback>()
      )
    )
      .thenAnswer {
        val callback = it.getArgument(0) as BluetoothConnectionManager.ConnectionCallback
        gattCallbacks.remove(callback)
      }

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

    oobConnectionManager =
      OobConnectionManager().apply {
        setOobData(TEST_DECRYPTION_IV + TEST_ENCRYPTION_IV + TEST_KEY.encoded)
      }
    serverOobConnectionManager =
      OobConnectionManager().apply {
        setOobData(TEST_ENCRYPTION_IV + TEST_DECRYPTION_IV + TEST_KEY.encoded)
      }

    serverRunner = EncryptionRunnerFactory.newRunner(EncryptionRunnerType.OOB_UKEY2)

    pendingCar = createPendingCar(oobConnectionManager)
  }

  @After
  fun tearDown() {
    database.close()

    Dispatchers.resetMain()
    testDispatcher.cleanupTestCoroutines()
  }

  @Test
  fun createdBySecurityVersion() {
    assertThat(
        PendingCar.create(
          2,
          context,
          isAssociating = true,
          stream = mockStream,
          associatedCarManager = associatedCarManager,
          device = bluetoothDevice,
          bluetoothManager = mockGattManager,
          oobConnectionManager = oobConnectionManager
        )
      )
      .isInstanceOf(PendingCarV2OobAssociation::class.java)
  }

  @Test
  fun connect_onConnected() {
    pendingCar.connect()
    respondToInitMessage()
    respondToOobContMessage()

    // Message containing device Id and secret key is delivered.
    streamCallbacks.forEach { it.onMessageSent(DEVICE_AND_SECRET_KEY_MESSAGE_ID) }

    verify(mockPendingCarCallback).onConnected(any())
    verify(mockPendingCarCallback, never()).onAuthStringAvailable(any())
  }

  @Test
  fun testOnOobAuthTokenAvailable_sendsEncryptedAuthToken() {
    pendingCar.encryptionCallback.onOobAuthTokenAvailable(TEST_VERIFICATION_TOKEN)
    verify(mockPendingCarCallback, never()).onConnectionFailed(pendingCar)
    argumentCaptor<StreamMessage>().apply {
      verify(mockStream).sendMessage(capture())

      assertThat(lastValue.operation).isEqualTo(OperationType.ENCRYPTION_HANDSHAKE)
      assertThat(lastValue.isPayloadEncrypted).isFalse()
    }
  }

  @Test
  fun testHandleOobVerificationMessage_invalidAuthTokenReceived_invokesOnConnectionFailed() {
    pendingCar.encryptionCallback.onOobAuthTokenAvailable(TEST_VERIFICATION_TOKEN)
    streamCallbacks.forEach {
      it.onMessageReceived(
        createStreamMessage(
          serverOobConnectionManager.encryptVerificationCode(
            "invalidVerificationToken".toByteArray()
          )
        )
      )
    }

    verify(mockPendingCarCallback).onConnectionFailed(pendingCar)
  }

  @Test
  fun testEncryptedCodeReceivedBeforeSent_ignoreMessage() {
    streamCallbacks.forEach {
      it.onMessageReceived(
        createStreamMessage(
          serverOobConnectionManager.encryptVerificationCode("IgnoredMessage".toByteArray())
        )
      )
    }

    verify(mockStream, never()).sendMessage(any())
    verifyZeroInteractions(mockPendingCarCallback)
  }

  /**
   * Sets up the returned message ID.
   *
   * Connection flow waits for certain message delivery. These message IDs can be used to trigger
   * the next state.
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
      streamCallbacks.forEach { it.onMessageReceived(createStreamMessage(message.nextMessage!!)) }
    }
  }

  private fun respondToOobContMessage() {
    argumentCaptor<StreamMessage>().apply {
      // Second message is encryption cont. message. Third is the encrypted code message.
      verify(mockStream, times(3)).sendMessage(capture())

      val contMessage =
        serverRunner.continueHandshake(secondValue.payload).also {
          assertThat(it.handshakeState).isEqualTo(HandshakeState.OOB_VERIFICATION_NEEDED)
        }
      oobAuthCode = contMessage.oobVerificationCode!!

      val encryptedToken = serverOobConnectionManager.encryptVerificationCode(oobAuthCode)
      streamCallbacks.forEach { it.onMessageReceived(createStreamMessage(encryptedToken)) }

      // Check the auth string is accepted as soon as it becomes available.
      verify(mockStream).encryptionKey = any()

      val idMessage = createStreamMessage(uuidToBytes(DEVICE_ID))
      // Send IHU device ID as confirmation.
      streamCallbacks.forEach { it.onMessageReceived(idMessage) }
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

  private fun createPendingCar(oobConnectionManager: OobConnectionManager) =
    PendingCarV2OobAssociation(
        context,
        messageStream = mockStream,
        associatedCarManager = associatedCarManager,
        device = bluetoothDevice,
        bluetoothManager = mockGattManager,
        oobConnectionManager = oobConnectionManager,
        coroutineScope = CoroutineScope(testDispatcher)
      )
      .apply { callback = mockPendingCarCallback }

  companion object {
    private val TEST_KEY = KeyGenerator.getInstance("AES").generateKey()
    private val TEST_ENCRYPTION_IV =
      ByteArray(OobConnectionManager.NONCE_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
    private val TEST_DECRYPTION_IV =
      ByteArray(OobConnectionManager.NONCE_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
  }
}
