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

package com.google.android.libraries.car.connectionservice

import androidx.lifecycle.LifecycleService
import com.google.android.libraries.car.trustagent.FeatureManager
import com.google.android.libraries.car.trustagent.PeriodicPingManager
import com.google.android.libraries.car.trustagent.SystemFeatureManager
import com.google.android.libraries.car.trustagent.api.PublicApi

/** A base service that creates features and allows in-process (binding) retrieval. */
@PublicApi
abstract class FeatureManagerService : LifecycleService() {
  /**
   * Creates a list of [FeatureManager]s.
   *
   * This method should not be directly invoked. To retrieve the features, use [featureManagers].
   */
  abstract fun createFeatureManagers(): List<FeatureManager>

  val featureManagers: List<FeatureManager> by lazy {
    // Make a copy of the list to avoid manipulation by subclass. Always add an additional system
    // feature manager that is responsible for handling device-level queries.
    createFeatureManagers().toMutableList().apply {
      add(SystemFeatureManager(context = this@FeatureManagerService))
      add(PeriodicPingManager())
    }
  }

  /**
   * Returns the [FeatureManager] instance of type [T].
   *
   * Returns `null` if the feature manager does not exist.
   */
  inline fun <reified T> getFeatureManager(): T? = featureManagers.find { it is T } as? T

  /**
   * Returns the [FeatureManager] instance of type [featureClass]; returns `null` if the feature
   * manager does not exist.
   *
   * This method is intended for Java clients, where kotlin inline reified method is not supported.
   */
  fun <T> getFeatureManager(featureClass: Class<T>): T? =
    featureClass.cast(featureManagers.find { featureClass.isInstance(it) })
}
