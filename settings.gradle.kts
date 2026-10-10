/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

rootProject.name = "FlorisBoard"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Uncomment the following if testing snapshots from Maven Central
        // maven("https://central.sonatype.com/repository/maven-snapshots/")
        // Uncomment the following if testing snapshots from Maven Local
        // mavenLocal()
    }

    versionCatalogs {
        create("tools") {
            from(files("gradle/tools.versions.toml"))
        }
    }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":autocorrect-provider-fixture")
include(":benchmark")
include(":lib:android")
include(":lib:autocorrect-api")
include(":lib:autocorrect-host-core")
include(":lib:color")
include(":lib:compose")
include(":lib:kotlin")
include(":lib:snygg")
