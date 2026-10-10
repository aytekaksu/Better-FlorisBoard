/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
