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

package com.google.android.libraries.car.trusteddevice.storage

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val DATABASE_NAME = "trusted-device-database"

/** Provider of the common database within this library. */
class DatabaseProvider private constructor(context: Context) {
  internal val database: TrustedDeviceDatabase =
    Room.databaseBuilder(context, TrustedDeviceDatabase::class.java, DATABASE_NAME)
      .addMigrations(DB_MIGRATION_1_2, DB_MIGRATION_3_4)
      .fallbackToDestructiveMigration()
      .build()

  companion object {
    /**
     * Database migration from version 1 to 2 requires simply clearing all the data.
     *
     * This migration corresponds with the migration of the `trustagent`'s migration to a new
     * version. This is to prevent leftover data from carried over when `trustagent`'s database
     * changes.
     */
    private val DB_MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
          database.execSQL("DELETE FROM credentials")
          database.execSQL("DELETE FROM unlock_history")
        }
      }

    /**
     * Database migration from version 3 to 4.
     *
     * This migration requires the creation of a new table to store feature state that needs to be
     * synced between the car and phone.
     */
    internal val DB_MIGRATION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
          database.execSQL(
            "CREATE TABLE IF NOT EXISTS `feature_state` " +
              "(`carId` TEXT NOT NULL, `state` BLOB NOT NULL, PRIMARY KEY(`carId`))"
          )
        }
      }

    private var instance: DatabaseProvider? = null

    fun getInstance(context: Context) =
      instance
        ?: synchronized(this) {
          // Double checked locking. Note the field must be volatile.
          instance ?: DatabaseProvider(context.applicationContext).also { instance = it }
        }
  }
}
