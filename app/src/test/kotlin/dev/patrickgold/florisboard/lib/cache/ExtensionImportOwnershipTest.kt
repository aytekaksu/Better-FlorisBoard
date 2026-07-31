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

package dev.patrickgold.florisboard.lib.cache

import dev.patrickgold.florisboard.ime.clipboard.provider.StagedExternalContent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ExtensionImportOwnershipTest :
    FunSpec({
        test("an interrupted handoff closes claimed provider data") {
            val stagedPath = Files.createTempFile("extension-import-handoff-", ".tmp")
            val staged = StagedExternalContent(stagedPath, 0L, null, null)
            val consumed = AtomicBoolean()
            val failure = AtomicReference<Throwable?>()
            val handoff = Thread {
                Thread.currentThread().interrupt()
                failure.set(
                    runCatching {
                        staged.useForExtensionImport {
                            consumed.set(true)
                        }
                    }.exceptionOrNull(),
                )
            }

            handoff.start()
            handoff.join(5_000L)

            handoff.isAlive shouldBe false
            (failure.get() is InterruptedException) shouldBe true
            consumed.get() shouldBe false
            Files.exists(stagedPath) shouldBe false
        }
    })
