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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.importWithLegacyMigrations
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.runtime.DataStoreReader
import dev.patrickgold.jetpref.datastore.runtime.DataStoreWriter
import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import dev.patrickgold.jetpref.datastore.runtime.LoadStrategy
import dev.patrickgold.jetpref.datastore.runtime.PersistStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PendingPreferenceSaveTest : FunSpec({
    test("reopen reads the newest pending edit before disk catches up") {
        runTest {
            var stored = 0
            val writes = mutableListOf<Int>()
            val save = PendingPreferenceSave(this, { stored }, { value ->
                writes += value
                stored = value
            }, { throw it })

            val edit = save.open()
            save.saveInBackground(1, edit.epoch)
            save.saveInBackground(2, edit.epoch)
            save.open().value shouldBe 2
            runCurrent()
            writes shouldBe listOf(2)
            save.open().value shouldBe 2
        }
    }

    test("intentional close waits for a durable write and a later edit stays ordered") {
        runTest {
            var stored = 0
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<Int>()
            val save = PendingPreferenceSave(this, { stored }, { value ->
                if (value == 1) {
                    writing.complete(Unit)
                    release.await()
                }
                writes += value
                stored = value
            }, { throw it })

            val edit = save.open()
            val close = async { save.saveAndAwait(1, edit.epoch) }
            writing.await()
            close.isCompleted shouldBe false
            save.saveInBackground(2, edit.epoch)
            save.open().value shouldBe 2
            release.complete(Unit)
            close.await() shouldBe false // A newer edit superseded this close.
            runCurrent()
            writes shouldBe listOf(1, 2)
            stored shouldBe 2
        }
    }

    test("intentional close acknowledges a committed edit and retries a failed result") {
        runTest {
            var stored = 0
            var fail = true
            val save = PendingPreferenceSave(this, { stored }, { value ->
                (if (fail) Result.failure<Unit>(IllegalStateException("synthetic failure"))
                    else Result.success(Unit)).getOrThrow()
                stored = value
            }, { throw it })
            val edit = save.open()

            runCatching { save.saveAndAwait(7, edit.epoch) }.isFailure shouldBe true
            save.open().value shouldBe 7
            stored shouldBe 0
            fail = false
            save.saveAndAwait(7, edit.epoch) shouldBe true
            stored shouldBe 7
        }
    }

    test("failed preference Result stays pending and a later backup barrier retries") {
        runTest {
            var stored = 0
            var fail = true
            var attempts = 0
            val failures = mutableListOf<Exception>()
            val save = PendingPreferenceSave(this, { stored }, { value ->
                attempts++
                val result = if (fail) Result.failure<Unit>(IllegalStateException("synthetic failure"))
                    else Result.success(Unit)
                result.getOrThrow()
                stored = value
            }, failures::add)

            save.saveInBackground(4, save.open().epoch)
            advanceUntilIdle()
            attempts shouldBe 3
            failures.size shouldBe 1
            save.open().value shouldBe 4
            val failedBackup = runCatching { save.withBarrier { stored } }
            failedBackup.isFailure shouldBe true

            fail = false
            save.withBarrier { stored } shouldBe 4
            save.open().value shouldBe 4
        }
    }

    test("reset and restore invalidate drafts opened before their mutation") {
        runTest {
            var stored = 0
            val releaseReset = CompletableDeferred<Unit>()
            val inReset = CompletableDeferred<Unit>()
            val save = PendingPreferenceSave(this, { stored }, { stored = it }, { throw it })
            val oldEdit = save.open()

            save.saveInBackground(1, oldEdit.epoch)
            val reset = launch {
                save.withReplacement {
                    stored shouldBe 1
                    inReset.complete(Unit)
                    releaseReset.await()
                    stored = 0
                }
            }
            inReset.await()
            save.saveInBackground(2, oldEdit.epoch)
            stored shouldBe 1
            releaseReset.complete(Unit)
            reset.join()
            save.flush()
            stored shouldBe 0
            save.saveAndAwait(3, oldEdit.epoch) shouldBe false
            stored shouldBe 0

            val newEdit = save.open()
            newEdit.epoch shouldBe oldEdit.epoch + 1
            save.saveAndAwait(4, newEdit.epoch) shouldBe true
            stored shouldBe 4
            save.withReplacement { stored = 9 } // A restored preference snapshot.
            save.saveAndAwait(5, newEdit.epoch) shouldBe false
            stored shouldBe 9
        }
    }

    test("failed replacement keeps the open draft unless rollback also fails") {
        runTest {
            var stored = 0
            val save = PendingPreferenceSave(this, { stored }, { stored = it }, { throw it })
            val edit = save.open()

            runCatching { save.withReplacement { error("synthetic preflight failure") } }
                .isFailure shouldBe true
            save.saveAndAwait(3, edit.epoch) shouldBe true
            stored shouldBe 3

            runCatching {
                save.withReplacement(invalidateOnFailure = { true }) {
                    stored = 8
                    error("synthetic rollback failure")
                }
            }.isFailure shouldBe true
            save.saveAndAwait(4, edit.epoch) shouldBe false
            stored shouldBe 8
        }
    }

    test("replace without a flush can recover from a failed pending save") {
        runTest {
            var stored = 0
            var fail = true
            val save = PendingPreferenceSave(this, { stored }, { value ->
                if (fail) error("synthetic write failure")
                stored = value
            }, {})
            val edit = save.open()
            save.saveInBackground(1, edit.epoch)
            advanceUntilIdle()
            save.open().value shouldBe 1

            runCatching {
                save.withReplacement(flushPending = false) { error("synthetic reset failure") }
            }.isFailure shouldBe true
            save.open().value shouldBe 1

            save.withReplacement(flushPending = false) { stored = 9 }
            save.saveAndAwait(2, edit.epoch) shouldBe false
            stored shouldBe 9
            fail = false
            save.flush()
            stored shouldBe 9
        }
    }

    test("pending action order reaches a JetPref backup and survives restore") {
        runTest {
            val store = jetprefDataStoreOf(FlorisPreferenceModel::class)
            store.init(LoadStrategy.Disabled, PersistStrategy.Disabled).getOrThrow()
            val prefs by store
            val arrangement = QuickActionArrangement.Default.copy(
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.REDO),
                    QuickAction.InsertKey(TextKeyData.UNDO),
                ),
            ).withAvailableActions()
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            var failWrite = false
            val save = PendingPreferenceSave(
                this,
                { prefs.smartbar.actionArrangement.get() },
                { value ->
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                    if (failWrite) error("synthetic write failure")
                    prefs.smartbar.actionArrangement.set(value).getOrThrow()
                },
                { throw it },
            )
            val oldEdit = save.open()
            save.saveInBackground(arrangement, oldEdit.epoch)
            writeStarted.await()

            var backup = ""
            val export = async {
                save.withBarrier {
                    store.export(DataStoreWriter { backup = it }).getOrThrow()
                }
            }
            runCurrent()
            export.isCompleted shouldBe false
            releaseWrite.complete(Unit)
            export.await()
            prefs.smartbar.actionArrangement.get() shouldBe arrangement
            backup.contains("smartbar__action_arrangement") shouldBe true

            save.withReplacement {
                prefs.smartbar.actionArrangement.set(QuickActionArrangement.Default).getOrThrow()
            }
            val pendingEdit = save.open()
            failWrite = true
            save.saveInBackground(
                QuickActionArrangement.Default.copy(dynamicActions = emptyList()),
                pendingEdit.epoch,
            )
            advanceUntilIdle()
            runCatching {
                save.withReplacement {
                    store.importWithLegacyMigrations(
                        strategy = ImportStrategy.Merge,
                        reader = DataStoreReader { backup },
                        sourceVersionCode = BuildConfig.VERSION_CODE,
                    ).getOrThrow()
                }
            }.isFailure shouldBe true
            save.withReplacement(flushPending = false) {
                store.importWithLegacyMigrations(
                    strategy = ImportStrategy.Erase,
                    reader = DataStoreReader { backup },
                    sourceVersionCode = BuildConfig.VERSION_CODE,
                ).getOrThrow()
            }
            prefs.smartbar.actionArrangement.get() shouldBe arrangement
            save.saveAndAwait(QuickActionArrangement.Default, oldEdit.epoch) shouldBe false
            save.saveAndAwait(QuickActionArrangement.Default, pendingEdit.epoch) shouldBe false
        }
    }
})
