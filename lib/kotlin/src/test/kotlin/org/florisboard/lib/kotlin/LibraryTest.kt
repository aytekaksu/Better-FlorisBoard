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
