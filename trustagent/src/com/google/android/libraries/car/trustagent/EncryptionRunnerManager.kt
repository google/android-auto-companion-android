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

import androidx.annotation.VisibleForTesting
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.encryptionrunner.EncryptionRunner
import com.google.android.encryptionrunner.EncryptionRunnerFactory
import com.google.android.encryptionrunner.EncryptionRunnerFactory.EncryptionRunnerType.UKEY2
import com.google.android.encryptionrunner.HandshakeException
import com.google.android.encryptionrunner.HandshakeMessage
import com.google.android.encryptionrunner.HandshakeMessage.HandshakeState
import com.google.android.encryptionrunner.Key
import com.google.android.libraries.car.trustagent.EncryptionRunnerManager.Callback
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.util.logd
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi

private const val TAG = "EncryptionRunnerManager"

/**
 * Establishes encryption with [EncryptionRunner] over [stream] as client side.
 *
 * If [previousKey] is `null`, attempts to establish a new encryption. Caller should implement
 * [Callback.onAuthStringAvailable], and [Callback.onEncryptionEstablished]. When auth string is
 * confirmed, caller should notify this class by [notifyAuthStringConfirmed].
 *
 * If [previousKey] is not `null`, attempts to re-establish an encryption. Caller should implement
 * [Callback.onEncryptionFailure] and [Callback.onEncryptionEstablished].
 *
 * When [stream] disconnects, this manager should be [reset].
 */
