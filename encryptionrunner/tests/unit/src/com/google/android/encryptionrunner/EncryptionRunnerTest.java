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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.encryptionrunner.EncryptionRunnerFactory.EncryptionRunnerType;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Combined unit test for {@link Ukey2EncryptionRunner} and {@link FakeEncryptionRunner} due to the
 * flows for both being similar.
 */
@RunWith(AndroidJUnit4.class)
public class EncryptionRunnerTest {
  private final byte[] testData = "testData".getBytes(StandardCharsets.UTF_8);

  private Key clientKey;
  private Key serverKey;

  @After
  public void tearDown() {
    clientKey = null;
    serverKey = null;
  }

  @Test
  public void happyFlow_fakeRunner() throws Exception {
    assertValidFlowCanEncryptDecryptMessages(
        EncryptionRunnerFactory::newFakeRunner, EncryptionRunnerTest::assertHandshake);
  }

  @Test
  public void happyFlow_ukey2Runner() throws Exception {
    assertValidFlowCanEncryptDecryptMessages(
        EncryptionRunnerTest::newRunner, EncryptionRunnerTest::assertHandshake);
  }

  @Test
  public void happyFlow_oobUkey2Runner() throws Exception {
    assertValidFlowCanEncryptDecryptMessages(
        EncryptionRunnerTest::newOobRunner, EncryptionRunnerTest::assertOobHandshake);
  }

  @Test
  public void happyFlow_fakeRunner_reconnect() throws Exception {
    setUpFirstConnection(
        EncryptionRunnerFactory::newFakeRunner, EncryptionRunnerTest::assertHandshake);
    assertValidReconnectionFlowCanEncryptDecryptMessages(EncryptionRunnerFactory::newFakeRunner);
  }

  @Test
  public void happyFlow_uKey2Runner_reconnect() throws Exception {
    setUpFirstConnection(EncryptionRunnerTest::newRunner, EncryptionRunnerTest::assertHandshake);
    assertValidReconnectionFlowCanEncryptDecryptMessages(EncryptionRunnerTest::newRunner);
  }

  @Test
  public void fakeRunner_reconnect_encrypt_and_decrypt() throws Exception {
    setUpFirstConnection(
        EncryptionRunnerFactory::newFakeRunner, EncryptionRunnerTest::assertHandshake);
    setUpReconnection(
        EncryptionRunnerFactory::newFakeRunner, EncryptionRunnerTest::assertHandshake);
    assertThat(clientKey.decryptData(serverKey.encryptData(testData))).isEqualTo(testData);
  }

  @Test
  public void uKey2Runner_reconnect_encrypt_and_decrypt() throws Exception {
    setUpFirstConnection(EncryptionRunnerTest::newRunner, EncryptionRunnerTest::assertHandshake);
    setUpReconnection(EncryptionRunnerTest::newRunner, EncryptionRunnerTest::assertHandshake);
    assertThat(clientKey.decryptData(serverKey.encryptData(testData))).isEqualTo(testData);
  }

  @Test
  public void invalidPin_fake() throws Exception {
    assertInvalidPinThrowsError(
        EncryptionRunnerFactory::newFakeRunner, EncryptionRunnerTest::assertHandshake);
  }

  @Test
  public void invalidPin_ukey2() throws Exception {
    assertInvalidPinThrowsError(
        EncryptionRunnerTest::newRunner, EncryptionRunnerTest::assertHandshake);
  }

  @Test
  public void invalidPin_oobUkey2() throws Exception {
    assertInvalidPinThrowsError(
        EncryptionRunnerTest::newOobRunner, EncryptionRunnerTest::assertOobHandshake);
  }

  private static EncryptionRunner newRunner() {
    return EncryptionRunnerFactory.newRunner(EncryptionRunnerType.UKEY2);
  }

  private static EncryptionRunner newOobRunner() {
    return EncryptionRunnerFactory.newRunner(EncryptionRunnerType.OOB_UKEY2);
  }

  private void setUpFirstConnection(
      RunnerFactory runnerFactory, HandshakeAsserter handshakeAsserter) throws Exception {
    EncryptionRunner clientRunner = runnerFactory.newRunner();
    EncryptionRunner serverRunner = runnerFactory.newRunner();
    handshakeAsserter.assertHandshake(clientRunner, serverRunner);
    HandshakeMessage finalServerMessage = serverRunner.notifyPinVerified();
    HandshakeMessage finalClientMessage = clientRunner.notifyPinVerified();
    serverKey = finalServerMessage.getKey();
    clientKey = finalClientMessage.getKey();
  }

