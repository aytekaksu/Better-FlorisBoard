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

import com.android.build.api.dsl.ApplicationExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutlibraries)
    alias(libs.plugins.kotest)
}

@CacheableTask
abstract class GenerateProjectLicenseAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val target = outputDirectory.file("license/project_license.txt").get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.copy(licenseFile.get().asFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
    }
}

@CacheableTask
abstract class GenerateBuiltInThemeAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseStylesheet: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val overlaysDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val targetRoot = outputDirectory.get().asFile.resolve("ime/theme")
        check(!targetRoot.exists() || targetRoot.deleteRecursively()) { "Unable to replace generated themes" }
        generateFamily(
            baseStylesheet.get().asFile,
            overlaysDirectory.get().asFile,
            targetRoot.resolve("org.florisboard.themes/stylesheets"),
            mapOf(
                "floris_night" to listOf("night"),
                "floris_pure_night" to listOf("night", "pure-night"),
                "floris_day_borderless" to listOf("borderless", "day-borderless"),
                "floris_night_borderless" to listOf("night", "borderless"),
                "floris_pure_night_borderless" to listOf("night", "pure-night", "borderless"),
            ),
        )
        generateFamily(
            baseStylesheet.get().asFile,
            overlaysDirectory.get().asFile.resolve("material-you"),
            targetRoot.resolve("org.florisboard.themes.my/stylesheets"),
            mapOf(
                "floris_day_my" to listOf("day"),
                "floris_night_my" to listOf("day", "night"),
                "floris_pure_night_my" to listOf("day", "night", "pure-night"),
                "floris_day_my_borderless" to listOf("day", "borderless"),
                "floris_night_my_borderless" to listOf("day", "night", "borderless"),
                "floris_pure_night_my_borderless" to listOf("day", "night", "pure-night", "borderless"),
            ),
        )
    }

    private fun generateFamily(baseFile: File, overlays: File, target: File, stylesheets: Map<String, List<String>>) {
        val base = readObject(baseFile)
        check(target.mkdirs()) { "Unable to create generated theme directory" }

        for ((name, layers) in stylesheets) {
            val result = base.toMutableMap()
            for (layer in layers) {
                val overlay = readObject(overlays.resolve("$layer.json"))
                for ((rule, value) in overlay) {
                    result[rule] = if (rule == "@defines") {
                        readObject(result[rule], "base @defines") + readObject(value, "$layer @defines")
                    } else {
                        // A selector overlay replaces the rule, including properties it removes.
                        value
                    }
                }
            }
            target.resolve("$name.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(result)) + "\n")
        }
    }

    private fun readObject(file: File) = readObject(JsonSlurper().parse(file), file.name)

    private fun readObject(value: Any?, label: String): Map<String, Any?> {
        val entries = value as? Map<*, *> ?: error("$label must be a JSON object")
        return entries.mapKeys { (key, _) -> key as? String ?: error("$label has a non-string key") }
    }
}

@CacheableTask
abstract class GenerateNumericRowAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templateFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val zeroDigitsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val digits = JsonSlurper().parse(zeroDigitsFile.get().asFile) as? Map<*, *>
            ?: error("Numeric row digits must be a JSON object")
        val target = outputDirectory.get().asFile.resolve(
            "ime/keyboard/org.florisboard.layouts/layouts/numericRow",
        )
        check(!target.exists() || target.deleteRecursively()) { "Unable to replace generated numeric rows" }
        check(target.mkdirs()) { "Unable to create generated numeric row directory" }

        for ((nameValue, zeroValue) in digits) {
            val name = nameValue as? String ?: error("Numeric row name must be a string")
            check(name.matches(Regex("[a-z_]+"))) { "Invalid numeric row name: $name" }
            val zero = (zeroValue as? Number)?.toInt() ?: error("$name needs a zero-digit code point")
            check(Character.isValidCodePoint(zero + 9)) { "Invalid zero-digit code point for $name" }

            val layout = JsonSlurper().parse(templateFile.get().asFile) as? List<*>
                ?: error("Numeric row template must be an array")
            val row = layout.singleOrNull() as? List<*>
                ?: error("Numeric row template must contain one row")
            check(row.size == 10) { "Numeric row template must contain ten keys" }
            for ((index, value) in row.withIndex()) {
                @Suppress("UNCHECKED_CAST")
                val key = value as? MutableMap<String, Any?>
                    ?: error("Numeric row template key must be an object")
                val digit = if (index == 9) 0 else index + 1
                val codePoint = zero + digit
                key["code"] = codePoint
                key["label"] = String(Character.toChars(codePoint))
            }
            target.resolve("$name.json").writeText(JsonOutput.toJson(layout) + "\n", Charsets.UTF_8)
        }
    }
}

private class GeneratedAssetSafety {
    companion object {
        fun removeTreeNoFollow(root: Path) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                        if (failure != null) throw failure
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        fun clearTarget(output: File, assetPath: String, label: String): File {
            val root = output.toPath()
            val target = root.resolve(assetPath)
            var component = root
            check(!Files.isSymbolicLink(component)) { "Generated $label output path is linked" }
            for (segment in root.relativize(target)) {
                component = component.resolve(segment)
                check(!Files.isSymbolicLink(component)) { "Generated $label output path is linked" }
            }
            removeTreeNoFollow(target)
            return target.toFile()
        }

        fun clearOutputRoot(output: File, label: String): File {
            val root = output.toPath().toAbsolutePath().normalize()
            // Check the variant root and its task-owned localizationAssets/generated/build parents.
            generateSequence(root) { it.parent }.take(4).forEach { component ->
                check(!Files.isSymbolicLink(component)) { "Generated $label output path is linked" }
            }
            removeTreeNoFollow(root)
            return output
        }

        fun readSource(file: File, label: String): String {
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "$label source is missing or linked: ${file.name}"
            }
            return try {
                Files.readString(file.toPath(), Charsets.UTF_8)
            } catch (cause: CharacterCodingException) {
                throw IllegalStateException("${file.name} contains malformed UTF-8", cause)
            }
        }
    }
}

