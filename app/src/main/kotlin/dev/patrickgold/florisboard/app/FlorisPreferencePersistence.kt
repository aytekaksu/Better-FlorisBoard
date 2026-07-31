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

package dev.patrickgold.florisboard.app

import android.content.Context
import dev.patrickgold.florisboard.ime.window.ImeWindowConfigByType
import dev.patrickgold.jetpref.datastore.runtime.AndroidAppDataStorage
import dev.patrickgold.jetpref.datastore.runtime.DataStore
import dev.patrickgold.jetpref.datastore.runtime.DataStoreReader
import dev.patrickgold.jetpref.datastore.runtime.FileBasedStorage
import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import dev.patrickgold.jetpref.datastore.runtime.LoadStrategy
import dev.patrickgold.jetpref.datastore.runtime.PersistStrategy
import dev.patrickgold.jetpref.datastore.runtime.jetprefDatastoreDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal fun DataStoreReader.withLegacyPreferencePreprocessing(
    baseWindowConfig: ImeWindowConfigByType? = null,
    baseSmartbar: LegacySmartbarMigrationBase? = null,
    sourceVersionCode: Int? = null,
    sourceVersionName: String? = null,
): DataStoreReader = DataStoreReader {
    LegacyPreferencePayloadPreprocessor.process(
        payload = read(),
        baseWindowConfig = baseWindowConfig,
        baseSmartbar = baseSmartbar,
        sourceVersionCode = sourceVersionCode,
        sourceVersionName = sourceVersionName,
    )
}

internal suspend fun DataStore<FlorisPreferenceModel>.initAndroidWithLegacyMigrations(context: Context): Result<Unit> {
    val datastoreFile = context.jetprefDatastoreDir
        .resolve("${FlorisPreferenceModel.NAME}.${AndroidAppDataStorage.JETPREF_FILE_EXT}")
    val storage = FileBasedStorage(datastoreFile.absolutePath)
    val loadStrategy = try {
        when {
            Files.notExists(datastoreFile.toPath(), LinkOption.NOFOLLOW_LINKS) ->
                LoadStrategy.Disabled

            Files.isRegularFile(datastoreFile.toPath(), LinkOption.NOFOLLOW_LINKS) ->
                LoadStrategy.UseReader(storage.withLegacyPreferencePreprocessing())

            else -> return Result.failure(IOException("Preference storage is unavailable."))
        }
    } catch (error: SecurityException) {
        return Result.failure(error)
    }
    return init(
        loadStrategy = loadStrategy,
        persistStrategy = PersistStrategy.UseWriter(storage),
    )
}

internal suspend fun DataStore<FlorisPreferenceModel>.importWithLegacyMigrations(
    strategy: ImportStrategy,
    reader: DataStoreReader,
    sourceVersionCode: Int? = null,
    sourceVersionName: String? = null,
): Result<Unit> {
    val baseWindowConfig: ImeWindowConfigByType?
    val baseSmartbar: LegacySmartbarMigrationBase?
    if (strategy == ImportStrategy.Merge) {
        val prefs by this
        baseWindowConfig = prefs.keyboard.windowConfig.get()
        baseSmartbar = LegacySmartbarMigrationBase(
            layout = prefs.smartbar.layout.get(),
            actionArrangement = prefs.smartbar.actionArrangement.get(),
        )
    } else {
        baseWindowConfig = null
        baseSmartbar = null
    }
    return import(
        strategy = strategy,
        reader = reader.withLegacyPreferencePreprocessing(
            baseWindowConfig = baseWindowConfig,
            baseSmartbar = baseSmartbar,
            sourceVersionCode = sourceVersionCode,
            sourceVersionName = sourceVersionName,
        ),
    )
}
