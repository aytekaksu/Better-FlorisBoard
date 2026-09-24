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

package org.florisboard.lib.android

import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile
import java.io.IOException

/**
 * Public typealias for the Android AssetManager class, which provides a basic API to read files which are statically
 * shipped in the APK assets. The typealias is used to allow both the FlorisBoard and Android asset managers to coexist
 * without name clashes.
 */
typealias AndroidAssetManager = android.content.res.AssetManager

/**
 * Copies an APK asset file or directory into [dst], traversing directories recursively.
 *
 * @param path The relative path of the asset file or directory.
 * @param dst The destination directory.
 *
 * @throws IOException If an asset cannot be read or written. Some files may already have been copied.
 */
fun AndroidAssetManager.copyRecursively(path: String, dst: FsDir) {
    this.copyApkAssets(path, "", dst)
}

private fun AndroidAssetManager.copyApkAssets(base: String, path: String, dst: FsDir) {
    val apkAssetsPath = if (base.isBlank()) path else if (path.isBlank()) base else "$base/$path"
    val list = this.list(apkAssetsPath) ?: return
    if (list.isEmpty()) {
        val file = dst.subFile(path)
        this.open(apkAssetsPath).use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } else {
        val dir = dst.subDir(path)
        dir.mkdirs()
        for (entry in list) {
            this.copyApkAssets(base, if (path.isBlank()) entry else "$path/$entry", dst)
        }
    }
}
