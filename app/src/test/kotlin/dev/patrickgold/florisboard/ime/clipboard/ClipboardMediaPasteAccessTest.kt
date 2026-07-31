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

package dev.patrickgold.florisboard.ime.clipboard

import dev.patrickgold.florisboard.ime.editor.dispatchMediaPasteContent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipboardMediaPasteAccessTest :
    FunSpec({
        test("success resolves access exactly once") {
            var accepted = 0
            var rejected = 0
            val access = ClipboardMediaPasteAccess(
                mimeTypes = listOf("image/png"),
                acceptAction = { accepted += 1 },
                rejectAction = { rejected += 1 },
            )

            access.commitSucceededOrMayHaveSucceeded()
            access.commitSucceededOrMayHaveSucceeded()
            access.commitRejected()

            accepted shouldBe 1
            rejected shouldBe 0
        }

        test("rejection resolves access exactly once") {
            var accepted = 0
            var rejected = 0
            val access = ClipboardMediaPasteAccess(
                mimeTypes = listOf("image/png"),
                acceptAction = { accepted += 1 },
                rejectAction = { rejected += 1 },
            )

            access.commitRejected()
            access.commitRejected()
            access.commitSucceededOrMayHaveSucceeded()

            accepted shouldBe 0
            rejected shouldBe 1
        }

        test("a completed false editor invocation retains capability access") {
            var accepted = 0
            var rejected = 0
            val access = ClipboardMediaPasteAccess(
                mimeTypes = listOf("image/png"),
                acceptAction = { accepted += 1 },
                rejectAction = { rejected += 1 },
            )

            dispatchMediaPasteContent(access) { false } shouldBe false
            access.commitRejected()

            accepted shouldBe 1
            rejected shouldBe 0
        }

        test("closing the access registry rejects each unresolved admission once") {
            val registry = ClipboardMediaPasteAccessRegistry()
            var rejected = 0
            lateinit var access: ClipboardMediaPasteAccess
            access = ClipboardMediaPasteAccess(
                mimeTypes = listOf("image/png"),
                acceptAction = {},
                rejectAction = { rejected += 1 },
                resolvedAction = { registry.unregister(access) },
            )
            registry.register(access) shouldBe true

            registry.close()
            registry.close()
            access.commitRejected()

            rejected shouldBe 1
        }

        test("an admission which loses the shutdown race is rejected once") {
            val registry = ClipboardMediaPasteAccessRegistry()
            registry.close()
            var rollbacks = 0
            val access = ClipboardMediaPasteAccess(
                mimeTypes = listOf("image/png"),
                acceptAction = {},
                rejectAction = { rollbacks += 1 },
            )

            val registered = registry.register(access)
            registered shouldBe false
            if (!registered) {
                access.commitRejected()
            }
            access.commitRejected()

            rollbacks shouldBe 1
        }
    })
