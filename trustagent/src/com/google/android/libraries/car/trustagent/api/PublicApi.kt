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

package com.google.android.libraries.car.trustagent.api

/**
 * Signifies this class or interface is intended to be exposed publicly.
 *
 * This annotation is also used to keep the annotated types during Proguard. Retention set to BINARY
 * to enable annotation to be seen by Proguard.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
)
@Retention(AnnotationRetention.BINARY)
annotation class PublicApi()
