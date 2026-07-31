/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

import android.content.ClipData
import android.content.ContentResolver
import android.content.ClipDescription.EXTRA_IS_REMOTE_DEVICE
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import android.provider.BaseColumns
import android.provider.MediaStore.Images.Media
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.patrickgold.florisboard.R
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.UriSerializer
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.kotlin.tryOrNull

private const val CLIPBOARD_HISTORY_TABLE = "clipboard_history"
private const val CLIPBOARD_FILES_TABLE = "clipboard_files"
private const val EXTRA_CLIP_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
internal const val MAX_CLIPBOARD_FUTURE_SKEW_MS = 5L * 60L * 1_000L

internal fun isValidClipboardTimestamp(
    timestampMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (timestampMs < 0L || nowMs < 0L) return false
    val latest = if (nowMs > Long.MAX_VALUE - MAX_CLIPBOARD_FUTURE_SKEW_MS) {
        Long.MAX_VALUE
    } else {
        nowMs + MAX_CLIPBOARD_FUTURE_SKEW_MS
    }
    return timestampMs <= latest
}

enum class ItemType(val value: Int) {
    TEXT(1),
    IMAGE(2),
    VIDEO(3);

    companion object {
        fun fromInt(value : Int) : ItemType {
            return entries.first { it.value == value }
        }
    }
}

enum class ClipboardMediaOwnershipState(val value: Int) {
    LEGACY(0),
    PENDING(1),
    ACTIVE(2),
    RETIRING(3);

    companion object {
        fun fromInt(value: Int): ClipboardMediaOwnershipState =
            entries.firstOrNull { it.value == value } ?: LEGACY
    }
}

internal sealed interface ClipboardItemImportPlan {
    data class Ready(val item: ClipboardItem) : ClipboardItemImportPlan

    data class ExternalMedia(
        val source: Uri,
        val type: ItemType,
        val text: String?,
        val creationTimestampMs: Long,
        val mimeTypes: List<String>,
        val isSensitive: Boolean,
        val isRemoteDevice: Boolean,
    ) : ClipboardItemImportPlan {
        init {
            require(source.scheme == ContentResolver.SCHEME_CONTENT)
            require(!source.authority.isNullOrEmpty())
            require(source.host == source.authority)
            require(source.userInfo == null)
            require(source.authority != ClipboardMediaProvider.AUTHORITY)
            require(source.authority != OreoSystemClipboardMediaProvider.AUTHORITY)
            require(type != ItemType.TEXT)
        }

        internal fun install(
            context: Context,
            stagedFile: java.nio.file.Path,
            byteCount: Long,
            displayName: String?,
            registerInstalled: (InstalledClipboardMedia) -> Unit,
        ): ClipboardItem {
            val installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = stagedFile,
                expectedBytes = byteCount,
                type = type,
                mimeTypes = mimeTypes,
                displayName = displayName,
            )
            registerInstalled(installed)
            val fileInfo = ClipboardFileStorage.fileInfo(context, installed.ownedUri)
                ?: error("Clipboard media copy is unavailable.")
            return ClipboardItem(
                type = type,
                text = text,
                uri = installed.ownedUri.uri,
                creationTimestampMs = creationTimestampMs,
                isPinned = false,
                mimeTypes = fileInfo.mimeTypes,
                isSensitive = isSensitive,
                isRemoteDevice = isRemoteDevice,
            )
        }

        override fun toString(): String =
            "ExternalMedia(type=$type, hasText=${text != null}, mimeTypeCount=${mimeTypes.size}, " +
                "isSensitive=$isSensitive, isRemoteDevice=$isRemoteDevice, source=<redacted>)"
    }
}

/**
 * Represents an item on the clipboard.
 *
 * Media items require a URI; text items require text.
 */
