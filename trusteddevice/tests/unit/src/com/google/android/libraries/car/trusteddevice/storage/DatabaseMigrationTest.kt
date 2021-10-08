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

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.time.Clock
import org.junit.Test
import org.junit.runner.RunWith

private val DEFAULT_CAR_ID = "testId"
private val DEFAULT_HANDLE = "handle".toByteArray()
private val DEFAULT_TOKEN = "token".toByteArray()

private val CREDENTIALS_TABLE_NAME = "credentials"
private val UNLOCK_HISTORY_TABLE_NAME = "unlock_history"

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
  private val TEST_DB = "migration-test"

  val helper: MigrationTestHelper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      TrustedDeviceDatabase::class.java.canonicalName,
      FrameworkSQLiteOpenHelperFactory()
    )

  @Test
  @Throws(IOException::class)
  fun testMigrate3To4() {
    var database = helper.createDatabase(TEST_DB, 3)

    var values = ContentValues()
    values.put("carId", DEFAULT_CAR_ID)
    values.put("token", DEFAULT_HANDLE)
    values.put("handle", DEFAULT_TOKEN)
    database.insert(CREDENTIALS_TABLE_NAME, SQLiteDatabase.CONFLICT_REPLACE, values)

    val now = Clock.systemUTC().instant()
    values = ContentValues()
    values.put("carId", DEFAULT_CAR_ID)
    values.put("instant", now.getNano())
    database.insert(UNLOCK_HISTORY_TABLE_NAME, SQLiteDatabase.CONFLICT_REPLACE, values)

    database.close()

    database = helper.runMigrationsAndValidate(TEST_DB, 4, true, DatabaseProvider.DB_MIGRATION_3_4)
    database.close()
  }
}
