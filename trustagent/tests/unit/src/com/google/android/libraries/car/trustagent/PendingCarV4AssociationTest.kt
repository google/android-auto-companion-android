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
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.companionprotos.VerificationCode
import com.google.android.companionprotos.VerificationCodeState
import com.google.android.companionprotos.verificationCode
import com.google.android.encryptionrunner.EncryptionRunner
import com.google.android.encryptionrunner.EncryptionRunnerFactory
import com.google.android.encryptionrunner.EncryptionRunnerFactory.EncryptionRunnerType
import com.google.android.encryptionrunner.HandshakeMessage.HandshakeState
import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.blemessagestream.FakeBluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.testutils.Base64CryptoHelper
import com.google.android.libraries.car.trustagent.testutils.FakeMessageStream
import com.google.android.libraries.car.trustagent.util.uuidToBytes
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.lang.IllegalStateException
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class PendingCarV4AssociationTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testDispatcher = UnconfinedTestDispatcher()

  private val mockPendingCarCallback: PendingCar.Callback = mock()

  private val fakeBluetoothGattManager = spy(FakeBluetoothConnectionManager())
  private val fakeMessageStream: FakeMessageStream = FakeMessageStream()

  private lateinit var pendingCar: PendingCarV4Association

  private lateinit var serverRunner: EncryptionRunner
  private lateinit var associatedCarManager: AssociatedCarManager
  private lateinit var database: ConnectedCarDatabase
  private lateinit var bluetoothDevice: BluetoothDevice

  private var authString: String? = null
  private var serverKey: Key? = null

  @Before
  fun setUp() {
    bluetoothDevice =
      context
        .getSystemService(BluetoothManager::class.java)
        .adapter
        .getRemoteDevice("00:11:22:33:AA:BB")

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
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun createdBySecurityVersion() {
    assertThat(
        PendingCar.create(
          securityVersion = 4,
          context = context,
          isAssociating = true,
          stream = fakeMessageStream,
          associatedCarManager = associatedCarManager,
          device = bluetoothDevice,
          bluetoothManager = fakeBluetoothGattManager,
          oobChannelTypes = emptyList(),
          oobData = null
        )
      )
      .isInstanceOf(PendingCarV4Association::class.java)
  }

  @Test
  fun connect_onAuthStringAvailable() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()
    respondToContMessageAndVisualVerificationMessage()

    verify(mockPendingCarCallback).onAuthStringAvailable(checkNotNull(authString))
  }

  @Test
  fun connect_visualConfirmationMessageConfirmsEncryption() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()
    respondToContMessageAndVisualVerificationMessage()
    respondEncryptedDeviceId()

    assertThat(pendingCar.deviceId).isEqualTo(DEVICE_ID)
    verify(mockPendingCarCallback).onDeviceIdReceived(DEVICE_ID)
  }

  @Test
  fun connect_onConnected() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()
    respondToContMessageAndVisualVerificationMessage()
    respondEncryptedDeviceId()
    // Message containing device Id and secret key is delivered.
    // The message ID of the last sent message.
    for (callback in fakeMessageStream.callbacks) {
      callback.onMessageSent(fakeMessageStream.lastSentMessageId)
    }

    verify(mockPendingCarCallback).onConnected(any())
  }

  @Test
  fun disconnect_GattIsDisconnected() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()
    respondToContMessageAndVisualVerificationMessage()

    pendingCar.disconnect()

    verify(fakeBluetoothGattManager).disconnect()
  }

  @Test
  fun gattDisconnection_connectionFails() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()

    fakeBluetoothGattManager.connectionCallbacks.forEach { it.onDisconnected() }

    verify(mockPendingCarCallback).onConnectionFailed(pendingCar)
  }

  @Test
  fun gattConnectedCallback_connectionFails() = runBlocking {
    pendingCar = createPendingCar()
    pendingCar.connect()
    respondToInitMessage()

    // Unexpected callback also fails connection.
    fakeBluetoothGattManager.connectionCallbacks.forEach { it.onConnected() }

    verify(mockPendingCarCallback).onConnectionFailed(pendingCar)
  }

  @Test
  fun connect_noVerificationCodeEncryptionFailure_connectionFails(): Unit = runBlocking {
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

  private fun respondToInitMessage() {
    // First message is encryption init.
    assertThat(fakeMessageStream.sentMessages).hasSize(1)

    val message = serverRunner.respondToInitRequest(fakeMessageStream.sentMessages.last().payload)
    for (callback in fakeMessageStream.callbacks) {
      callback.onMessageReceived(createStreamMessage(message.nextMessage!!))
    }
  }

  /**
   * Continues association by acting as IHU.
   *
   * Responds with messages from IHU. Also checks the expected messages.
   * - feed the 2nd mobile message to UKEY2 runner (1st message is UKEY2 INIT);
   * - parse the 3rd mobile message as visual verification request;
   * - confirm the encryption on the IHU side;
   * - send visual confirmation to mobile (as if user has confirmed);
   * - verify the mobile side also confirms the encryption.
   */
  private fun respondToContMessageAndVisualVerificationMessage() {
    // Second message is encryption cont. message.
    // Third message is phone requesting visual verification.
    assertThat(fakeMessageStream.sentMessages).hasSize(3)

    val contMessage =
      serverRunner.continueHandshake(fakeMessageStream.sentMessages[1].payload).also {
        assertThat(it.handshakeState).isEqualTo(HandshakeState.VERIFICATION_NEEDED)
      }
    authString = contMessage.verificationCode

    // Verify the phone is request visual verification.
    val visualVerification = VerificationCode.parseFrom(fakeMessageStream.sentMessages[2].payload)
    assertThat(visualVerification.state).isEqualTo(VerificationCodeState.VISUAL_VERIFICATION)

    // Confirm PIN on server side; retrieve the encryption key.
    val lastMessage = serverRunner.notifyPinVerified()
    serverKey = lastMessage.key

    // Send visual confirmation to phone.
    val visualConfirmation = verificationCode { state = VerificationCodeState.VISUAL_CONFIRMATION }
    for (callback in fakeMessageStream.callbacks) {
      callback.onMessageReceived(createStreamMessage(visualConfirmation.toByteArray()))
    }

    // Check the auth string is accepted.
    assertThat(fakeMessageStream.encryptionKey).isNotNull()
  }

  private fun respondEncryptedDeviceId() {
    val idMessage = createStreamMessage(uuidToBytes(DEVICE_ID))
    // Send IHU device ID as confirmation.
    for (callback in fakeMessageStream.callbacks) {
      callback.onMessageReceived(idMessage)
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
    PendingCarV4Association(
        context,
        messageStream = fakeMessageStream,
        associatedCarManager = associatedCarManager,
        device = bluetoothDevice,
        bluetoothManager = fakeBluetoothGattManager,
        oobData = null,
        coroutineDispatcher = testDispatcher,
        oobChannelManagerFactory =
          OobChannelManagerFactory { _, _, _ -> InvalidOobChannelManager() }
      )
      .apply { callback = mockPendingCarCallback }

  companion object {
    private const val NONCE_LENGTH_BYTES = 12

    private val DEVICE_ID = UUID.randomUUID()
  }
}

private class InvalidOobChannelManager() :
  OobChannelManager(oobChannels = emptyList(), executorService = null) {

  override suspend fun readOobData(device: BluetoothDevice): OobData? {
    return null
  }
}
