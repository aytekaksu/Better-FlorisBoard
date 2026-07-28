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

package dev.patrickgold.florisboard.ime.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SharedActionsAnimationSuppressionTest : FunSpec({
    test("stale composition cannot consume a newer suppression") {
        val tracker = SharedActionsAnimationSuppressionTracker()
        tracker.suppress(1L, targetExpanded = false)
        val stale = tracker.suppression.value!!
        tracker.suppress(2L, targetExpanded = true)
        val latest = tracker.suppression.value!!

        tracker.acknowledge(stale) shouldBe false
        tracker.suppression.value shouldBe latest
        tracker.acknowledge(latest) shouldBe true
        tracker.suppression.value shouldBe null
    }

    test("user transition clears pending automatic suppression") {
        val tracker = SharedActionsAnimationSuppressionTracker()
        tracker.suppress(1L, targetExpanded = false)

        tracker.clear()

        tracker.suppression.value shouldBe null
    }
})
