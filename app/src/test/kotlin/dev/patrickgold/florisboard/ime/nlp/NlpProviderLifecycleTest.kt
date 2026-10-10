/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NlpProviderLifecycleTest :
    FunSpec({
        test("create is idempotent") {
            val lifecycle = NlpProviderLifecycle()
            var creates = 0

            lifecycle.createIfNecessary { creates++ }
            lifecycle.createIfNecessary { creates++ }

            creates shouldBe 1
        }

        test("failed create remains retryable") {
            val lifecycle = NlpProviderLifecycle()
            var attempts = 0

            shouldThrow<IllegalStateException> {
                lifecycle.createIfNecessary {
                    attempts++
                    error("create failed")
                }
            }
            lifecycle.createIfNecessary { attempts++ }

            attempts shouldBe 2
        }
    })
