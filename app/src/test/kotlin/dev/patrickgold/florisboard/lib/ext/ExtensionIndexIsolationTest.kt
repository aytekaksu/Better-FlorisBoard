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

package dev.patrickgold.florisboard.lib.ext

import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.serialization.encodeToString

class ExtensionIndexIsolationTest : FunSpec({
    val serializer = ThemeExtension.serializer()
    val missingId = "local.themes.missing"
    val healthyId = "local.themes.healthy"

    test("a failed fingerprint does not hide a healthy sibling") {
        var failedArchive: ThemeExtension? = null
        val indexed = listOf(missingId, healthyId).mapNotNull { id ->
            indexInstalledExtension(
                fileName = ExtensionDefaults.createFlexName(id),
                serializer = serializer,
                bundledIds = emptySet(),
                readManifest = { manifest(id) },
                fingerprint = { ext ->
                    if (id == missingId) {
                        failedArchive = ext
                        throw IOException("archive disappeared")
                    }
                    fingerprint(ext)
                },
            )
        }

        indexed.map { it.meta.id } shouldBe listOf(healthyId)
        failedArchive?.sourceArchiveFingerprint shouldBe null
        indexed.single().sourceArchiveFingerprint shouldBe fingerprint(indexed.single())
    }

    test("the next refresh can accept an archive after a transient failure") {
        val fileName = ExtensionDefaults.createFlexName(missingId)
        var fail = true
        fun index() = indexInstalledExtension(
            fileName = fileName,
            serializer = serializer,
            bundledIds = emptySet(),
            readManifest = { manifest(missingId) },
            fingerprint = { ext ->
                if (fail) throw IOException("archive disappeared")
                fingerprint(ext)
            },
        )

        index() shouldBe null
        fail = false
        val recovered = requireNotNull(index())
        recovered.meta.id shouldBe missingId
        recovered.sourceArchiveFingerprint shouldBe fingerprint(recovered)
    }

    test("invalid, noncanonical, and bundled packages never publish") {
        val canonicalName = ExtensionDefaults.createFlexName(healthyId)
        var fingerprintCalls = 0
        fun index(fileName: String, bundledIds: Set<String> = emptySet(), content: String = manifest(healthyId)) =
            indexInstalledExtension(
                fileName = fileName,
                serializer = serializer,
                bundledIds = bundledIds,
                readManifest = { content },
                fingerprint = { ext -> fingerprintCalls++; fingerprint(ext) },
            )

        index("unrelated.txt") shouldBe null
        index("renamed.flex") shouldBe null
        index(canonicalName, setOf(healthyId)) shouldBe null
        index(canonicalName, content = "not JSON") shouldBe null
        fingerprintCalls shouldBe 0
    }

    test("cancellation and interruption are not converted to invalid archives") {
        val fileName = ExtensionDefaults.createFlexName(healthyId)
        shouldThrow<CancellationException> {
            indexInstalledExtension(
                fileName, serializer, emptySet(),
                readManifest = { throw CancellationException("cancelled") },
                fingerprint = ::fingerprint,
            )
        }
        shouldThrow<InterruptedException> {
            indexInstalledExtension(
                fileName, serializer, emptySet(),
                readManifest = { manifest(healthyId) },
                fingerprint = { throw InterruptedException("interrupted") },
            )
        }
    }
})

private fun manifest(id: String): String = ExtensionJsonConfig.encodeToString(
    ThemeExtension.serializer(),
    ThemeExtension(
        meta = ExtensionMeta(
            id = id,
            version = "1.0.0",
            title = "Index fixture",
            maintainers = listOf(ExtensionMaintainer("Test")),
            license = "apache-2.0",
        ),
        themes = emptyList(),
    ),
)

private fun fingerprint(extension: ThemeExtension) = InstalledExtensionArchiveFingerprint(
    extensionId = extension.meta.id,
    serialType = extension.serialType(),
    sha256 = "synthetic",
)
