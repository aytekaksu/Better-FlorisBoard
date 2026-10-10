/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
