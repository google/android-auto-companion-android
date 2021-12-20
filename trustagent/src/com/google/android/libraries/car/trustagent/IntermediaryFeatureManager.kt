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

/**
 * Interface manages the intermidiary state of feature setup between association is done and
 * association information is shown to the user.
 */
interface IntermediaryFeatureManager {
  /**
   * [listener] should be notified when the feature finished the SUW setup flow.
   *
   * Set it to [null] will clear the previous listener.
   */
  fun setOnCompletedListener(listener: OnCompletedListener?)

  /** Listener which listens to feature setup event. */
  fun interface OnCompletedListener {
    /**
     * Will be invoked when the feature completes the work and passed the result [isSuccess] to
     * listener.
     */
    fun onCompleted(isSuccess: Boolean)
  }
}
