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

import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * During an {@link EncryptionRunner} handshake process, these are the messages returned as part of
 * each step.
 */
public class HandshakeMessage {

  /** States for handshake progress. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    HandshakeState.UNKNOWN,
    HandshakeState.IN_PROGRESS,
    HandshakeState.VERIFICATION_NEEDED,
    HandshakeState.FINISHED,
    HandshakeState.INVALID,
    HandshakeState.RESUMING_SESSION,
    HandshakeState.OOB_VERIFICATION_NEEDED,
  })
  public @interface HandshakeState {
    /** The initial state, this value is not expected to be returned. */
    int UNKNOWN = 0;
    /** The handshake is in progress. */
    int IN_PROGRESS = 1;
    /** The handshake is complete, but verification of the code is needed. */
    int VERIFICATION_NEEDED = 2;
    /** The handshake is complete. */
    int FINISHED = 3;
    /** The handshake is complete and not successful. */
    int INVALID = 4;
    /** The handshake is complete, but extra verification is needed. */
    int RESUMING_SESSION = 5;
    /** The handshake is complete, but out of band verification of the code is needed. */
    int OOB_VERIFICATION_NEEDED = 6;
  }

  /** The length of the verification string. 6 is chosen as the length for product reasons. */
  private static final int AUTH_STRING_LENGTH = 6;

  @HandshakeState private final int handshakeState;
  private final Key key;
  private final byte[] nextMessage;
  private final String verificationCode;
  private final byte[] fullVerificationCode;

  private HandshakeMessage(
      @HandshakeState int handshakeState,
      @Nullable Key key,
      @Nullable byte[] nextMessage,
      @Nullable String verificationCode,
      @Nullable byte[] fullVerificationCode) {
    this.handshakeState = handshakeState;
    this.key = key;
    this.nextMessage = nextMessage;
    this.verificationCode = verificationCode;
    this.fullVerificationCode = fullVerificationCode;
  }

  /** Returns the next message to send in a handshake. */
  @Nullable
  public byte[] getNextMessage() {
    return nextMessage == null ? null : nextMessage.clone();
  }

  /** Returns the state of the handshake. */
  @HandshakeState
  public int getHandshakeState() {
    return handshakeState;
  }

  /** Returns the encryption key that can be used to encrypt data. */
  @Nullable
  public Key getKey() {
    return key;
  }

  /**
   * Returns a string representing the truncated bytes returned by
   * {@link #getFullVerificationCode()} to show to the user.
   */
  @Nullable
  public String getVerificationCode() {
    return verificationCode;
  }

  /** Returns the full bytes of the verification code. */
  @Nullable
  public byte[] getFullVerificationCode() {
    return fullVerificationCode;
  }

  /** Returns a builder for {@link HandshakeMessage}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  static class Builder {
    @HandshakeState int handshakeState;
    Key key;
    byte[] nextMessage;
    String verificationCode;
    byte[] fullVerificationCode;

    Builder setHandshakeState(@HandshakeState int handshakeState) {
      this.handshakeState = handshakeState;
      return this;
    }

    Builder setKey(@Nullable Key key) {
      this.key = key;
      return this;
    }

    Builder setNextMessage(@Nullable byte[] nextMessage) {
      this.nextMessage = nextMessage == null ? null : nextMessage.clone();
      return this;
    }

    Builder setFullVerificationCode(@Nullable byte[] fullVerificationCode) {
      this.fullVerificationCode = fullVerificationCode;
      if (fullVerificationCode == null) {
        verificationCode = null;
        return this;
      }
      verificationCode =
          generateReadablePairingCode(Arrays.copyOf(fullVerificationCode, AUTH_STRING_LENGTH));
      return this;
    }

    HandshakeMessage build() {
      if (handshakeState == HandshakeState.UNKNOWN) {
        throw new IllegalStateException("Handshake state must be set before calling build");
      }

      if (handshakeState == HandshakeState.VERIFICATION_NEEDED
          && TextUtils.isEmpty(verificationCode)) {
        throw new IllegalStateException(
            "Handshake state of verification needed requires a verification code.");
      }

      if (handshakeState == HandshakeState.OOB_VERIFICATION_NEEDED
          && (fullVerificationCode == null || fullVerificationCode.length == 0)) {
        throw new IllegalStateException(
            "Handshake state of OOB verification needed requires an out of band verification"
                + " code.");
      }

      return new HandshakeMessage(
          handshakeState, key, nextMessage, verificationCode, fullVerificationCode);
    }

    /**
     * Returns a human-readable pairing code string generated from the verification bytes. Converts
     * each byte into a digit with a simple modulo.
     *
     * <p>This should match the implementation in the iOS and Android client libraries.
     */
    private static String generateReadablePairingCode(byte[] verificationCode) {
      StringBuilder outString = new StringBuilder();
      for (byte b : verificationCode) {
        int unsignedInt = toUnsignedInt(b);
        int digit = unsignedInt % 10;
        outString.append(digit);
      }

      return outString.toString();
    }

    /**
     * Converts the argument to an {@code int} by an unsigned conversion.
     *
     * @param value the value to convert to an unsigned {@code int}
     * @return the argument converted to {@code int} by an unsigned conversion
     */
    @SuppressWarnings("AndroidJdkLibsChecker") // Call already guarded against API version.
    private static int toUnsignedInt(byte value) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return Byte.toUnsignedInt(value);
      }

      return ((int) value) & 0xff;
    }
  }
}