@Serializable
@Entity(tableName = CLIPBOARD_HISTORY_TABLE)
data class ClipboardItem @OptIn(ExperimentalSerializationApi::class) constructor(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = BaseColumns._ID, index = true)
    var id: Long = 0,
    val type: ItemType,
    val text: String?,
    @Serializable(with = UriSerializer::class)
    val uri: Uri?,
    val creationTimestampMs: Long,
    val isPinned: Boolean,
    val mimeTypes: List<String>,
    @EncodeDefault
    @ColumnInfo(name = "is_sensitive", defaultValue = "0")
    val isSensitive: Boolean = false,
    @EncodeDefault
    @ColumnInfo(name= "is_remote_device", defaultValue = "0")
    val isRemoteDevice: Boolean = false,
) {
    override fun toString(): String =
        "ClipboardItem(type=$type, hasText=${text != null}, hasUri=${uri != null}, " +
            "mimeTypeCount=${mimeTypes.size}, isPinned=$isPinned, isSensitive=$isSensitive, " +
            "isRemoteDevice=$isRemoteDevice)"

    companion object {
        /**
         * So that every item doesn't have to allocate its own array.
         */
        private val TEXT_PLAIN = listOf("text/plain")

        const val FLORIS_CLIP_LABEL = "florisboard/clipboard_item"

        fun text(text: String): ClipboardItem {
            return ClipboardItem(
                type = ItemType.TEXT,
                text = text,
                uri = null,
                creationTimestampMs = System.currentTimeMillis(),
                isPinned = false,
                mimeTypes = TEXT_PLAIN,
            )
        }

        internal fun planFromClipData(
            context: Context,
            data: ClipData,
        ): ClipboardItemImportPlan {
            require(data.itemCount > 0) { "Clipboard content is unavailable." }
            val dataItem = data.getItemAt(0)
            val type = when {
                dataItem.uri != null && data.description.hasMimeType("image/*") -> ItemType.IMAGE
                dataItem.uri != null && data.description.hasMimeType("video/*") -> ItemType.VIDEO
                else -> ItemType.TEXT
            }

            val isSensitive =
                data.description.extras?.getBoolean(EXTRA_CLIP_IS_SENSITIVE) ?: false

            val isRemoteDevice = if (AndroidVersion.ATLEAST_API34_U) {
                data.description.extras?.getBoolean(EXTRA_IS_REMOTE_DEVICE) ?: false
            } else {
                false
            }

            val text = dataItem.text?.toString()
            if (type == ItemType.TEXT && text == null) {
                error("Clipboard text is unavailable.")
            }
            val mimeTypes = when (type) {
                ItemType.TEXT -> TEXT_PLAIN
                ItemType.IMAGE, ItemType.VIDEO -> normalizeLiveMediaMimeTypes(
                    type = type,
                    candidates = data.description.mimeTypes(),
                )
            }
            val item = ClipboardItem(
                type = type,
                text = text,
                uri = null,
                creationTimestampMs = System.currentTimeMillis(),
                isPinned = false,
                mimeTypes = mimeTypes,
                isSensitive = isSensitive,
                isRemoteDevice = isRemoteDevice,
            )
            if (type == ItemType.TEXT) return ClipboardItemImportPlan.Ready(item)

            val sourceUri = dataItem.uri ?: error("Clipboard media is unavailable.")
            OwnedClipboardMediaUri.parse(sourceUri, type)?.let { owned ->
                val fileInfo = tryOrNull { ClipboardFileStorage.fileInfo(context, owned) }
                    ?: error("Owned clipboard media is unavailable.")
                return ClipboardItemImportPlan.Ready(
                    item.copy(uri = owned.uri, mimeTypes = fileInfo.mimeTypes),
                )
            }
            OwnedClipboardMediaUri.parseOreoSystemClipboard(sourceUri, type)?.let { owned ->
                val fileInfo = tryOrNull {
                    ClipboardFileStorage.currentSystemRootFileInfo(context, owned)
                } ?: error("Owned clipboard media is unavailable.")
                return ClipboardItemImportPlan.Ready(
                    item.copy(uri = owned.uri, mimeTypes = fileInfo.mimeTypes),
                )
            }
            if (sourceUri.scheme != ContentResolver.SCHEME_CONTENT ||
                sourceUri.authority.isNullOrEmpty() ||
                sourceUri.authority == ClipboardMediaProvider.AUTHORITY ||
                sourceUri.host == ClipboardMediaProvider.AUTHORITY ||
                sourceUri.authority == OreoSystemClipboardMediaProvider.AUTHORITY ||
                sourceUri.host == OreoSystemClipboardMediaProvider.AUTHORITY
            ) {
                error("Clipboard media source is unavailable.")
            }
            return ClipboardItemImportPlan.ExternalMedia(
                source = sourceUri,
                type = type,
                text = text,
                creationTimestampMs = item.creationTimestampMs,
                mimeTypes = mimeTypes,
                isSensitive = isSensitive,
                isRemoteDevice = isRemoteDevice,
            )
        }

        private fun android.content.ClipDescription.mimeTypes(): List<String> =
            (0 until minOf(mimeTypeCount, MAX_LIVE_MIME_TYPE_CANDIDATES)).map(::getMimeType)

        private fun normalizeLiveMediaMimeTypes(
            type: ItemType,
            candidates: List<String>,
        ): List<String> {
            val requiredPrefix = when (type) {
                ItemType.IMAGE -> "image/"
                ItemType.VIDEO -> "video/"
                ItemType.TEXT -> error("Clipboard media type expected.")
            }
            val conflictingPrefix = if (type == ItemType.IMAGE) "video/" else "image/"
            val normalized = candidates
                .asSequence()
                .map { it.trim().lowercase() }
                .filter { candidate ->
                    candidate.length <= MAX_LIVE_MIME_TYPE_LENGTH &&
                        '*' !in candidate &&
                        LIVE_MIME_TYPE.matches(candidate) &&
                        !candidate.startsWith(conflictingPrefix)
                }
                .distinct()
                .take(MAX_LIVE_MIME_TYPES)
                .toMutableList()
            if (normalized.none { it.startsWith(requiredPrefix) }) {
                normalized.add(0, "${requiredPrefix}unknown")
            }
            return normalized.take(MAX_LIVE_MIME_TYPES)
        }

        private const val MAX_LIVE_MIME_TYPE_CANDIDATES = 64
        private const val MAX_LIVE_MIME_TYPES = 16
        private const val MAX_LIVE_MIME_TYPE_LENGTH = 127
        private val LIVE_MIME_TYPE = Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")
    }

    @Composable
    fun displayText(): String {
        val context = LocalContext.current
        return displayText(context)
    }

    fun displayText(context: Context): String {
        return if (isSensitive) {
            context.stringRes(R.string.clipboard__sensitive_clip_content)
        } else {
            stringRepresentation()
        }
    }

    fun isEqualTo(
        context: Context,
        other: ClipData?,
    ): Boolean {
        if (other == null || other.itemCount < 1) return false
        val otherItem = other.getItemAt(0)
        val sameContent = when (type) {
            ItemType.TEXT -> other.itemCount == 1 && text == otherItem.text
            ItemType.IMAGE, ItemType.VIDEO -> {
                val owned = uri?.let { OwnedClipboardMediaUri.parse(it, type) }
                val systemOwned = otherItem.uri
                    ?.let { resolveObservedSystemClipboardMedia(context, it, type) }
                owned != null &&
                    owned == systemOwned &&
                    text == otherItem.text?.toString()
            }
        }
        val otherIsRemote = AndroidVersion.ATLEAST_API34_U &&
            (other.description.extras?.getBoolean(EXTRA_IS_REMOTE_DEVICE) ?: false)
        return sameContent &&
            isSensitive ==
            (other.description.extras?.getBoolean(EXTRA_CLIP_IS_SENSITIVE) ?: false) &&
            isRemoteDevice == otherIsRemote &&
            (
                type != ItemType.TEXT ||
                    mimeTypes.map(String::lowercase).toSet() ==
                    other.description.mimeTypes().map(String::lowercase).toSet()
            )
    }

    /**
     * Creates a new ClipData which has the same contents as this.
     *
     * @return null when a media reference is not an exact, available URI owned by this app.
     */
    fun toClipData(context: Context): ClipData? {
        val data = when (type) {
            ItemType.TEXT -> {
                ClipData.newPlainText(FLORIS_CLIP_LABEL, text)
            }
            ItemType.IMAGE, ItemType.VIDEO -> {
                val ownedUri = uri?.let { OwnedClipboardMediaUri.parse(it, type) } ?: return null
                val fileInfo = tryOrNull {
                    ClipboardFileStorage.fileInfo(context, ownedUri)
                } ?: return null
                ClipData(
                    FLORIS_CLIP_LABEL,
                    fileInfo.mimeTypes.toTypedArray(),
                    ClipData.Item(systemClipboardMediaUri(ownedUri)),
                )
            }
        }
        val extras = PersistableBundle(data.description.extras ?: PersistableBundle()).apply {
            putBoolean(EXTRA_CLIP_IS_SENSITIVE, isSensitive)
            if (AndroidVersion.ATLEAST_API34_U) {
                putBoolean(EXTRA_IS_REMOTE_DEVICE, isRemoteDevice)
            }
        }
        data.description.extras = extras
        return data
    }

    fun stringRepresentation(): String {
        return when (type) {
            ItemType.TEXT -> text ?: "#ERROR"
            ItemType.IMAGE -> "(Image)"
            ItemType.VIDEO -> "(Video)"
        }
    }
}

