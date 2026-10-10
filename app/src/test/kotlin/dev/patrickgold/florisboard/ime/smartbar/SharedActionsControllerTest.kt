/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SharedActionsControllerTest :
    FunSpec({
        test("shared actions sample live selection and preserve inline suggestions") {
            runTest {
                val fixture = SharedActionsFixture(this)
                val present = listOf(Unit)
                val cases = listOf(
                    PresentationCase(present, null, false, false),
                    PresentationCase(present, null, true, true),
                    PresentationCase(present, null, false, false),
                    PresentationCase(emptyList<Unit>(), null, false, true),
                    PresentationCase(null, present, false, false),
                    PresentationCase(present, present, true, true),
                    PresentationCase(null, null, false, true),
                )
                for (case in cases) {
                    fixture.selection = case.selection
                    fixture.controller.update(case.candidates, case.inlineSuggestions)
                    runCurrent()
                    fixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe case.expanded
                }
                fixture.selectionReads shouldBe cases.size
            }
        }

        test("stale composition cannot consume a newer suppression with the same target") {
            runTest {
                val fixture = SharedActionsFixture(this)
                fixture.controller.update(null, null)
                runCurrent()
                val stale = requireNotNull(fixture.controller.animationSuppression.value)
                fixture.controller.collapseForTyping()
                runCurrent()
                fixture.controller.update(null, null)
                runCurrent()
                val latest = requireNotNull(fixture.controller.animationSuppression.value)

                latest.targetExpanded shouldBe stale.targetExpanded
                fixture.controller.acknowledge(stale)
                fixture.controller.animationSuppression.value shouldBe latest
                fixture.controller.acknowledge(latest)
                fixture.controller.animationSuppression.value shouldBe null
            }
        }

        test("user transition clears suppression even for an equal target while disabled") {
            runTest {
                val fixture = SharedActionsFixture(this)
                fixture.controller.update(null, null)
                runCurrent()
                requireNotNull(fixture.controller.animationSuppression.value).targetExpanded shouldBe true
                fixture.prefs.smartbar.enabled.set(false).getOrThrow()

                fixture.controller.setExpandedByUser(true)
                runCurrent()

                fixture.controller.animationSuppression.value shouldBe null
                fixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe true
            }
        }

        test("new intent retires unstarted work and disabled automation leaves user intent alone") {
            runTest {
                val fixture = SharedActionsFixture(this)
                val writes = mutableListOf<Boolean>()
                fixture.prefs.smartbar.sharedActionsExpanded.init(
                    false,
                    object : PreferenceData.ValuePersistHandler<Boolean> {
                        override suspend fun onValueChanged(value: Boolean?): Result<Unit> {
                            writes += requireNotNull(value)
                            return Result.success(Unit)
                        }
                    },
                )
                fixture.controller.update(null, null)
                fixture.controller.setExpandedByUser(false)
                runCurrent()
                fixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe false
                fixture.controller.animationSuppression.value shouldBe null
                writes shouldBe listOf(false)

                fixture.controller.setExpandedByUser(true)
                fixture.prefs.smartbar.enabled.set(false).getOrThrow()
                fixture.controller.collapseForTyping()
                runCurrent()
                fixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe true
                fixture.controller.animationSuppression.value shouldBe null
                writes shouldBe listOf(false, true)
            }
        }

        test("equal automatic intent retires older user work without replacing suppression") {
            runTest {
                val fixture = SharedActionsFixture(this)
                fixture.controller.update(null, null)
                runCurrent()
                val suppression = requireNotNull(fixture.controller.animationSuppression.value)

                fixture.controller.setExpandedByUser(false)
                fixture.controller.update(null, null)
                runCurrent()

                fixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe true
                fixture.controller.animationSuppression.value shouldBe suppression
            }
        }

        test("latest automatic or user change waits for an admitted preference write") {
            for (byUser in listOf(false, true)) {
                runTest {
                    val fixture = SharedActionsFixture(this)
                    val writeStarted = CompletableDeferred<Unit>()
                    val resumeWrite = CompletableDeferred<Unit>()
                    var stored = true
                    fixture.prefs.smartbar.sharedActionsExpanded.init(
                        true,
                        object : PreferenceData.ValuePersistHandler<Boolean> {
                            override suspend fun onValueChanged(value: Boolean?): Result<Unit> {
                                if (value == false) {
                                    writeStarted.complete(Unit)
                                    resumeWrite.await()
                                }
                                stored = requireNotNull(value)
                                return Result.success(Unit)
                            }
                        },
                    )
                    try {
                        fixture.controller.collapseForTyping()
                        runCurrent()
                        writeStarted.await()
                        val pending = requireNotNull(fixture.controller.animationSuppression.value)
                        pending.targetExpanded shouldBe false
                        fixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe false

                        if (byUser) {
                            fixture.controller.setExpandedByUser(true)
                        } else {
                            fixture.controller.update(null, null)
                        }
                        runCurrent()
                        // JetPref publishes before persistence; suppression must stay ordered too.
                        fixture.controller.animationSuppression.value shouldBe pending
                        stored shouldBe true

                        resumeWrite.complete(Unit)
                        runCurrent()
                        fixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe true
                        stored shouldBe true
                        val expectedTarget = if (byUser) null else true
                        fixture.controller.animationSuppression.value?.targetExpanded shouldBe expectedTarget
                    } finally {
                        resumeWrite.complete(Unit)
                        runCurrent()
                    }
                }
            }
        }
    })

private class SharedActionsFixture(scope: CoroutineScope) {
    private val dataStore = jetprefDataStoreOf(FlorisPreferenceModel::class)
    val prefs by dataStore
    var selection = false
    var selectionReads = 0
    val controller = SharedActionsController(prefs.smartbar, scope) {
        selectionReads++
        selection
    }
}

private data class PresentationCase(
    val candidates: List<*>?,
    val inlineSuggestions: List<*>?,
    val selection: Boolean,
    val expanded: Boolean,
)
