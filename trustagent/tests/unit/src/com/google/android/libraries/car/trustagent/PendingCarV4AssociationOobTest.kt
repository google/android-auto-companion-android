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
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
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
import com.google.protobuf.ByteString
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class PendingCarV4AssociationOobTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testDispatcher = UnconfinedTestDispatcher()

  private val mockPendingCarCallback: PendingCar.Callback = mock()

  private val fakeBluetoothGattManager = FakeBluetoothConnectionManager()
  private val fakeMessageStream: FakeMessageStream = FakeMessageStream()

  private lateinit var pendingCar: PendingCarV4Association

  private lateinit var serverRunner: EncryptionRunner
  private lateinit var associatedCarManager: AssociatedCarManager
  private lateinit var database: ConnectedCarDatabase
  private lateinit var bluetoothDevice: BluetoothDevice
  private lateinit var oobData: OobData

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

    oobData =
      OobData(encryptionKey = TEST_KEY.encoded, ihuIv = TEST_IHU_IV, mobileIv = TEST_MOBILE_IV)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun connect_onConnected() = runBlocking {
    pendingCar = createPendingCar(PassThroughOobChannelManager(oobData))
    pendingCar.connect()
    respondToInitMessage()
    respondToContMessageAndOobVerificationMessage()
    respondEncryptedDeviceId()

    // The message ID of the last sent message.
    for (callback in fakeMessageStream.callbacks) {
      callback.onMessageSent(fakeMessageStream.lastSentMessageId)
    }

    verify(mockPendingCarCallback).onConnected(any())
  }

  @Test
  fun connect_oobDataIsNull_visualConfirmation() = runBlocking {
    pendingCar = createPendingCar(InvalidOobChannelManager(oobData))
    pendingCar.connect()
    respondToInitMessage()

    // Verify the phone is request visual verification.
    val visualVerification =
      VerificationCode.parseFrom(fakeMessageStream.sentMessages.last().payload)
    assertThat(visualVerification.state).isEqualTo(VerificationCodeState.VISUAL_VERIFICATION)
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
   *
   * - feed the 2nd mobile message to UKEY2 runner (1st message is UKEY2 INIT);
   * - parse the 3rd mobile message as OOB verification request;
   * -
   * - decrypt the mobile message with mobile IV;
   * - verify it's the same as UKEY verification code;
   * - confirm the encryption on the IHU side;
   * - encrypt the UKEY2 verification code with IHU IV;
   * - send the encrypted message to mobile;
   * - verify the mobile side also confirms the encryption.
   */
  private fun respondToContMessageAndOobVerificationMessage() {
    // Second message is encryption cont. message.
    // Third message is phone requesting OOB verification.
    assertThat(fakeMessageStream.sentMessages).hasSize(3)

    val contMessage =
      serverRunner.continueHandshake(fakeMessageStream.sentMessages[1].payload).also {
        assertThat(it.handshakeState).isEqualTo(HandshakeState.VERIFICATION_NEEDED)
      }
    val fullVerificationCode = contMessage.fullVerificationCode!!

    // Verify the phone is request OOB verification.
    val oobVerification = VerificationCode.parseFrom(fakeMessageStream.sentMessages[2].payload)
    assertThat(oobVerification.state).isEqualTo(VerificationCodeState.OOB_VERIFICATION)
    val encryptedVerificationCode = oobVerification.payload

    // Decode the message with server IV.
    // Verify its the same as local fullVerificationCode.
    val ihuOobDataManager = IhuOobDataManager(oobData)
    val decrypted =
      ihuOobDataManager.decryptVerificationCode(encryptedVerificationCode.toByteArray())
    assertThat(decrypted).isEqualTo(fullVerificationCode)

    // Confirm PIN on server side; retrieve the encryption key.
    val lastMessage = serverRunner.notifyPinVerified()
    serverKey = lastMessage.key

    // Encrypt the fullVerificationCode with ihu IV.
    val encrypted = ihuOobDataManager.encryptVerificationCode(fullVerificationCode)
    val oobConfirmation = verificationCode {
      state = VerificationCodeState.OOB_VERIFICATION
      payload = ByteString.copyFrom(encrypted)
    }
    for (callback in fakeMessageStream.callbacks) {
      callback.onMessageReceived(createStreamMessage(oobConfirmation.toByteArray()))
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

  private fun createPendingCar(oobChannelManager: OobChannelManager) =
    PendingCarV4Association(
        context,
        messageStream = fakeMessageStream,
        associatedCarManager = associatedCarManager,
        device = bluetoothDevice,
        bluetoothManager = fakeBluetoothGattManager,
        oobData = null,
        oobChannelManagerFactory = FakeOobChannelManagerFactory(oobChannelManager),
        coroutineDispatcher = testDispatcher,
      )
      .apply { callback = mockPendingCarCallback }

  companion object {
    private const val NONCE_LENGTH_BYTES = 12

    private val DEVICE_ID = UUID.randomUUID()
    private val TEST_VERIFICATION_TOKEN = "testVerificationToken".toByteArray()

    private val TEST_KEY = KeyGenerator.getInstance("AES").generateKey()
    private val TEST_MOBILE_IV =
      ByteArray(NONCE_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
    private val TEST_IHU_IV = ByteArray(NONCE_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
  }
}

private class PassThroughOobChannelManager(private val oobData: OobData?) :
  OobChannelManager(oobChannels = emptyList(), executorService = null) {

  override suspend fun readOobData(device: BluetoothDevice) = oobData
}

private class InvalidOobChannelManager(private val oobData: OobData?) :
  OobChannelManager(oobChannels = emptyList(), executorService = null) {

  override suspend fun readOobData(device: BluetoothDevice): OobData? {
    return null
  }
}

private class FakeOobChannelManagerFactory(private val oobChannelManager: OobChannelManager) :
  OobChannelManagerFactory {

  override fun create(
    oobChannelTypes: List<OobChannelType>,
    oobData: OobData?,
    securityVersion: Int
  ) = oobChannelManager
}

private class IhuOobDataManager(oobData: OobData) {
  private val cipher = Cipher.getInstance(ALGORITHM)

  private val encryptionKey: SecretKey
  private val mobileIv: ByteArray
  private val ihuIv: ByteArray

  init {
    check(!(oobData.mobileIv contentEquals oobData.ihuIv)) {
      "Invalid OOB data. IVs must not be the same."
    }

    encryptionKey = SecretKeySpec(oobData.encryptionKey, KeyProperties.KEY_ALGORITHM_AES)
    mobileIv = oobData.mobileIv
    ihuIv = oobData.ihuIv
  }

  fun encryptVerificationCode(verificationCode: ByteArray): ByteArray {
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, IvParameterSpec(ihuIv))
    return cipher.doFinal(verificationCode)
  }

  fun decryptVerificationCode(encryptedMessage: ByteArray): ByteArray {
    cipher.init(Cipher.DECRYPT_MODE, encryptionKey, IvParameterSpec(mobileIv))
    return cipher.doFinal(encryptedMessage)
  }

  companion object {
    private const val ALGORITHM = "AES/GCM/NoPadding"
  }
}
