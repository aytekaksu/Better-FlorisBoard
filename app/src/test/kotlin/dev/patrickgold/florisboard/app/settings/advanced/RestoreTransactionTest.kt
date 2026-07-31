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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class RestoreTransactionTest :
    FunSpec({
        val root = Files.createTempDirectory("restore-transaction-test")

        afterSpec {
            root.toFile().deleteRecursively()
        }

        test("a late final-commit failure restores preferences and every live tree") {
            val fixture = root.fixture("late-failure")
            val keyboardSource = fixture.source("keyboard").apply {
                write("replaced.flex", "new replacement")
                write("added.flex", "new addition")
            }
            val themeSource = fixture.source("theme").apply {
                write("incoming.flex", "new theme")
            }
            val liveKeyboard = fixture.live("keyboard").apply {
                write("kept.flex", "old kept")
                write("replaced.flex", "old replacement")
            }
            val liveTheme = fixture.live("theme").apply {
                write("old.flex", "old theme")
            }
            val preferenceSource = fixture.file("source/preferences.jetpref", "new preferences")
            var preferences = "old preferences"

            shouldThrow<LateCommitFailure> {
                RestoreTransaction.execute(
                    scratchParent = fixture.scratchParent,
                    eraseExisting = false,
                    preferences = preferencePlan(preferenceSource, { preferences }) {
                        preferences = it
                    },
                    directories = listOf(
                        RestoreDirectoryTransaction(keyboardSource, liveKeyboard),
                        RestoreDirectoryTransaction(themeSource, liveTheme),
                    ),
                    finalCommit = {
                        preferences shouldBe "new preferences"
                        liveKeyboard.contents() shouldBe mapOf(
                            "added.flex" to "new addition",
                            "kept.flex" to "old kept",
                            "replaced.flex" to "new replacement",
                        )
                        liveTheme.contents() shouldBe mapOf(
                            "incoming.flex" to "new theme",
                            "old.flex" to "old theme",
                        )
                        throw LateCommitFailure()
                    },
                )
            }

            preferences shouldBe "old preferences"
            liveKeyboard.contents() shouldBe mapOf(
                "kept.flex" to "old kept",
                "replaced.flex" to "old replacement",
            )
            liveTheme.contents() shouldBe mapOf("old.flex" to "old theme")
            fixture.hasScratchData() shouldBe false
        }

        test("a successful erase keeps only staged data and runs final commit last") {
            val fixture = root.fixture("erase-success")
            val source = fixture.source("keyboard").apply {
                write("new.flex", "new keyboard")
            }
            val live = fixture.live("keyboard").apply {
                write("stale.flex", "stale keyboard")
                write("nested/old.flex", "old nested keyboard")
            }
            val preferenceSource = fixture.file("source/preferences.jetpref", "new preferences")
            var preferences = "old preferences"
            var finalCommitCalled = false

            RestoreTransaction.execute(
                scratchParent = fixture.scratchParent,
                eraseExisting = true,
                preferences = preferencePlan(preferenceSource, { preferences }) {
                    preferences = it
                },
                directories = listOf(RestoreDirectoryTransaction(source, live)),
                finalCommit = {
                    preferences shouldBe "new preferences"
                    live.contents() shouldBe mapOf("new.flex" to "new keyboard")
                    finalCommitCalled = true
                },
            )

            finalCommitCalled shouldBe true
            preferences shouldBe "new preferences"
            live.contents() shouldBe mapOf("new.flex" to "new keyboard")
            fixture.hasScratchData() shouldBe false
        }

        test("a preference apply which mutates and then fails restores its snapshot") {
            val fixture = root.fixture("partial-preference-failure")
            val preferenceSource = fixture.file("source/preferences.jetpref", "new preferences")
            var preferences = "old preferences"
            var finalCommitCalled = false
            val plan = RestorePreferenceTransaction(
                stagedSource = preferenceSource,
                snapshot = { destination ->
                    destination.writeContents(preferences)
                },
                prepare = { staged, _, canonical ->
                    Files.copy(staged, canonical)
                },
                apply = { staged ->
                    preferences = staged.readContents()
                    throw PartialApplyFailure()
                },
                rollback = { snapshot ->
                    preferences = snapshot.readContents()
                },
            )

            shouldThrow<PartialApplyFailure> {
                RestoreTransaction.execute(
                    scratchParent = fixture.scratchParent,
                    eraseExisting = false,
                    preferences = plan,
                    directories = emptyList(),
                    finalCommit = {
                        finalCommitCalled = true
                    },
                )
            }

            preferences shouldBe "old preferences"
            finalCommitCalled shouldBe false
            fixture.hasScratchData() shouldBe false
        }

        test("a malformed preference candidate fails before live apply") {
            val fixture = root.fixture("malformed-preference")
            val preferenceSource = fixture.file("source/preferences.jetpref", "malformed preferences")
            var preferences = "old preferences"
            var applyCalls = 0
            var rollbackCalls = 0
            var finalCommitCalled = false
            val plan = RestorePreferenceTransaction(
                stagedSource = preferenceSource,
                snapshot = { destination ->
                    destination.writeContents(preferences)
                },
                prepare = { staged, snapshot, canonical ->
                    staged.readContents() shouldBe "malformed preferences"
                    snapshot.readContents() shouldBe "old preferences"
                    canonical.writeContents("partial candidate")
                    throw MalformedPreferenceFailure()
                },
                apply = {
                    applyCalls += 1
                    preferences = it.readContents()
                },
                rollback = {
                    rollbackCalls += 1
                    preferences = it.readContents()
                },
            )

            shouldThrow<MalformedPreferenceFailure> {
                RestoreTransaction.execute(
                    scratchParent = fixture.scratchParent,
                    eraseExisting = false,
                    preferences = plan,
                    directories = emptyList(),
                    finalCommit = {
                        finalCommitCalled = true
                    },
                )
            }

            preferences shouldBe "old preferences"
            applyCalls shouldBe 0
            rollbackCalls shouldBe 0
            finalCommitCalled shouldBe false
            fixture.hasScratchData() shouldBe false
        }

        test("rollback removes a live tree which did not exist before restore") {
            val fixture = root.fixture("missing-live-rollback")
            val source = fixture.source("keyboard").apply {
                write("new.flex", "new keyboard")
            }
            val live = fixture.missingLive("keyboard")

            shouldThrow<LateCommitFailure> {
                RestoreTransaction.execute(
                    scratchParent = fixture.scratchParent,
                    eraseExisting = true,
                    preferences = null,
                    directories = listOf(RestoreDirectoryTransaction(source, live)),
                    finalCommit = {
                        live.contents() shouldBe mapOf("new.flex" to "new keyboard")
                        throw LateCommitFailure()
                    },
                )
            }

            Files.notExists(live, LinkOption.NOFOLLOW_LINKS) shouldBe true
            fixture.hasScratchData() shouldBe false
        }

        test("rollback retries once before returning the original failure") {
            val fixture = root.fixture("rollback-retry")
            val preferenceSource = fixture.file("source/preferences.jetpref", "new preferences")
            var preferences = "old preferences"
            var rollbackAttempts = 0
            val plan = RestorePreferenceTransaction(
                stagedSource = preferenceSource,
                snapshot = { destination ->
                    destination.writeContents(preferences)
                },
                prepare = { staged, _, canonical ->
                    Files.copy(staged, canonical)
                },
                apply = { staged ->
                    preferences = staged.readContents()
                },
                rollback = { snapshot ->
                    rollbackAttempts += 1
                    if (rollbackAttempts == 1) {
                        throw SyntheticRollbackFailure()
                    }
                    preferences = snapshot.readContents()
                },
            )

            shouldThrow<LateCommitFailure> {
                RestoreTransaction.execute(
                    scratchParent = fixture.scratchParent,
                    eraseExisting = false,
                    preferences = plan,
                    directories = emptyList(),
                    finalCommit = {
                        throw LateCommitFailure()
                    },
                )
            }

            rollbackAttempts shouldBe 2
            preferences shouldBe "old preferences"
            fixture.hasScratchData() shouldBe false
        }

        test("rollback failures wrap the original late failure") {
            val fixture = root.fixture("rollback-failure")
            val preferenceSource = fixture.file("source/preferences.jetpref", "new preferences")
            var preferences = "old preferences"
            var rollbackAttempts = 0
            val plan = RestorePreferenceTransaction(
                stagedSource = preferenceSource,
                snapshot = { destination ->
                    destination.writeContents(preferences)
                },
                prepare = { staged, _, canonical ->
                    Files.copy(staged, canonical)
                },
                apply = { staged ->
                    preferences = staged.readContents()
                },
                rollback = {
                    rollbackAttempts += 1
                    throw SyntheticRollbackFailure()
                },
            )

            val failure = shouldThrow<RestoreTransactionRollbackException> {
                RestoreTransaction.execute(
                    scratchParent = fixture.scratchParent,
                    eraseExisting = false,
                    preferences = plan,
                    directories = emptyList(),
                    finalCommit = {
                        throw LateCommitFailure()
                    },
                )
            }

            (failure.cause is LateCommitFailure) shouldBe true
            failure.suppressed.single()::class shouldBe SyntheticRollbackFailure::class
            failure.suppressed.single().suppressed.single()::class shouldBe SyntheticRollbackFailure::class
            rollbackAttempts shouldBe 2
            preferences shouldBe "new preferences"
            fixture.hasScratchData() shouldBe false
        }
    })

