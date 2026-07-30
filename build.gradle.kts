/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask

plugins {
    alias(libs.plugins.agp.application) apply false
    alias(libs.plugins.agp.library) apply false
    alias(libs.plugins.agp.test) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotest) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.kover) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.mikepenz.aboutlibraries) apply false
    alias(libs.plugins.spotless)
}

/*
 * Start quality enforcement at the fork-owned autocorrect boundary. Inherited
 * FlorisBoard code can be added package-by-package once it has been formatted
 * and its current Detekt findings have been reviewed.
 */
val qualityKotlinSources = files(
    fileTree("app/src") {
        include("**/ime/nlp/plugin/**/*.kt")
    },
    fileTree("lib/autocorrect-api/src") {
        include("**/*.kt")
    },
    fileTree("lib/autocorrect-host-core/src") {
        include("**/*.kt")
    },
)

val formattedKotlinSources = files(
    fileTree("lib/autocorrect-host-core/src") {
        include("**/*.kt")
    },
    file("app/src/main/kotlin/dev/patrickgold/florisboard/app/devtools/DevtoolsPrivacySummary.kt"),
    file("app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/NlpProviderLifecycle.kt"),
    file(
        "app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/" +
            "AutocorrectPerformanceSection.kt",
    ),
    file(
        "app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/" +
            "AutocorrectPluginDiagnostics.kt",
    ),
    file("app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginUi.kt"),
    file("app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginUiApp.kt"),
    file("app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginUiFormatting.kt"),
    file("app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginUiKeyboard.kt"),
    file("app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginUiPresentation.kt"),
    file(
        "app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/" +
            "AutocorrectSuggestionRequestCoordinator.kt",
    ),
    file("app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectTracePolicy.kt"),
    file(
        "app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/" +
            "TextKeyboardInteractionPolicy.kt",
    ),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/app/devtools/DevtoolsPrivacySummaryTest.kt"),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/ime/keyboard/KeyDataPrivacyTest.kt"),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/ime/nlp/NlpProviderLifecycleTest.kt"),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/ime/nlp/SpellingDiagnosticsTest.kt"),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/ime/theme/FlorisAssetResolverTest.kt"),
    file(
        "app/src/test/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/" +
            "AutocorrectPluginDiagnosticsTest.kt",
    ),
    file(
        "app/src/test/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/" +
            "AutocorrectPluginUiFormattingTest.kt",
    ),
    file(
        "app/src/test/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/" +
            "AutocorrectPluginUiLeaseTest.kt",
    ),
    file(
        "app/src/test/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/" +
            "AutocorrectSuggestionRequestCoordinatorTest.kt",
    ),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/lib/devtools/FlogDiagnosticsTest.kt"),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectTracePolicyTest.kt"),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/test/editor/DeterministicInputConnection.kt"),
    file("app/src/test/kotlin/dev/patrickgold/florisboard/test/editor/DeterministicInputConnectionTest.kt"),
)

configure<SpotlessExtension> {
    kotlin {
        /*
         * New pure-core code starts formatted. Existing fork packages remain
         * under Detekt's baseline ratchet and can join this formatter scope
         * after their next deliberate cleanup, without a repository-wide diff.
         */
        target(formattedKotlinSources)
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_code_style" to "intellij_idea",
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "max_line_length" to "120",
                ),
            )
    }
    kotlinGradle {
        target(
            "build.gradle.kts",
            "app/build.gradle.kts",
            "lib/autocorrect-host-core/build.gradle.kts",
        )
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_code_style" to "intellij_idea",
                    "max_line_length" to "120",
                ),
            )
    }
}

detekt {
    source.setFrom(qualityKotlinSources)
    config.setFrom(files("$rootDir/config/quality/detekt.yml"))
    baseline.set(file("$rootDir/config/quality/detekt-baseline.xml"))
    buildUponDefaultConfig.set(true)
    parallel.set(true)
}

