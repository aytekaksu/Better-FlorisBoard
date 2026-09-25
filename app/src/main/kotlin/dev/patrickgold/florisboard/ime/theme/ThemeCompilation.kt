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

package dev.patrickgold.florisboard.ime.theme

import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.snygg.SnyggStylesheet
import org.florisboard.lib.snygg.SnyggTheme
import org.florisboard.lib.snygg.value.SnyggAssetResolver

/** Resolve and load preview fonts away from the Compose thread. */
internal suspend fun compileThemeOffMain(
    stylesheet: SnyggStylesheet,
    assetResolver: SnyggAssetResolver,
    fontResolver: FontFamily.Resolver,
): SnyggTheme {
    val compiled = runInterruptible(Dispatchers.IO) {
        SnyggTheme.compileFrom(stylesheet, assetResolver)
    }
    return compiled.preloadFonts(fontResolver)
}

/** Keeps preview assets alive through compilation and publication. */
internal suspend fun <T> compileCurrentTheme(
    materialization: ThemeMaterialization?,
    fallbackDir: FsDir?,
    isCurrent: () -> Boolean,
    compile: suspend (FsDir?) -> T,
    publish: (T) -> Boolean,
): Boolean {
    val lease = materialization?.tryAcquire()
    if (materialization != null && lease == null) return false
    try {
        val compiled = compile(lease?.directory ?: fallbackDir)
        currentCoroutineContext().ensureActive()
        return isCurrent() && publish(compiled)
    } finally {
        lease?.close()
    }
}