private object SubtypePresetManifest {
    private val MARKER = Regex("""^    @preset\((\{.*\})\)(,?)$""")
    private val FIELD = Regex("\"([A-Za-z][A-Za-z0-9]*)\": \"([A-Za-z0-9_-]+)\"")
    private val PREFERRED = listOf("characters", "symbols", "symbols2", "numericRow", "numericAdvanced")
    private val ALLOWED = (PREFERRED + listOf("tag", "currency", "popup", "composer")).toSet()

    fun expand(template: File): String {
        val source = GeneratedAssetSafety.readSource(template, "Subtype preset")
        check(source.endsWith('\n') && '\r' !in source) { "Subtype preset source must use LF-terminated lines" }
        var count = 0
        val rendered = source.removeSuffix("\n").split('\n').joinToString("\n") { line ->
            val marker = MARKER.matchEntire(line)
            if (marker == null) {
                check("@preset" !in line) { "Malformed @preset marker" }
                line
            } else {
                count++
                renderPreset(marker.groupValues[1], marker.groupValues[2])
            }
        } + "\n"
        check(count == 71) { "Expected 71 @preset markers, found $count" }
        val manifest = try {
            JsonSlurper().parseText(rendered) as? Map<*, *>
        } catch (cause: Exception) {
            throw IllegalStateException("Generated subtype preset manifest is malformed JSON", cause)
        } ?: error("Generated subtype preset manifest must be an object")
        check((manifest["meta"] as? Map<*, *>)?.get("id") == "org.florisboard.localization") {
            "Generated subtype preset manifest has the wrong extension ID"
        }
        val presets = manifest["subtypePresets"] as? List<*>
            ?: error("Generated subtype preset manifest has no preset list")
        check(presets.size == 73) { "Expected 73 subtype presets, found ${presets.size}" }
        check(presets.all { it is Map<*, *> }) { "Generated subtype preset manifest has invalid presets" }
        check(presets.toSet().size == presets.size) { "Duplicate subtype preset record" }
        return rendered
    }

    private fun renderPreset(data: String, trailingComma: String): String {
        val fields = FIELD.findAll(data).toList()
        check(fields.isNotEmpty() && "{${fields.joinToString(", ") { it.value }}}" == data) {
            "Invalid @preset fields"
        }
        val values = fields.associate { it.groupValues[1] to it.groupValues[2] }
        check(values.size == fields.size) { "Duplicate @preset field" }
        check(values.keys.all(ALLOWED::contains)) { "Unknown @preset field" }
        val tag = values["tag"] ?: error("@preset needs tag")
        val currency = values["currency"] ?: error("@preset needs currency")
        check("characters" in values) { "@preset needs characters" }
        val preferred = PREFERRED.mapNotNull { key -> values[key]?.let { key to it } }
        return buildList {
            add("    {")
            add("      \"languageTag\": \"$tag\",")
            add("      \"composer\": \"org.florisboard.composers:${values["composer"] ?: "appender"}\",")
            add("      \"currencySet\": \"org.florisboard.currencysets:$currency\",")
            values["popup"]?.let { add("      \"popupMapping\": \"org.florisboard.localization:$it\",") }
            add("      \"preferred\": {")
            preferred.forEachIndexed { index, (key, value) ->
                val comma = if (index < preferred.lastIndex) "," else ""
                add("        \"$key\": \"org.florisboard.layouts:$value\"$comma")
            }
            add("      }")
            add("    }$trailingComma")
        }.joinToString("\n")
    }
}