internal class EncryptionRunnerManager(
  private var runner: EncryptionRunner,
  private val stream: MessageStream,
  @VisibleForTesting internal val previousKey: ByteArray? = null
) {
  init {
    runner.setIsReconnect(previousKey != null)
  }
  var callback: Callback? = null

  enum class FailureReason {
    /**
     * The Ukey2 key exchanged during reconnection does not match the key stored on the device from
     * the previous session.
     *
     * This failure should only occur during reconnect flows.
     */
    UKEY2_KEY_MISMATCH,

    /**
     * The state of the handshake is [HandshakeState.VERIFICATION_NEEDED] or
     * [HandshakeState.OOB_VERIFICATION_NEEDED], but no verification code is provided in the
     * handshake message.
     *
     * This failure should only occur during association flows.
     */
    NO_VERIFICATION_CODE
  }

  private var state = HandshakeState.UNKNOWN
  private var continueHandshakeMessageId = INVALID_MESSAGE_ID

  private val streamCallback =
    object : MessageStream.Callback {
      override fun onMessageReceived(streamMessage: StreamMessage) {
        // Only accept message of operation type ENCRYPTION_HANDSHAKE.
        // NOTE: in version 1 IHU could send a handshake message of operation type CLIENT_MESSAGE.
        // V1 is no longer suported. Refer to b/145764386 for more info.
        if (streamMessage.operation != OperationType.ENCRYPTION_HANDSHAKE) {
          logi(TAG, "Received message with non-encryption-handshake op. Ignoring.")
          return
        }
        process(streamMessage.payload)
      }

      // Only contains logic for reconnection flow where we need to send 2 consecutive messages.
      override fun onMessageSent(messageId: Int) {
        logi(TAG, "Received onMessageSent callback for $messageId")
        if (messageId != continueHandshakeMessageId) {
          logd(TAG, "Expected $continueHandshakeMessageId to be sent but got $messageId. Ignored")
          return
        }
        if (state != HandshakeState.RESUMING_SESSION) {
          loge(TAG, "$continueHandshakeMessageId was sent but state is $state. Reset.")
          reset()
          return
        }
        // Attempting to re-establish previous encryption by sending init message for
        // reconnection authentication.
        val previousKey =
          checkNotNull(previousKey) {
            "EncryptionRunner next state is RESUMING_SESSION but previous key is null."
          }
        val resumeSessionMessage = runner.initReconnectAuthentication(previousKey)
        logi(TAG, "Resuming session: sending HMAC.")
        sendMessage(resumeSessionMessage.nextMessage)
        state = resumeSessionMessage.handshakeState
      }
    }

  constructor(
    stream: MessageStream,
    previousKey: ByteArray? = null
  ) : this(EncryptionRunnerFactory.newRunner(UKEY2), stream, previousKey)

  /** Resets the internal encryption state; also restores [stream] callback. */
  fun reset() {
    runner = EncryptionRunnerFactory.newRunner(UKEY2)
    state = HandshakeState.UNKNOWN
    continueHandshakeMessageId = INVALID_MESSAGE_ID
    stream.unregisterMessageEventCallback(streamCallback)
  }

  /**
   * Starts encryption workflow.
   *
   * [stream] callback will be taken over until encryption succeeds or resets.
   */
  fun initEncryption() {
    logi(TAG, "initEncryption")

    stream.registerMessageEventCallback(streamCallback)

    val handshakeMessage = runner.initHandshake()
    state = handshakeMessage.handshakeState
    sendMessage(handshakeMessage.nextMessage)
  }

  private fun process(payload: ByteArray) {
    logi(TAG, "Processing current state: $state.")
    when (state) {
      HandshakeState.IN_PROGRESS -> {
        handleInProgressMessage(payload)
      }
      HandshakeState.RESUMING_SESSION -> {
        handleResumingSessionMessage(payload)
      }
      HandshakeState.VERIFICATION_NEEDED -> {
        // This message should allow caller to confirm the verification code.
        // Expect a call to [notifyAuthStringConfirmed].
        logi(TAG, "Received message in state VERIFICATION_NEEDED. Ignored.")
      }
      HandshakeState.OOB_VERIFICATION_NEEDED -> {
        // There should be 2 messages:
        // - 1st message is the OOB verification message;
        // - 2nd message is the confirmation for verification code (confirmed OOB).
        // Expect a call to [notifyAuthStringConfirmed].
        logi(TAG, "Received message in state OOB_VERIFICATION_NEEDED. Ignored.")
      }
      else -> {
        loge(TAG, "Encryption state: $state; received unexpected payload. Reset.")
        reset()
      }
    }
  }

  private fun handleInProgressMessage(payload: ByteArray) {
    require(state == HandshakeState.IN_PROGRESS) { "Expected state IN_PROGRESS; actual $state." }

    val message = runner.continueHandshake(payload)
    val messageId = sendMessage(message.nextMessage)
    // Process the next state.
    state = message.handshakeState
    when (state) {
      HandshakeState.VERIFICATION_NEEDED -> {
        notifyAuthStringAvailable(message)
      }
      HandshakeState.OOB_VERIFICATION_NEEDED -> {
        notifyOobAuthTokenAvailable(message)
      }
      HandshakeState.RESUMING_SESSION -> {
        // RESUMING_SESSION is a state during reconnection.
        // It should continue by triggering onMessageSent().
        continueHandshakeMessageId = messageId
        logi(TAG, "Pending delivery of message $continueHandshakeMessageId.")
      }
      else -> {
        throw IllegalStateException("IN_PROGRESS: after cont. handshake. state is $state.")
      }
    }
  }

  /** Notifies callback about auth string available. */
  private fun notifyAuthStringAvailable(message: HandshakeMessage) {
    val verificationCode = message.verificationCode
    if (verificationCode == null) {
      loge(TAG, "Unexpected. Verification needed, but the code to display is null. Reset.")
      callback?.onEncryptionFailure(FailureReason.NO_VERIFICATION_CODE)
      reset()
      return
    }
    logi(TAG, "Requiring display of verification code: $verificationCode")

    // Attempting to establish a new encryption.
    // Waiting for user to verify on server side; expect confirmation signal.
    callback?.onAuthStringAvailable(verificationCode)
  }

  private fun notifyOobAuthTokenAvailable(message: HandshakeMessage) {
    val oobVerificationCode = message.fullVerificationCode
    if (oobVerificationCode == null) {
      loge(TAG, "Unexpected. Verification needed, but the code is null")
      callback?.onEncryptionFailure(FailureReason.NO_VERIFICATION_CODE)
      reset()
      return
    }
    callback?.onOobAuthTokenAvailable(oobVerificationCode)
  }

  /**
   * Informs the encryption that auth string has been confirmed.
   *
   * This method must only be invoked after callback [onAuthStringAvailable]. Throws exception
   * otherwise.
   */
  fun notifyAuthStringConfirmed() {
    require(
      state == HandshakeState.VERIFICATION_NEEDED || state == HandshakeState.OOB_VERIFICATION_NEEDED
    ) { "Unexpected call of notifyAuthStringConfirmed. Internal state is $state." }
    logi(TAG, "Notify encryption runner verification code is confirmed.")
    val message = runner.notifyPinVerified()

    state = message.handshakeState
    val key = message.key
    if (state != HandshakeState.FINISHED || key == null) {
      "VERIFICATION_NEEDED: unexpected next handshake state: $state; or null key. Reset."
      reset()
      return
    }
    callback?.onEncryptionEstablished(key)
    reset()
  }

  private fun handleResumingSessionMessage(payload: ByteArray) {
    require(state == HandshakeState.RESUMING_SESSION) {
      "Expected state RESUMING_SESSION; actual $state."
    }
    val previousKey =
      checkNotNull(previousKey) {
        "EncryptionRunner current state is RESUMING_SESSION but previous key is null."
      }
    val message: HandshakeMessage =
      try {
        runner.authenticateReconnection(payload, previousKey)
      } catch (e: HandshakeException) {
        loge(TAG, "Could not resume session with previous key. Resetting.")
        reset()
        callback?.onEncryptionFailure(FailureReason.UKEY2_KEY_MISMATCH)
        return
      }

    state = message.handshakeState

    val key = message.key
    if (state != HandshakeState.FINISHED || key == null) {
      "RESUMING_SESSION: unexpected handshake state ($state) or null key. Resetting."
      reset()
      return
    }
    callback?.onEncryptionEstablished(key)
    reset()
  }

  /**
   * Returns the generated message ID of [message].
   *
   * When [message] is delivered, [streamCallback] will be invoked.
   */
  private fun sendMessage(message: ByteArray?): Int {
    checkNotNull(message) { "Received request to send null message." }

    logi(TAG, "Sending message. Current handshake state: $state")
    return stream.sendMessage(
      StreamMessage(
        message,
        OperationType.ENCRYPTION_HANDSHAKE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = null
      )
    )
  }

  /** Callbacks that will be invoked for encryption progress and result. */
  interface Callback {
    /**
     * Invoked when a verification string should be displayed to user. This string should match the
     * one displayed on server side.
     */
    fun onAuthStringAvailable(authString: String)

    /**
     * Invoked when the device should listen for an incoming message and decrypt it using a key that
     * was exchanged via an out of band channel prior to the start of the handshake. The decrypted
     * message should match [oobToken].
     */
    fun onOobAuthTokenAvailable(oobToken: ByteArray)

    /** Invoked when encryption has been established successfully. */
    fun onEncryptionEstablished(key: Key)

    /**
     * Invoked when encryption manager has encountered an error attempting to establish an
     * encryption.
     */
    fun onEncryptionFailure(reason: FailureReason)
  }

  companion object {
    // BleMessageStream can only return non-negative Int as message.
    private const val INVALID_MESSAGE_ID = -1
  }
}
