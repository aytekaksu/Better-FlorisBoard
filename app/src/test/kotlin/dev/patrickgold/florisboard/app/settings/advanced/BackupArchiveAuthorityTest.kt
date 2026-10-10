/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

class BackupArchiveAuthorityTest :
    FunSpec({
        test("validated carriers require validator authority") {
            val metadata = BackupArchive.Metadata(
                packageName = "dev.patrickgold.florisboard",
                versionCode = BackupArchive.MIN_SUPPORTED_VERSION_CODE,
                versionName = "test",
                timestamp = 1,
            )
            val metadataFact = storedFile(BackupArchive.METADATA_JSON_NAME)
            val payloadFact = storedFile(BackupArchive.PREFERENCES_PATH)
            val facts = listOf(metadataFact, payloadFact)
            val preflight = (
                BackupArchive.preflight(facts.asSequence(), archiveSize = 1_000) as
                    ArchiveValidation.Valid<ArchivePreflight>
                ).value
            val archive = (
                BackupArchive.inspect(
                    preflight,
                    ArchiveDescriptor(DecodedArchiveFile.Parsed(metadata)),
                ) as ArchiveValidation.Valid<ValidatedArchive>
                ).value
            val component = archive.components.single()
            val payloadPath = SafeArchivePath.parse(
                payloadFact.path,
                payloadFact.kind,
                ArchiveLimits.Default,
            )!!

            shouldThrow<IllegalStateException> {
                ValidatedArchiveEntry.create(Any(), payloadPath, payloadFact)
            }
            shouldThrow<IllegalStateException> {
                ValidatedComponent.create(Any(), component.component, component.entries)
            }
            shouldThrow<IllegalStateException> {
                ArchivePreflight.create(
                    authority = Any(),
                    components = preflight.components,
                    clipboardMediaEntries = preflight.clipboardMediaEntries,
                    metadataEntry = preflight.metadataEntry,
                    manifestEntry = preflight.manifestEntry,
                )
            }
            shouldThrow<IllegalStateException> {
                ValidatedArchive.create(
                    authority = Any(),
                    preflight = preflight,
                    metadata = archive.metadata,
                )
            }
            shouldThrow<IllegalStateException> {
                RestorePlan.create(
                    authority = Any(),
                    componentsToStage = listOf(component),
                    clipboardMediaCandidatesToStage = emptyList(),
                    declaredComponentBytes = 1,
                )
            }
        }
    })

private fun storedFile(path: String) = ArchiveEntryFact(
    path = path,
    kind = ArchiveEntryKind.FILE,
    compressedSize = 1,
    uncompressedSize = 1,
    crc32 = 0,
    compression = ArchiveCompression.STORED,
)