tasks.withType<Detekt>().configureEach {
    include("**/*.kt")
    jvmTarget.set("11")
    reports {
        checkstyle.required.set(true)
        html.required.set(true)
        sarif.required.set(true)
        markdown.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    include("**/*.kt")
    jvmTarget.set("11")
}

val formatCheck by tasks.registering {
    group = "verification"
    description = "Checks formatting for fork-owned Kotlin and Gradle Kotlin sources."
    dependsOn(tasks.named("spotlessCheck"))
}

val formatApply by tasks.registering {
    group = "formatting"
    description = "Formats fork-owned Kotlin and Gradle Kotlin sources."
    dependsOn(tasks.named("spotlessApply"))
}

val ciUnitTest by tasks.registering {
    group = "verification"
    description = "Runs every JVM and Robolectric unit-test suite."
    dependsOn(
        ":lib:autocorrect-host-core:koverVerify",
        ":lib:autocorrect-host-core:koverXmlReport",
        ":lib:autocorrect-host-core:koverHtmlReport",
    )
}

val ciLint by tasks.registering {
    group = "verification"
    description = "Runs Android lint for every Android module."
}

val privacySourceCheck by tasks.registering {
    group = "verification"
    description = "Checks common production diagnostic privacy hazards."

    val privacySources = fileTree(rootDir) {
        include("**/src/main/**/*.kt")
        exclude("**/build/**")
    }
    inputs.files(privacySources)

    doLast {
        val logStart = Regex(
            """\b(flog(?:Error|Warning|Info|Debug)|Flog\s*\.\s*log|Log\s*\.\s*(?:wtf|[vdiew]))\b""",
        )
        val forbiddenLogImport = Regex(
            """(?m)^\s*import\s+(?:android\.util\.Log(?:\.(?:wtf|[vdiew]|\*)(?:\s+as\s+\w+)?|\s+as\s+\w+)|""" +
                """.*\.lib\.devtools\.(?:Flog(?:\.log)?|flog(?:Error|Warning|Info|Debug))\s+as\s+\w+)\s*$""",
        )
        val identifier = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
        val safeProjection = Regex(
            """^\s*(?:[A-Za-z_]\w*\.(?:size|length|count\s*(?:\(\s*\)|\{.*}))|""" +
                """[A-Za-z_]\w*(?:::class|\.javaClass)\.simpleName|""" +
                """(?:[A-Za-z_]\w*\.)*(?:id|(?:enabled|(?:is|has|can|should|use|was|were|will)[A-Z]\w*|""" +
                """\w+Enabled)\.get\s*\(\s*\))|""" +
                """[A-Za-z_]\w*\s*[!=]=\s*null|null\s*[!=]=\s*[A-Za-z_]\w*)\s*$""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val unsafeRendering = Regex(
            """\b(?:debugSummarize|stackTraceToString)\s*\(|\.(?:toString\s*\(\s*\)|message\b|localizedMessage\b)""",
        )
        val safeMetadataName = Regex(
            """^(?:(?:is|has|can|did|does|should|was|were|will)_.+|""" +
                """.+_(?:available|class|count|empty|enabled|kind|length|limit|present|size|state|type))$""",
        )
        val consoleOutput = Regex("""(?<![A-Za-z0-9_])(?:print|println)\s*\(""")
        val stackTraceOutput = Regex("""\.printStackTrace\s*\(""")
        val exportRendering = Regex("""\bstackTraceToString\s*\(|\.(?:message|localizedMessage)\b""")
        val appendStart = Regex("""\b(?:append|appendLine)\s*\(""")
        val rawDebugExport = Regex(
            """\b(?:Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec|ProcessBuilder\s*\()""",
        )
        val rawLogcatCommand = Regex("""(?i)["']logcat["']""")
        val diagnosticExportDeclaration = Regex(
            """\bfun\s+(?:generateDebugLog|generateDebugLogForGithub|generateDiagnosticDump)\s*\(""",
        )
        val nonCode = Regex(
            "(?s)/\\*.*?\\*/|//[^\\n]*|\"\"\".*?\"\"\"|'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"",
        )
        val logMarker = Regex("""^\s*//\s*quality: allow-sensitive-log\s+--\s+\S.*$""")
        val consoleMarker = Regex("""^\s*//\s*quality: allow-console-output\s+--\s+\S.*$""")
        val schemaGenerator = "lib/snygg/src/main/kotlin/org/florisboard/lib/snygg/SnyggJsonSchemaGenerator.kt"
        val violations = linkedSetOf<String>()

        fun codeMask(source: String) = nonCode.replace(source) { match ->
            match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        }

        fun balancedEnd(code: String, open: Int): Int? {
            val stack = mutableListOf(code[open])
            for (index in open + 1 until code.length) {
                when (code[index]) {
                    '(', '[', '{' -> stack += code[index]

                    ')', ']', '}' -> {
                        val expected = when (code[index]) {
                            ')' -> '('
                            ']' -> '['
                            else -> '{'
                        }
                        if (stack.lastOrNull() == expected) {
                            stack.removeAt(stack.lastIndex)
                            if (stack.isEmpty()) return index
                        }
                    }
                }
            }
            return null
        }

        fun logCalls(source: String): List<Triple<String, Int, String>> {
            val code = codeMask(source)
            return logStart.findAll(code).mapNotNull { match ->
                val name = match.groupValues[1]
                val normalizedName = name.filterNot(Char::isWhitespace)
                val previous = code.take(match.range.first).trimEnd().takeLastWhile {
                    it.isLetterOrDigit() || it == '_'
                }
                if (previous == "fun") return@mapNotNull null
                var cursor = match.range.last + 1
                while (code.getOrNull(cursor)?.isWhitespace() == true) cursor++
                if (code.getOrNull(cursor) == '(') {
                    cursor = (balancedEnd(code, cursor) ?: return@mapNotNull null) + 1
                    while (code.getOrNull(cursor)?.isWhitespace() == true) cursor++
                } else if (!normalizedName.startsWith("flog")) {
                    return@mapNotNull null
                }
                if (normalizedName.startsWith("flog") && code.getOrNull(cursor) == '{') {
                    cursor = (balancedEnd(code, cursor) ?: return@mapNotNull null) + 1
                }
                Triple(normalizedName, match.range.first, source.substring(match.range.first, cursor))
            }.toList()
        }

        fun topLevelArguments(call: String): List<String> {
            val code = codeMask(call)
            val open = code.indexOf('(')
            if (open < 0) return emptyList()
            val close = balancedEnd(code, open) ?: return emptyList()
            val parts = mutableListOf<String>()
            var start = open + 1
            var index = start
            while (index < close) {
                if (code[index] in "([{") {
                    index = (balancedEnd(code, index) ?: close - 1) + 1
                } else if (code[index] == ',') {
                    parts += call.substring(start, index)
                    start = ++index
                } else {
                    index++
                }
            }
            if (start < close) parts += call.substring(start, close)
            return parts
        }

        fun argumentsAt(source: String, start: Int): List<String> {
            val tail = source.substring(start)
            val code = codeMask(tail)
            val open = code.indexOf('(')
            val close = open.takeIf { it >= 0 }?.let { balancedEnd(code, it) } ?: return emptyList()
            return topLevelArguments(tail.substring(0, close + 1))
        }

        fun templateExpressions(payload: String): List<String> = buildList {
            var cursor = 0
            while (cursor < payload.length) {
                val dollar = payload.indexOf('$', cursor)
                if (dollar < 0 || dollar + 1 >= payload.length) break
                if (payload[dollar + 1] == '{') {
                    val tail = payload.substring(dollar + 1)
                    val close = balancedEnd(codeMask(tail), 0) ?: break
                    val expression = tail.substring(1, close)
                    add(expression)
                    addAll(templateExpressions(expression))
                    cursor = dollar + close + 2
                } else {
                    val match = identifier.find(payload, dollar + 1)
                    if (match?.range?.first == dollar + 1) {
                        add(match.value)
                        cursor = match.range.last + 1
                    } else {
                        cursor = dollar + 1
                    }
                }
            }
        }

        fun nameParts(name: String) = Regex("""[A-Z]+(?=[A-Z][a-z]|\b)|[A-Z]?[a-z]+|\d+""")
            .findAll(name.replace('_', ' '))
            .map { it.value.lowercase() }
            .toList()

        fun isUnsafeName(name: String): Boolean {
            val parts = nameParts(name)
            val normalized = parts.joinToString("")
            if (Regex(
                    """^(?:activeimeids|actionid|actionlabel|contentmimetypes|dx|dy|editorinfo|fieldid|""" +
                        """contentmimetype|defaultimeid|eventx|eventy|failure|fieldname|imeoptions|info|""" +
                        """initialcapsmode|initialselend|initialselstart|inputtype|selectionend|selectionstart|""" +
                        """newselend|newselstart|oldselend|oldselstart|packagename|privateimeoptions|selectedimeid|x|y)$""",
                ).matches(normalized)
            ) {
                return true
            }
            if (safeMetadataName.matches(parts.joinToString("_"))) {
                return false
            }
            val payloadPart = Regex(
                """^(?:bundle|candidates?|clip|clipboard|composing|composition|coordinates?|coords|""" +
                    """dictionary|editor|extras|message|motion|path|payload|pointer|position|raw|suggestions?|""" +
                    """text|touch|uri|velocity|word)$""",
            )
            val throwable = normalized in setOf("e", "err", "error", "exception", "failure", "it", "t", "throwable") ||
                parts.lastOrNull() in setOf("cause", "error", "exception", "failure", "throwable")
            return throwable || parts.any { payloadPart.matches(it) } ||
                normalized in setOf("keydata", "listraw", "primaryclip", "textinfo")
        }

        fun isUnsafePayload(payload: String): Boolean {
            fun unsafe(code: String) = unsafeRendering.containsMatchIn(code) ||
                (!safeProjection.matches(code) && identifier.findAll(code).any { isUnsafeName(it.value) })
            return unsafe(codeMask(payload)) || templateExpressions(payload).any(::unsafe)
        }

        fun isUnsafeLog(name: String, call: String): Boolean {
            val arguments = topLevelArguments(call)
            if (name == "Flog.log") return true
            if (name.startsWith("Log.")) {
                return arguments.size >= 3 || arguments.getOrNull(1)?.let(::isUnsafePayload) == true
            }
            val code = codeMask(call)
            val blockStart = code.indexOf('{')
            val payloads = buildList {
                if (blockStart >= 0) {
                    val end = balancedEnd(code, blockStart)
                    if (end != null) add(call.substring(blockStart + 1, end))
                }
                arguments.getOrNull(1)?.let(::add)
                arguments.firstOrNull { it.trimStart().startsWith("block") }
                    ?.substringAfter('=', "")
                    ?.let(::add)
            }
            return payloads.any(::isUnsafePayload)
        }

        fun previousLineMatches(source: String, offset: Int, marker: Regex): Boolean {
            val lineStart = source.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
            if (lineStart == 0) return false
            val previousEnd = lineStart - 1
            val previousStart = source.lastIndexOf('\n', previousEnd - 1).let { if (it < 0) 0 else it + 1 }
            return marker.matches(source.substring(previousStart, previousEnd))
        }

        val unsafeExamples = listOf(
            """flogDebug { "safe=${'$'}candidateCount raw=${'$'}{currentWordText.trim()} message=${'$'}message editor=${'$'}{editorInfo.inputType}" }""",
            """flogDebug { text }""",
            """Log.d(TAG, message)""",
            """flogDebug { "clip=${'$'}clipboardItem bundle=${'$'}rawBundle pointer=${'$'}pointerPosition x=${'$'}x" }""",
            """flogDebug { "length=${'$'}{word.take(3).length}" }""",
        )
        check(unsafeExamples.all { logCalls(it).single().let { call -> isUnsafeLog(call.first, call.third) } })
        listOf("v", "d", "i", "w", "e", "wtf").forEach {
            val call = logCalls("""Log.$it(TAG, nested("message", value()), error)""").single()
            check(isUnsafeLog(call.first, call.third))
        }
        val safeExamples = listOf(
            """flogDebug { "count=${'$'}candidateCount size=${'$'}{suggestions.size} total=${'$'}{candidates.count()} length=${'$'}{word.length}" }""",
            """flogDebug { "kind=${'$'}{candidate::class.simpleName} failed=${'$'}{error.javaClass.simpleName}" }""",
            """flogDebug { "hasInfo=${'$'}{editorInfo != null} id=${'$'}requestId" }""",
        )
        check(safeExamples.all { logCalls(it).single().let { call -> !isUnsafeLog(call.first, call.third) } })
        val validMarker = "// quality: allow-sensitive-log -- reviewed enum-only value\nflogDebug { text }"
        val invalidMarker = "// quality: allow-sensitive-log -- \nflogDebug { text }"
        val validConsoleMarker = "// quality: allow-console-output -- generator progress only\nprintln(\"done\")"
        check(
            consoleOutput.containsMatchIn("""println("raw")""") &&
                stackTraceOutput.containsMatchIn("error.printStackTrace()") &&
                exportRendering.containsMatchIn("error.localizedMessage") &&
                previousLineMatches(validMarker, validMarker.indexOf("flog"), logMarker) &&
                !previousLineMatches(invalidMarker, invalidMarker.indexOf("flog"), logMarker) &&
                previousLineMatches(
                    validConsoleMarker,
                    validConsoleMarker.indexOf("println"),
                    consoleMarker,
                ) &&
                !logMarker.matches("val bypass = 1 // quality: allow-sensitive-log -- reason") &&
                !consoleMarker.matches("// quality: allow-console-output -- ") &&
                isUnsafeLog("Log.d", logCalls("Log\n . \n d(TAG, text)").single().third) &&
                isUnsafeLog("Flog.log", logCalls("""Flog . log(LEVEL, "redacted")""").single().third) &&
                isUnsafePayload(argumentsAt("appendLine(nested(prefix(), failure))", 0).single()) &&
                !isUnsafePayload(argumentsAt("append(error.javaClass.simpleName)", 0).single()) &&
                rawDebugExport.containsMatchIn(codeMask("""Runtime.getRuntime().exec("logcat")""")) &&
                rawLogcatCommand.containsMatchIn("""arrayOf("logcat")""") &&
                diagnosticExportDeclaration.containsMatchIn(
                    "fun generateDiagnosticDump() = buildString { appendLine() }",
                ),
        )
        check(
            listOf(
                "import android.util.Log.d",
                "import android.util.Log.d as debugLog",
                "import android.util.Log.*",
                "import android.util.Log as AndroidLog",
                "import dev.patrickgold.florisboard.lib.devtools.Flog as PrivateLog",
                "import dev.patrickgold.florisboard.lib.devtools.Flog.log as privateLog",
                "import dev.patrickgold.florisboard.lib.devtools.flogDebug as privateLog",
            ).all(forbiddenLogImport::matches),
        )
        privacySources.files.sorted().forEach { source ->
            val contents = source.readText()
            val path = source.relativeTo(rootDir).invariantSeparatorsPath
            val code = codeMask(contents)
            forbiddenLogImport.findAll(code).forEach {
                violations += "$path:${contents.take(it.range.first).count { char -> char == '\n' } + 1}"
            }
            logCalls(contents).forEach { (name, offset, call) ->
                if (!previousLineMatches(contents, offset, logMarker) && isUnsafeLog(name, call)) {
                    violations += "$path:${contents.take(offset).count { it == '\n' } + 1}"
                }
            }
            stackTraceOutput.findAll(code).forEach {
                violations += "$path:${contents.take(it.range.first).count { char -> char == '\n' } + 1}"
            }
            consoleOutput.findAll(code).forEach {
                val allowed = path == schemaGenerator &&
                    previousLineMatches(contents, it.range.first, consoleMarker)
                if (!allowed) violations += "$path:${contents.take(it.range.first).count { char -> char == '\n' } + 1}"
            }
            if (diagnosticExportDeclaration.containsMatchIn(code)) {
                appendStart.findAll(code).forEach {
                    val firstArgument = argumentsAt(contents, it.range.first).firstOrNull()
                    if (firstArgument?.let(::isUnsafePayload) == true) {
                        violations += "$path:${contents.take(it.range.first).count { char -> char == '\n' } + 1}"
                    }
                }
                exportRendering.findAll(code).forEach {
                    violations += "$path:${contents.take(it.range.first).count { char -> char == '\n' } + 1}"
                }
                templateExpressions(contents).filter { expression ->
                    !safeProjection.matches(expression) &&
                        identifier.findAll(expression).any { isUnsafeName(it.value) }
                }.forEach { violations += "$path:export" }
            }
            if (path.contains("/devtools/") &&
                (rawDebugExport.containsMatchIn(code) || rawLogcatCommand.containsMatchIn(contents))
            ) {
                violations += "$path:raw-debug-export"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Sensitive data must not be written to production diagnostics:\n" +
                    violations.sorted().joinToString(separator = "\n", postfix = "\n") { "  $it" } +
                    "Log only redacted counts, booleans, closed state/type names, opaque IDs, or duration " +
                    "buckets. See config/quality/README.md for the narrow exception marker.",
            )
        }
    }
}

val documentationCheck by tasks.registering {
    group = "verification"
    description = "Checks that local links in canonical Markdown documentation resolve."

    val documentationFiles = files(
        fileTree(rootDir) {
            include("*.md")
        },
        fileTree("$rootDir/docs") {
            include("**/*.md")
        },
    )
    inputs.files(documentationFiles)

    doLast {
        val inlineLink = Regex(
            """!?\[[^]\n]*]\(\s*(?:<([^>\n]+)>|([^\s)\n]+))""",
        )
        val referenceLink = Regex(
            """^\s*\[[^]\n]+]:\s*(?:<([^>\n]+)>|(\S+))""",
        )
        val inlineCode = Regex("""`[^`\n]*`""")
        val uriScheme = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
        val failures = mutableListOf<String>()

        documentationFiles.files.sorted().forEach { markdown ->
            var activeFence: String? = null
            markdown.readLines().forEachIndexed { index, rawLine ->
                val trimmed = rawLine.trimStart()
                val fenceMarker = when {
                    trimmed.startsWith("```") -> "```"
                    trimmed.startsWith("~~~") -> "~~~"
                    else -> null
                }
                if (fenceMarker != null) {
                    activeFence =
                        if (activeFence == null) {
                            fenceMarker
                        } else if (activeFence == fenceMarker) {
                            null
                        } else {
                            activeFence
                        }
                    return@forEachIndexed
                }
                if (activeFence != null) {
                    return@forEachIndexed
                }

                val line = rawLine.replace(inlineCode, "")
                val rawTargets = buildList {
                    inlineLink.findAll(line).forEach { match ->
                        add(match.groupValues[1].ifBlank { match.groupValues[2] })
                    }
                    referenceLink.find(line)?.let { match ->
                        add(match.groupValues[1].ifBlank { match.groupValues[2] })
                    }
                }

                rawTargets.forEach { rawTarget ->
                    val target = rawTarget.trim()
                    if (
                        target.isEmpty() ||
                        target.startsWith("#") ||
                        target.startsWith("//") ||
                        uriScheme.containsMatchIn(target)
                    ) {
                        return@forEach
                    }

                    val pathOnly = target.substringBefore('#').substringBefore('?')
                    if (pathOnly.isEmpty()) {
                        return@forEach
                    }
                    val decodedPath = java.net.URLDecoder.decode(
                        pathOnly.replace("+", "%2B"),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                    val resolved = if (decodedPath.startsWith("/")) {
                        rootDir.toPath().resolve(decodedPath.removePrefix("/"))
                    } else {
                        markdown.parentFile.toPath().resolve(decodedPath)
                    }.normalize()

                    if (!resolved.startsWith(rootDir.toPath()) || !resolved.toFile().exists()) {
                        failures += "${markdown.relativeTo(rootDir)}:${index + 1} -> $target"
                    }
                }
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Broken local Markdown links:")
                    failures.distinct().sorted().forEach { appendLine("  $it") }
                },
            )
        }
    }
}

val ciStaticAnalysis by tasks.registering {
    group = "verification"
    description = "Runs formatting, Detekt, Android lint, documentation, and privacy checks."
    dependsOn(formatCheck, tasks.named("detekt"), ciLint, documentationCheck, privacySourceCheck)
}

val ciPackage by tasks.registering {
    group = "verification"
    description = "Builds debug, minified beta, benchmark, and autocorrect API artifacts."
    dependsOn(
        ":app:assembleDebug",
        ":app:assembleBeta",
        ":benchmark:assembleBenchmark",
        ":lib:autocorrect-api:checkAutocorrectApi",
    )
}

tasks.register("qualityGate") {
    group = "verification"
    description = "Runs the complete local merge-safety gate."
    dependsOn(ciUnitTest, ciStaticAnalysis, ciPackage)
}

/*
 * Task discovery happens after every subproject has been evaluated. This keeps
 * the gate current when another Android or pure Kotlin module is added.
 */
gradle.projectsEvaluated {
    ciUnitTest.configure {
        dependsOn(
            subprojects.flatMap { project ->
                val androidUnitTests = project.tasks.matching { it.name == "testDebugUnitTest" }.toList()
                if (androidUnitTests.isNotEmpty()) {
                    androidUnitTests
                } else {
                    project.tasks.matching { it.name == "test" }.toList()
                }
            },
        )
    }
    ciLint.configure {
        dependsOn(
            subprojects.flatMap { project ->
                project.tasks.matching { it.name == "lintDebug" }.toList()
            },
        )
    }
}
