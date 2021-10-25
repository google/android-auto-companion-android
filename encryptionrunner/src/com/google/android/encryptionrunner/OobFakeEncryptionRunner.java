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

import java.util.Arrays;

/**
 * An encryption runner that doesn't actually do encryption. Useful for debugging out of band
 * association. Do not use in production environments.
 */
public class OobFakeEncryptionRunner extends FakeEncryptionRunner {
  @Override
  public HandshakeMessage continueHandshake(byte[] response) throws HandshakeException {
    if (getState() != HandshakeMessage.HandshakeState.IN_PROGRESS) {
      throw new HandshakeException("not waiting for response but got one");
    }

    @HandshakeMessage.HandshakeState
    int newState = HandshakeMessage.HandshakeState.OOB_VERIFICATION_NEEDED;
    switch (getMode()) {
      case Mode.SERVER:
        if (!Arrays.equals(CLIENT_RESPONSE, response)) {
          throw new HandshakeException("unexpected response: " + new String(response));
        }
        setState(newState);
        return HandshakeMessage.newBuilder()
            .setFullVerificationCode(VERIFICATION_CODE)
            .setHandshakeState(newState)
            .build();
      case Mode.CLIENT:
        if (!Arrays.equals(INIT_RESPONSE, response)) {
          throw new HandshakeException("unexpected response: " + new String(response));
        }
        setState(newState);
        return HandshakeMessage.newBuilder()
            .setHandshakeState(newState)
            .setNextMessage(CLIENT_RESPONSE)
            .setFullVerificationCode(VERIFICATION_CODE)
            .build();
      default:
        throw new IllegalStateException("unexpected role: " + getMode());
    }
  }

  @Override
  public HandshakeMessage notifyPinVerified() throws HandshakeException {
    @HandshakeMessage.HandshakeState int state = getState();
    if (state != HandshakeMessage.HandshakeState.OOB_VERIFICATION_NEEDED) {
      throw new IllegalStateException("asking to verify pin, state = " + state);
    }
    state = HandshakeMessage.HandshakeState.FINISHED;
    return HandshakeMessage.newBuilder().setKey(new FakeKey()).setHandshakeState(state).build();
  }
}
