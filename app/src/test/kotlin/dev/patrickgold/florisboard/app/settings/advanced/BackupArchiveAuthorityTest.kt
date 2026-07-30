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
                    ignoredEntryCount = preflight.ignoredEntryCount,
                    warnings = preflight.warnings,
                    metadataEntry = preflight.metadataEntry,
                    manifestEntry = preflight.manifestEntry,
                )
            }
            shouldThrow<IllegalStateException> {
                ValidatedArchive.create(
                    authority = Any(),
                    preflight = preflight,
                    metadata = archive.metadata,
                    source = archive.source,
                    extraWarnings = emptySet(),
                )
            }
            shouldThrow<IllegalStateException> {
                RestorePlan.create(
                    authority = Any(),
                    mode = RestoreMode.MERGE,
                    resetComponentsOnCommit = emptyList(),
                    componentsToStage = listOf(component),
                    clipboardMediaCandidatesToStage = emptyList(),
                    clipboardMediaPolicy = ClipboardMediaPolicy.NONE,
                    declaredPayloadBytes = 1,
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
