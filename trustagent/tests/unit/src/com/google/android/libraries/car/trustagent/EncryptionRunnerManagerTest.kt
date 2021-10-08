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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.encryptionrunner.EncryptionRunner
import com.google.android.encryptionrunner.EncryptionRunnerFactory
import com.google.android.encryptionrunner.EncryptionRunnerFactory.EncryptionRunnerType.OOB_UKEY2
import com.google.android.encryptionrunner.HandshakeMessage.HandshakeState
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val INIT_MESSAGE_ID = 1
private const val CONT_MESSAGE_ID = 2
private const val HMAC_MESSAGE_ID = 3
private const val TAG = "Test"

private val PREVIOUS_KEY = "previous_key".toByteArray()
private val TEST_DATA = "initResponse".toByteArray()

@RunWith(AndroidJUnit4::class)
class EncryptionRunnerManagerTest {

  private lateinit var manager: EncryptionRunnerManager
  private lateinit var clientRunner: EncryptionRunner
  private lateinit var serverRunner: EncryptionRunner
  private val streamCallbacks = mutableListOf<MessageStream.Callback>()

  private val mockV2Stream: MessageStream = mock()
  private val mockEncryptionCallback: EncryptionRunnerManager.Callback = mock()

  @Before
  fun setUp() {
    clientRunner = EncryptionRunnerFactory.newFakeRunner()
    serverRunner = EncryptionRunnerFactory.newFakeRunner()

    setUpEncryptionManager()

    // Expected total messages are 3.
    whenever(mockV2Stream.sendMessage(any()))
      .thenReturn(INIT_MESSAGE_ID)
      .thenReturn(CONT_MESSAGE_ID)
      .thenReturn(HMAC_MESSAGE_ID)
  }

  private fun setUpEncryptionManager(previousKey: ByteArray? = null) {
    manager = EncryptionRunnerManager(clientRunner, mockV2Stream, previousKey)
    manager.callback = mockEncryptionCallback
  }

  private fun setUpOob() {
    clientRunner = EncryptionRunnerFactory.newRunner(OOB_UKEY2)
    serverRunner = EncryptionRunnerFactory.newRunner(OOB_UKEY2)

    setUpEncryptionManager()
  }

  @Test
  fun testFirstTimeEncryption_showVerificationCode() {
    initEncryption()
    respondToClientInitMessage()

    verify(mockEncryptionCallback).onAuthStringAvailable(any())
  }

  @Test
  fun testOobFirstTimeEncryption_onOobVerificationCodeCallbackInvoked() {
    setUpOob()

    initEncryption()
    respondToClientInitMessage()

    verify(mockEncryptionCallback).onOobAuthTokenAvailable(any())
  }

  @Test
  fun testFirstTimeEncryption_confirmVerificationCode() {
    initEncryption()
    respondToClientInitMessage()
    respondToContMessage(HandshakeState.VERIFICATION_NEEDED)

    verify(mockEncryptionCallback).onEncryptionEstablished(any())
  }

  @Test
  fun testOobFirstTimeEncryption_confirmOobVerificationCode() {
    setUpOob()

    initEncryption()
    respondToClientInitMessage()
    respondToContMessage(HandshakeState.OOB_VERIFICATION_NEEDED)

    verify(mockEncryptionCallback).onEncryptionEstablished(any())
  }

  @Test
  fun testReconnection_success() {
    setUpEncryptionManager(PREVIOUS_KEY)

    initEncryption()
    respondToClientInitMessage()
    respondToReconnection()

    verify(mockEncryptionCallback).onEncryptionEstablished(any())
  }

  @Test
  fun testV2StreamWithClientMessageOperationTypeMessage_ignore() {
    setUpEncryptionManager()

    initEncryption()
    // initEncryption() would triggers a message.
    verify(mockV2Stream).sendMessage(any())

    streamCallbacks[0].onMessageReceived(
      StreamMessage(
        TEST_DATA,
        OperationType.CLIENT_MESSAGE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = null
      )
    )
    // Verify non-encryption-handshake message (client_message) is ignored.
    verify(mockV2Stream).sendMessage(any())
  }

  private fun initEncryption() {
    manager.initEncryption()
    argumentCaptor<MessageStream.Callback>().apply {
      verify(mockV2Stream).registerMessageEventCallback(capture())
      streamCallbacks.add(firstValue)
    }
  }

  private fun respondToClientInitMessage() {
    argumentCaptor<StreamMessage>().apply {
      verify(mockV2Stream).sendMessage(capture())

      val payload = lastValue.payload
      Log.i(TAG, "respondToClientInitMessage: ${payload.toString(Charsets.UTF_8)}")
      val message = serverRunner.respondToInitRequest(payload)
      streamCallbacks.forEach {
        it.onMessageReceived(createStreamMessage(message.nextMessage!!))
      }
    }
  }

  private fun respondToContMessage(expectedState: Int) {
    argumentCaptor<StreamMessage>().apply {
      // times(2) because cont. message is the second one after init message.
      verify(mockV2Stream, times(2)).sendMessage(capture())

      val payload = lastValue.payload
      Log.i(TAG, "respondToContMessage: ${payload.toString(Charsets.UTF_8)}")
      // Calling continueHandshake() only verifies call from server end;
      // it doesn't affect the response.
      serverRunner.continueHandshake(payload).also {
        assertThat(it.handshakeState).isEqualTo(expectedState)
      }
      // After user confirming verification code on server side (IHU), notify client.
      manager.notifyAuthStringConfirmed()
    }
  }

  // TODO(b/140443698): Test handling bad previous key; need support from dummy encryption runner.
  private fun respondToReconnection(previousKey: ByteArray = PREVIOUS_KEY) {
    // client sends 2 consecutive messages: cont., then HMAC.
    // Trigger sending HMAC by notifying the second message has been sent.
    streamCallbacks.forEach { it.onMessageSent(CONT_MESSAGE_ID) }
    // times(3) because cont. is the second message; HMAC is the third message.
    argumentCaptor<StreamMessage>().apply {
      verify(mockV2Stream, times(3)).sendMessage(capture())

      val message = serverRunner.authenticateReconnection(lastValue.payload, previousKey).also {
        assertThat(it.handshakeState).isEqualTo(HandshakeState.FINISHED)
      }
      streamCallbacks.forEach {
        it.onMessageReceived(createStreamMessage(message.nextMessage!!))
      }
    }
  }

  private fun createStreamMessage(payload: ByteArray): StreamMessage {
    return StreamMessage(
      payload,
      operation = OperationType.ENCRYPTION_HANDSHAKE,
      isPayloadEncrypted = false,
      originalMessageSize = 0,
      recipient = null
    )
  }
}
