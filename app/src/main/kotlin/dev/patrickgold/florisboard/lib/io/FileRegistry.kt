/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.lib.io

import org.florisboard.lib.kotlin.io.FsFile

object FileRegistry {
    const val BACKUP_ARCHIVE_MEDIA_TYPE = "application/zip"
    const val FLEX_EXTENSION_MEDIA_TYPE = "application/vnd.florisboard.extension+zip"
    private const val FLEX_EXTENSION_FILE_EXT = "flex"
    private val FLEX_EXTENSION_ALTERNATIVE_MEDIA_TYPES = listOf(
        "application/zip",
        "application/octet-stream",
    )

    fun guessMediaType(file: FsFile, givenMediaType: String?): String? {
        return if (file.extension == FLEX_EXTENSION_FILE_EXT &&
            FLEX_EXTENSION_ALTERNATIVE_MEDIA_TYPES.contains(givenMediaType)
        ) {
            FLEX_EXTENSION_MEDIA_TYPE
        } else {
            givenMediaType
        }
    }
}