@CacheableTask
abstract class GenerateLocalizationAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fragmentFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestTemplateFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() = render(
        sourceDirectory.get().asFile,
        fragmentFile.get().asFile,
        manifestTemplateFile.get().asFile,
        outputDirectory.get().asFile,
    )

    companion object {
        private const val MARKER = "@@COMMON_RIGHT_POPUP@@"
        private const val ASSET_PATH = "ime/keyboard/org.florisboard.localization"

        fun render(sources: File, fragmentFile: File, manifestTemplateFile: File, output: File) {
            val target = clearOutput(output)

            val fragment = readSource(fragmentFile).removeSuffix("\n")
            check(fragment.startsWith("    \"~right\": {") && fragment.endsWith("    }")) {
                "Shared popup fragment must be one ~right entry"
            }
            val fragmentObject = jsonObject("{\n$fragment\n}", fragmentFile.name)
            check(
                fragmentObject.keys == setOf("~right") &&
                    Regex("\"~right\"\\s*:").findAll(fragment).count() == 1,
            ) {
                "Shared popup fragment must contain only one ~right entry"
            }
            val sharedRight = fragmentObject["~right"]
            check(sharedRight is Map<*, *>) { "Shared popup fragment must contain a ~right object" }

            val expandedManifest = SubtypePresetManifest.expand(manifestTemplateFile)
            val manifest = jsonObject(expandedManifest, manifestTemplateFile.name)
            val components = manifest["popupMappings"] as? List<*>
                ?: error("Popup mappings are missing from extension metadata")
            val expected = components.map { component ->
                val entry = component as? Map<*, *> ?: error("Invalid popup mapping metadata")
                val id = entry["id"] as? String ?: error("Popup mapping needs an ID")
                check(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid popup mapping ID: $id" }
                check((entry["mappingFile"] ?: "popupMappings/$id.json") == "popupMappings/$id.json") {
                    "Unexpected popup mapping path for $id"
                }
                "$id.json"
            }
            check(expected.size == expected.toSet().size) { "Duplicate popup mapping ID" }

            check(Files.isDirectory(sources.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Popup mapping source directory is missing or linked"
            }
            val files = sources.listFiles()?.sortedBy(File::getName) ?: error("Cannot list popup mapping sources")
            val rendered = mutableMapOf<String, String>()
            for (file in files) {
                val template = file.name.endsWith(".json.in")
                check(template || file.name.endsWith(".json")) {
                    "Unexpected popup mapping source: ${file.name}"
                }
                val name = if (template) file.name.removeSuffix(".in") else file.name
                val input = readSource(file)
                val firstMarker = input.indexOf(MARKER)
                val markerCount = when {
                    firstMarker < 0 -> 0
                    input.indexOf(MARKER, firstMarker + MARKER.length) < 0 -> 1
                    else -> 2
                }
                check(markerCount == if (template) 1 else 0) {
                    "${file.name} must contain ${if (template) "one" else "no"} shared popup marker"
                }
                val text = if (template) input.replace(MARKER, fragment) else input
                val mapping = jsonObject(text, file.name)
                if (template) {
                    val all = mapping["all"] as? Map<*, *>
                    check(all?.get("~right") == sharedRight) {
                        "${file.name} must place the shared popup in all.~right"
                    }
                }
                check(rendered.putIfAbsent(name, text) == null) { "Duplicate popup mapping file: $name" }
            }
            check(rendered.keys == expected.toSet()) {
                "Popup mapping files differ from extension metadata: " +
                    "missing=${expected.toSet() - rendered.keys}, extra=${rendered.keys - expected.toSet()}"
            }

            try {
                check(target.mkdirs()) { "Unable to create generated localization directory" }
                target.resolve("extension.json").writeText(expandedManifest, Charsets.UTF_8)
                val popupTarget = target.resolve("popupMappings")
                check(popupTarget.mkdirs()) { "Unable to create generated popup mapping directory" }
                for ((name, text) in rendered.toSortedMap()) {
                    popupTarget.resolve(name).writeText(text, Charsets.UTF_8)
                }
            } catch (cause: Exception) {
                try {
                    GeneratedAssetSafety.removeTreeNoFollow(target.toPath())
                } catch (cleanupFailure: Exception) {
                    cause.addSuppressed(cleanupFailure)
                }
                throw cause
            }
        }

        private fun clearOutput(output: File) =
            GeneratedAssetSafety.clearOutputRoot(output, "localization").resolve(ASSET_PATH)

        fun removeTreeNoFollow(root: Path) = GeneratedAssetSafety.removeTreeNoFollow(root)

        private fun readSource(file: File) = GeneratedAssetSafety.readSource(file, "Popup mapping")

        private fun jsonObject(text: String, name: String): Map<*, *> {
            val value = try {
                JsonSlurper().parseText(text)
            } catch (cause: Exception) {
                throw IllegalStateException("$name contains malformed JSON", cause)
            }
            return value as? Map<*, *> ?: error("$name must be a JSON object")
        }
    }
}

@CacheableTask
abstract class GenerateCharacterLayoutAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val staticDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extensionFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() = render(
        sourceDirectory.get().asFile,
        staticDirectory.get().asFile,
        extensionFile.get().asFile,
        outputDirectory.get().asFile,
    )

    companion object {
        private const val ASSET_PATH = "ime/keyboard/org.florisboard.layouts/layouts/characters"
        private val MARKER = Regex("""^    @autoKeys\("([^"]+)"(?:, width=([4-7]))?\)(,?)$""")
        private val FILE_NAME = Regex("[a-z0-9_]+\\.json")
        private val SOURCE_NAME = Regex("[a-z0-9_]+")

        fun render(sources: File, staticFiles: File, extensionFile: File, output: File) {
            val target = GeneratedAssetSafety.clearTarget(output, ASSET_PATH, "character layout")
            val expected = expectedFiles(extensionFile)
            val static = listFiles(staticFiles, ".json").mapTo(mutableSetOf()) { file ->
                validateLayout(readSource(file), file.name)
                file.name
            }
            val generated = listFiles(sources, ".json.in").associate { file ->
                val name = file.name.removeSuffix(".in")
                name to expand(readSource(file), file.name)
            }
            check(static.intersect(generated.keys).isEmpty()) { "Static and generated character layouts collide" }
            check(static + generated.keys == expected) {
                "Character layout files differ from extension metadata: " +
                    "missing=${expected - static - generated.keys}, " +
                    "extra=${(static + generated.keys) - expected}"
            }
            try {
                check(target.mkdirs()) { "Unable to create generated character layout directory" }
                for ((name, text) in generated.toSortedMap()) target.resolve(name).writeText(text, Charsets.UTF_8)
            } catch (cause: Exception) {
                try {
                    GeneratedAssetSafety.removeTreeNoFollow(target.toPath())
                } catch (cleanupFailure: Exception) {
                    cause.addSuppressed(cleanupFailure)
                }
                throw cause
            }
        }

        private fun listFiles(directory: File, suffix: String): List<File> {
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Character layout directory is missing or linked: ${directory.name}"
            }
            return (directory.listFiles() ?: error("Cannot list character layouts in ${directory.name}"))
                .sortedBy(File::getName).onEach { file ->
                    check(file.name.endsWith(suffix) && file.name.removeSuffix(suffix).matches(SOURCE_NAME)) {
                        "Unexpected character layout source: ${file.name}"
                    }
                    check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "Character layout source is missing or linked: ${file.name}"
                    }
                }
        }

        private fun readSource(file: File) = GeneratedAssetSafety.readSource(file, "Character layout")

        private fun expectedFiles(extensionFile: File): Set<String> {
            val extension = jsonObject(readSource(extensionFile), extensionFile.name)
            val layouts = extension["layouts"] as? Map<*, *> ?: error("Extension layouts are missing")
            val characters = layouts["characters"] as? List<*> ?: error("Character layouts are missing")
            val ids = mutableSetOf<String>()
            return characters.map { value ->
                val component = value as? Map<*, *> ?: error("Invalid character layout metadata")
                val id = component["id"] as? String ?: error("Character layout needs an ID")
                check(id.matches(Regex("[a-z0-9_]+"))) { "Invalid character layout ID: $id" }
                check(ids.add(id)) { "Duplicate character layout ID: $id" }
                val path = component["arrangementFile"] ?: "layouts/characters/$id.json"
                check(
                    path is String && path.startsWith("layouts/characters/") &&
                        path.removePrefix("layouts/characters/").matches(FILE_NAME),
                ) {
                    "Unexpected character layout path for $id"
                }
                path.removePrefix("layouts/characters/")
            }.toSet()
        }

        private fun expand(template: String, name: String): String {
            check(template.endsWith('\n') && '\r' !in template) { "$name must use LF-terminated lines" }
            var count = 0
            val text = template.removeSuffix("\n").split('\n').joinToString("\n") { line ->
                val marker = MARKER.matchEntire(line)
                if (marker == null) {
                    check("@autoKeys" !in line) { "$name contains malformed @autoKeys marker" }
                    line
                } else {
                    count++
                    val keys = marker.groupValues[1].codePoints().toArray()
                    check(keys.size in 3..32 && keys.all(Character::isLetter)) {
                        "$name contains invalid @autoKeys characters"
                    }
                    val width = marker.groupValues[2].toIntOrNull() ?: 5
                    val trailingComma = marker.groupValues[3].isNotEmpty()
                    keys.mapIndexed { index, codePoint ->
                        val code = codePoint.toString()
                        check(code.length < width) { "$name has an @autoKeys code wider than $width" }
                        val comma = if (index < keys.lastIndex || trailingComma) "," else ""
                        val label = String(Character.toChars(codePoint))
                        "    { \"$\": \"auto_text_key\", \"code\":${code.padStart(
                            width,
                        )}, \"label\": \"$label\" }$comma"
                    }.joinToString("\n")
                }
            } + "\n"
            check(count > 0) { "$name contains no @autoKeys markers" }
            validateLayout(text, name)
            return text
        }

        private fun validateLayout(text: String, name: String) {
            val rows = try {
                JsonSlurper().parseText(text) as? List<*>
            } catch (cause: Exception) {
                throw IllegalStateException("$name contains malformed JSON", cause)
            } ?: error("$name must be a JSON layout array")
            check(
                rows.isNotEmpty() && rows.all { row ->
                    row is List<*> && row.isNotEmpty() && row.all { it is Map<*, *> }
                },
            ) {
                "$name must contain nonempty key rows"
            }
        }

        private fun jsonObject(text: String, name: String): Map<*, *> = try {
            JsonSlurper().parseText(text) as? Map<*, *> ?: error("$name must be a JSON object")
        } catch (cause: Exception) {
            throw IllegalStateException("$name contains malformed JSON", cause)
        }
    }
}

