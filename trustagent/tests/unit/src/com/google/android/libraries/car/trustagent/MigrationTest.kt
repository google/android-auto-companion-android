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

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
  private val TEST_DB = "migration-test"

  val helper: MigrationTestHelper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      ConnectedCarDatabase::class.java.canonicalName,
      FrameworkSQLiteOpenHelperFactory()
    )

  @Test
  @Throws(IOException::class)
  fun testMigrate3To4() {
    var db = helper.createDatabase(TEST_DB, 3)

    val values = ContentValues()
    values.put("id", "testId")
    values.put("encryptionKey", "testEncryptionKey")
    values.put("identificationKey", "testIdentificationKey")
    values.put("name", "testName")
    values.put("isUserRenamed", false)
    db.insert("associated_cars", SQLiteDatabase.CONFLICT_REPLACE, values)
    db.close()

    // Re-open the database with version 4 and provide
    db = helper.runMigrationsAndValidate(TEST_DB, 4, true, DatabaseProvider.MIGRATION_3_4)
    db.close()
  }
}
