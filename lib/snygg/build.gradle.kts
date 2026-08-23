import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
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

abstract class SchemaOutputArgumentProvider : CommandLineArgumentProvider {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun asArguments(): Iterable<String> {
        return listOf(outputFile.get().asFile.absolutePath)
    }
}

@DisableCachingByDefault(because = "Verification has no outputs.")
abstract class VerifySnyggJsonSchemaTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedSchema: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val trackedSchema: RegularFileProperty

    @TaskAction
    fun verify() {
        val generated = generatedSchema.get().asFile
        val tracked = trackedSchema.get().asFile
        if (!tracked.isFile || !tracked.readBytes().contentEquals(generated.readBytes())) {
            throw GradleException(
                "Snygg JSON schema is out of date. Run :lib:snygg:updateSnyggJsonSchema.",
            )
        }
    }
}

@DisableCachingByDefault(because = "Updates a source-controlled file.")
abstract class UpdateSnyggJsonSchemaTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedSchema: RegularFileProperty

    @get:OutputFile
    abstract val trackedSchema: RegularFileProperty

    @TaskAction
    fun update() {
        val tracked = trackedSchema.get().asFile
        tracked.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "Unable to create the tracked schema directory."
            }
        }
        generatedSchema.get().asFile.copyTo(tracked, overwrite = true)
    }
}

plugins {
    alias(libs.plugins.agp.library)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val projectMinSdk: String by project
val projectCompileSdk: String by project

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.set(listOf(
            "-Xconsistent-data-class-copy-visibility",
        ))
    }
}

configure<LibraryExtension> {
    namespace = "org.florisboard.lib.snygg"
    compileSdk = projectCompileSdk.toInt()

    defaultConfig {
        minSdk = projectMinSdk.toInt()
    }

    buildFeatures {
        compose = true
    }
    buildTypes {
        create("beta")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<Test> {
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
    useJUnitPlatform()
}

dependencies {
    implementation(projects.lib.color)
    implementation(projects.lib.kotlin)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
}

val generatedSnyggSchema = layout.buildDirectory.file("generated/snygg/stylesheet.schema.json")
val trackedSnyggSchema = layout.projectDirectory.file("schemas/stylesheet.schema.json")
val schemaOutputArgumentProvider = objects.newInstance<SchemaOutputArgumentProvider>().apply {
    outputFile.set(generatedSnyggSchema)
}

val generateSnyggJsonSchema by tasks.registering(JavaExec::class) {
    val compileSchemaGenerator = tasks.named<KotlinCompile>("compileDebugUnitTestKotlin")
    val compileSnygg = tasks.named<KotlinCompile>("compileDebugKotlin")
    val debugRuntime = configurations.named("debugRuntimeClasspath")
    val debugRuntimeArtifactView = debugRuntime.get().incoming.artifactView {
        attributes { attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes") }
    }
    group = "build"
    description = "Generates the Snygg JSON schema under the build directory."
    dependsOn(compileSchemaGenerator)
    mainClass.set("org.florisboard.lib.snygg.SnyggJsonSchemaGenerator")
    classpath = files(
        compileSchemaGenerator.map { it.destinationDirectory },
        compileSnygg.map { it.destinationDirectory },
        debugRuntimeArtifactView.files,
    )
    argumentProviders.add(schemaOutputArgumentProvider)
}

val verifySnyggJsonSchema by tasks.registering(VerifySnyggJsonSchemaTask::class) {
    group = "verification"
    description = "Checks that the tracked Snygg JSON schema is current."
    dependsOn(generateSnyggJsonSchema)
    generatedSchema.set(generatedSnyggSchema)
    trackedSchema.set(trackedSnyggSchema)
}

val updateSnyggJsonSchema by tasks.registering(UpdateSnyggJsonSchemaTask::class) {
    group = "build setup"
    description = "Updates the tracked Snygg JSON schema."
    dependsOn(generateSnyggJsonSchema)
    generatedSchema.set(generatedSnyggSchema)
    trackedSchema.set(trackedSnyggSchema)
}

tasks.named("check") {
    dependsOn(verifySnyggJsonSchema)
}
