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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class BackupArchiveTest :
    FunSpec({
        test("legacy archives infer every independently present component") {
            val archive = validArchive(
                metadataEntry(),
                directory("jetpref_datastore"),
                file(BackupArchive.PREFERENCES_PATH),
                directory("files"),
                directory("files/ime"),
                file("${BackupArchive.KEYBOARD_ROOT}/nested/custom.flex"),
                directory(BackupArchive.THEME_ROOT),
                directory(BackupArchive.CLIPBOARD_ROOT),
                file(BackupArchive.CLIPBOARD_TEXT_PATH),
                file(BackupArchive.CLIPBOARD_IMAGES_PATH),
                file(BackupArchive.CLIPBOARD_VIDEO_PATH),
                directory(BackupArchive.CLIPBOARD_MEDIA_ROOT),
                file("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/42"),
                file("${BackupArchive.RETIRED_SPELLING_ROOT}/legacy.flex"),
            )

            archive.source shouldBe ArchiveSource.LEGACY
            archive.components.map { it.component } shouldBe BackupComponent.entries
            archive.ignoredEntryCount shouldBe 1
            archive.warnings shouldBe setOf(ArchiveWarning.RETIRED_COMPONENT_IGNORED)
            archive.clipboardMediaEntries.map { it.archivePath } shouldBe
                listOf("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/42")
        }

        test("component recognition follows paths rather than source version") {
            val developmentClipboardArchive = validArchive(
                metadataEntry(),
                file(BackupArchive.CLIPBOARD_TEXT_PATH),
                metadata = metadata(versionCode = 64),
            )
            developmentClipboardArchive.availableComponents shouldBe setOf(BackupComponent.CLIPBOARD_TEXT)

            val futurePartialArchive = validArchive(
                metadataEntry(),
                directory(BackupArchive.KEYBOARD_ROOT),
                metadata = metadata(versionCode = Int.MAX_VALUE),
            )
            futurePartialArchive.availableComponents shouldBe setOf(BackupComponent.KEYBOARD_EXTENSIONS)
        }

        test("vendor warnings recognize only shipped package variants") {
            listOf(
                Restore.PACKAGE_NAME,
                "${Restore.PACKAGE_NAME}.debug",
                "${Restore.PACKAGE_NAME}.beta",
                "${Restore.PACKAGE_NAME}.bench",
            ).forEach { packageName ->
                Restore.isSameVendorPackage(packageName) shouldBe true
            }
            listOf(
                "",
                "org.example.keyboard",
                "${Restore.PACKAGE_NAME}evil",
                "${Restore.PACKAGE_NAME}.nightly",
                "${Restore.PACKAGE_NAME}.debug.evil",
            ).forEach { packageName ->
                Restore.isSameVendorPackage(packageName) shouldBe false
            }
        }

        test("metadata accepts bounded provenance and rejects unsafe display fields") {
            listOf(
                metadata(packageName = ""),
                metadata(packageName = "keyboard"),
                metadata(packageName = ".dev.example"),
                metadata(packageName = "dev..example"),
                metadata(packageName = "dev.example."),
                metadata(packageName = "dev.example/foreign"),
                metadata(packageName = "a.${"b".repeat(254)}"),
                metadata(versionCode = BackupArchive.MIN_SUPPORTED_VERSION_CODE - 1),
                metadata(versionName = "a".repeat(129)),
                metadata(versionName = "release\u202Eevil"),
                metadata(versionName = "release\u2028evil"),
                metadata(versionName = "release\u2029evil"),
                metadata(versionName = "release${String(Character.toChars(0xE0001))}evil"),
                metadata(versionName = "release\uD800evil"),
                metadata(timestamp = -1),
            ).forEach { invalidMetadata ->
                inspect(
                    entries = listOf(metadataEntry(), file(BackupArchive.PREFERENCES_PATH)),
                    descriptor = legacyDescriptor(invalidMetadata),
                ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.INVALID_METADATA)
            }

            validArchive(
                metadataEntry(),
                file(BackupArchive.PREFERENCES_PATH),
                metadata = metadata(versionName = ""),
            ).metadata.versionName shouldBe ""
        }

        test("an explicit empty extension root is present while an absent root is unavailable") {
            val archive = validArchive(
                metadataEntry(),
                directory(BackupArchive.KEYBOARD_ROOT),
            )

            archive.availableComponents shouldBe setOf(BackupComponent.KEYBOARD_EXTENSIONS)
            archive.component(BackupComponent.KEYBOARD_EXTENSIONS)?.entries?.single()?.kind shouldBe
                ArchiveEntryKind.DIRECTORY
            inspect(
                entries = listOf(metadataEntry(), file("safe/future-component.bin")),
                descriptor = legacyDescriptor(),
                archiveSize = TEST_ARCHIVE_SIZE,
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.NOTHING_TO_RESTORE)
        }

        test("clipboard indexes are independent and media never becomes selectable") {
            val archive = validArchive(
                metadataEntry(),
                file(BackupArchive.CLIPBOARD_TEXT_PATH),
                file("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/7"),
                file("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/007"),
            )

            archive.availableComponents shouldBe setOf(BackupComponent.CLIPBOARD_TEXT)
            archive.clipboardMediaEntries shouldBe emptyList()
            archive.ignoredEntryCount shouldBe 2
            archive.warnings shouldBe setOf(
                ArchiveWarning.UNKNOWN_ENTRIES_IGNORED,
                ArchiveWarning.UNUSED_CLIPBOARD_MEDIA_IGNORED,
            )
        }

        test("safe unknown and retired entries are ignored with aggregate warnings") {
            val archive = validArchive(
                metadataEntry(),
                file(BackupArchive.PREFERENCES_PATH),
                file("future/data.bin"),
                directory("future/empty"),
                directory(BackupArchive.RETIRED_SPELLING_ROOT),
            )

            archive.availableComponents shouldBe setOf(BackupComponent.PREFERENCES)
            archive.ignoredEntryCount shouldBe 3
            archive.warnings shouldBe setOf(
                ArchiveWarning.UNKNOWN_ENTRIES_IGNORED,
                ArchiveWarning.RETIRED_COMPONENT_IGNORED,
            )
        }

        test("a matching declared manifest is authoritative") {
            val components = listOf(
                BackupComponent.PREFERENCES,
                BackupComponent.THEME_EXTENSIONS,
            )
            val archive = validArchive(
                metadataEntry(),
                manifestEntry(),
                file(BackupArchive.PREFERENCES_PATH),
                directory(BackupArchive.THEME_ROOT),
                descriptor = declaredDescriptor(components),
            )

            archive.source shouldBe ArchiveSource.DECLARED
            archive.availableComponents shouldBe components.toSet()
        }

        test("control-file wire shapes keep metadata stable and require a manifest version") {
            val metadata = metadata()
            BackupArchive.controlFileJson.encodeToString(metadata) shouldBe
                """{"package":"dev.patrickgold.florisboard","versionCode":64,""" +
                """"versionName":"test","timestamp":1}"""
            val metadataWithFutureField =
                """{"package":"dev.patrickgold.florisboard","versionCode":64,""" +
                    """"versionName":"test","timestamp":1,"future":true}"""
            BackupArchive.controlFileJson.decodeFromString<BackupArchive.Metadata>(
                metadataWithFutureField,
            ) shouldBe metadata

            val manifest = BackupArchive.Manifest(
                formatVersion = BackupArchive.CURRENT_MANIFEST_VERSION,
                components = listOf(BackupComponent.PREFERENCES.wireId),
            )
            BackupArchive.controlFileJson.encodeToString(manifest) shouldBe
                """{"formatVersion":1,"components":["preferences"]}"""
            BackupArchive.controlFileJson.decodeFromString<BackupArchive.Manifest>(
                """{"formatVersion":1,"components":["preferences"],"future":true}""",
            ) shouldBe manifest
            shouldThrow<SerializationException> {
                BackupArchive.controlFileJson.decodeFromString<BackupArchive.Manifest>(
                    """{"components":["preferences"]}""",
                )
            }
        }

        test("unknown declared components remain forward compatible and content-free") {
            val marker = "future-secret-marker"
            val archive = validArchive(
                metadataEntry(),
                manifestEntry(),
                file(BackupArchive.PREFERENCES_PATH),
                file("future/payload.bin"),
                descriptor = ArchiveDescriptor(
                    metadata = DecodedArchiveFile.Parsed(metadata()),
                    manifest = DecodedArchiveFile.Parsed(
                        BackupArchive.Manifest(
                            formatVersion = BackupArchive.CURRENT_MANIFEST_VERSION,
                            components = listOf(BackupComponent.PREFERENCES.wireId, marker),
                        ),
                    ),
                ),
            )

            archive.availableComponents shouldBe setOf(BackupComponent.PREFERENCES)
            archive.warnings shouldBe setOf(
                ArchiveWarning.UNKNOWN_ENTRIES_IGNORED,
                ArchiveWarning.UNKNOWN_COMPONENTS_IGNORED,
            )
            archive.toString() shouldNotContain marker
        }

        test("a present malformed manifest never falls back to legacy inference") {
            val entries = listOf(
                metadataEntry(),
                manifestEntry(),
                file(BackupArchive.PREFERENCES_PATH),
            )

            inspect(
                entries,
                descriptor = ArchiveDescriptor(
                    metadata = DecodedArchiveFile.Parsed(metadata()),
                    manifest = DecodedArchiveFile.Invalid,
                ),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.INVALID_MANIFEST)
            inspect(entries, descriptor = legacyDescriptor()) shouldBe
                ArchiveValidation.Invalid(ArchiveFailure.INVALID_MANIFEST)
            inspect(
                entries = entries,
                descriptor = declaredDescriptor(emptyList()),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.MANIFEST_MISMATCH)
        }

        test("manifest format and component IDs fail closed") {
            val entries = listOf(
                metadataEntry(),
                manifestEntry(),
                file(BackupArchive.PREFERENCES_PATH),
            )
            inspect(
                entries,
                descriptor = declaredDescriptor(
                    components = listOf(BackupComponent.PREFERENCES),
                    formatVersion = 2,
                ),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.UNSUPPORTED_FORMAT)

            listOf(
                listOf("preferences", "preferences"),
                listOf("UPPERCASE"),
                listOf("../component"),
                List(65) { "future-$it" },
            ).forEach { componentIds ->
                val descriptor = ArchiveDescriptor(
                    metadata = DecodedArchiveFile.Parsed(metadata()),
                    manifest = DecodedArchiveFile.Parsed(
                        BackupArchive.Manifest(
                            formatVersion = BackupArchive.CURRENT_MANIFEST_VERSION,
                            components = componentIds,
                        ),
                    ),
                )
                inspect(entries, descriptor) shouldBe
                    ArchiveValidation.Invalid(ArchiveFailure.INVALID_MANIFEST)
            }
        }

        test("metadata keeps the historical validity boundary") {
            listOf(
                DecodedArchiveFile.Absent,
                DecodedArchiveFile.Invalid,
                DecodedArchiveFile.Parsed(metadata(packageName = "")),
                DecodedArchiveFile.Parsed(metadata(versionCode = 63)),
            ).forEach { decodedMetadata ->
                inspect(
                    entries = listOf(metadataEntry(), file(BackupArchive.PREFERENCES_PATH)),
                    descriptor = ArchiveDescriptor(metadata = decodedMetadata),
                ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.INVALID_METADATA)
            }

            validArchive(
                metadataEntry(),
                file(BackupArchive.PREFERENCES_PATH),
                metadata = metadata(versionName = ""),
            ).metadata shouldBe metadata(versionName = "")
        }

        test("unsafe paths reject the whole archive") {
            val hostileEntries = listOf(
                file(""),
                file("/absolute"),
                file("../escape"),
                file("a/./b"),
                file("a//b"),
                file("""a\b"""),
                file("C:/drive"),
                file("nul\u0000name"),
                file("file/"),
                directoryFact("directory"),
                file("${"a".repeat(256)}/leaf"),
            )

            hostileEntries.forEach { hostile ->
                inspect(listOf(metadataEntry(), file(BackupArchive.PREFERENCES_PATH), hostile)) shouldBe
                    ArchiveValidation.Invalid(ArchiveFailure.UNSAFE_ENTRY)
            }

            val limits = testLimits(maxPathBytes = 8)
            inspect(
                entries = listOf(metadataEntry(), file(BackupArchive.PREFERENCES_PATH)),
                limits = limits,
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.UNSAFE_ENTRY)
        }

        test("duplicate and file-directory conflicts are rejected independent of order") {
            val duplicate = listOf(
                metadataEntry(),
                file(BackupArchive.PREFERENCES_PATH),
                file(BackupArchive.PREFERENCES_PATH),
            )
            inspect(duplicate) shouldBe ArchiveValidation.Invalid(ArchiveFailure.DUPLICATE_ENTRY)
            inspect(duplicate.reversed()) shouldBe ArchiveValidation.Invalid(ArchiveFailure.DUPLICATE_ENTRY)

            val kindCollision = listOf(metadataEntry(), file("conflict"), directory("conflict"))
            inspect(kindCollision) shouldBe ArchiveValidation.Invalid(ArchiveFailure.CONFLICTING_ENTRY)

            val parentCollision = listOf(
                metadataEntry(),
                file("jetpref_datastore"),
                file(BackupArchive.PREFERENCES_PATH),
            )
            inspect(parentCollision) shouldBe ArchiveValidation.Invalid(ArchiveFailure.CONFLICTING_ENTRY)
            inspect(parentCollision.reversed()) shouldBe ArchiveValidation.Invalid(ArchiveFailure.CONFLICTING_ENTRY)
        }

        test("unsupported and inconsistent entry headers are rejected") {
            val invalidFacts = listOf(
                file("bad", kind = ArchiveEntryKind.SYMBOLIC_LINK),
                file("bad", encrypted = true),
                file("bad", compression = ArchiveCompression.UNSUPPORTED),
                file("bad", compressedSize = -1),
                file("bad", uncompressedSize = -1),
                file("bad", crc32 = -1),
                file("bad", crc32 = 0x1_0000_0000L),
                file("bad", compressedSize = 0, uncompressedSize = 1),
                file("bad", compressedSize = 1, uncompressedSize = 2),
                directory("bad", uncompressedSize = 1),
                ArchiveEntryFact(
                    path = "bad/",
                    kind = ArchiveEntryKind.DIRECTORY,
                    compressedSize = 1,
                    uncompressedSize = 0,
                    crc32 = 0,
                    compression = ArchiveCompression.STORED,
                ),
            )

            invalidFacts.forEach { invalid ->
                val expected = if (
                    invalid.kind == ArchiveEntryKind.SYMBOLIC_LINK ||
                    invalid.encrypted ||
                    invalid.compression == ArchiveCompression.UNSUPPORTED
                ) {
                    ArchiveFailure.UNSUPPORTED_ENTRY
                } else {
                    ArchiveFailure.INVALID_ENTRY
                }
                inspect(listOf(metadataEntry(), file(BackupArchive.PREFERENCES_PATH), invalid)) shouldBe
                    ArchiveValidation.Invalid(expected)
            }
        }

        test("entry count and declared sizes use injectable overflow-safe limits") {
            val baseEntries = listOf(metadataEntry(size = 1), file(BackupArchive.PREFERENCES_PATH, size = 1))

            inspect(
                entries = baseEntries,
                archiveSize = 11,
                limits = testLimits(maxArchiveBytes = 10),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.ARCHIVE_TOO_LARGE)
            inspect(
                entries = baseEntries,
                limits = testLimits(maxEntries = 1),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.TOO_MANY_ENTRIES)
            inspect(
                entries = listOf(metadataEntry(size = 2), file(BackupArchive.PREFERENCES_PATH)),
                limits = testLimits(maxMetadataBytes = 1),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.ENTRY_TOO_LARGE)
            inspect(
                entries = listOf(metadataEntry(), file(BackupArchive.PREFERENCES_PATH, size = 6)),
                limits = testLimits(maxPreferencesOrJsonBytes = 5),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.ENTRY_TOO_LARGE)
            inspect(
                entries = listOf(metadataEntry(size = 6), file(BackupArchive.PREFERENCES_PATH, size = 6)),
                limits = testLimits(maxExpandedBytes = 10),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.ARCHIVE_TOO_LARGE)
            inspect(
                entries = baseEntries,
                archiveSize = 1,
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.INVALID_ARCHIVE_SIZE)

            val competingLimits = listOf(
                metadataEntry(size = 1),
                file(BackupArchive.PREFERENCES_PATH, size = 6),
                file("future/oversized", size = 11),
            )
            val smallLimits = testLimits(maxExpandedBytes = 10, maxEntryBytes = 10)
            inspect(competingLimits, limits = smallLimits) shouldBe
                ArchiveValidation.Invalid(ArchiveFailure.ENTRY_TOO_LARGE)
            inspect(competingLimits.reversed(), limits = smallLimits) shouldBe
                ArchiveValidation.Invalid(ArchiveFailure.ENTRY_TOO_LARGE)

            val overflowFacts = listOf(
                file(
                    BackupArchive.METADATA_JSON_NAME,
                    compressedSize = Long.MAX_VALUE,
                    uncompressedSize = 1,
                    compression = ArchiveCompression.DEFLATED,
                ),
                file(BackupArchive.PREFERENCES_PATH),
            )
            inspect(
                entries = overflowFacts,
                archiveSize = Long.MAX_VALUE,
                limits = testLimits(
                    maxArchiveBytes = Long.MAX_VALUE,
                    maxExpandedBytes = Long.MAX_VALUE,
                    maxEntryBytes = Long.MAX_VALUE,
                    maxMetadataBytes = Long.MAX_VALUE,
                    maxPreferencesOrJsonBytes = Long.MAX_VALUE,
                ),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.ARCHIVE_TOO_LARGE)
        }

        test("preflight checks archive bytes first and caps lazy entry enumeration") {
            var yielded = 0
            val facts = sequence {
                while (true) {
                    yielded++
                    yield(file("future/$yielded"))
                }
            }
            BackupArchive.preflight(
                entries = facts,
                archiveSize = 1,
                limits = testLimits(maxEntries = 2),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.TOO_MANY_ENTRIES)
            yielded shouldBe 3

            yielded = 0
            BackupArchive.preflight(
                entries = sequence {
                    yielded++
                    yield(metadataEntry())
                },
                archiveSize = 11,
                limits = testLimits(maxArchiveBytes = 10),
            ) shouldBe ArchiveValidation.Invalid(ArchiveFailure.ARCHIVE_TOO_LARGE)
            yielded shouldBe 0
        }

        test("highly compressible entries remain compatible within absolute budgets") {
            val result = BackupArchive.preflight(
                entries = sequenceOf(
                    metadataEntry(size = 1),
                    file(
                        BackupArchive.PREFERENCES_PATH,
                        compressedSize = 1,
                        uncompressedSize = 1_000,
                        compression = ArchiveCompression.DEFLATED,
                    ),
                ),
                archiveSize = TEST_ARCHIVE_SIZE,
                limits = testLimits(
                    maxExpandedBytes = 1_001,
                    maxEntryBytes = 1_000,
                    maxPreferencesOrJsonBytes = 1_000,
                ),
            )

            (result as ArchiveValidation.Valid).value.availableComponents shouldBe
                setOf(BackupComponent.PREFERENCES)
        }

        test("archive inspection is deterministic across entry permutations") {
            val entries = listOf(
                metadataEntry(),
                file("${BackupArchive.THEME_ROOT}/z.flex"),
                file("${BackupArchive.THEME_ROOT}/a.flex"),
                file(BackupArchive.PREFERENCES_PATH),
                file("unknown/value"),
                file("${BackupArchive.RETIRED_SPELLING_ROOT}/legacy.flex"),
            )

            val first = validArchive(*entries.toTypedArray())
            val second = validArchive(*entries.reversed().toTypedArray())
            first.source shouldBe second.source
            first.components.map { it.component to it.entries.map { entry -> entry.archivePath } } shouldBe
                second.components.map { it.component to it.entries.map { entry -> entry.archivePath } }
            first.ignoredEntryCount shouldBe second.ignoredEntryCount
            first.warnings shouldBe second.warnings
            first.warnings.toList() shouldBe listOf(
                ArchiveWarning.RETIRED_COMPONENT_IGNORED,
                ArchiveWarning.UNKNOWN_ENTRIES_IGNORED,
            )
        }

        test("merge plans stage selected components without reset operations") {
            val archive = validArchive(
                metadataEntry(),
                file(BackupArchive.PREFERENCES_PATH, size = 3),
                file("${BackupArchive.KEYBOARD_ROOT}/board.flex", size = 5),
                file(BackupArchive.CLIPBOARD_TEXT_PATH, size = 7),
            )
            val result = RestorePlanner.create(
                archive,
                RestoreRequest(
                    mode = RestoreMode.MERGE,
                    selectedComponents = linkedSetOf(
                        BackupComponent.PREFERENCES,
                        BackupComponent.CLIPBOARD_TEXT,
                        BackupComponent.KEYBOARD_EXTENSIONS,
                    ),
                ),
            ) as RestorePlanResult.Valid

            result.plan.componentsToStage.map { it.component } shouldBe listOf(
                BackupComponent.PREFERENCES,
                BackupComponent.KEYBOARD_EXTENSIONS,
                BackupComponent.CLIPBOARD_TEXT,
            )
            result.plan.resetComponentsOnCommit shouldBe emptyList()
            result.plan.declaredComponentBytes shouldBe 15
        }

        test("replace plans reset exactly the selected present components") {
            val archive = validArchive(
                metadataEntry(),
                directory(BackupArchive.KEYBOARD_ROOT),
                file("${BackupArchive.THEME_ROOT}/theme.flex"),
                file(BackupArchive.PREFERENCES_PATH),
            )
            val result = RestorePlanner.create(
                archive,
                RestoreRequest(
                    mode = RestoreMode.REPLACE_SELECTED,
                    selectedComponents = setOf(
                        BackupComponent.PREFERENCES,
                        BackupComponent.KEYBOARD_EXTENSIONS,
                    ),
                ),
            ) as RestorePlanResult.Valid

            result.plan.componentsToStage.map { it.component } shouldBe listOf(
                BackupComponent.PREFERENCES,
                BackupComponent.KEYBOARD_EXTENSIONS,
            )
            result.plan.resetComponentsOnCommit shouldBe listOf(
                BackupComponent.PREFERENCES,
                BackupComponent.KEYBOARD_EXTENSIONS,
            )
        }

        test("planner rejects empty and unavailable selections before producing actions") {
            val archive = validArchive(metadataEntry(), file(BackupArchive.PREFERENCES_PATH))

            RestorePlanner.create(
                archive,
                RestoreRequest(RestoreMode.MERGE, emptySet()),
            ) shouldBe RestorePlanResult.Invalid(RestorePlanFailure.EMPTY_SELECTION)
            RestorePlanner.create(
                archive,
                RestoreRequest(RestoreMode.REPLACE_SELECTED, setOf(BackupComponent.THEME_EXTENSIONS)),
            ) shouldBe RestorePlanResult.Invalid(RestorePlanFailure.COMPONENT_UNAVAILABLE)
        }

        test("image and video plans expose shared media candidates once") {
            val archive = validArchive(
                metadataEntry(),
                file(BackupArchive.CLIPBOARD_IMAGES_PATH, size = 2),
                file(BackupArchive.CLIPBOARD_VIDEO_PATH, size = 3),
                file("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/2", size = 5),
                file("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/1", size = 7),
            )
            val result = RestorePlanner.create(
                archive,
                RestoreRequest(
                    RestoreMode.MERGE,
                    linkedSetOf(BackupComponent.CLIPBOARD_VIDEOS, BackupComponent.CLIPBOARD_IMAGES),
                ),
            ) as RestorePlanResult.Valid

            result.plan.clipboardMediaCandidatesToStage.map { it.archivePath } shouldBe listOf(
                "${BackupArchive.CLIPBOARD_MEDIA_ROOT}/1",
                "${BackupArchive.CLIPBOARD_MEDIA_ROOT}/2",
            )
            result.plan.clipboardMediaPolicy shouldBe ClipboardMediaPolicy.COPY_SELECTED_REFERENCES
            result.plan.declaredComponentBytes shouldBe 5
        }

        test("plan output is deterministic across selection order") {
            val archive = validArchive(
                metadataEntry(),
                file(BackupArchive.PREFERENCES_PATH),
                file("${BackupArchive.KEYBOARD_ROOT}/board.flex"),
            )
            val first = RestorePlanner.create(
                archive,
                RestoreRequest(
                    RestoreMode.REPLACE_SELECTED,
                    linkedSetOf(BackupComponent.PREFERENCES, BackupComponent.KEYBOARD_EXTENSIONS),
                ),
            )
            val second = RestorePlanner.create(
                archive,
                RestoreRequest(
                    RestoreMode.REPLACE_SELECTED,
                    linkedSetOf(BackupComponent.KEYBOARD_EXTENSIONS, BackupComponent.PREFERENCES),
                ),
            )

            (first as RestorePlanResult.Valid).plan.toString() shouldBe
                (second as RestorePlanResult.Valid).plan.toString()
        }

        test("every component subset preserves the exact merge and replace scope") {
            val archive = validArchive(
                metadataEntry(),
                directory(BackupArchive.KEYBOARD_ROOT),
                directory(BackupArchive.THEME_ROOT),
                file(BackupArchive.CLIPBOARD_TEXT_PATH),
                file(BackupArchive.CLIPBOARD_IMAGES_PATH),
                file(BackupArchive.CLIPBOARD_VIDEO_PATH),
                file("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/1"),
                file(BackupArchive.PREFERENCES_PATH),
            )

            for (mask in 1 until (1 shl BackupComponent.entries.size)) {
                val selected = BackupComponent.entries
                    .filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                    .toSet()
                RestoreMode.entries.forEach { mode ->
                    val result = RestorePlanner.create(
                        archive,
                        RestoreRequest(mode, selected),
                    ) as RestorePlanResult.Valid
                    result.plan.componentsToStage.mapTo(linkedSetOf()) { it.component } shouldBe selected
                    result.plan.resetComponentsOnCommit.toSet() shouldBe
                        if (mode == RestoreMode.REPLACE_SELECTED) selected else emptySet()
                    val needsMedia = selected.any {
                        it == BackupComponent.CLIPBOARD_IMAGES ||
                            it == BackupComponent.CLIPBOARD_VIDEOS
                    }
                    result.plan.clipboardMediaPolicy shouldBe when {
                        !needsMedia -> ClipboardMediaPolicy.NONE
                        mode == RestoreMode.MERGE -> ClipboardMediaPolicy.COPY_SELECTED_REFERENCES
                        else -> ClipboardMediaPolicy.RECONCILE_SELECTED_REFERENCES
                    }
                }
            }
        }

        test("partial clipboard replacement requires reference-aware media reconciliation") {
            val archive = validArchive(
                metadataEntry(),
                file(BackupArchive.CLIPBOARD_IMAGES_PATH),
                file(BackupArchive.CLIPBOARD_VIDEO_PATH),
                file("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/1"),
            )
            val plan = (
                RestorePlanner.create(
                    archive,
                    RestoreRequest(
                        RestoreMode.REPLACE_SELECTED,
                        setOf(BackupComponent.CLIPBOARD_IMAGES),
                    ),
                ) as RestorePlanResult.Valid
                ).plan

            plan.resetComponentsOnCommit shouldBe listOf(BackupComponent.CLIPBOARD_IMAGES)
            plan.componentsToStage.map { it.component } shouldBe listOf(BackupComponent.CLIPBOARD_IMAGES)
            plan.clipboardMediaPolicy shouldBe ClipboardMediaPolicy.RECONCILE_SELECTED_REFERENCES
        }

        test("validated snapshots and plans do not expose mutable collections") {
            val archive = validArchive(metadataEntry(), file(BackupArchive.PREFERENCES_PATH))
            shouldThrow<UnsupportedOperationException> {
                (archive.components as MutableList<ValidatedComponent>).clear()
            }
            shouldThrow<UnsupportedOperationException> {
                (archive.availableComponents as MutableSet<BackupComponent>).clear()
            }

            val selected = mutableSetOf(BackupComponent.PREFERENCES)
            val request = RestoreRequest(RestoreMode.REPLACE_SELECTED, selected)
            selected.clear()
            shouldThrow<UnsupportedOperationException> {
                (request.selectedComponents as MutableSet<BackupComponent>).clear()
            }
            val plan = (RestorePlanner.create(archive, request) as RestorePlanResult.Valid).plan
            plan.resetComponentsOnCommit shouldBe listOf(BackupComponent.PREFERENCES)
            shouldThrow<UnsupportedOperationException> {
                (plan.resetComponentsOnCommit as MutableList<BackupComponent>).clear()
            }
        }

        test("failures and entry descriptions never retain hostile paths or IDs") {
            val marker = "private_marker"
            val hostileMetadata = metadata(packageName = "dev.$marker", versionName = marker)
            hostileMetadata.toString() shouldNotContain marker
            val hostileManifest = BackupArchive.Manifest(
                formatVersion = BackupArchive.CURRENT_MANIFEST_VERSION,
                components = listOf(marker),
            )
            hostileManifest.toString() shouldNotContain marker
            ArchiveDescriptor(
                metadata = DecodedArchiveFile.Parsed(hostileMetadata),
                manifest = DecodedArchiveFile.Parsed(hostileManifest),
            ).toString() shouldNotContain marker

            val unsafeFact = file("../$marker")
            unsafeFact.toString() shouldNotContain marker
            inspect(listOf(metadataEntry(), file(BackupArchive.PREFERENCES_PATH), unsafeFact))
                .toString() shouldNotContain marker

            val valid = validArchive(
                metadataEntry(),
                file("${BackupArchive.KEYBOARD_ROOT}/$marker.flex"),
                metadata = hostileMetadata,
            )
            valid.toString() shouldNotContain marker
            valid.components.single().entries.single().toString() shouldNotContain marker
            RestorePlanner.create(
                valid,
                RestoreRequest(RestoreMode.MERGE, setOf(BackupComponent.KEYBOARD_EXTENSIONS)),
            ).toString() shouldNotContain marker
        }
    })