class Converters {
    @TypeConverter
    fun uriFromString(value: String?): Uri? {
        return value?.toUri()
    }

    @TypeConverter
    fun stringFromUri(value: Uri?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun itemTypeToInt(value: ItemType?): Int? {
        return value?.value
    }

    @TypeConverter
    fun intToItemType(value: Int?): ItemType? {
        return value?.let { ItemType.fromInt(it) }
    }

    @TypeConverter
    fun mediaOwnershipStateToInt(value: ClipboardMediaOwnershipState): Int = value.value

    @TypeConverter
    fun intToMediaOwnershipState(value: Int): ClipboardMediaOwnershipState =
        ClipboardMediaOwnershipState.fromInt(value)

    /**
     * Only works because the string array is a mimetype.
     * DOES NOT USE A GENERALIZED FORMAT.
     */
    @TypeConverter
    fun mimeTypesToString(mimeTypes: List<String>): String {
        return mimeTypes.joinToString(",")
    }

    @TypeConverter
    fun stringToMimeTypes(value: String): List<String> {
        return value.split(",")
    }
}

@Dao
interface ClipboardHistoryDao {
    @Query("SELECT * FROM $CLIPBOARD_HISTORY_TABLE")
    fun getAll(): List<ClipboardItem>

    @Insert
    fun insert(item: ClipboardItem): Long

