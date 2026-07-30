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

package dev.patrickgold.florisboard.ime.theme

import dev.patrickgold.florisboard.lib.devtools.flogError
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.subFile
import org.florisboard.lib.snygg.value.SnyggAssetResolver
import java.net.URI

class FlorisAssetResolver(private val loadedDir: FsDir?) : SnyggAssetResolver {
    override fun resolveAbsolutePath(uri: String) = runCatching {
        val parsedUri = try {
            URI.create(uri)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed asset URI")
        }
        require(parsedUri.scheme == "flex") { "Unsupported asset URI scheme" }
        require(parsedUri.authority.isNullOrEmpty()) { "Asset URI authority is not allowed" }
        val assetPath = requireNotNull(parsedUri.path) { "Asset URI path was missing" }
        val baseDir = checkNotNull(loadedDir) { "Loaded directory was null" }.canonicalFile
        val canonicalFile = baseDir.subFile(assetPath).canonicalFile
        check(canonicalFile.toPath().startsWith(baseDir.toPath())) {
            "Asset path escapes the theme directory"
        }
        check(canonicalFile.exists()) {
            "Asset file does not exist"
        }
        check(canonicalFile.isFile()) {
            "Asset path is not a file"
        }
        canonicalFile.path
    }.onFailure { exception ->
        flogError { "Theme asset resolution failed: error=${exception.javaClass.simpleName}" }
    }
}
