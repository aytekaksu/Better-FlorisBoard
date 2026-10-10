/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first

class ImeWindowControllerTest : FunSpec({
    val tolerance = 1e-3f.dp

    coroutineTestScope = true

    context("isWindowShown state") {
        test("simple onShown onHidden") {
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)

            windowController.isWindowShown.value shouldBe false
            windowController.onWindowShown() shouldBe true
            windowController.isWindowShown.value shouldBe true
            windowController.onWindowHidden() shouldBe true
            windowController.isWindowShown.value shouldBe false
        }

        test("duplicate onWindowShown is detected") {
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)

            windowController.isWindowShown.value shouldBe false
            windowController.onWindowShown() shouldBe true
            windowController.isWindowShown.value shouldBe true
            windowController.onWindowShown().shouldBe(false, "duplicate onWindowShown is detected")
            windowController.isWindowShown.value shouldBe true
        }

        test("duplicate onWindowHidden is detected") {
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)

            windowController.isWindowShown.value shouldBe false
            windowController.onWindowShown() shouldBe true
            windowController.isWindowShown.value shouldBe true
            windowController.onWindowHidden() shouldBe true
            windowController.isWindowShown.value shouldBe false
            windowController.onWindowHidden().shouldBe(false, "duplicate onWindowHidden should fail")
            windowController.isWindowShown.value shouldBe false
        }
    }

    context("for all root insets") {
        test("for all fixed window configs in prefs") {
            checkAll(
                Arb.rootInsets(),
                Arb.windowConfigFixed(),
            ) { rootInsets, windowConfig ->
                val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
                prefs.keyboard.windowConfig.set(mapOf(rootInsets.formFactor.typeGuess to windowConfig))
                val windowController = ImeWindowController(prefs, backgroundScope)
                windowController.updateRootInsets(rootInsets)

                val spec = windowController.activeWindowSpec.first { it !== ImeWindowSpec.Fallback }

                assertSoftly {
                    val constraints = ImeWindowConstraints.of(rootInsets, windowConfig.fixedMode)
                    val spec = spec.shouldBeInstanceOf<ImeWindowSpec.Fixed>()
                    spec.props.shouldBeConstrainedTo(constraints, tolerance)
                }
            }
        }

        test("for all floating window configs in prefs") {
            checkAll(
                Arb.rootInsets(),
                Arb.windowConfigFloating(),
            ) { rootInsets, windowConfig ->
                val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
                prefs.keyboard.windowConfig.set(mapOf(rootInsets.formFactor.typeGuess to windowConfig))
                val windowController = ImeWindowController(prefs, backgroundScope)
                windowController.updateRootInsets(rootInsets)

                val spec = windowController.activeWindowSpec.first { it !== ImeWindowSpec.Fallback }

                assertSoftly {
                    val constraints = ImeWindowConstraints.of(rootInsets, windowConfig.floatingMode)
                    val spec = spec.shouldBeInstanceOf<ImeWindowSpec.Floating>()
                    spec.props.shouldBeConstrainedTo(constraints, tolerance)
                }
            }
        }
    }
})