    @Update
    fun update(item: ClipboardItem)

    @Update
    fun update(items: List<ClipboardItem>)

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE WHERE ${BaseColumns._ID} = :id")
    fun delete(id: Long)

    @Delete
    fun delete(items: List<ClipboardItem>)

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE")
    fun deleteAll()

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE WHERE type = :type")
    fun deleteAllFromType(type: ItemType)

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE WHERE NOT isPinned")
    fun deleteAllUnpinned()
}

@Database(
    entities = [ClipboardItem::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 2, to = 4),
        AutoMigration(from = 3, to = 4, spec = ClipboardHistoryDatabase.MIGRATE_3_TO_4::class),
    ],
)
@TypeConverters(Converters::class)
abstract class ClipboardHistoryDatabase : RoomDatabase() {
    abstract fun clipboardItemDao(): ClipboardHistoryDao

    @RenameColumn(
        tableName = CLIPBOARD_HISTORY_TABLE,
        fromColumnName = "isSensitive",
        toColumnName = "is_sensitive",
    )
    @RenameColumn(
        tableName = CLIPBOARD_HISTORY_TABLE,
        fromColumnName = "isRemoteDevice",
        toColumnName = "is_remote_device",
    )
    class MIGRATE_3_TO_4 : AutoMigrationSpec

