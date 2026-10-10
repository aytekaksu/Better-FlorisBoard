/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.cash.turbine.test
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.plusOrMinus
import dev.patrickgold.florisboard.shouldBeGreaterThanOrEqualTo
import dev.patrickgold.florisboard.shouldBeLessThanOrEqualTo
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.assume
import io.kotest.property.checkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class ImeWindowControllerActionsTest : FunSpec({
    val tolerance = 1e-3f.dp

    coroutineTestScope = true

    test("toggleFloatingWindow()") {
        checkAll(
            Arb.enum<ImeWindowMode>(),
            Arb.enum<ImeWindowMode.Fixed>(),
            Arb.enum<ImeWindowMode.Floating>(),
        ) { mode, fixedMode, floatingMode ->
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)
            val config = ImeWindowConfig(mode, fixedMode = fixedMode, floatingMode = floatingMode)

            windowController.activeWindowSpec.test {
                skipItems(1)
                windowController.updateWindowConfig { config }
                val specBefore = awaitItem()
                windowController.actions.toggleFloatingWindow()
                val specAfter = awaitItem()
                assertSoftly {
                    when (specBefore) {
                        is ImeWindowSpec.Fixed -> specAfter.shouldBeInstanceOf<ImeWindowSpec.Floating>()
                        is ImeWindowSpec.Floating -> specAfter.shouldBeInstanceOf<ImeWindowSpec.Fixed>()
                    }
                }
            }
        }
    }

    test("queued floating toggles keep their original form factor during rotation") {
        val portraitInsets = with(Density(3f)) { ImeInsets.Root.of(IntRect(0, 0, 1080, 2400)) }
        val landscapeInsets = with(Density(3f)) { ImeInsets.Root.of(IntRect(0, 0, 2400, 1080)) }
        val portraitConfig = ImeWindowConfig(ImeWindowMode.FIXED)
        val landscapeConfig = ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = ImeWindowMode.Fixed.COMPACT)
        val originalConfigs = mapOf(
            portraitInsets.formFactor.typeGuess to portraitConfig,
            landscapeInsets.formFactor.typeGuess to landscapeConfig,
            ImeFormFactor.Type.DESKTOP to ImeWindowConfig(ImeWindowMode.FLOATING),
        )
        val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
        val firstWriteEntered = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var firstWrite = true
        prefs.keyboard.windowConfig.init(originalConfigs, PreferenceData.ValuePersistHandler {
            if (firstWrite) {
                firstWrite = false
                firstWriteEntered.complete(Unit)
                releaseFirstWrite.await()
            }
            Result.success(Unit)
        })
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        try {
            val windowController = ImeWindowController(prefs, scope)
            windowController.updateRootInsets(portraitInsets)
            scheduler.runCurrent()
            windowController.activeWindowConfig.value shouldBe portraitConfig

            // Real preferences publish the first change before awaiting persistence acknowledgement.
            windowController.actions.toggleFloatingWindow()
            firstWriteEntered.await()
            prefs.keyboard.windowConfig.get()
                .getValue(portraitInsets.formFactor.typeGuess).mode shouldBe ImeWindowMode.FLOATING
            windowController.actions.toggleFloatingWindow()

            windowController.updateRootInsets(landscapeInsets)
            scheduler.runCurrent()
            windowController.activeWindowConfig.value shouldBe landscapeConfig

            releaseFirstWrite.complete(Unit)
            scheduler.runCurrent()
            prefs.keyboard.windowConfig.get() shouldBe originalConfigs
            windowController.activeWindowConfig.value shouldBe landscapeConfig

            windowController.updateRootInsets(portraitInsets)
            scheduler.runCurrent()
            windowController.activeWindowConfig.value shouldBe portraitConfig
        } finally {
            releaseFirstWrite.complete(Unit)
            scope.cancel()
        }
    }

    test("toggleCompactLayout()") {
        checkAll(
            Arb.enum<ImeWindowMode>(),
            Arb.enum<ImeWindowMode.Fixed>(),
            Arb.enum<ImeWindowMode.Floating>(),
        ) { mode, fixedMode, floatingMode ->
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)
            val config = ImeWindowConfig(mode, fixedMode = fixedMode, floatingMode = floatingMode)

            windowController.activeWindowSpec.test {
                skipItems(1)
                windowController.updateWindowConfig { config }
                val specBefore = awaitItem()
                windowController.actions.toggleCompactLayout()
                val specAfter = awaitItem()
                assertSoftly {
                    when (specBefore) {
                        is ImeWindowSpec.Fixed -> when (specBefore.fixedMode) {
                            ImeWindowMode.Fixed.COMPACT -> specAfter.shouldBeFixedNormal()
                            else -> specAfter.shouldBeFixedCompact()
                        }
                        is ImeWindowSpec.Floating -> specAfter.shouldBeFixedCompact()
                    }
                }
            }
        }
    }

    test("compactLayoutToLeft()") {
        checkAll(Arb.rootInsets()) { rootInsets ->
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)

            windowController.activeWindowSpec.test {
                skipItems(1)
                windowController.updateRootInsets(rootInsets)
                skipItems(1)
                windowController.actions.compactLayoutToLeft()
                val spec = awaitItem()
                assertSoftly {
                    val spec = spec.shouldBeFixedCompact()
                    spec.props.paddingLeft.shouldBeLessThanOrEqualTo(spec.props.paddingRight, tolerance)
                }
            }
        }
    }

    test("compactLayoutToRight()") {
        checkAll(Arb.rootInsets()) { rootInsets ->
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)

            windowController.activeWindowSpec.test {
                skipItems(1)
                windowController.updateRootInsets(rootInsets)
                skipItems(1)
                windowController.actions.compactLayoutToRight()
                val spec = awaitItem()
                assertSoftly {
                    val spec = spec.shouldBeFixedCompact()
                    spec.props.paddingLeft.shouldBeGreaterThanOrEqualTo(spec.props.paddingRight, tolerance)
                }
            }
        }
    }

    test("compactLayoutFlipSide()") {
        checkAll(Arb.rootInsets()) { rootInsets ->
            assume(ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.COMPACT).minPaddingHorizontal > 0.dp)

            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)

            fun paddingsShouldBeFlipped(a: ImeWindowProps.Fixed, b: ImeWindowProps.Fixed) {
                a.paddingLeft shouldBe b.paddingRight.plusOrMinus(tolerance)
                a.paddingRight shouldBe b.paddingLeft.plusOrMinus(tolerance)
            }

            windowController.activeWindowSpec.test {
                skipItems(1)
                windowController.updateRootInsets(rootInsets)
                skipItems(1)
                windowController.actions.compactLayoutToRight()
                val specBefore = awaitItem()
                windowController.actions.compactLayoutFlipSide()
                val specAfterSplit1 = awaitItem()
                windowController.actions.compactLayoutFlipSide()
                val specAfterSplit2 = awaitItem()
                windowController.actions.compactLayoutFlipSide()
                val specAfterSplit3 = awaitItem()
                assertSoftly {
                    val specBefore = specBefore.shouldBeFixedCompact()
                    val specAfterSplit1 = specAfterSplit1.shouldBeFixedCompact()
                    val specAfterSplit2 = specAfterSplit2.shouldBeFixedCompact()
                    val specAfterSplit3 = specAfterSplit3.shouldBeFixedCompact()
                    paddingsShouldBeFlipped(specBefore.props, specAfterSplit1.props)
                    paddingsShouldBeFlipped(specAfterSplit1.props, specAfterSplit2.props)
                    paddingsShouldBeFlipped(specAfterSplit2.props, specAfterSplit3.props)
                }
            }
        }
    }

    val customFixed = ImeWindowProps.Fixed(300.dp, 20.dp, 30.dp, 40.dp)
    val customFloating = ImeWindowProps.Floating(250.dp, 300.dp, 20.dp, 40.dp)
    val fixedProps = ImeWindowMode.Fixed.entries.associateWith { customFixed }
    val floatingProps = mapOf(ImeWindowMode.Floating.NORMAL to customFloating)
    val resetCases = listOf(
        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = ImeWindowMode.Fixed.NORMAL) to
            ImeWindowProps.Fixed(227.5.dp, 0.dp, 0.dp, 0.dp),
        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = ImeWindowMode.Fixed.COMPACT) to
            ImeWindowProps.Fixed(182.dp, 70.dp, 0.dp, 45.5.dp),
        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = ImeWindowMode.Fixed.THUMBS) to
            ImeWindowProps.Fixed(227.5.dp, 0.dp, 0.dp, 0.dp),
        ImeWindowConfig(ImeWindowMode.FLOATING) to
            ImeWindowProps.Floating(192.5.dp, 256.75.dp, 20.dp, 40.dp),
    )
    for ((selection, expectedProps) in resetCases) {
        val fixed = selection.mode == ImeWindowMode.FIXED
        val caseName = if (fixed) "resetFixedSize() for ${selection.fixedMode}" else "resetFloatingSize()"
        test(caseName) {
            // A non-default, fully visible size makes the reset itself produce a new spec.
            val rootInsets = with(Density(3f)) { ImeInsets.Root.of(IntRect(0, 0, 1080, 2400)) }
            val profile = rootInsets.formFactor.typeGuess
            val config = selection.copy(fixedProps = fixedProps, floatingProps = floatingProps)
            val otherConfig = config.copy(mode = ImeWindowMode.FLOATING)
            val originalConfigs = mapOf(profile to config, ImeFormFactor.Type.DESKTOP to otherConfig)
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            prefs.keyboard.windowConfig.set(originalConfigs).getOrThrow()
            val windowController = ImeWindowController(prefs, backgroundScope)
            windowController.updateRootInsets(rootInsets)
            val before = windowController.activeWindowSpec.first {
                it.props == if (fixed) customFixed else customFloating
            }

            windowController.activeWindowSpec.distinctUntilChangedBy { it.props }.test {
                awaitItem().props shouldBe before.props
                if (fixed) {
                    windowController.actions.resetFixedSize()
                } else {
                    windowController.actions.resetFloatingSize()
                }
                when (val after = awaitItem()) {
                    is ImeWindowSpec.Fixed -> {
                        fixed shouldBe true
                        after.fixedMode shouldBe selection.fixedMode
                        val expected = expectedProps.shouldBeInstanceOf<ImeWindowProps.Fixed>()
                        after.props.keyboardHeight shouldBe expectedProps.keyboardHeight.plusOrMinus(tolerance)
                        after.props.paddingLeft shouldBe expected.paddingLeft.plusOrMinus(tolerance)
                        after.props.paddingRight shouldBe expected.paddingRight.plusOrMinus(tolerance)
                        after.props.paddingBottom shouldBe expected.paddingBottom.plusOrMinus(tolerance)
                    }
                    is ImeWindowSpec.Floating -> {
                        fixed shouldBe false
                        after.floatingMode shouldBe selection.floatingMode
                        val expected = expectedProps.shouldBeInstanceOf<ImeWindowProps.Floating>()
                        after.props.keyboardHeight shouldBe expected.keyboardHeight.plusOrMinus(tolerance)
                        after.props.keyboardWidth shouldBe expected.keyboardWidth.plusOrMinus(tolerance)
                        // Only dimensions reset; this valid saved position must not move.
                        after.props.offsetLeft shouldBe customFloating.offsetLeft
                        after.props.offsetBottom shouldBe customFloating.offsetBottom
                    }
                }
            }

            val saved = prefs.keyboard.windowConfig.asFlow().first { it != originalConfigs }
            saved.keys shouldBe originalConfigs.keys
            saved[ImeFormFactor.Type.DESKTOP] shouldBe otherConfig
            val updated = saved.getValue(profile)
            updated.mode shouldBe selection.mode
            updated.fixedMode shouldBe selection.fixedMode
            updated.floatingMode shouldBe selection.floatingMode
            if (fixed) {
                updated.fixedProps shouldBe fixedProps.minus(selection.fixedMode)
                updated.floatingProps shouldBe floatingProps
            } else {
                updated.fixedProps shouldBe fixedProps
                updated.floatingProps.keys shouldBe floatingProps.keys
                val actual = updated.floatingProps.getValue(selection.floatingMode)
                val expected = expectedProps.shouldBeInstanceOf<ImeWindowProps.Floating>()
                actual.keyboardHeight shouldBe expected.keyboardHeight.plusOrMinus(tolerance)
                actual.keyboardWidth shouldBe expected.keyboardWidth.plusOrMinus(tolerance)
                actual.offsetLeft shouldBe expected.offsetLeft
                actual.offsetBottom shouldBe expected.offsetBottom
            }
        }
    }
})
