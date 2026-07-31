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

package org.florisboard.lib.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ContentResolverAndroidTest {
    @Test
    fun readToFileReturnsTheActualCopiedSize() {
        withTestDirectory { directory ->
            val sourceBytes = byteArrayOf(1, 2, 3, 4, 5)
            val source = directory.resolve("source.flex").also { Files.write(it, sourceBytes) }
            val destination = directory.resolve("destination.flex")
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

            val copiedSize = resolver.readToFile(
                uri = Uri.fromFile(source.toFile()),
                file = destination.toFile(),
                maxSize = sourceBytes.size.toLong(),
            )

            assertEquals(sourceBytes.size.toLong(), copiedSize)
            assertArrayEquals(sourceBytes, Files.readAllBytes(destination))
        }
    }

    @Test
    fun readToFileRejectsTheFirstByteBeyondTheStreamLimitAndDeletesThePartialFile() {
        withTestDirectory { directory ->
            val source = directory.resolve("source.flex").also {
                Files.write(it, byteArrayOf(1, 2, 3, 4, 5))
            }
            val destination = directory.resolve("destination.flex")
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

            val failure = assertThrows(ContentSizeLimitExceededException::class.java) {
                resolver.readToFile(
                    uri = Uri.fromFile(source.toFile()),
                    file = destination.toFile(),
                    maxSize = 4,
                )
            }

            assertEquals("Selected content exceeds the allowed size.", failure.message)
            assertNull(failure.cause)
            assertFalse(Files.exists(destination))
        }
    }

    private inline fun withTestDirectory(block: (java.nio.file.Path) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "content-resolver-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
