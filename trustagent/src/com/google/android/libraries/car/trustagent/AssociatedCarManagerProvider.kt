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

import android.content.Context

/** Provides the manager that stores data related to associated cars. */
internal class AssociatedCarManagerProvider private constructor(context: Context) {
  internal val manager: AssociatedCarManager = AssociatedCarManager(context)

  companion object {
    @Volatile var instance: AssociatedCarManagerProvider? = null

    internal fun getInstance(context: Context) =
      instance
        ?: synchronized(this) {
          // Double checked locking. Note the field must be volatile.
          instance
            ?: AssociatedCarManagerProvider(context.applicationContext).also { instance = it }
        }
  }
}
