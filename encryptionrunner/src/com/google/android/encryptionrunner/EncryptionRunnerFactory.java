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

import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;

/** Factory that creates encryption runner. */
public class EncryptionRunnerFactory {
  private EncryptionRunnerFactory() {}

  /** Types of {@link EncryptionRunner}s. */
  @IntDef({EncryptionRunnerType.UKEY2, EncryptionRunnerType.OOB_UKEY2})
  public @interface EncryptionRunnerType {
    /** Use Ukey2 as underlying key exchange. */
    int UKEY2 = 0;
    /** Use Ukey2 and an out of band channel as underlying key exchange. */
    int OOB_UKEY2 = 1;
  }

  /** Creates a new {@link EncryptionRunner} based on type. */
  public static EncryptionRunner newRunner(@EncryptionRunnerType int type) {
    switch (type) {
      case EncryptionRunnerType.UKEY2:
        return new Ukey2EncryptionRunner();
      case EncryptionRunnerType.OOB_UKEY2:
        return new OobUkey2EncryptionRunner();
      default:
        throw new IllegalArgumentException("Unknown EncryptionRunnerType: " + type);
    }
  }

  /**
   * Creates a new {@link EncryptionRunner} one that doesn't actually do encryption but is useful
   * for testing.
   */
  @VisibleForTesting
  public static EncryptionRunner newFakeRunner() {
    return new FakeEncryptionRunner();
  }

  /**
   * Creates a new {@link EncryptionRunner} that doesn't actually do encryption but is useful for
   * out of band association testing.
   */
  @VisibleForTesting
  public static EncryptionRunner newOobFakeRunner() {
    return new OobFakeEncryptionRunner();
  }
}
