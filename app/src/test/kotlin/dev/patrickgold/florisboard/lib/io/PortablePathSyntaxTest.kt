/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PortablePathSyntaxTest :
    FunSpec({
        test("shared syntax rejects ambiguous and platform-specific paths") {
            listOf("", "/root", "C:/drive", "a\\b", "a\u0000b", ".", "..", "a/./b", "a/../b", "a//b", "a/")
                .forEach { path ->
                    parsePortablePathSegments(path, 128, 64) shouldBe null
                }
            parsePortablePathSegments("a/b", 128, 64) shouldBe listOf("a", "b")
        }

        test("directory marker is checked before stripping one slash") {
            parsePortablePathSegments("a/b/", 128, 64, directory = true) shouldBe listOf("a", "b")
            parsePortablePathSegments("a/b", 128, 64, directory = true) shouldBe null
            parsePortablePathSegments("a/b/", 128, 64) shouldBe null
            parsePortablePathSegments("/", 128, 64, directory = true) shouldBe null
        }

        test("whole-path and segment bounds count both UTF-16 characters and UTF-8 bytes") {
            parsePortablePathSegments("é/", 3, 2, directory = true) shouldBe listOf("é")
            parsePortablePathSegments("é/", 2, 2, directory = true) shouldBe null
            parsePortablePathSegments("é", 2, 1) shouldBe null
            parsePortablePathSegments("ab", 1, 2) shouldBe null
            // Java's UTF-8 encoder replaces an unpaired surrogate with one byte.
            parsePortablePathSegments("\uD800", 1, 1) shouldBe listOf("\uD800")
        }

        test("depth and blank-segment rules remain caller-specific") {
            parsePortablePathSegments("a/b/c", 128, 64, maxDepth = 2) shouldBe null
            parsePortablePathSegments("a/b/c", 128, 64) shouldBe listOf("a", "b", "c")
            parsePortablePathSegments("a/ /b", 128, 64) shouldBe listOf("a", " ", "b")
            parsePortablePathSegments("a/ /b", 128, 64, rejectBlankSegments = true) shouldBe null
        }
    })
