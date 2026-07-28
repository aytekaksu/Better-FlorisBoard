import com.android.build.api.dsl.LibraryExtension
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

plugins {
    alias(libs.plugins.agp.library)
}

val projectMinSdk: String by project
val projectCompileSdk: String by project
val updatingApiSnapshots = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "updateAutocorrectApiSnapshots"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

configure<LibraryExtension> {
    namespace = "org.florisboard.autocorrect.api"
    compileSdk = projectCompileSdk.toInt()

    defaultConfig {
        minSdk = projectMinSdk.toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        create("beta") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "autocorrect.api.reportDir",
                layout.buildDirectory.dir("reports/autocorrect-api").get().asFile.absolutePath,
            )
            if (updatingApiSnapshots) {
                it.systemProperty(
                    "autocorrect.api.snapshotDir",
                    file("src/test/resources/api").absolutePath,
                )
                it.outputs.upToDateWhen { false }
            }
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}

val releaseAar = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")
val verifyAutocorrectApiAar by tasks.registering {
    group = "verification"
    description = "Verifies the release AAR contract and packaged consumer rules."
    dependsOn("bundleReleaseAar")
    inputs.file(releaseAar)
    inputs.file("consumer-rules.pro")

    doLast {
        val expectedRules = file("consumer-rules.pro").readText().trim()
        ZipFile(releaseAar.get().asFile).use { aar ->
            fun requiredEntry(name: String) = aar.getEntry(name)
                ?: error("Release AAR is missing required entry '$name'")

            val packagedRules = aar.getInputStream(requiredEntry("proguard.txt"))
                .bufferedReader()
                .use { it.readText() }
                .trim()
            check(packagedRules == expectedRules) {
                "Packaged consumer rules differ from consumer-rules.pro"
            }
            check("org.florisboard.autocorrect.api.**" in packagedRules) {
                "Release AAR no longer preserves the provider-facing API package"
            }

            val manifest = aar.getInputStream(requiredEntry("AndroidManifest.xml"))
                .bufferedReader()
                .use { it.readText() }
            check("org.florisboard.autocorrect.api" in manifest) {
                "Release AAR manifest lost the public API package"
            }
            check("android.permission.INTERNET" !in manifest) {
                "The transport-only API AAR must not request INTERNET"
            }

            val requiredClasses = setOf(
                "org/florisboard/autocorrect/api/AutocorrectPluginContract.class",
                "org/florisboard/autocorrect/api/AutocorrectPluginService.class",
                "org/florisboard/autocorrect/api/AutocorrectRequest.class",
                "org/florisboard/autocorrect/api/AutocorrectCandidate.class",
                "org/florisboard/autocorrect/api/AutocorrectPluginUi.class",
                "org/florisboard/autocorrect/api/AutocorrectUserDictionaryReader.class",
            )
            val packagedClasses = mutableSetOf<String>()
            ZipInputStream(
                aar.getInputStream(requiredEntry("classes.jar")),
            ).use { classes ->
                while (true) {
                    val entry = classes.nextEntry ?: break
                    if (!entry.isDirectory) packagedClasses += entry.name
                }
            }
            check(packagedClasses.containsAll(requiredClasses)) {
                "Release AAR is missing public classes: ${requiredClasses - packagedClasses}"
            }
        }
    }
}

val checkAutocorrectApi by tasks.registering {
    group = "verification"
    description = "Runs the fast protocol, ABI snapshot, and release AAR checks."
    dependsOn("testDebugUnitTest", verifyAutocorrectApiAar)
}

tasks.register("updateAutocorrectApiSnapshots") {
    group = "verification"
    description = "Updates checked protocol and public API snapshots for deliberate review."
    dependsOn("testDebugUnitTest")
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(checkAutocorrectApi)
}
