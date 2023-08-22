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
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.encryptionrunner.EncryptionRunner
import com.google.android.encryptionrunner.EncryptionRunnerFactory
import com.google.android.encryptionrunner.EncryptionRunnerFactory.EncryptionRunnerType
import com.google.android.encryptionrunner.HandshakeMessage.HandshakeState
import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.PendingCarV2Reconnection.Companion.CHALLENGE_BLOCK_SIZE_BYTES
import com.google.android.libraries.car.trustagent.PendingCarV2Reconnection.Companion.SALT_SIZE_BYTES
import com.google.android.libraries.car.trustagent.PendingCarV2Reconnection.Companion.computeHmac
import com.google.android.libraries.car.trustagent.PendingCarV2Reconnection.Companion.toTruncated
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothGattManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.testutils.Base64CryptoHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.lang.IllegalStateException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.SecretKey
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.shadow.api.Shadow

private const val HMAC_CHALLENGE_MESSAGE_ID = 1
private const val INIT_MESSAGE_ID = 2
private const val CONT_MESSAGE_ID = 3
private const val HMAC_MESSAGE_ID = 4

private val DEVICE_ID = UUID.randomUUID()
private const val DEVICE_NAME = "deviceName"

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class PendingCarV2ReconnectionTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testBluetoothDevice =
    BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:FF")

  private val mockGattManager: BluetoothGattManager = mock()
  private val mockPendingCarCallback: PendingCar.Callback = mock()

  private lateinit var pendingCar: PendingCarV2Reconnection

  private lateinit var streamCallbacks: CopyOnWriteArrayList<MessageStream.Callback>
  private lateinit var gattCallbacks:
    CopyOnWriteArrayList<BluetoothConnectionManager.ConnectionCallback>

  private lateinit var serverRunner: EncryptionRunner
  private lateinit var associatedCarManager: AssociatedCarManager
  private lateinit var database: ConnectedCarDatabase
  private lateinit var bluetoothDevice: BluetoothDevice
  private lateinit var identificationKey: SecretKey
  private lateinit var storedSession: Key
  private lateinit var mockStream: MessageStream

  // Expected HMAC of salt in advertised data.
  private var expectedHmac: ByteArray? = null

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

    identificationKey = associatedCarManager.generateKey()
    storedSession = createStoredSession()

    mockStream = mock() { on { encryptionKey } doReturn storedSession }

    runBlocking { createAssociatedCar() }

    serverRunner =
      EncryptionRunnerFactory.newRunner(EncryptionRunnerType.UKEY2).apply { setIsReconnect(true) }

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

    setUpMockStreamMessageId()

    // Keep track of the stream callbacks because reconnection stream unregisters after HMAC.
    streamCallbacks = CopyOnWriteArrayList()
    whenever(mockStream.registerMessageEventCallback(any<MessageStream.Callback>())).thenAnswer {
      val callback = it.getArgument(0) as MessageStream.Callback
      streamCallbacks.add(callback)
    }
    whenever(mockStream.unregisterMessageEventCallback(any<MessageStream.Callback>())).thenAnswer {
      val callback = it.getArgument(0) as MessageStream.Callback
      streamCallbacks.remove(callback)
    }

    pendingCar = createPendingCar()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun createdBySecurityVersion() {
    assertThat(
        PendingCar.create(
          2,
          context,
          isAssociating = false,
          stream = mockStream,
          associatedCarManager = associatedCarManager,
          device = bluetoothDevice,
          bluetoothManager = mockGattManager,
          oobChannelTypes = emptyList(),
          oobData = null
        )
      )
      .isInstanceOf(PendingCarV2Reconnection::class.java)
  }

  @Test
  fun connect_incorrectHmac_connectionFailed() = runBlocking {
    pendingCar.connect(createAdvertisedData())

    respondToSaltHmacAndChallenge(respondCorrectValue = false)

    verify(mockPendingCarCallback).onConnectionFailed(pendingCar)
    verify(mockGattManager).disconnect()
  }

  @Test
  fun connect_onConnected() = runBlocking {
    pendingCar.connect(createAdvertisedData())
    respondToSaltHmacAndChallenge()
    respondToInitMessage()
    respondToReconnection()

    verify(mockPendingCarCallback).onConnected(any())
    verify(mockPendingCarCallback, never()).onDeviceIdReceived(any())
  }

  @Test
  fun connect_ukey2KeyMismatchEncryptionFailure_connectionFails() = runBlocking {
    pendingCar.connect(createAdvertisedData())
    respondToSaltHmacAndChallenge()
    respondToInitMessage()

    pendingCar.encryptionCallback.onEncryptionFailure(
      EncryptionRunnerManager.FailureReason.UKEY2_KEY_MISMATCH
    )

    verify(mockPendingCarCallback).onConnectionFailed(pendingCar)
    verify(mockGattManager).disconnect()
  }

  @Test
  fun connect_noVerificationCodeEncryptionFailure_illegalStateException(): Unit = runBlocking {
    pendingCar.connect(createAdvertisedData())
    respondToSaltHmacAndChallenge()
    respondToInitMessage()

    assertFailsWith<IllegalStateException> {
      pendingCar.encryptionCallback.onEncryptionFailure(
        EncryptionRunnerManager.FailureReason.NO_VERIFICATION_CODE
      )
    }
  }

  @Test
  fun toCar_loadNameFromDatabase(): Unit = runBlocking {
    pendingCar.connect(createAdvertisedData())
    respondToSaltHmacAndChallenge()
    respondToInitMessage()
    respondToReconnection()

    argumentCaptor<Car>().apply {
      verify(mockPendingCarCallback).onConnected(capture())
      assertThat(firstValue.name).isEqualTo(DEVICE_NAME)
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
      .thenReturn(HMAC_CHALLENGE_MESSAGE_ID)
      .thenReturn(INIT_MESSAGE_ID)
      .thenReturn(CONT_MESSAGE_ID)
      .thenReturn(HMAC_MESSAGE_ID)
  }

  private suspend fun createAssociatedCar() {
    associatedCarManager.add(
      mock<Car>() {
        on { deviceId } doReturn DEVICE_ID
        on { bluetoothDevice } doReturn testBluetoothDevice
        // Returning key to be persisted as stored session.
        on { identificationKey } doReturn identificationKey
        on { messageStream } doReturn mockStream
        on { name } doReturn DEVICE_NAME
      }
    )
  }

  private fun createAdvertisedData(): ByteArray {
    val salt = ByteArray(SALT_SIZE_BYTES).apply { SecureRandom().nextBytes(this) }
    val paddedSalt = salt.copyOf(CHALLENGE_BLOCK_SIZE_BYTES)

    val hmac = computeHmac(paddedSalt, identificationKey)
    val truncatedHmac = hmac.toTruncated()

    expectedHmac = hmac
    return truncatedHmac + salt
  }

  private fun respondToSaltHmacAndChallenge(respondCorrectValue: Boolean = true) {
    argumentCaptor<StreamMessage>().apply {
      // First message is encryption init.
      verify(mockStream).sendMessage(capture())

      val size = lastValue.payload.size

      val hmac = lastValue.payload.copyOfRange(0, size - CHALLENGE_BLOCK_SIZE_BYTES)
      assertThat(hmac contentEquals checkNotNull(expectedHmac)).isTrue()

      val challenge = lastValue.payload.copyOfRange(size - CHALLENGE_BLOCK_SIZE_BYTES, size)
      val response = computeHmac(challenge, identificationKey)
      if (!respondCorrectValue) {
        // Modify the correct HMAC by changing one byte.
        response[0] = ++response[0]
      }

      streamCallbacks.forEach { it.onMessageReceived(createStreamMessage(response)) }
    }
  }

  private fun respondToInitMessage() {
    argumentCaptor<StreamMessage>().apply {
      // Second message is encryption init.
      verify(mockStream, times(2)).sendMessage(capture())

      val message = serverRunner.respondToInitRequest(lastValue.payload)
      streamCallbacks.forEach { it.onMessageReceived(createStreamMessage(message.nextMessage!!)) }
    }
  }

  private fun respondToReconnection() {
    argumentCaptor<StreamMessage>().apply {
      // Third message is encryption cont. message.
      verify(mockStream, times(3)).sendMessage(capture())

      serverRunner.continueHandshake(lastValue.payload)
    }

    // client sends 2 consecutive messages: cont., then HMAC.
    // Trigger sending HMAC by notifying the cont. message has been sent.
    streamCallbacks.forEach { it.onMessageSent(CONT_MESSAGE_ID) }

    argumentCaptor<StreamMessage>().apply {
      // Forth message is HMAC.
      verify(mockStream, times(4)).sendMessage(capture())

      val message =
        serverRunner.authenticateReconnection(lastValue.payload, storedSession.asBytes())
      assertThat(message.handshakeState).isEqualTo(HandshakeState.FINISHED)

      streamCallbacks.forEach { it.onMessageReceived(createStreamMessage(message.nextMessage!!)) }
    }
  }

  private fun createStoredSession(): Key {
    val client = EncryptionRunnerFactory.newRunner(EncryptionRunnerType.UKEY2)
    val server = EncryptionRunnerFactory.newRunner(EncryptionRunnerType.UKEY2)

    var message = client.initHandshake()
    message = server.respondToInitRequest(checkNotNull(message.nextMessage))

    client.continueHandshake(checkNotNull(message.nextMessage))

    message = client.notifyPinVerified()
    return checkNotNull(message.key)
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
    PendingCarV2Reconnection(
        messageStream = mockStream,
        associatedCarManager = associatedCarManager,
        device = bluetoothDevice,
        bluetoothManager = mockGattManager,
      )
      .apply {
        coroutineDispatcher = UnconfinedTestDispatcher()
        callback = mockPendingCarCallback
      }
}
