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

package dev.patrickgold.florisboard.ime.text.gestures

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GlideTypingTimingTest : FunSpec({
    test("gesture duration comes from motion event timestamps") {
        motionEventElapsedTimeMillis(eventTime = 12_345L, downTime = 12_000L) shouldBe 345
    }

    test("invalid and oversized motion event durations are bounded") {
        motionEventElapsedTimeMillis(eventTime = 99L, downTime = 100L) shouldBe 0
        motionEventElapsedTimeMillis(Long.MAX_VALUE, 0L) shouldBe Int.MAX_VALUE
    }

    test("provider trace sampling fills its budget and preserves both endpoints") {
        val indices = sampledGestureIndices(129)

        indices.size shouldBe 128
        indices.first() shouldBe 0
        indices.last() shouldBe 128
        indices.toSet().size shouldBe indices.size
        sampledGestureIndices(3).toList() shouldBe listOf(0, 1, 2)
    }
})