private const val TEST_ARCHIVE_SIZE = 1_000_000L

private fun metadata(
    packageName: String = "dev.patrickgold.florisboard",
    versionCode: Int = BackupArchive.MIN_SUPPORTED_VERSION_CODE,
    versionName: String = "test",
    timestamp: Long = 1,
): BackupArchive.Metadata = BackupArchive.Metadata(packageName, versionCode, versionName, timestamp)

private fun legacyDescriptor(metadata: BackupArchive.Metadata = metadata()): ArchiveDescriptor =
    ArchiveDescriptor(DecodedArchiveFile.Parsed(metadata))

private fun declaredDescriptor(
    components: List<BackupComponent>,
    formatVersion: Int = BackupArchive.CURRENT_MANIFEST_VERSION,
): ArchiveDescriptor = ArchiveDescriptor(
    metadata = DecodedArchiveFile.Parsed(metadata()),
    manifest = DecodedArchiveFile.Parsed(
        BackupArchive.Manifest(
            formatVersion = formatVersion,
            components = components.map { it.wireId },
        ),
    ),
)

private fun inspect(
    entries: List<ArchiveEntryFact>,
    descriptor: ArchiveDescriptor = legacyDescriptor(),
    archiveSize: Long = TEST_ARCHIVE_SIZE,
    limits: ArchiveLimits = ArchiveLimits.Default,
): ArchiveValidation<ValidatedArchive> =
    when (val preflight = BackupArchive.preflight(entries.asSequence(), archiveSize, limits)) {
        is ArchiveValidation.Invalid -> preflight
        is ArchiveValidation.Valid -> BackupArchive.inspect(preflight.value, descriptor)
    }

