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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