    companion object {
        internal val MIGRATION_4_TO_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE `$CLIPBOARD_HISTORY_TABLE`
                    SET `uri` = NULL
                    WHERE `type` = ${ItemType.TEXT.value}
                        AND `uri` = 'null'
                    """.trimIndent(),
                )
            }
        }

        fun new(context: Context): ClipboardHistoryDatabase {
            return Room
                .databaseBuilder(
                    context, ClipboardHistoryDatabase::class.java, CLIPBOARD_HISTORY_TABLE,
                )
                .addMigrations(MIGRATION_4_TO_5)
                .build()
        }
    }
}

/**
 * Streams the history columns needed to rescue media written before file
 * metadata became transactional with history. The cursor keeps application
 * memory independent of history length.
 */
internal fun forEachLegacyClipboardMediaHistoryRef(
    context: Context,
    consume: (ArchiveClipboardMediaRef) -> Unit,
) {
    val database = ClipboardHistoryDatabase.new(context)
    try {
        val maximumUriLength = ClipboardMediaProvider.AUTHORITY.length + 64
        database.openHelper.readableDatabase.query(
            """
            SELECT `type`, `uri`
            FROM `$CLIPBOARD_HISTORY_TABLE`
            WHERE `type` IN (${ItemType.IMAGE.value}, ${ItemType.VIDEO.value})
                AND `uri` IS NOT NULL
                AND length(`uri`) BETWEEN 1 AND $maximumUriLength
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = when (cursor.getInt(0)) {
                    ItemType.IMAGE.value -> ItemType.IMAGE
                    ItemType.VIDEO.value -> ItemType.VIDEO
                    else -> continue
                }
                val rawUri = cursor.getString(1) ?: continue
                val reference = ArchiveClipboardMediaRef.parse(
                    raw = rawUri,
                    sourceAuthority = ClipboardMediaProvider.AUTHORITY,
                    expectedType = type,
                ) ?: continue
                consume(reference)
            }
        }
    } finally {
        database.close()
    }
}

@Serializable
@Entity(
    tableName = CLIPBOARD_FILES_TABLE,
    indices = [
        Index(
            value = ["share_operation_token"],
            unique = true,
        ),
    ],
)
data class ClipboardFileInfo(
    @PrimaryKey @ColumnInfo(name=BaseColumns._ID, index=true) val id: Long,
    @ColumnInfo(name=OpenableColumns.DISPLAY_NAME) val displayName: String,
    @ColumnInfo(name=OpenableColumns.SIZE) val size: Long,
    @ColumnInfo(name=Media.ORIENTATION) val orientation: Int,
    val mimeTypes: List<String>,
    @ColumnInfo(name = "ownership_state", defaultValue = "0")
    val ownershipState: ClipboardMediaOwnershipState = ClipboardMediaOwnershipState.LEGACY,
    @ColumnInfo(name = "is_system_root", defaultValue = "0")
    val isSystemRoot: Boolean = false,
    @ColumnInfo(name = "paste_retained_until_ms", defaultValue = "0")
    val pasteRetainedUntilMs: Long = 0L,
    @ColumnInfo(name = "external_capability_boot_count")
    val externalCapabilityBootCount: Int? = null,
    @Transient
    @ColumnInfo(name = "share_operation_token")
    val shareOperationToken: String? = null,
    @Transient
    @ColumnInfo(name = "share_request_fingerprint")
    val shareRequestFingerprint: String? = null,
    @Transient
    @ColumnInfo(name = "share_pending_boot_count")
    val sharePendingBootCount: Int? = null,
    @Transient
    @ColumnInfo(
        name = "share_pending_deadline_elapsed_realtime_ms",
        defaultValue = "0",
    )
    val sharePendingDeadlineElapsedRealtimeMs: Long = 0L,
) {
    override fun toString(): String =
        "ClipboardFileInfo(size=$size, orientation=$orientation, " +
            "mimeTypeCount=${mimeTypes.size}, ownershipState=$ownershipState, " +
            "isSystemRoot=$isSystemRoot, pasteRetained=${pasteRetainedUntilMs > 0L}, " +
            "hasExternalCapability=${externalCapabilityBootCount != null}, " +
            "hasShareOperation=${shareOperationToken != null}, " +
            "hasShareRequest=${shareRequestFingerprint != null}, " +
            "sharePending=${sharePendingDeadlineElapsedRealtimeMs > 0L}, " +
            "id=<redacted>, displayName=<redacted>)"
}