private fun validArchive(
    vararg entries: ArchiveEntryFact,
    metadata: BackupArchive.Metadata = metadata(),
    descriptor: ArchiveDescriptor = legacyDescriptor(metadata),
): ValidatedArchive = (inspect(entries.toList(), descriptor) as ArchiveValidation.Valid<ValidatedArchive>).value

private fun metadataEntry(size: Long = 100): ArchiveEntryFact = file(BackupArchive.METADATA_JSON_NAME, size = size)

private fun manifestEntry(size: Long = 100): ArchiveEntryFact = file(BackupArchive.MANIFEST_JSON_NAME, size = size)

private fun file(
    path: String,
    size: Long = 1,
    kind: ArchiveEntryKind = ArchiveEntryKind.FILE,
    compressedSize: Long = size,
    uncompressedSize: Long = size,
    crc32: Long = 0,
    compression: ArchiveCompression = ArchiveCompression.STORED,
    encrypted: Boolean = false,
): ArchiveEntryFact = ArchiveEntryFact(
    path = path,
    kind = kind,
    compressedSize = compressedSize,
    uncompressedSize = uncompressedSize,
    crc32 = crc32,
    compression = compression,
    encrypted = encrypted,
)

private fun directory(path: String, uncompressedSize: Long = 0): ArchiveEntryFact =
    directoryFact("$path/", uncompressedSize)

