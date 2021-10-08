/*
 * Copyright (C) 2020 The Android Open Source Project
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

import androidx.annotation.IntDef;
import com.google.common.primitives.Bytes;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * An encryption runner that doesn't actually do encryption. Useful for debugging. Do not use in
 * production environments.
 */
public class FakeEncryptionRunner implements EncryptionRunner {

  private static final byte[] FAKE_MESSAGE = "Fake Message".getBytes();
  private static final byte[] TEST_ENCRYPT_PADDING = "padding".getBytes();

  public static final byte[] INIT_MESSAGE = "init".getBytes();
  public static final byte[] INIT_MESSAGE_EMPTY_RESPONSE = "initEmptyResponse".getBytes();
  public static final byte[] INIT_RESPONSE = "initResponse".getBytes();
  public static final byte[] CLIENT_RESPONSE = "clientResponse".getBytes();
  public static final byte[] RECONNECTION_MESSAGE_STATE_ERROR =
      "reconnectMessageStateError".getBytes();
  public static final byte[] RECONNECTION_MESSAGE_KEY_ERROR = "reconnectMessageKeyError".getBytes();
  public static final byte[] RECONNECTION_MESSAGE_EMPTY_RESPONSE =
      "reconnectMessageEmptyResponse".getBytes();
  public static final String VERIFICATION_CODE = "1234";

