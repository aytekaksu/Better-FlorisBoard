/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.clipboard.provider

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardHistoryDatabaseMigrationAndroidTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClipboardHistoryDatabase::class.java,
    )

    @Test
    fun migrationRepairsOnlyLegacyNullTextUris() {
        migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, 4).use { database ->
            database.execSQL(
                """
                INSERT INTO `clipboard_history`
                    (`_id`, `type`, `text`, `uri`, `creationTimestampMs`, `isPinned`,
                     `mimeTypes`, `is_sensitive`, `is_remote_device`)
                VALUES
                    (1, 1, 'legacy text', 'null', 1, 0, 'text/plain', 0, 0),
                    (2, 1, 'other text', 'content://example/kept', 2, 0, 'text/plain', 0, 0),
                    (3, 2, NULL, 'null', 3, 0, 'image/png', 0, 0)
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE_NAME,
            5,
            true,
            ClipboardHistoryDatabase.MIGRATION_4_TO_5,
        ).use { database ->
            database.query(
                """
                SELECT `uri`
                FROM `clipboard_history`
                ORDER BY `_id`
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(3, cursor.count)
                assertTrue(cursor.moveToNext())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.moveToNext())
                assertEquals("content://example/kept", cursor.getString(0))
                assertTrue(cursor.moveToNext())
                assertEquals("null", cursor.getString(0))
            }
        }
    }

    @Test
    fun daoRoundTripStoresTextUriAsSqlNull() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ClipboardHistoryDatabase::class.java,
        ).build()
        try {
            val dao = database.clipboardItemDao()
            val id = dao.insert(
                ClipboardItem.text("round-trip text").copy(
                    creationTimestampMs = 1L,
                ),
            )

            val stored = dao.getAll().single()
            assertEquals(id, stored.id)
            assertNull(stored.uri)
            database.openHelper.readableDatabase.query(
                "SELECT `uri` FROM `clipboard_history` WHERE `_id` = $id",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        } finally {
            database.close()
        }
    }

    companion object {
        private const val MIGRATION_DATABASE_NAME = "clipboard-history-migration-test"
    }
}
