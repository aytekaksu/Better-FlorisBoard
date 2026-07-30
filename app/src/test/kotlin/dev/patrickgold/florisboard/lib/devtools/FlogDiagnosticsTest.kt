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

package dev.patrickgold.florisboard.lib.devtools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class FlogDiagnosticsTest :
    FunSpec({
        fun recordInfo(message: String) {
            flogInfo { message }
        }

        fun recordWarning(message: String) {
            flogWarning { message }
        }

        beforeTest {
            Flog.install(
                isLogcatEnabled = false,
                isDiagnosticCaptureEnabled = true,
                flogTopics = Flog.TOPIC_ALL,
                flogLevels = Flog.LEVEL_ALL,
            )
        }

        afterSpec {
            Flog.install(
                isLogcatEnabled = false,
                isDiagnosticCaptureEnabled = false,
                flogTopics = Flog.TOPIC_NONE,
                flogLevels = Flog.LEVEL_NONE,
            )
        }

        test("diagnostic snapshots are bounded and detached") {
            repeat(205) { sequence ->
                recordInfo("syntheticEvent=$sequence")
            }

            val snapshot = Flog.diagnosticSnapshot()
            snapshot shouldHaveSize 200
            snapshot.first() shouldContain "syntheticEvent=5"
            snapshot.last() shouldContain "syntheticEvent=204"

            recordInfo("syntheticEvent=205")
            snapshot.last() shouldContain "syntheticEvent=204"
        }

        test("retained diagnostic lines have a fixed size limit") {
            recordWarning("syntheticState=${"x".repeat(1_000)}")

            Flog.diagnosticSnapshot().single().length shouldBeLessThanOrEqual 512
        }

        test("retained entries are always one printable line") {
            recordInfo("first\r\nsecond\u0000third")

            val line = Flog.diagnosticSnapshot().single()
            line shouldContain "FlogDiagnosticsTest"
            line shouldContain "first  second third"
            line shouldNotContain "\r"
            line shouldNotContain "\n"
            line shouldNotContain "\u0000"
        }

        test("disabled outputs do not evaluate messages") {
            Flog.install(
                isLogcatEnabled = false,
                isDiagnosticCaptureEnabled = false,
                flogTopics = Flog.TOPIC_ALL,
                flogLevels = Flog.LEVEL_ALL,
            )
            var evaluated = false

            flogInfo {
                evaluated = true
                "syntheticEvent"
            }

            evaluated shouldBe false
            Flog.diagnosticSnapshot() shouldBe emptyList()
        }

        test("disabled levels do not evaluate messages") {
            Flog.install(
                isLogcatEnabled = false,
                isDiagnosticCaptureEnabled = true,
                flogTopics = Flog.TOPIC_ALL,
                flogLevels = Flog.LEVEL_ERROR or Flog.LEVEL_WARNING,
            )
            var evaluated = false

            flogDebug {
                evaluated = true
                "syntheticEvent"
            }

            evaluated shouldBe false
            Flog.diagnosticSnapshot() shouldBe emptyList()
        }
    })
