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

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardFilesDatabaseMigrationAndroidTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClipboardFilesDatabase::class.java,
    )

    @Test
    fun migratesVersionOneMetadataToCurrentSchemaWithoutDroppingRows() {
        migrationHelper.createDatabase(DATABASE_NAME, 1).use { database ->
            database.execSQL(
                """
                INSERT INTO `clipboard_files`
                    (`_id`, `_display_name`, `_size`, `mimeTypes`)
                VALUES (7, 'legacy', 4, 'image/png')
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            6,
            true,
            ClipboardFilesDatabase.MIGRATION_1_TO_3,
            ClipboardFilesDatabase.MIGRATION_4_TO_5,
            ClipboardFilesDatabase.MIGRATION_5_TO_6,
        ).use { database ->
            database.query(
                """
                SELECT `_id`, `_display_name`, `_size`, `mimeTypes`,
                    `orientation`, `ownership_state`, `is_system_root`,
                    `paste_retained_until_ms`, `external_capability_boot_count`,
                    `share_operation_token`, `share_request_fingerprint`,
                    `share_pending_boot_count`,
                    `share_pending_deadline_elapsed_realtime_ms`
                FROM `clipboard_files`
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(7L, cursor.getLong(0))
                assertEquals("legacy", cursor.getString(1))
                assertEquals(4L, cursor.getLong(2))
                assertEquals("image/png", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(ClipboardMediaOwnershipState.LEGACY.value, cursor.getInt(5))
                assertEquals(0, cursor.getInt(6))
                assertEquals(0, cursor.getInt(7))
                assertEquals(LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT, cursor.getInt(8))
                assertEquals(null, cursor.getString(9))
                assertEquals(null, cursor.getString(10))
                assertEquals(null, cursor.getString(11))
                assertEquals(0L, cursor.getLong(12))
            }
        }
    }

    @Test
    fun migratesVersionTwoOrientationAndMimeMetadataToCurrentSchema() {
        migrationHelper.createDatabase(DATABASE_NAME, 2).use { database ->
            database.execSQL(
                """
                INSERT INTO `clipboard_files`
                    (`_id`, `_display_name`, `_size`, `orientation`, `mimeTypes`)
                VALUES (12, 'legacy-oriented', 8, 270, 'image/jpeg,image/*')
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            6,
            true,
            ClipboardFilesDatabase.MIGRATION_4_TO_5,
            ClipboardFilesDatabase.MIGRATION_5_TO_6,
        ).use { database ->
            database.query(
                """
                SELECT `_id`, `orientation`, `mimeTypes`, `ownership_state`,
                    `is_system_root`, `paste_retained_until_ms`,
                    `external_capability_boot_count`, `share_operation_token`,
                    `share_request_fingerprint`, `share_pending_boot_count`,
                    `share_pending_deadline_elapsed_realtime_ms`
                FROM `clipboard_files`
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(12L, cursor.getLong(0))
                assertEquals(270, cursor.getInt(1))
                assertEquals("image/jpeg,image/*", cursor.getString(2))
                assertEquals(ClipboardMediaOwnershipState.LEGACY.value, cursor.getInt(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(0L, cursor.getLong(5))
                assertEquals(LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT, cursor.getInt(6))
                assertEquals(null, cursor.getString(7))
                assertEquals(null, cursor.getString(8))
                assertEquals(null, cursor.getString(9))
                assertEquals(0L, cursor.getLong(10))
            }
        }
    }

    @Test
    fun migrationMarksPossiblyExposedRowsButNotPendingInstalls() {
        migrationHelper.createDatabase(DATABASE_NAME, 4).use { database ->
            database.execSQL(
                """
                INSERT INTO `clipboard_files`
                    (`_id`, `_display_name`, `_size`, `orientation`, `mimeTypes`,
                     `ownership_state`, `is_system_root`, `paste_retained_until_ms`)
                VALUES
                    (7, 'system', 4, 0, 'image/png', 2, 1, 0),
                    (8, 'paste', 4, 0, 'image/png', 2, 0, 42),
                    (9, 'private', 4, 0, 'image/png', 2, 0, 0),
                    (10, 'retiring', 4, 0, 'image/png', 3, 0, 0),
                    (11, 'pending', 4, 0, 'image/png', 1, 0, 0)
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            6,
            true,
            ClipboardFilesDatabase.MIGRATION_4_TO_5,
            ClipboardFilesDatabase.MIGRATION_5_TO_6,
        ).use { database ->
            database.query(
                """
                SELECT `_id`, `external_capability_boot_count`
                FROM `clipboard_files`
                ORDER BY `_id`
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(5, cursor.count)
                cursor.moveToNext()
                repeat(4) { index ->
                    assertEquals(7L + index, cursor.getLong(0))
                    assertEquals(LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT, cursor.getInt(1))
                    cursor.moveToNext()
                }
                assertEquals(11L, cursor.getLong(0))
                assertEquals(null, cursor.getString(1))
            }
        }
    }

    @Test
    fun migrationAddsUniqueShareIdentityAndEmptyMonotonicClaimClock() {
        migrationHelper.createDatabase(DATABASE_NAME, 5).use { database ->
            database.execSQL(
                """
                INSERT INTO `clipboard_files`
                    (`_id`, `_display_name`, `_size`, `orientation`, `mimeTypes`,
                     `ownership_state`, `is_system_root`, `paste_retained_until_ms`,
                     `external_capability_boot_count`)
                VALUES
                    (21, 'first', 4, 0, 'image/png', 1, 0, 0, NULL),
                    (22, 'second', 4, 0, 'image/png', 1, 0, 0, NULL)
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            6,
            true,
            ClipboardFilesDatabase.MIGRATION_5_TO_6,
        ).use { database ->
            database.query(
                """
                SELECT `share_operation_token`, `share_request_fingerprint`,
                    `share_pending_boot_count`,
                    `share_pending_deadline_elapsed_realtime_ms`
                FROM `clipboard_files`
                ORDER BY `_id`
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(2, cursor.count)
                while (cursor.moveToNext()) {
                    assertEquals(null, cursor.getString(0))
                    assertEquals(null, cursor.getString(1))
                    assertEquals(null, cursor.getString(2))
                    assertEquals(0L, cursor.getLong(3))
                }
            }
            database.execSQL(
                """
                UPDATE `clipboard_files`
                SET `share_operation_token` = 'token'
                WHERE `_id` = 21
                """.trimIndent(),
            )
            val duplicateRejected = runCatching {
                database.execSQL(
                    """
                    UPDATE `clipboard_files`
                    SET `share_operation_token` = 'token'
                    WHERE `_id` = 22
                    """.trimIndent(),
                )
            }.isFailure
            assertEquals(true, duplicateRejected)
        }
    }

    companion object {
        private const val DATABASE_NAME = "clipboard-files-migration-test"
    }
}
