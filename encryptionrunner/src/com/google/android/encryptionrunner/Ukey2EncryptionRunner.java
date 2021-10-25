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

import android.util.Log;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.security.cryptauth.lib.securegcm.D2DConnectionContext;
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake;
import com.google.security.cryptauth.lib.securemessage.CryptoOps;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import javax.crypto.spec.SecretKeySpec;

/**
 * An {@link EncryptionRunner} that uses UKey2 as the underlying implementation.
 *
 * <p>See go/ukey2 for more details on UKey2 itself.
 */
public class Ukey2EncryptionRunner implements EncryptionRunner {
  private static final String TAG = "Ukey2EncryptionRunner";

  private static final Ukey2Handshake.HandshakeCipher CIPHER =
      Ukey2Handshake.HandshakeCipher.P256_SHA512;

  // The following values are determined by the resumption logic found at go/d2dsessionresumption.
  private static final int RESUME_HMAC_LENGTH = 32;
  private static final byte[] RESUME = "RESUME".getBytes();
  private static final byte[] SERVER = "SERVER".getBytes();
  private static final byte[] CLIENT = "CLIENT".getBytes();

  /** Full length of the verification code bytes. */
  private static final int FULL_VERIFICATION_LENGTH = 32;

  @IntDef({Mode.UNKNOWN, Mode.CLIENT, Mode.SERVER})
  private @interface Mode {
    int UNKNOWN = 0;
    int CLIENT = 1;
    int SERVER = 2;
  }

  private Ukey2Handshake uKey2Client;
  private boolean isRunnerValid = true;

  private Key currentKey;
  private byte[] currentUniqueSesion;
  private byte[] prevUniqueSesion;
  private boolean isReconnect;
  private boolean isInitReconnectionVerification;

  @Mode private int mode = Mode.UNKNOWN;

  @Override
  public HandshakeMessage initHandshake() throws HandshakeException {
    assertUkey2ClientUninitialized();
    mode = Mode.CLIENT;
    try {
      uKey2Client = Ukey2Handshake.forInitiator(CIPHER);
      return HandshakeMessage.newBuilder()
          .setHandshakeState(getNextHandshakeMessageState())
          .setNextMessage(uKey2Client.getNextHandshakeMessage())
          .build();
    } catch (com.google.security.cryptauth.lib.securegcm.HandshakeException e) {
      Log.e(TAG, "Unexpected exception when creating initial handshake message", e);
      throw new HandshakeException(e);
    }
  }

  @Override
  public void setIsReconnect(boolean isReconnect) {
    this.isReconnect = isReconnect;
  }

  @Override
  public HandshakeMessage respondToInitRequest(byte[] initializationRequest)
      throws HandshakeException {
    assertUkey2ClientUninitialized();
    mode = Mode.SERVER;

    try {
      uKey2Client = Ukey2Handshake.forResponder(CIPHER);
      uKey2Client.parseHandshakeMessage(initializationRequest);
      return HandshakeMessage.newBuilder()
          .setHandshakeState(getNextHandshakeMessageState())
          .setNextMessage(uKey2Client.getNextHandshakeMessage())
          .build();
    } catch (com.google.security.cryptauth.lib.securegcm.HandshakeException
        | Ukey2Handshake.AlertException e) {
      throw new HandshakeException(e);
    }
  }

