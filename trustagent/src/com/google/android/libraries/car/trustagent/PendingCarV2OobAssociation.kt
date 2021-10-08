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
import com.google.android.companionprotos.OperationProto.OperationType
import com.google.android.encryptionrunner.EncryptionRunnerFactory
import com.google.android.libraries.car.trustagent.blemessagestream.BluetoothConnectionManager
import com.google.android.libraries.car.trustagent.blemessagestream.MessageStream
import com.google.android.libraries.car.trustagent.blemessagestream.StreamMessage
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * Represents a car that is ready to be associated by out-of-band verification.
 *
 * Initiates connection to a remote device using existing out of band data for verification, namely
 * [Callback.onAuthStringAvailable] will NOT be invoked for association. Verification UI should not
 * be shown to the user.
 *
 * The verification sequence is:
 * 1. Phone sends OOB verification message to HU.
 * 2. HU verifies the message and sends back its own OOB verification message.
 * 3. Phone verifies that message matches its expected value.
 * 4. If confirmed, then finish and establish a secure channel.
 *
 * Successful connection will invoke [Callback.onConnected].
 *
 * See also [PendingCarV2Association] and [PendingCarV2Reconnection].
 */
internal open class PendingCarV2OobAssociation
internal constructor(
  context: Context,
  messageStream: MessageStream,
  associatedCarManager: AssociatedCarManager,
  device: BluetoothDevice,
  bluetoothManager: BluetoothConnectionManager,
  private val oobConnectionManager: OobConnectionManager,
  coroutineScope: CoroutineScope = MainScope()
) :
  PendingCarV2Association(
    context,
    messageStream,
    associatedCarManager,
    device,
    bluetoothManager,
    coroutineScope
  ) {
  private var oobAuthToken: ByteArray? = null

  override val encryptionRunnerType = EncryptionRunnerFactory.EncryptionRunnerType.OOB_UKEY2

  override fun handleOobAuthToken(token: ByteArray) {
    logi(TAG, "Received Oob auth string of ${token.size} bytes, sending oob data.")
    oobAuthToken = token
    state = State.PENDING_OOB_CONFIRMATION
    val encryptedVerificationCode = oobConnectionManager.encryptVerificationCode(token)
    messageStream.sendMessage(
      StreamMessage(
        payload = encryptedVerificationCode,
        operation = OperationType.ENCRYPTION_HANDSHAKE,
        isPayloadEncrypted = false,
        originalMessageSize = 0,
        recipient = null
      )
    )
  }

  override fun handleOobVerificationMessage(streamMessage: StreamMessage) {
    if (streamMessage.operation != OperationType.ENCRYPTION_HANDSHAKE) {
      loge(TAG, "Received non-handshake message ${streamMessage.operation}. Ignored.")
      return
    }

    val token = oobAuthToken
    if (token == null) {
      loge(TAG, "Received OOB verification message but OOB auth token is null.")
      callback?.onConnectionFailed(this@PendingCarV2OobAssociation)
      return
    }

    val decryptedMessage = oobConnectionManager.decryptVerificationCode(streamMessage.payload)
    if (!decryptedMessage.contentEquals(token)) {
      loge(TAG, "OOB verification exchange failed: verification codes don't match.")
      callback?.onConnectionFailed(this@PendingCarV2OobAssociation)
      return
    }

    state = State.PENDING_CONFIRMATION
    // Internally we blindly accept the auth string so encryption key is ready
    // to decrypt the next message as IHU device ID.
    // This leads to onEncryptionEstablished() callback.
    encryptionRunnerManager?.notifyAuthStringConfirmed()
  }

  override fun cleanUp() {
    super.cleanUp()
    oobAuthToken = null
  }

  companion object {
    private const val TAG = "PendingCarV2OobAssociation"
  }
}