val projectMinSdk: String by project
val projectTargetSdk: String by project
val projectCompileSdk: String by project
val projectVersionCode: String by project
val projectVersionName: String by project
val projectVersionNameSuffix = projectVersionName.substringAfter("-", "").let { suffix ->
    if (suffix.isNotEmpty()) {
        "-$suffix"
    } else {
        suffix
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.set(
            listOf(
                "-opt-in=kotlin.contracts.ExperimentalContracts",
                "-Xexplicit-backing-fields",
                "-Xcontext-parameters",
            ),
        )
    }
}

configure<ApplicationExtension> {
    namespace = "dev.patrickgold.florisboard"
    compileSdk = projectCompileSdk.toInt()
    buildToolsVersion = tools.versions.buildTools.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "dev.patrickgold.florisboard"
        minSdk = projectMinSdk.toInt()
        targetSdk = projectTargetSdk.toInt()
        versionCode = projectVersionCode.toInt()
        versionName = projectVersionName.substringBefore("-")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${getGitCommitHash().get()}\"")
        buildConfigField("String", "FLADDONS_API_VERSION", "\"v~draft2\"")
        buildConfigField("String", "FLADDONS_STORE_URL", "\"beta.addons.florisboard.org\"")

        sourceSets {
            maybeCreate("androidTest").apply {
                assets.directories += "$projectDir/schemas"
                assets.directories += "$projectDir/../test-fixtures/fonts"
            }
        }
    }

    bundle {
        language {
            // We disable language split because FlorisBoard does not use
            // runtime Google Play Service APIs and thus cannot dynamically
            // request to download the language resources for a specific locale.
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug+${getGitCommitHash(short = true).get()}"

            isDebuggable = true
        }

        create("beta") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }

        named("release") {
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }

        create("benchmark") {
            initWith(getByName("release"))

            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench+${getGitCommitHash(short = true).get()}"

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }

        // Capture profile rules from readable, unoptimized code. The shipped
        // release and measured benchmark variants remain minified.
        create("profile") {
            initWith(getByName("release"))

            applicationIdSuffix = ".profile"
            versionNameSuffix = "-profile+${getGitCommitHash(short = true).get()}"

            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    lint {
        /*
         * lint.xml configures detectors. lint-baseline.xml records findings
         * present when the gate was introduced, so new warnings and errors
         * fail the gate.
         */
        lintConfig = file("lint.xml")
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

val testLocalizationAssetGenerator by tasks.registering {
    group = "verification"
    description = "Exercises bundled manifest and popup generation with synthetic sources."

    doLast {
        val marker = "@@COMMON_RIGHT_POPUP@@"
        val fragment = "    \"~right\": {\n      \"main\": {\"code\": 44}\n    }"
        val assetPath = "ime/keyboard/org.florisboard.localization/popupMappings"
        val firstPreset = "    @preset({\"tag\": \"en-US\", \"currency\": \"dollar\", \"characters\": \"qwerty\"})"
        val secondPreset = "    @preset({\"tag\": \"en-US\", \"currency\": \"euro\", " +
            "\"popup\": \"en\", \"characters\": \"qwertz\", \"symbols\": \"western\"})"
        val extraPresets = (2 until 71).joinToString(",\n") { index ->
            "    @preset({\"tag\": \"case-$index\", \"currency\": \"dollar\", \"characters\": \"qwerty\"})"
        }
        val literalPreset = "{\"languageTag\":\"fr\",\"composer\":\"org.florisboard.composers:appender\"," +
            "\"currencySet\":\"org.florisboard.currencysets:euro\",\"preferred\":{" +
            "\"characters\":\"org.florisboard.layouts:azerty\"}}"
        val secondLiteral = "{\"languageTag\":\"de\",\"composer\":\"org.florisboard.composers:appender\"," +
            "\"currencySet\":\"org.florisboard.currencysets:euro\",\"preferred\":{" +
            "\"characters\":\"org.florisboard.layouts:qwertz\"}}"
        var caseNumber = 0

        fun exercise(
            ids: List<String>,
            files: Map<String, String>,
            shared: String = fragment,
            failure: String? = null,
            expectOutputAbsent: Boolean = true,
            outputPath: String = "output",
            prepare: (File) -> Unit = {},
        ): File {
            val root = temporaryDir.resolve("case-${caseNumber++}")
            GenerateLocalizationAssets.removeTreeNoFollow(root.toPath())
            val sources = root.resolve("sources").apply { mkdirs() }
            for ((name, text) in files) sources.resolve(name).writeText(text)
            val sharedFile = root.resolve("right.inc").apply { writeText("$shared\n") }
            val extension = root.resolve("extension.json").apply {
                val entries = ids.joinToString { "{\"id\":\"$it\"}" }
                writeText(
                    "{\"meta\":{\"id\":\"org.florisboard.localization\"},\"popupMappings\":[$entries]," +
                        "\"subtypePresets\":[\n" +
                        "$firstPreset,\n$secondPreset,\n$extraPresets,\n" +
                        "    $literalPreset,\n    $secondLiteral\n]}\n",
                )
            }
            val output = root.resolve(outputPath)
            prepare(root)
            val error = runCatching {
                GenerateLocalizationAssets.render(sources, sharedFile, extension, output)
            }.exceptionOrNull()
            if (failure == null) {
                check(error == null) { "Localization generator unexpectedly failed: ${error?.message}" }
            } else {
                check(error?.message?.contains(failure) == true) {
                    "Expected localization generator failure '$failure', got '${error?.message}'"
                }
                if (expectOutputAbsent) {
                    check(!output.resolve(assetPath).exists()) { "Invalid localization data produced popups" }
                    check(!output.resolve("ime/keyboard/org.florisboard.localization/extension.json").exists()) {
                        "Invalid localization data produced a manifest"
                    }
                }
            }
            return output.resolve(assetPath)
        }

        var siblingOutside: File? = null
        var generatedOutput: File? = null
        val generated = exercise(
            listOf("en", "fr", "ar"),
            mapOf(
                "en.json.in" to "{\"all\":{\n$marker\n}}\n",
                "fr.json.in" to "{\"all\":{\n$marker\n},\"uri\":{}}\n",
                "ar.json" to "{\"all\":{\"a\":{}}}\n",
            ),
        ) { root ->
            val output = root.resolve("output").apply { mkdirs() }
            output.resolve("stale.json").writeText("stale")
            val outside = root.resolve("outside").apply { mkdirs() }
            outside.resolve("keep.json").writeText("safe")
            Files.createSymbolicLink(output.resolve("stale-link").toPath(), outside.toPath())
            siblingOutside = outside
            generatedOutput = output
        }
        check(generatedOutput?.listFiles().orEmpty().map(File::getName) == listOf("ime"))
        check(siblingOutside?.resolve("keep.json")?.readText() == "safe")
        check(
            generated.listFiles().orEmpty().map(File::getName).sorted() ==
                listOf("ar.json", "en.json", "fr.json"),
        )
        check(generated.resolve("ar.json").readText() == "{\"all\":{\"a\":{}}}\n")
        check(generated.resolve("en.json").readText() == "{\"all\":{\n$fragment\n}}\n")
        check(generated.resolve("fr.json").readText() == "{\"all\":{\n$fragment\n},\"uri\":{}}\n")
        val generatedManifest = generated.parentFile.resolve("extension.json").readText()
        check(
            generatedManifest.startsWith(
                "{\"meta\":{\"id\":\"org.florisboard.localization\"},\"popupMappings\":[" +
                    "{\"id\":\"en\"}, {\"id\":\"fr\"}, {\"id\":\"ar\"}],\"subtypePresets\":[\n" +
                    "    {\n      \"languageTag\": \"en-US\",\n" +
                    "      \"composer\": \"org.florisboard.composers:appender\",\n" +
                    "      \"currencySet\": \"org.florisboard.currencysets:dollar\",\n" +
                    "      \"preferred\": {\n        \"characters\": \"org.florisboard.layouts:qwerty\"\n" +
                    "      }\n    },\n" +
                    "    {\n      \"languageTag\": \"en-US\",\n" +
                    "      \"composer\": \"org.florisboard.composers:appender\",\n" +
                    "      \"currencySet\": \"org.florisboard.currencysets:euro\",\n" +
                    "      \"popupMapping\": \"org.florisboard.localization:en\",\n" +
                    "      \"preferred\": {\n        \"characters\": \"org.florisboard.layouts:qwertz\",\n" +
                    "        \"symbols\": \"org.florisboard.layouts:western\"\n      }\n    },\n",
            ),
        )
        check(generatedManifest.endsWith("    $literalPreset,\n    $secondLiteral\n]}\n"))
        val generatedPresets = (JsonSlurper().parseText(generatedManifest) as Map<*, *>)["subtypePresets"] as List<*>
        check(generatedPresets.size == 73)
        check(
            generatedPresets.map { (it as Map<*, *>)["languageTag"] } ==
                listOf("en-US", "en-US") + (2 until 71).map { "case-$it" } + listOf("fr", "de"),
        )

        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "Expected 71 @preset markers, found 0") { root ->
            val template = root.resolve("extension.json")
            template.writeText(template.readText().replace("@preset", "@preseT"))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "Expected 71 @preset markers, found 70") { root ->
            val template = root.resolve("extension.json")
            template.writeText(template.readText().replaceFirst("$firstPreset,\n", ""))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "Expected 71 @preset markers, found 72") { root ->
            val template = root.resolve("extension.json")
            template.writeText(template.readText().replaceFirst("$firstPreset,\n", "$firstPreset,\n$firstPreset,\n"))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "Expected 73 subtype presets, found 72") { root ->
            val template = root.resolve("extension.json")
            template.writeText(template.readText().replace("    $literalPreset,\n", ""))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "Duplicate subtype preset record") { root ->
            val template = root.resolve("extension.json")
            template.writeText(template.readText().replaceFirst(secondPreset, firstPreset))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "Malformed @preset marker") { root ->
            val template = root.resolve("extension.json")
            template.writeText(template.readText().replace("@preset(", "@preset!("))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "Duplicate @preset field") { root ->
            val template = root.resolve("extension.json")
            template.writeText(
                template.readText().replaceFirst("\"tag\": \"en-US\"", "\"tag\": \"en-US\", \"tag\": \"hy\""),
            )
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "Unknown @preset field") { root ->
            val template = root.resolve("extension.json")
            template.writeText(
                template.readText().replaceFirst(
                    "\"currency\": \"dollar\"",
                    "\"currency\": \"dollar\", \"extra\": \"x\"",
                ),
            )
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "@preset needs characters") { root ->
            val template = root.resolve("extension.json")
            template.writeText(template.readText().replaceFirst(", \"characters\": \"qwerty\"", ""))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "malformed JSON") { root ->
            val template = root.resolve("extension.json")
            template.writeText(template.readText().replace("]}\n", "]\n"))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "malformed UTF-8") { root ->
            Files.write(root.resolve("extension.json").toPath(), byteArrayOf(0xc3.toByte(), 0x28))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "missing or linked") { root ->
            val template = root.resolve("extension.json")
            val outside = root.resolve("outside.json")
            Files.move(template.toPath(), outside.toPath())
            Files.createSymbolicLink(template.toPath(), outside.toPath())
        }

        exercise(listOf("en"), emptyMap(), failure = "missing=[en.json]")
        exercise(listOf("en"), mapOf("en.json" to "{}", "extra.json" to "{}"), failure = "extra=[extra.json]")
        exercise(
            listOf("en"),
            mapOf(
                "en.json" to "{}",
                "en.json.in" to "{\"all\":{\n$marker\n}}",
            ),
            failure = "Duplicate popup mapping file",
        )
        exercise(
            listOf("en"),
            mapOf(
                "en.json.in" to "{\"all\":{\n$marker,\n$marker\n}}",
            ),
            failure = "one shared popup marker",
        )
        exercise(listOf("en"), mapOf("en.json.in" to "{\"all\":{}}"), failure = "one shared popup marker")
        exercise(listOf("en"), mapOf("en.json" to marker), failure = "no shared popup marker")
        exercise(listOf("en"), mapOf("en.json.in" to "{\"all\":{\n$marker\n"), failure = "malformed JSON")
        exercise(listOf("en"), mapOf("en.json" to "{broken}"), failure = "malformed JSON")
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "malformed UTF-8") { root ->
            Files.write(root.resolve("sources/en.json").toPath(), byteArrayOf(0xc3.toByte(), 0x28))
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "malformed UTF-8") { root ->
            Files.write(root.resolve("right.inc").toPath(), byteArrayOf(0xc3.toByte(), 0x28))
        }
        exercise(listOf("en"), emptyMap(), failure = "missing or linked") { root ->
            val outside = root.resolve("outside.json").apply { writeText("{}") }
            Files.createSymbolicLink(root.resolve("sources/en.json").toPath(), outside.toPath())
        }
        exercise(listOf("en"), mapOf("en.json" to "{}"), failure = "missing or linked") { root ->
            val linked = root.resolve("right.inc")
            val outside = root.resolve("outside.inc")
            Files.move(linked.toPath(), outside.toPath())
            Files.createSymbolicLink(linked.toPath(), outside.toPath())
        }
        exercise(listOf("en"), mapOf("en.json.in" to "{\"uri\":{\n$marker\n}}"), failure = "all.~right")
        exercise(
            listOf("en"),
            mapOf("en.json.in" to "{\"all\":{\n$marker\n}}"),
            shared = "    \"~right\": {\n      broken\n    }",
            failure = "malformed JSON",
        )
        exercise(
            listOf("en"),
            mapOf("en.json.in" to "{\"all\":{\n$marker\n}}"),
            shared = "    \"~right\": {},\n    \"extra\": {\n    }",
            failure = "only one ~right entry",
        )
        exercise(
            listOf("en"),
            mapOf("en.json.in" to "{\"all\":{\n$marker\n}}"),
            shared = "    \"~right\": {},\n    \"~right\": {\n    }",
            failure = "only one ~right entry",
        )
        exercise(listOf("en"), mapOf("en.json" to "{broken}"), failure = "malformed JSON") { root ->
            root.resolve("output/$assetPath/stale.json").apply {
                parentFile.mkdirs()
                writeText("{}")
            }
        }
        var nestedOutside: File? = null
        exercise(listOf("en"), mapOf("en.json" to "{broken}"), failure = "malformed JSON") { root ->
            val outside = root.resolve("outside").apply { mkdirs() }
            outside.resolve("keep.json").writeText("safe")
            val target = root.resolve("output/$assetPath").apply { mkdirs() }
            Files.createSymbolicLink(target.resolve("nested").toPath(), outside.toPath())
            nestedOutside = outside
        }
        check(nestedOutside?.resolve("keep.json")?.readText() == "safe")

        var parentOutside: File? = null
        exercise(
            listOf("en"),
            mapOf("en.json" to "{}"),
        ) { root ->
            val outside = root.resolve("outside")
            outside.resolve("keyboard/org.florisboard.localization/popupMappings/stale.json").apply {
                parentFile.mkdirs()
                writeText("safe")
            }
            Files.createDirectories(root.resolve("output").toPath())
            Files.createSymbolicLink(root.resolve("output/ime").toPath(), outside.toPath())
            parentOutside = outside
        }
        check(
            parentOutside?.resolve("keyboard/org.florisboard.localization/popupMappings/stale.json")?.readText() ==
                "safe",
        )

        var rootOutside: File? = null
        exercise(
            listOf("en"),
            mapOf("en.json" to "{}"),
            failure = "output path is linked",
            expectOutputAbsent = false,
        ) { root ->
            val outside = root.resolve("outside").apply { mkdirs() }
            outside.resolve("keep.json").writeText("safe")
            Files.createSymbolicLink(root.resolve("output").toPath(), outside.toPath())
            rootOutside = outside
        }
        check(rootOutside?.resolve("keep.json")?.readText() == "safe")
        var parentLinkOutside: File? = null
        exercise(
            listOf("en"),
            mapOf("en.json" to "{}"),
            failure = "output path is linked",
            expectOutputAbsent = false,
            outputPath = "linked/debug",
        ) { root ->
            val outside = root.resolve("outside").apply { mkdirs() }
            outside.resolve("keep.json").writeText("safe")
            Files.createSymbolicLink(root.resolve("linked").toPath(), outside.toPath())
            parentLinkOutside = outside
        }
        check(parentLinkOutside?.resolve("keep.json")?.readText() == "safe")
        exercise(listOf("en", "en"), mapOf("en.json" to "{}"), failure = "Duplicate popup mapping ID")
        exercise(listOf("../other"), emptyMap(), failure = "Invalid popup mapping ID")
    }
}