  private void setUpReconnection(RunnerFactory runnerFactory, HandshakeAsserter handshakeAsserter)
      throws Exception {
    setUpFirstConnection(runnerFactory, handshakeAsserter);
    EncryptionRunner clientRunner = runnerFactory.newRunner();
    EncryptionRunner serverRunner = runnerFactory.newRunner();
    assertHandshakeReconnect(clientRunner, serverRunner);
    HandshakeMessage nextClientMessage =
        clientRunner.initReconnectAuthentication(clientKey.asBytes());
    HandshakeMessage finalServerMessage =
        serverRunner.authenticateReconnection(
            nextClientMessage.getNextMessage(), serverKey.asBytes());
    HandshakeMessage finalClientMessage =
        clientRunner.authenticateReconnection(
            finalServerMessage.getNextMessage(), serverKey.asBytes());
    serverKey = finalServerMessage.getKey();
    clientKey = finalClientMessage.getKey();
  }

  /**
   * Runs through the set up of the handshake and verifies that messages can be encrypted and
   * decrypted afterwards.
   */
  private void assertValidFlowCanEncryptDecryptMessages(
      RunnerFactory runnerFactory, HandshakeAsserter handshakeAsserter) throws Exception {
    EncryptionRunner clientRunner = runnerFactory.newRunner();
    EncryptionRunner serverRunner = runnerFactory.newRunner();

    handshakeAsserter.assertHandshake(clientRunner, serverRunner);

    HandshakeMessage finalServerMessage = serverRunner.notifyPinVerified();
    assertThat(finalServerMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.FINISHED);
    assertThat(finalServerMessage.getKey()).isNotNull();
    assertThat(finalServerMessage.getNextMessage()).isNull();

    HandshakeMessage finalClientMessage = clientRunner.notifyPinVerified();
    assertThat(finalClientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.FINISHED);
    assertThat(finalClientMessage.getKey()).isNotNull();
    assertThat(finalClientMessage.getNextMessage()).isNull();

    assertThat(
            finalServerMessage
                .getKey()
                .decryptData(finalClientMessage.getKey().encryptData(testData)))
        .isEqualTo(testData);
    assertThat(
            finalClientMessage
                .getKey()
                .decryptData(finalServerMessage.getKey().encryptData(testData)))
        .isEqualTo(testData);
  }

  private void assertValidReconnectionFlowCanEncryptDecryptMessages(RunnerFactory runnerFactory)
      throws Exception {
    EncryptionRunner clientRunner = runnerFactory.newRunner();
    EncryptionRunner serverRunner = runnerFactory.newRunner();
    assertHandshakeReconnect(clientRunner, serverRunner);

    HandshakeMessage nextClientMessage =
        clientRunner.initReconnectAuthentication(clientKey.asBytes());
    assertThat(nextClientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.RESUMING_SESSION);
    assertThat(nextClientMessage.getKey()).isNull();
    assertThat(nextClientMessage.getNextMessage()).isNotNull();

    HandshakeMessage finalServerMessage =
        serverRunner.authenticateReconnection(
            nextClientMessage.getNextMessage(), serverKey.asBytes());
    assertThat(finalServerMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.FINISHED);
    assertThat(finalServerMessage.getKey()).isNotNull();
    assertThat(finalServerMessage.getNextMessage()).isNotNull();

    HandshakeMessage finalClientMessage =
        clientRunner.authenticateReconnection(
            finalServerMessage.getNextMessage(), serverKey.asBytes());
    assertThat(finalClientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.FINISHED);
    assertThat(finalClientMessage.getKey()).isNotNull();
    assertThat(finalClientMessage.getNextMessage()).isNull();

    assertThat(
            finalServerMessage
                .getKey()
                .decryptData(finalClientMessage.getKey().encryptData(testData)))
        .isEqualTo(testData);
    assertThat(
            finalClientMessage
                .getKey()
                .decryptData(finalServerMessage.getKey().encryptData(testData)))
        .isEqualTo(testData);
  }

  private static void assertHandshake(EncryptionRunner clientRunner, EncryptionRunner serverRunner)
      throws Exception {
    HandshakeMessage initialClientMessage = clientRunner.initHandshake();

    assertThat(initialClientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.IN_PROGRESS);
    assertThat(initialClientMessage.getKey()).isNull();
    assertThat(initialClientMessage.getNextMessage()).isNotNull();

    HandshakeMessage initialServerMessage =
        serverRunner.respondToInitRequest(initialClientMessage.getNextMessage());

    assertThat(initialServerMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.IN_PROGRESS);
    assertThat(initialServerMessage.getKey()).isNull();
    assertThat(initialServerMessage.getNextMessage()).isNotNull();

    HandshakeMessage clientMessage =
        clientRunner.continueHandshake(initialServerMessage.getNextMessage());

    assertThat(clientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.VERIFICATION_NEEDED);
    assertThat(clientMessage.getKey()).isNull();
    assertThat(clientMessage.getVerificationCode()).isNotEmpty();
    assertThat(clientMessage.getNextMessage()).isNotNull();

    HandshakeMessage serverMessage = serverRunner.continueHandshake(clientMessage.getNextMessage());
    assertThat(serverMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.VERIFICATION_NEEDED);
    assertThat(serverMessage.getKey()).isNull();
    assertThat(serverMessage.getNextMessage()).isNull();
  }

