/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.kotlin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CancellationException

class LibraryTest :
    FunSpec({
        test("tryOrNull returns values and converts ordinary exceptions to null") {
            tryOrNull { "value" } shouldBe "value"
            tryOrNull { error("failure") } shouldBe null
        }

        test("tryOrNull does not swallow cancellation or fatal errors") {
            shouldThrow<CancellationException> {
                tryOrNull { throw CancellationException() }
            }
            shouldThrow<AssertionError> {
                tryOrNull { throw AssertionError() }
            }
        }
    })