  /** The role that this runner is playing. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({Mode.UNKNOWN, Mode.CLIENT, Mode.SERVER})
  protected @interface Mode {
    int UNKNOWN = 0;
    int CLIENT = 1;
    int SERVER = 2;
  }

  private boolean isReconnect;
  private boolean isInitReconnectVerification;
  @Mode private int mode;

  @HandshakeMessage.HandshakeState private int state;

  @Override
  public HandshakeMessage initHandshake() throws HandshakeException {
    if (state != HandshakeMessage.HandshakeState.UNKNOWN) {
      throw new IllegalStateException("runner already initialized.");
    }

    mode = Mode.CLIENT;
    state = HandshakeMessage.HandshakeState.IN_PROGRESS;

    return HandshakeMessage.newBuilder()
        .setHandshakeState(state)
        .setNextMessage(INIT_MESSAGE)
        .build();
  }

  @Override
  public HandshakeMessage respondToInitRequest(byte[] initializationRequest)
      throws HandshakeException {
    if (state != HandshakeMessage.HandshakeState.UNKNOWN) {
      throw new IllegalStateException("runner already initialized.");
    }

    mode = Mode.SERVER;

    if (Arrays.equals(INIT_MESSAGE_EMPTY_RESPONSE, initializationRequest)) {
      return HandshakeMessage.newBuilder()
          .setHandshakeState(HandshakeMessage.HandshakeState.IN_PROGRESS)
          .setNextMessage(null)
          .build();
    }

    if (!Arrays.equals(INIT_MESSAGE, initializationRequest)) {
      throw new HandshakeException("Unexpected initialization request");
    }

    state = HandshakeMessage.HandshakeState.IN_PROGRESS;

    return HandshakeMessage.newBuilder()
        .setHandshakeState(HandshakeMessage.HandshakeState.IN_PROGRESS)
        .setNextMessage(INIT_RESPONSE)
        .build();
  }

  @Override
  public HandshakeMessage continueHandshake(byte[] response) throws HandshakeException {
    if (state != HandshakeMessage.HandshakeState.IN_PROGRESS) {
      throw new HandshakeException("not waiting for response but got one");
    }

    byte[] expectedResponse;
    byte[] nextMessage;

    switch (mode) {
      case Mode.SERVER:
        expectedResponse = CLIENT_RESPONSE;
        nextMessage = null;
        break;
      case Mode.CLIENT:
        expectedResponse = INIT_RESPONSE;
        nextMessage = CLIENT_RESPONSE;
        break;
      default:
        throw new IllegalStateException(
            "Encountered unexpected role during continuation of handshake: " + mode);
    }

    if (!Arrays.equals(expectedResponse, response)) {
      throw new HandshakeException(
          String.format(
              "Expected (%s) but received (%s) during handshake continuation",
              new String(expectedResponse), new String(response)));
    }

    // The state needs to be set to verification needed before a call to `verifyPin`.
    state = HandshakeMessage.HandshakeState.VERIFICATION_NEEDED;

    // If reconnecting, then blindly accept pairing code.
    if (isReconnect) {
      notifyPinVerified();
      state = HandshakeMessage.HandshakeState.RESUMING_SESSION;
    }

    return HandshakeMessage.newBuilder()
        .setVerificationCode(VERIFICATION_CODE)
        .setNextMessage(nextMessage)
        .setHandshakeState(state)
        .build();
  }

  @Override
  public HandshakeMessage authenticateReconnection(byte[] message, byte[] previousKey)
      throws HandshakeException {
    if (Arrays.equals(RECONNECTION_MESSAGE_STATE_ERROR, message)) {
      return HandshakeMessage.newBuilder()
          .setHandshakeState(HandshakeMessage.HandshakeState.IN_PROGRESS)
          .setKey(new FakeKey())
          .setNextMessage(isInitReconnectVerification ? null : FAKE_MESSAGE)
          .build();
    }

    if (Arrays.equals(RECONNECTION_MESSAGE_KEY_ERROR, message)) {
      return HandshakeMessage.newBuilder()
          .setHandshakeState(HandshakeMessage.HandshakeState.FINISHED)
          .setNextMessage(isInitReconnectVerification ? null : FAKE_MESSAGE)
          .build();
    }

    if (Arrays.equals(RECONNECTION_MESSAGE_EMPTY_RESPONSE, message)) {
      return HandshakeMessage.newBuilder()
          .setHandshakeState(HandshakeMessage.HandshakeState.FINISHED)
          .setKey(new FakeKey())
          .setNextMessage(null)
          .build();
    }

    // Blindly verify the reconnection because this is a fake encryption runner.
    return HandshakeMessage.newBuilder()
        .setHandshakeState(HandshakeMessage.HandshakeState.FINISHED)
        .setKey(new FakeKey())
        .setNextMessage(isInitReconnectVerification ? null : FAKE_MESSAGE)
        .build();
  }

  @Override
  public HandshakeMessage initReconnectAuthentication(byte[] previousKey)
      throws HandshakeException {
    isInitReconnectVerification = true;
    state = HandshakeMessage.HandshakeState.RESUMING_SESSION;

    return HandshakeMessage.newBuilder()
        .setHandshakeState(state)
        .setNextMessage(FAKE_MESSAGE)
        .build();
  }

  @Override
  public Key keyOf(byte[] serialized) {
    return new FakeKey();
  }

  @Override
  public HandshakeMessage notifyPinVerified() throws HandshakeException {
    if (state != HandshakeMessage.HandshakeState.VERIFICATION_NEEDED) {
      throw new HandshakeException("asking to verify pin, state = " + state);
    }

    state = HandshakeMessage.HandshakeState.FINISHED;
    return HandshakeMessage.newBuilder().setKey(new FakeKey()).setHandshakeState(state).build();
  }

  @Override
  public void notifyPinNotValidated() {
    state = HandshakeMessage.HandshakeState.INVALID;
  }

  @Override
  public void setIsReconnect(boolean isReconnect) {
    this.isReconnect = isReconnect;
  }

  @HandshakeMessage.HandshakeState
  protected int getState() {
    return state;
  }

  protected void setState(@HandshakeMessage.HandshakeState int state) {
    this.state = state;
  }

  @Mode
  protected int getMode() {
    return mode;
  }

  /**
   * Encrypted the given byte array with schema defined in {@link FakeKey} class.
   * @param data The byte array ready to be encrypted.
   * @return The encrypted byt array.
   */
  public static byte[] encryptDataWithFakeKey(byte[] data) {
    return Bytes.concat(TEST_ENCRYPT_PADDING, data);
  }

  private static byte[] decryptDataWithFakeKey(byte[] encryptedData) {
    return Arrays.copyOfRange(encryptedData, TEST_ENCRYPT_PADDING.length, encryptedData.length);
  }

  static class FakeKey implements Key {
    private static final byte[] KEY_BYTES = "key".getBytes();
    private static final byte[] UNIQUE_SESSION_BYTES = "unique_session".getBytes();

    @Override
    public byte[] asBytes() {
      return KEY_BYTES;
    }

    @Override
    public byte[] encryptData(byte[] data) {
      return encryptDataWithFakeKey(data);
    }

    @Override
    public byte[] decryptData(byte[] encryptedData) {
      return decryptDataWithFakeKey(encryptedData);
    }

    @Override
    public byte[] getUniqueSession() {
      return UNIQUE_SESSION_BYTES;
    }
  }
}