private fun directoryFact(path: String, uncompressedSize: Long = 0): ArchiveEntryFact = ArchiveEntryFact(
    path = path,
    kind = ArchiveEntryKind.DIRECTORY,
    compressedSize = 2,
    uncompressedSize = uncompressedSize,
    crc32 = 0,
    compression = ArchiveCompression.DEFLATED,
)

private fun testLimits(
    maxArchiveBytes: Long = 2_000_000,
    maxEntries: Int = 10,
    maxExpandedBytes: Long = 1_000,
    maxEntryBytes: Long = 1_000,
    maxMetadataBytes: Long = 1_000,
    maxManifestBytes: Long = 1_000,
    maxPreferencesOrJsonBytes: Long = 1_000,
    maxPathBytes: Int = 1_000,
    maxPathSegmentBytes: Int = 255,
): ArchiveLimits = ArchiveLimits(
    maxArchiveBytes = maxArchiveBytes,
    maxEntries = maxEntries,
    maxExpandedBytes = maxExpandedBytes,
    maxEntryBytes = maxEntryBytes,
    maxMetadataBytes = maxMetadataBytes,
    maxManifestBytes = maxManifestBytes,
    maxPreferencesOrJsonBytes = maxPreferencesOrJsonBytes,
    maxPathBytes = maxPathBytes,
    maxPathSegmentBytes = maxPathSegmentBytes,
)
