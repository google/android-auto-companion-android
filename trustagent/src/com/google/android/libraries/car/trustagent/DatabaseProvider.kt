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
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val DATABASE_NAME = "connected-cars-database"

/** Provider of the common database within this library. */
internal class DatabaseProvider private constructor(context: Context) {
  internal val database: ConnectedCarDatabase =
    Room.databaseBuilder(context, ConnectedCarDatabase::class.java, DATABASE_NAME)
      .addMigrations(MIGRATION_3_4)
      .build()

  companion object {
    var instance: DatabaseProvider? = null

    internal fun getInstance(context: Context) =
      instance
        ?: synchronized(this) {
          // Double checked locking. Note the field must be volatile.
          instance ?: DatabaseProvider(context.applicationContext).also { instance = it }
        }
    internal val MIGRATION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
          database.execSQL(
            "ALTER TABLE associated_cars ADD COLUMN macAddress TEXT NOT NULL DEFAULT" +
              " 'AA:BB:CC:DD:EE:FF'"
          )
        }
      }
  }
}