val testCharacterLayoutAssetGenerator by tasks.registering {
    group = "verification"
    description = "Exercises character layout expansion and invalid-source rejection."

    doLast {
        val assetPath = "ime/keyboard/org.florisboard.layouts/layouts/characters"
        val staticLayout = "[[{\"code\":0}]]\n"
        val ascii = "[\n  [\n    @autoKeys(\"abc\")\n  ]\n]\n"
        val warang = "[\n  [\n    @autoKeys(\"𑣀𑣂𑣃\", width=7),\n    {\"code\":0}\n  ]\n]\n"
        var caseNumber = 0

        fun exercise(
            ids: List<String>,
            templates: Map<String, String>,
            static: Map<String, String> = emptyMap(),
            failure: String? = null,
            expectOutputAbsent: Boolean = true,
            prepare: (File) -> Unit = {},
        ): File {
            val root = temporaryDir.resolve("case-${caseNumber++}")
            GenerateLocalizationAssets.removeTreeNoFollow(root.toPath())
            val sources = root.resolve("sources").apply { mkdirs() }
            val staticFiles = root.resolve("static").apply { mkdirs() }
            templates.forEach { (name, text) -> sources.resolve(name).writeText(text, Charsets.UTF_8) }
            static.forEach { (name, text) -> staticFiles.resolve(name).writeText(text, Charsets.UTF_8) }
            val extension = root.resolve("extension.json").apply {
                val components = ids.joinToString(",") { "{\"id\":\"$it\"}" }
                writeText("{\"layouts\":{\"characters\":[$components]}}", Charsets.UTF_8)
            }
            val output = root.resolve("output")
            prepare(root)
            val error = runCatching {
                GenerateCharacterLayoutAssets.render(sources, staticFiles, extension, output)
            }.exceptionOrNull()
            if (failure == null) {
                check(error == null) { "Character layout generator unexpectedly failed: ${error?.message}" }
            } else {
                check(error?.message?.contains(failure) == true) {
                    "Expected character layout failure '$failure', got '${error?.message}'"
                }
                if (expectOutputAbsent) {
                    check(!output.resolve(assetPath).exists()) {
                        "Invalid character layouts produced assets"
                    }
                }
            }
            return output.resolve(assetPath)
        }

        val generated = exercise(
            listOf("ascii", "warang", "other"),
            mapOf("ascii.json.in" to ascii, "warang.json.in" to warang),
            static = mapOf("other.json" to staticLayout),
        )
        check(generated.listFiles().orEmpty().map(File::getName).sorted() == listOf("ascii.json", "warang.json"))
        check(
            generated.resolve("ascii.json").readText() ==
                "[\n  [\n" +
                "    { \"$\": \"auto_text_key\", \"code\":   97, \"label\": \"a\" },\n" +
                "    { \"$\": \"auto_text_key\", \"code\":   98, \"label\": \"b\" },\n" +
                "    { \"$\": \"auto_text_key\", \"code\":   99, \"label\": \"c\" }\n" +
                "  ]\n]\n",
        )
        check(
            generated.resolve("warang.json").readText() ==
                "[\n  [\n" +
                "    { \"$\": \"auto_text_key\", \"code\":  71872, \"label\": \"𑣀\" },\n" +
                "    { \"$\": \"auto_text_key\", \"code\":  71874, \"label\": \"𑣂\" },\n" +
                "    { \"$\": \"auto_text_key\", \"code\":  71875, \"label\": \"𑣃\" },\n" +
                "    {\"code\":0}\n  ]\n]\n",
        )

        exercise(listOf("ascii"), emptyMap(), failure = "missing=[ascii.json]")
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to ascii, "extra.json.in" to ascii),
            failure = "extra=[extra.json]",
        )
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to ascii),
            static = mapOf("ascii.json" to staticLayout),
            failure = "collide",
        )
        exercise(listOf("ascii", "ascii"), mapOf("ascii.json.in" to ascii), failure = "Duplicate character layout ID")
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to ascii),
            failure = "Unexpected character layout path",
        ) { root ->
            root.resolve("extension.json").writeText(
                "{\"layouts\":{\"characters\":[{\"id\":\"ascii\",\"arrangementFile\":\"../outside\"}]}}",
            )
        }
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to ascii.replace("abc", "ab")),
            failure = "invalid @autoKeys characters",
        )
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to ascii.replace("abc", "a:c")),
            failure = "invalid @autoKeys characters",
        )
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to ascii.replace("@autoKeys", "@autoKey")),
            failure = "contains no @autoKeys markers",
        )
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to ascii.replace("@autoKeys(\"abc\")", "@autoKeys(\"abc\", width=8)")),
            failure = "malformed @autoKeys marker",
        )
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to "[\n  [\n    @autoKeys(\"abc\")\n"),
            failure = "malformed JSON",
        )
        exercise(listOf("ascii"), mapOf("ascii.json.in" to ascii), failure = "malformed UTF-8") { root ->
            Files.write(root.resolve("sources/ascii.json.in").toPath(), byteArrayOf(0xc3.toByte(), 0x28))
        }
        exercise(listOf("ascii"), emptyMap(), failure = "missing or linked") { root ->
            val outside = root.resolve("outside.json.in").apply { writeText(ascii) }
            Files.createSymbolicLink(root.resolve("sources/ascii.json.in").toPath(), outside.toPath())
        }
        exercise(listOf("ascii"), mapOf("ascii.json.in" to ascii), failure = "missing or linked") { root ->
            val outside = root.resolve("outside.json").apply { writeText(staticLayout) }
            Files.createSymbolicLink(root.resolve("static/other.json").toPath(), outside.toPath())
        }
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to "[\n  [\n    @autoKeys(\"abc\")\n"),
            failure = "malformed JSON",
        ) { root ->
            root.resolve("output/$assetPath/stale.json").apply {
                parentFile.mkdirs()
                writeText("stale")
            }
        }
        val outside = temporaryDir.resolve("outside-nested").apply { mkdirs() }
        outside.resolve("keep.json").writeText("safe")
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to "[\n  [\n    @autoKeys(\"abc\")\n"),
            failure = "malformed JSON",
        ) { root ->
            val target = root.resolve("output/$assetPath").apply { mkdirs() }
            Files.createSymbolicLink(target.resolve("nested").toPath(), outside.toPath())
        }
        check(outside.resolve("keep.json").readText() == "safe")
        exercise(
            listOf("ascii"),
            mapOf("ascii.json.in" to ascii),
            failure = "output path is linked",
            expectOutputAbsent = false,
        ) { root ->
            val parent = root.resolve("elsewhere").apply { mkdirs() }
            Files.createSymbolicLink(root.resolve("output").toPath(), parent.toPath())
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val variantName = variant.name.replaceFirstChar { it.titlecase() }
        val task = tasks.register<GenerateProjectLicenseAsset>(
            "generate${variantName}ProjectLicenseAsset",
        ) {
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            outputDirectory.set(layout.buildDirectory.dir("generated/projectLicenseAssets/${variant.name}"))
        }
        checkNotNull(variant.sources.assets).addGeneratedSourceDirectory(
            task,
            GenerateProjectLicenseAsset::outputDirectory,
        )
        val themes = tasks.register<GenerateBuiltInThemeAssets>("generate${variantName}BuiltInThemeAssets") {
            baseStylesheet.set(
                layout.projectDirectory.file(
                    "src/main/assets/ime/theme/org.florisboard.themes/stylesheets/floris_day.json",
                ),
            )
            overlaysDirectory.set(layout.projectDirectory.dir("theme-overlays"))
            outputDirectory.set(layout.buildDirectory.dir("generated/builtInThemeAssets/${variant.name}"))
        }
        checkNotNull(variant.sources.assets).addGeneratedSourceDirectory(
            themes,
            GenerateBuiltInThemeAssets::outputDirectory,
        )
        val numericRows = tasks.register<GenerateNumericRowAssets>("generate${variantName}NumericRowAssets") {
            templateFile.set(
                layout.projectDirectory.file(
                    "src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/numericRow/bengali.json",
                ),
            )
            zeroDigitsFile.set(layout.projectDirectory.file("numeric-row-zero-digits.json"))
            outputDirectory.set(layout.buildDirectory.dir("generated/numericRowAssets/${variant.name}"))
        }
        checkNotNull(variant.sources.assets).addGeneratedSourceDirectory(
            numericRows,
            GenerateNumericRowAssets::outputDirectory,
        )
        val localization = tasks.register<GenerateLocalizationAssets>(
            "generate${variantName}LocalizationAssets",
        ) {
            sourceDirectory.set(layout.projectDirectory.dir("popup-mapping-sources"))
            fragmentFile.set(layout.projectDirectory.file("popup-mapping-right-punctuation.inc"))
            manifestTemplateFile.set(layout.projectDirectory.file("subtype-preset-manifest.json.in"))
            outputDirectory.set(layout.buildDirectory.dir("generated/localizationAssets/${variant.name}"))
        }
        checkNotNull(variant.sources.assets).addGeneratedSourceDirectory(
            localization,
            GenerateLocalizationAssets::outputDirectory,
        )
        val characterLayouts = tasks.register<GenerateCharacterLayoutAssets>(
            "generate${variantName}CharacterLayoutAssets",
        ) {
            sourceDirectory.set(layout.projectDirectory.dir("character-layout-sources"))
            staticDirectory.set(
                layout.projectDirectory.dir(
                    "src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters",
                ),
            )
            extensionFile.set(
                layout.projectDirectory.file(
                    "src/main/assets/ime/keyboard/org.florisboard.layouts/extension.json",
                ),
            )
            outputDirectory.set(layout.buildDirectory.dir("generated/characterLayoutAssets/${variant.name}"))
        }
        checkNotNull(variant.sources.assets).addGeneratedSourceDirectory(
            characterLayouts,
            GenerateCharacterLayoutAssets::outputDirectory,
        )
        tasks.withType<Test>().matching { it.name == "test${variantName}UnitTest" }.configureEach {
            dependsOn(
                localization,
                characterLayouts,
                testLocalizationAssetGenerator,
                testCharacterLayoutAssetGenerator,
            )
            inputs.dir(localization.flatMap { it.outputDirectory })
            inputs.dir(characterLayouts.flatMap { it.outputDirectory })
            systemProperty(
                "florisboard.localizationAssetRoot",
                layout.buildDirectory.dir(
                    "generated/localizationAssets/${variant.name}/ime/keyboard/org.florisboard.localization",
                ).get().asFile.absolutePath,
            )
            systemProperty(
                "florisboard.characterLayoutAssetRoot",
                layout.buildDirectory.dir(
                    "generated/characterLayoutAssets/${variant.name}/ime/keyboard/org.florisboard.layouts",
                ).get().asFile.absolutePath,
            )
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("src/main/config")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

tasks.withType<Test> {
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
}

dependencies {
    implementation(projects.lib.autocorrectHostCore)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.views)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.window.core)
    implementation(libs.cache4k)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mikepenz.aboutlibraries.core)
    implementation(libs.mikepenz.aboutlibraries.compose)
    implementation(libs.patrickgold.compose.tooltip)
    implementation(libs.patrickgold.jetpref.datastore.model)
    ksp(libs.patrickgold.jetpref.datastore.model.processor)
    implementation(libs.patrickgold.jetpref.datastore.ui)
    implementation(libs.patrickgold.jetpref.material.ui)

    implementation(projects.lib.android)
    implementation(projects.lib.autocorrectApi)
    implementation(projects.lib.color)
    implementation(projects.lib.compose)
    implementation(projects.lib.kotlin)
    implementation(projects.lib.snygg)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}

fun getGitCommitHash(short: Boolean = false): Provider<String> {
    if (!File(".git").exists()) {
        return providers.provider { "null" }
    }

    val execProvider = providers.exec {
        if (short) {
            commandLine("git", "rev-parse", "--short", "HEAD")
        } else {
            commandLine("git", "rev-parse", "HEAD")
        }
    }
    return execProvider.standardOutput.asText.map { it.trim() }
}
