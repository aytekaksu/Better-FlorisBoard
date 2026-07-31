/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionComponent
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import dev.patrickgold.florisboard.lib.ext.SafeRelativePath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.florisboard.lib.kotlin.io.FsDir
import java.nio.file.Files
import java.nio.file.LinkOption

@Serializable
class LanguagePackComponent(
    override val id: String,
    override val label: String,
    override val authors: List<String>,
    val locale: FlorisLocale = FlorisLocale.fromTag(id),
    val hanShapeBasedKeyCode: String = "abcdefghijklmnopqrstuvwxyz",
) : ExtensionComponent {
    @SerialName("hanShapeBasedTable")
    private val _hanShapeBasedTable: String? = null

    val hanShapeBasedTable
        get() = _hanShapeBasedTable ?: locale.variant
}

@SerialName(LanguagePackExtension.SERIAL_TYPE)
@Serializable
class LanguagePackExtension(
    override val meta: ExtensionMeta,
    override val dependencies: List<String>? = null,
    val items: List<LanguagePackComponent> = emptyList(),
    val hanShapeBasedSQLite: String = "han.sqlite3",
) : Extension() {

    override fun components(): List<ExtensionComponent> = items

    companion object {
        const val SERIAL_TYPE = "ime.extension.languagepack"
    }

    override fun serialType() = SERIAL_TYPE

    @Transient private val hanDatabaseLock = Any()
    @Transient private var hanShapeBasedSQLiteDatabase: SQLiteDatabase? = null

    override fun onAfterLoad(context: Context, cacheDir: FsDir) {
        super.onAfterLoad(context, cacheDir)

        synchronized(hanDatabaseLock) {
            closeHanDatabaseLocked()
            val databasePath = SafeRelativePath.parse(hanShapeBasedSQLite)
                .mapCatching { it.resolveWithin(cacheDir.toPath()).getOrThrow() }
                .getOrNull()
            if (databasePath == null ||
                !Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS)
            ) {
                flogError { "Han shape database is unavailable" }
                throw IllegalStateException("Language database is unavailable.")
            }
            hanShapeBasedSQLiteDatabase = try {
                SQLiteDatabase.openDatabase(
                    databasePath.toFile().path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
            } catch (error: SQLiteException) {
                flogError {
                    "Failed to open Han shape database: error=${error.javaClass.simpleName}"
                }
                throw error
            }
        }
    }

    override fun onBeforeUnload(context: Context, cacheDir: FsDir) {
        try {
            super.onBeforeUnload(context, cacheDir)
        } finally {
            synchronized(hanDatabaseLock) {
                closeHanDatabaseLocked()
            }
        }
    }

    internal fun loadForHanProvider(context: Context): Result<Unit> {
        if (isLoaded() && hasOpenHanDatabase()) {
            return Result.success(Unit)
        }
        val result = load(context, force = isLoaded())
        if (result.isFailure || hasOpenHanDatabase()) {
            return result
        }
        runCatching { unload(context) }
        synchronized(hanDatabaseLock) {
            closeHanDatabaseLocked()
        }
        return Result.failure(IllegalStateException("Language database is unavailable."))
    }

    internal fun unloadForHanProvider(context: Context): Result<Unit> {
        val result = runCatching { unload(context) }
        synchronized(hanDatabaseLock) {
            closeHanDatabaseLocked()
        }
        return result
    }

    internal fun hasOpenHanDatabase(): Boolean =
        synchronized(hanDatabaseLock) {
            openHanDatabaseLocked() != null
        }

    internal fun <T> withHanDatabase(block: (SQLiteDatabase) -> T): T? =
        synchronized(hanDatabaseLock) {
            val database = openHanDatabaseLocked() ?: return@synchronized null
            block(database)
        }

    private fun openHanDatabaseLocked(): SQLiteDatabase? {
        val database = hanShapeBasedSQLiteDatabase ?: return null
        if (!runCatching { database.isOpen }.getOrDefault(false)) {
            hanShapeBasedSQLiteDatabase = null
            return null
        }
        return database
    }

    private fun closeHanDatabaseLocked() {
        val database = hanShapeBasedSQLiteDatabase
        hanShapeBasedSQLiteDatabase = null
        if (database != null) {
            runCatching {
                if (database.isOpen) {
                    database.close()
                }
            }.onFailure {
                flogError { "Failed to close Han shape database" }
            }
        }
    }
}
