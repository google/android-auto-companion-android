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

import androidx.annotation.NonNull;

/**
 * A generalized interface that allows for generating shared secrets as well as encrypting messages.
 *
 * <p>To use this interface:
 *
 * <p>1. As a client.
 *
 * <pre>{@code
 * HandshakeMessage initialClientMessage = clientRunner.initHandshake();
 * sendToServer(initialClientMessage.getNextMessage());
 * byte message = getServerResponse();
 * HandshakeMessage message = clientRunner.continueHandshake(message);
 * }
 *
 * <p>If it is a first-time connection,
 *
 * <p>{@code message.getHandshakeState()} should be {@code VERIFICATION_NEEDED}, show user the
 * verification code and ask to verify. After user confirmed:
 *
 * <p>{@code HandshakeMessage lastMessage = clientRunner.notifyPinVerified();}
 *
 * <p>Otherwise, call {@code clientRunner.notifyPinNotValidated();}
 *
 * <p>Once the handshake has been completed, the last handshake message returned will contain the
 * encryption key. Use {@code lastMessage.getKey()} to get the key for encryption.
 *
 * <p>If it is a reconnection, i.e. you have already established a secure connection before with the
 * other device, then call {@link #initReconnectAuthentication(byte[])} after the last handshake.
 * There is no need to call any of the PIN verification methods.
 *
 * <pre>{@code
 * clientMessage = clientRunner.initReconnectAuthentication(previousKey);
 * sendToServer(clientMessage.getNextMessage());
 * HandshakeMessage lastMessage = clientRunner.authenticateReconnection(previousKey, message);
 * }
 *
 * <p>{@code lastMessage.getHandshakeState()} should be {@code FINISHED} if reconnection handshake
 * is done.
 *
 * <p>2. As a server.
 *
 * <pre>{@code
 * byte[] initialMessage = getClientMessageBytes();
 * HandshakeMessage message = serverRunner.respondToInitRequest(initialMessage);
 * sendToClient(message.getNextMessage());
 * byte[] clientMessage = getClientResponse();
 * HandshakeMessage message = serverRunner.continueHandshake(clientMessage);
 * }
 *
 * <p>if it is a first-time connection,
 *
 * <p>{@code message.getHandshakeState()} should be {@code VERIFICATION_NEEDED}. Show the user the
 * verification code and ask to verify. After PIN is confirmed:
 *
 * <p>{@code HandshakeMessage lastMessage = serverRunner.verifyPin}
 *
 * <p>Otherwise, call {@code clientRunner.notifyPinNotValidated();}
 *
 * <p>Use {@code lastMessage.getKey()} to get the key for encryption.
 *
 * <p>If it this is a reconnection, {@code message.getHandshakeState()} should be
 * {@code RESUMING_SESSION}. After client message been received, generate a corresponding message to
 * send back to the client.
 *
 * <pre>{@code
 * serverMessage = serverRunner.authenticateReconnection(previousKey, message);
 * sendToClient(serverMessage.getNextMessage());
 * }
 *
 * <p>Also see {@link EncryptionRunnerTest} for examples.
 */
public interface EncryptionRunner {
  /**
   * Starts an encryption handshake.
   *
   * <p>The returned handshake message should be passed to another {@code EncryptionRunner} to
   * continue to handshake.
   *
   * @return A handshake message with information about the handshake that is started.
   */
  @NonNull
  HandshakeMessage initHandshake() throws HandshakeException;

  /**
   * Starts an encryption handshake where the device that is being communicated with already
   * initiated the request.
   *
   * <p>The initialization request should be the message returned from the other device's {@link
   * #initHandshake()} method.
   *
   * @param initializationRequest the bytes that the other device sent over.
   * @return a handshake message with information about the handshake.
   * @throws HandshakeException if initialization request is invalid.
   */
  @NonNull
  HandshakeMessage respondToInitRequest(@NonNull byte[] initializationRequest)
      throws HandshakeException;

  /**
   * Continues a handshake after receiving another response from the connected device.
   *
   * <p>The response passed to this method should be the message returned from the other device's
   * {@link #responseToInitRequest(byte[])} method.
   *
   * @param response the response from the other device.
   * @return a message that can be used to continue the handshake.
   * @throws HandshakeException if unexpected bytes in response.
   */
  @NonNull
  HandshakeMessage continueHandshake(@NonNull byte[] response) throws HandshakeException;

  /**
   * Notifies this runner that the user has verified the pin shown.
   *
   * <p>The handshake message returned will typically contain an encryption key that can be used to
   * encrypt and decrypt messages.
   *
   * @return A message that contains the encryption key.
   * @throws HandshakeException if not in state to verify pin.
   */
  @NonNull
  HandshakeMessage notifyPinVerified() throws HandshakeException;

  /**
   * Notifies the encryption runner that the user failed to validate the pin. After calling this
   * method the runner should not be used, and will throw exceptions.
   */
  void notifyPinNotValidated();

  /**
   * Verifies the reconnection message.
   *
   * <p>The message passed to this method should have been generated by {@link
   * #initReconnectAuthentication(byte[] previousKey)}.
   *
   * <p>If the message is valid, then a {@link HandshakeMessage} will be returned that contains the
   * encryption key and a handshake message which can be used to verify the other side of the
   * connection.
   *
   * @param previousKey previously stored key.
   * @param message message from the client
   * @return a handshake message with an encryption key if verification succeed.
   * @throws HandshakeException if the message does not match.
   */
  @NonNull
  HandshakeMessage authenticateReconnection(@NonNull byte[] message, @NonNull byte[] previousKey)
      throws HandshakeException;

  /**
   * Initiates the reconnection verification by generating a message that should be sent to the
   * device that is being reconnected to.
   *
   * @param previousKey previously stored key.
   * @return a handshake message with client's message which will be sent to server.
   * @throws HandshakeException when get encryption key's unique session fail.
   */
  @NonNull
  HandshakeMessage initReconnectAuthentication(@NonNull byte[] previousKey)
      throws HandshakeException;

  /**
   * De-serializes a previously serialized key generated by an instance of this encryption runner.
   *
   * @param serialized the serialized bytes of the key.
   * @return the Key object used for encryption.
   */
  @NonNull
  Key keyOf(@NonNull byte[] serialized);

  /**
   * Sets whether this runner is re-establishing a previous secure connection.
   *
   * @param isReconnect {@code true} for reconnection; {@code false} if a new secure session should
   *     be set up.
   */
  void setIsReconnect(boolean isReconnect);
}