private class LateCommitFailure : IOException("synthetic late commit failure")

private class PartialApplyFailure : IOException("synthetic preference apply failure")

private class MalformedPreferenceFailure : IOException("synthetic malformed preference failure")

private class SyntheticRollbackFailure : IOException("synthetic rollback failure")

private fun preferencePlan(
    source: Path,
    readCurrent: () -> String,
    writeCurrent: (String) -> Unit,
) = RestorePreferenceTransaction(
    stagedSource = source,
    snapshot = { destination ->
        destination.writeContents(readCurrent())
    },
    prepare = { staged, _, canonical ->
        Files.copy(staged, canonical)
    },
    apply = { staged ->
        writeCurrent(staged.readContents())
    },
    rollback = { snapshot ->
        writeCurrent(snapshot.readContents())
    },
)

private class RestoreTransactionFixture(
    private val root: Path,
) {
    val scratchParent: Path = Files.createDirectory(root.resolve("private"))

    fun source(name: String): Path = directory("source/$name")

    fun live(name: String): Path = directory("live/$name")

    fun missingLive(name: String): Path = root.resolve("live/$name").also { path ->
        Files.createDirectories(path.parent)
        Files.deleteIfExists(path)
    }

    fun file(
        relativePath: String,
        contents: String,
    ): Path = root.resolve(relativePath).also { path ->
        Files.createDirectories(path.parent)
        path.writeContents(contents)
    }

    fun hasScratchData(): Boolean =
        Files.exists(scratchParent.resolve(".restore-transactions"), LinkOption.NOFOLLOW_LINKS)

    private fun directory(relativePath: String): Path =
        root.resolve(relativePath).also { Files.createDirectories(it) }
}

private fun Path.write(
    relativePath: String,
    contents: String,
) {
    val file = resolve(relativePath)
    Files.createDirectories(file.parent)
    file.writeContents(contents)
}

private fun Path.contents(): Map<String, String> {
    val contents = sortedMapOf<String, String>()
    Files.walk(this).use { paths ->
        paths.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
            .forEach { path ->
                contents[relativize(path).joinToString("/")] = path.readContents()
            }
    }
    return contents
}

private fun Path.writeContents(contents: String) {
    Files.write(this, contents.toByteArray())
}

private fun Path.readContents(): String = String(Files.readAllBytes(this))

private fun Path.fixture(name: String): RestoreTransactionFixture =
    RestoreTransactionFixture(Files.createDirectories(resolve(name)))