  @Override
  public HandshakeMessage continueHandshake(byte[] response) throws HandshakeException {
    assertInitialized();

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

      byte[] fullVerificationCode = null;
      if (uKey2Client.getHandshakeState() == Ukey2Handshake.State.VERIFICATION_NEEDED) {
        // getVerificationString() needs to be called before notifyPinVerified().
        fullVerificationCode = uKey2Client.getVerificationString(FULL_VERIFICATION_LENGTH);

        if (isReconnect) {
          HandshakeMessage handshakeMessage = notifyPinVerified();
          return HandshakeMessage.newBuilder()
              .setHandshakeState(handshakeMessage.getHandshakeState())
              .setNextMessage(nextMessage)
              .build();
        }
      }

      return HandshakeMessage.newBuilder()
          .setHandshakeState(getNextHandshakeMessageState())
          .setNextMessage(nextMessage)
          .setFullVerificationCode(fullVerificationCode)
          .build();
    } catch (com.google.security.cryptauth.lib.securegcm.HandshakeException
        | Ukey2Handshake.AlertException e) {
      throw new HandshakeException(e);
    }
  }

  @Override
  public HandshakeMessage notifyPinVerified() throws HandshakeException {
    assertInitialized();
    uKey2Client.verifyHandshake();

    // Need to retrieve the handshake state before `toConnectionContext` is called because that
    // call advances the state within uKey2Client.
    int state = getNextHandshakeMessageState();

    try {
      currentKey = new UKey2Key(uKey2Client.toConnectionContext());
    } catch (com.google.security.cryptauth.lib.securegcm.HandshakeException e) {
      throw new HandshakeException(e);
    }

    return HandshakeMessage.newBuilder().setHandshakeState(state).setKey(currentKey).build();
  }

  /**
   * After getting message from the other device, authenticate the message with the previous stored
   * key.
   *
   * <p>If current device inits the reconnection authentication by calling {@code
   * initReconnectAuthentication} and sends the message to the other device, the other device will
   * call {@code authenticateReconnection()} with the received message and send its own message back
   * to the init device. The init device will call {@code authenticateReconnection()} on the
   * received message, but do not need to set the next message.
   */
  @Override
  public HandshakeMessage authenticateReconnection(byte[] message, byte[] previousKey)
      throws HandshakeException {
    if (!isReconnect) {
      throw new HandshakeException(
          "Call to authenticate reconnection when setIsReconnect(true) has not been called.");
    }

    if (currentKey == null) {
      throw new HandshakeException(
          "Current key is null when authenticating reconnection. "
              + "Ensure notifyPinVerified() called.");
    }

    if (message.length != RESUME_HMAC_LENGTH) {
      isRunnerValid = false;
      throw new HandshakeException(
          String.format(
              "Authentication of reconnection failed because message length of %d "
                  + "does not equal %d",
              message.length, RESUME_HMAC_LENGTH));
    }

    try {
      currentUniqueSesion = currentKey.getUniqueSession();
      prevUniqueSesion = keyOf(previousKey).getUniqueSession();
    } catch (NoSuchAlgorithmException e) {
      throw new HandshakeException(e);
    }

    byte[] currentMessageInfo;
    byte[] nextMessageInfo;

    switch (mode) {
      case Mode.SERVER:
        currentMessageInfo = CLIENT;
        nextMessageInfo = SERVER;
        break;
      case Mode.CLIENT:
        currentMessageInfo = SERVER;
        nextMessageInfo = CLIENT;
        break;
      default:
        throw new IllegalStateException(
            "Encountered unexpected role during authenticateReconnection: " + mode);
    }

    if (!MessageDigest.isEqual(
        message, computeMAC(prevUniqueSesion, currentUniqueSesion, currentMessageInfo))) {
      isRunnerValid = false;
      throw new HandshakeException(
          "Reconnection authentication failed because of message mismatch.");
    }

    byte[] nextMessage =
        isInitReconnectionVerification
            ? null
            : computeMAC(prevUniqueSesion, currentUniqueSesion, nextMessageInfo);

    return HandshakeMessage.newBuilder()
        .setHandshakeState(HandshakeMessage.HandshakeState.FINISHED)
        .setKey(currentKey)
        .setNextMessage(nextMessage)
        .build();
  }

  /**
   * Both client and server can call this method to send authentication message to the other device.
   */
  @Override
  public HandshakeMessage initReconnectAuthentication(byte[] previousKey)
      throws HandshakeException {
    if (!isReconnect) {
      throw new HandshakeException("Reconnection authentication requires setIsReconnect(true).");
    }
    if (currentKey == null) {
      throw new HandshakeException("Current key is null, make sure notifyPinVerified() is called.");
    }

    isInitReconnectionVerification = true;

    try {
      currentUniqueSesion = currentKey.getUniqueSession();
      prevUniqueSesion = keyOf(previousKey).getUniqueSession();
    } catch (NoSuchAlgorithmException e) {
      throw new HandshakeException(e);
    }

    byte[] nextMessageInfo;
    switch (mode) {
      case Mode.SERVER:
        nextMessageInfo = SERVER;
        break;
      case Mode.CLIENT:
        nextMessageInfo = CLIENT;
        break;
      default:
        throw new IllegalStateException(
            "Encountered unexpected role during authenticateReconnection: " + mode);
    }

    return HandshakeMessage.newBuilder()
        .setHandshakeState(HandshakeMessage.HandshakeState.RESUMING_SESSION)
        .setNextMessage(computeMAC(prevUniqueSesion, currentUniqueSesion, nextMessageInfo))
        .build();
  }

  @Override
  public Key keyOf(byte[] serialized) {
    return new UKey2Key(D2DConnectionContext.fromSavedSession(serialized));
  }

  @Override
  public void notifyPinNotValidated() {
    isRunnerValid = false;
  }

  protected Ukey2Handshake getUkey2Client() {
    return uKey2Client;
  }

  @HandshakeMessage.HandshakeState
  private int getNextHandshakeMessageState() {
    assertInitialized();

    // Note: exhaustive switch for enum, so no default case needed.
    // go/bugpattern/UnnecessaryDefaultInEnumSwitch
    switch (uKey2Client.getHandshakeState()) {
      case FINISHED:
        return isReconnect
            ? HandshakeMessage.HandshakeState.RESUMING_SESSION
            : HandshakeMessage.HandshakeState.FINISHED;
      case IN_PROGRESS:
        return HandshakeMessage.HandshakeState.IN_PROGRESS;
      case VERIFICATION_IN_PROGRESS:
      case VERIFICATION_NEEDED:
        return HandshakeMessage.HandshakeState.VERIFICATION_NEEDED;
      case ALREADY_USED:
      case ERROR:
        throw new IllegalStateException(
            "Request to retrieve handshake state, but currently in error state");
    }

    // This should not happen because the switch is exhaustive.
    throw new IllegalStateException(
        "Encounterd unknown handshake state " + uKey2Client.getHandshakeState());
  }

  protected void assertInitialized() {
    if (uKey2Client == null) {
      throw new IllegalStateException("runner not initialized");
    }
    if (!isRunnerValid) {
      throw new IllegalStateException("runner has been invalidated");
    }
  }

  private void assertUkey2ClientUninitialized() {
    if (uKey2Client != null) {
      throw new IllegalStateException("UKey2Client already initialized.");
    }
  }

  @Nullable
  private static byte[] computeMAC(byte[] previous, byte[] next, byte[] info) {
    try {
      // No algorithm specified because  key type is just plain raw bytes.
      SecretKeySpec inputKeyMaterial =
          new SecretKeySpec(concatByteArrays(previous, next), /* algorithm= */ "");
      return CryptoOps.hkdf(inputKeyMaterial, RESUME, info);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // Does not happen in practice
      Log.e(TAG, "Computation of resumption MAC has failed", e);
      return null;
    }
  }

  private static byte[] concatByteArrays(@NonNull byte[] a, @NonNull byte[] b) {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    try {
      outputStream.write(a);
      outputStream.write(b);
    } catch (IOException e) {
      return new byte[0];
    }
    return outputStream.toByteArray();
  }

  /**
   * A key that uses an established {@link D2DConnectionContext} to encrypt and decrypt messages.
   */
  private static class UKey2Key implements Key {
    private final D2DConnectionContext connectionContext;

    UKey2Key(@NonNull D2DConnectionContext connectionContext) {
      this.connectionContext = connectionContext;
    }

    @Override
    public byte[] asBytes() {
      return connectionContext.saveSession();
    }

    @Override
    public byte[] encryptData(byte[] data) {
      return connectionContext.encodeMessageToPeer(data);
    }

    @Override
    public byte[] decryptData(byte[] encryptedData) throws SignatureException {
      return connectionContext.decodeMessageFromPeer(encryptedData);
    }

    @Override
    public byte[] getUniqueSession() throws NoSuchAlgorithmException {
      return connectionContext.getSessionUnique();
    }
  }
}