internal const val LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT = -2

@Dao
interface ClipboardFilesDao {
    @Query("DELETE FROM $CLIPBOARD_FILES_TABLE WHERE ${BaseColumns._ID} == (:id)")
    fun delete(id: Long)

    @Insert
    fun insert(vararg clipboardFileInfos: ClipboardFileInfo)

    @Update
    fun update(clipboardFileInfo: ClipboardFileInfo)

    @Query("SELECT * FROM $CLIPBOARD_FILES_TABLE")
    fun getAll(): List<ClipboardFileInfo>
}

@Database(
    entities = [ClipboardFileInfo::class],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
@TypeConverters(Converters::class)
abstract class ClipboardFilesDatabase : RoomDatabase() {
    abstract fun clipboardFilesDao() : ClipboardFilesDao

    companion object {
        internal val MIGRATION_1_TO_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `${CLIPBOARD_FILES_TABLE}_new` (
                        `_id` INTEGER NOT NULL,
                        `_display_name` TEXT NOT NULL,
                        `_size` INTEGER NOT NULL,
                        `orientation` INTEGER NOT NULL,
                        `mimeTypes` TEXT NOT NULL,
                        `ownership_state` INTEGER NOT NULL DEFAULT 0,
                        `is_system_root` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `${CLIPBOARD_FILES_TABLE}_new`
                        (`_id`, `_display_name`, `_size`, `orientation`, `mimeTypes`,
                         `ownership_state`, `is_system_root`)
                    SELECT `_id`, `_display_name`, `_size`, 0, `mimeTypes`, 0, 0
                    FROM `$CLIPBOARD_FILES_TABLE`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `$CLIPBOARD_FILES_TABLE`")
                db.execSQL(
                    "ALTER TABLE `${CLIPBOARD_FILES_TABLE}_new` RENAME TO `$CLIPBOARD_FILES_TABLE`",
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_clipboard_files__id`
                    ON `$CLIPBOARD_FILES_TABLE` (`_id`)
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_4_TO_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `$CLIPBOARD_FILES_TABLE`
                    ADD COLUMN `external_capability_boot_count` INTEGER
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `$CLIPBOARD_FILES_TABLE`
                    SET `external_capability_boot_count` =
                        $LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT
                    WHERE `ownership_state` !=
                        ${ClipboardMediaOwnershipState.PENDING.value}
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_5_TO_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `$CLIPBOARD_FILES_TABLE`
                    ADD COLUMN `share_operation_token` TEXT
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    ALTER TABLE `$CLIPBOARD_FILES_TABLE`
                    ADD COLUMN `share_request_fingerprint` TEXT
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    ALTER TABLE `$CLIPBOARD_FILES_TABLE`
                    ADD COLUMN `share_pending_boot_count` INTEGER
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    ALTER TABLE `$CLIPBOARD_FILES_TABLE`
                    ADD COLUMN `share_pending_deadline_elapsed_realtime_ms`
                        INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                        `index_clipboard_files_share_operation_token`
                    ON `$CLIPBOARD_FILES_TABLE` (`share_operation_token`)
                    """.trimIndent(),
                )
            }
        }

        fun new(context: Context): ClipboardFilesDatabase {
            return Room
                .databaseBuilder(
                    context, ClipboardFilesDatabase::class.java, CLIPBOARD_FILES_TABLE,
                )
                .addMigrations(MIGRATION_1_TO_3, MIGRATION_4_TO_5, MIGRATION_5_TO_6)
                .build()
        }
    }
}
