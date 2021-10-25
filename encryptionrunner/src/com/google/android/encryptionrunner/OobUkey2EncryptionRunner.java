/*
 * Copyright (C) 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.encryptionrunner;

import com.google.android.encryptionrunner.HandshakeMessage.HandshakeState;
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake;

/**
 * An {@link EncryptionRunner} that uses UKey2 as the underlying implementation, and generates a
 * longer token for the out of band verification step.
 *
 * <p>See go/aae-oob-batmobile-design for more info.
 *
 * <p>See go/ukey2 for more details on UKey2 itself.
 */
public class OobUkey2EncryptionRunner extends Ukey2EncryptionRunner {
  // Choose max verification string length supported by Ukey2
  private static final int VERIFICATION_STRING_LENGTH = 32;

  @Override
  public HandshakeMessage continueHandshake(byte[] response) throws HandshakeException {
    assertInitialized();

    Ukey2Handshake uKey2Client = getUkey2Client();

    try {
      if (uKey2Client.getHandshakeState() != Ukey2Handshake.State.IN_PROGRESS) {
        throw new IllegalStateException(
            "handshake is not in progress, state =" + uKey2Client.getHandshakeState());
      }
      uKey2Client.parseHandshakeMessage(response);

      // Not obvious from ukey2 api, but getting the next message can change the state.
      // calling getNext message might go from in progress to verification needed, on
      // the assumption that we already send this message to the peer.
      byte[] nextMessage = null;
      if (uKey2Client.getHandshakeState() == Ukey2Handshake.State.IN_PROGRESS) {
        nextMessage = uKey2Client.getNextHandshakeMessage();
      }

      byte[] verificationCode = null;
      if (uKey2Client.getHandshakeState() == Ukey2Handshake.State.VERIFICATION_NEEDED) {
        // getVerificationString() needs to be called before notifyPinVerified().
        verificationCode = uKey2Client.getVerificationString(VERIFICATION_STRING_LENGTH);
      }

      return HandshakeMessage.newBuilder()
          .setHandshakeState(HandshakeState.OOB_VERIFICATION_NEEDED)
          .setNextMessage(nextMessage)
          .setFullVerificationCode(verificationCode)
          .build();
    } catch (com.google.security.cryptauth.lib.securegcm.HandshakeException
        | Ukey2Handshake.AlertException e) {
      throw new HandshakeException(e);
    }
  }
}