  private static void assertOobHandshake(
      EncryptionRunner clientRunner, EncryptionRunner serverRunner) throws Exception {
    HandshakeMessage initialClientMessage = clientRunner.initHandshake();

    assertThat(initialClientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.IN_PROGRESS);
    assertThat(initialClientMessage.getKey()).isNull();
    assertThat(initialClientMessage.getNextMessage()).isNotNull();

    HandshakeMessage initialServerMessage =
        serverRunner.respondToInitRequest(initialClientMessage.getNextMessage());

    assertThat(initialServerMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.IN_PROGRESS);
    assertThat(initialServerMessage.getKey()).isNull();
    assertThat(initialServerMessage.getNextMessage()).isNotNull();

    HandshakeMessage clientMessage =
        clientRunner.continueHandshake(initialServerMessage.getNextMessage());

    assertThat(clientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.OOB_VERIFICATION_NEEDED);
    assertThat(clientMessage.getKey()).isNull();
    assertThat(clientMessage.getOobVerificationCode()).isNotEmpty();
    assertThat(clientMessage.getNextMessage()).isNotNull();

    HandshakeMessage serverMessage = serverRunner.continueHandshake(clientMessage.getNextMessage());
    assertThat(serverMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.OOB_VERIFICATION_NEEDED);
    assertThat(serverMessage.getKey()).isNull();
    assertThat(serverMessage.getNextMessage()).isNull();
  }

  private static void assertHandshakeReconnect(
      EncryptionRunner clientRunner, EncryptionRunner serverRunner) throws HandshakeException {
    clientRunner.setIsReconnect(true);
    serverRunner.setIsReconnect(true);

    HandshakeMessage initialClientMessage = clientRunner.initHandshake();
    assertThat(initialClientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.IN_PROGRESS);
    assertThat(initialClientMessage.getKey()).isNull();
    assertThat(initialClientMessage.getNextMessage()).isNotNull();

    HandshakeMessage initialServerMessage =
        serverRunner.respondToInitRequest(initialClientMessage.getNextMessage());

    assertThat(initialServerMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.IN_PROGRESS);
    assertThat(initialServerMessage.getKey()).isNull();
    assertThat(initialServerMessage.getNextMessage()).isNotNull();

    HandshakeMessage clientMessage =
        clientRunner.continueHandshake(initialServerMessage.getNextMessage());

    assertThat(clientMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.RESUMING_SESSION);
    assertThat(clientMessage.getKey()).isNull();
    assertThat(clientMessage.getNextMessage()).isNotNull();

    HandshakeMessage serverMessage = serverRunner.continueHandshake(clientMessage.getNextMessage());
    assertThat(serverMessage.getHandshakeState())
        .isEqualTo(HandshakeMessage.HandshakeState.RESUMING_SESSION);
    assertThat(serverMessage.getKey()).isNull();
  }

  private static void assertInvalidPinThrowsError(
      RunnerFactory runnerFactory, HandshakeAsserter handshakeAsserter) throws Exception {
    EncryptionRunner clientRunner = runnerFactory.newRunner();
    EncryptionRunner serverRunner = runnerFactory.newRunner();

    handshakeAsserter.assertHandshake(clientRunner, serverRunner);
    clientRunner.notifyPinNotValidated();
    serverRunner.notifyPinNotValidated();

    try {
      clientRunner.notifyPinVerified();
      Assert.fail();
    } catch (Exception ignored) {
      // pass
    }

    try {
      serverRunner.notifyPinVerified();
      Assert.fail();
    } catch (Exception ignored) {
      // pass
    }
  }

  /** An interface for creating encryption runners that are under test. */
  private interface RunnerFactory {
    /** Returns a new runner for testing. */
    EncryptionRunner newRunner();
  }

  /** An interface for validating encryption handshakes in a test environment. */
  private interface HandshakeAsserter {
    /** Steps through encryption handshake verifying each message sent between server and client. */
    void assertHandshake(EncryptionRunner clientRunner, EncryptionRunner serverRunner)
        throws Exception;
  }
}
