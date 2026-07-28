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
    file("app/src/test/kotlin/dev/patrickgold/florisboard/ime/nlp/NlpProviderLifecycleTest.kt"),
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
    description = "Rejects obvious sensitive keyboard data in production logs."

    val privacySources = fileTree("app/src/main/kotlin") {
        include(
            "**/ime/keyboard/**/*.kt",
            "**/ime/nlp/**/*.kt",
            "**/ime/text/**/*.kt",
        )
    }
    inputs.files(privacySources)

    doLast {
        val logCall = Regex(
            """(?s)\b(?:flog(?:Error|Warning|Info|Debug)\s*(?:\([^)]*\))?\s*\{.*?}|Log\.[vdiew]\s*\(.*?\))""",
        )
        val rawPointerIdentifier = Regex(
            """\b(?:motionEvent|pointerEvent|pointerInputChange|pointerData)\b""",
        )
        val sensitiveInterpolation = Regex(
            """\$(?:\{[^}\n]*\b(?:typedText|inputText|text|candidates?|suggestions?|dictionary(?:Entry|Entries|Word|Words)?|word|composition|composingText)\b[^}\n]*}|(?:typedText|inputText|text|candidates?|suggestions?|dictionary(?:Entry|Entries|Word|Words)?|word|composition|composingText)\b)""",
            RegexOption.IGNORE_CASE,
        )
        val safeSensitiveProjection = Regex(
            """\$\{[^}\n]*\b(?:typedText|inputText|text|candidates?|suggestions?|dictionary(?:Entry|Entries|Word|Words)?|word|composition|composingText)\b[^}\n]*(?:::class\.simpleName|\.(?:size|length)|\.count\(\))\s*}""",
            RegexOption.IGNORE_CASE,
        )
        val suppressionMarker = "quality: allow-sensitive-log"
        val violations = mutableListOf<String>()

        check(
            logCall.containsMatchIn("""flogDebug { "candidate=${'$'}candidate" }""") &&
                sensitiveInterpolation.containsMatchIn("""flogDebug { "candidate=${'$'}candidate" }""") &&
                logCall.containsMatchIn("""Log.d("touch", motionEvent.toString())""") &&
                rawPointerIdentifier.containsMatchIn("""Log.d("touch", motionEvent.toString())"""),
        ) {
            "privacySourceCheck patterns no longer recognize their protected cases"
        }
        check(
            !sensitiveInterpolation.containsMatchIn("""flogDebug { "candidateCount=${'$'}candidateCount" }""") &&
                safeSensitiveProjection.matches("""${'$'}{candidate::class.simpleName}""") &&
                safeSensitiveProjection.matches("""${'$'}{candidates.size}"""),
        ) {
            "privacySourceCheck must allow explicitly redacted metadata and counts"
        }

        privacySources.files.sorted().forEach { source ->
            val contents = source.readText()
            val lines = contents.lineSequence().toList()
            logCall.findAll(contents).forEach { match ->
                val line = contents.take(match.range.first).count { it == '\n' } + 1
                val previousLine = lines.getOrNull(line - 2).orEmpty()
                val callLine = lines.getOrNull(line - 1).orEmpty().trimStart()
                val suppressed = previousLine.contains(suppressionMarker) ||
                    match.value.contains(suppressionMarker)
                val hasSensitiveInterpolation = sensitiveInterpolation.findAll(match.value)
                    .any { !safeSensitiveProjection.matches(it.value) }
                if (!suppressed && !callLine.startsWith("//") &&
                    (
                        rawPointerIdentifier.containsMatchIn(match.value) ||
                            hasSensitiveInterpolation
                        )
                ) {
                    violations += "${source.relativeTo(rootDir)}:$line"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Sensitive input must not be written to Log/Flog:")
                    violations.forEach { appendLine("  $it") }
                    append(
                        "Log only redacted counts, opaque IDs, or duration buckets. " +
                            "See config/quality/README.md for the narrow exception marker.",
                    )
                },
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
