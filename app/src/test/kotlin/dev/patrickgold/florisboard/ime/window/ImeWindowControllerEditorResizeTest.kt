/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.window.ExpectedDpChange.NOT_GREATER
import dev.patrickgold.florisboard.ime.window.ExpectedDpChange.NOT_LESS
import dev.patrickgold.florisboard.ime.window.ExpectedDpChange.SAME
import dev.patrickgold.florisboard.plusOrMinus
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class ImeWindowControllerEditorResizeTest :
    FunSpec({
        val tolerance = 1e-3f.dp
        val imeRowCount = 4
        val smartBarRowCount = 0

        coroutineTestScope = true

        test("resizing near the dock edge keeps floating editing active and saves props") {
            val fixture = floatingEditorFixture()
            try {
                val resized = fixture.spec.resizedBy(
                    DpOffset(0.dp, 20.dp),
                    ImeWindowResizeHandle.BOTTOM,
                    imeRowCount,
                    smartBarRowCount,
                ).shouldBeInstanceOf<ImeWindowSpec.Floating>()
                (resized.props.offsetBottom <= resized.constraints.dockToFixedHeight) shouldBe true

                fixture.controller.editor.beginResizeGesture()
                fixture.controller.editor.onSpecUpdated(resized)
                fixture.controller.editor.endResizeGesture(resized)

                fixture.controller.editor.state.value shouldBe ImeWindowController.EditorState.ACTIVE
                fixture.scheduler.runCurrent()
                fixture.controller.activeWindowConfig.value.mode shouldBe ImeWindowMode.FLOATING
                fixture.prefs.keyboard.windowConfig.get()[fixture.rootInsets.formFactor.typeGuess]
                    ?.floatingProps?.get(resized.floatingMode) shouldBe resized.props
            } finally {
                fixture.scope.cancel()
            }
        }

        test("resize handles retain fixed and floating placement") {
            ImeWindowResizeHandle.entries.map { it.alignment } shouldBe listOf(
                Alignment.CenterStart,
                Alignment.TopStart,
                Alignment.TopCenter,
                Alignment.TopEnd,
                Alignment.CenterEnd,
                Alignment.BottomEnd,
                Alignment.BottomCenter,
                Alignment.BottomStart,
            )
            val distance = 24.dp
            floatingResizeHandles.map { it.alignment to it.floatingOffset(distance) } shouldBe listOf(
                Alignment.TopStart to DpOffset(-distance, -distance),
                Alignment.TopEnd to DpOffset(distance, -distance),
                Alignment.BottomEnd to DpOffset(distance, distance),
                Alignment.BottomStart to DpOffset(-distance, distance),
            )
        }

        context("for all root insets and fixed modes") {
            test("for all resizes on top handle") {
                checkAll(
                    Arb.rootInsetsWithVerticalOffset(),
                    Arb.enum<ImeWindowMode.Fixed>(),
                ) { (rootInsets, offset), fixedMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = fixedMode),
                        backgroundScope,
                    ) { it.resizedBy(offset, ImeWindowResizeHandle.TOP, imeRowCount, smartBarRowCount) }
                        .assertAppliedGesture<ImeWindowSpec.Fixed> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldFollow(
                                before.keyboardHeight,
                                offset.y,
                                tolerance,
                                "keyboard height",
                                inverse = true,
                            )
                            after.paddingLeft.shouldMatch(before.paddingLeft, SAME, tolerance, "padding left")
                            after.paddingRight.shouldMatch(before.paddingRight, SAME, tolerance, "padding right")
                            after.paddingBottom.shouldMatch(before.paddingBottom, NOT_LESS, tolerance, "padding bottom")
                            withClue("resize operations must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all resizes on bottom handle") {
                checkAll(
                    Arb.rootInsetsWithVerticalOffset(),
                    Arb.enum<ImeWindowMode.Fixed>(),
                ) { (rootInsets, offset), fixedMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = fixedMode),
                        backgroundScope,
                    ) { it.resizedBy(offset, ImeWindowResizeHandle.BOTTOM, imeRowCount, smartBarRowCount) }
                        .assertAppliedGesture<ImeWindowSpec.Fixed> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldFollow(
                                before.keyboardHeight,
                                offset.y,
                                tolerance,
                                "keyboard height",
                            )
                            withClue("vertical resize operations must not alter window height") {
                                val windowHeightBefore = before.let { it.keyboardHeight + it.paddingBottom }
                                val windowHeightAfter = after.let { it.keyboardHeight + it.paddingBottom }
                                windowHeightAfter shouldBe windowHeightBefore.plusOrMinus(tolerance)
                            }
                            after.paddingLeft.shouldMatch(before.paddingLeft, SAME, tolerance, "padding left")
                            after.paddingRight.shouldMatch(before.paddingRight, SAME, tolerance, "padding right")
                            withClue("resize operations must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all resizes on left handle") {
                checkAll(
                    Arb.rootInsetsWithHorizontalOffset(),
                    Arb.enum<ImeWindowMode.Fixed>(),
                ) { (rootInsets, offset), fixedMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = fixedMode),
                        backgroundScope,
                    ) { it.resizedBy(offset, ImeWindowResizeHandle.LEFT, imeRowCount, smartBarRowCount) }
                        .assertAppliedGesture<ImeWindowSpec.Fixed> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.paddingLeft.shouldFollow(before.paddingLeft, offset.x, tolerance, "padding left")

                            after.paddingRight.shouldMatch(before.paddingRight, SAME, tolerance, "padding right")
                            after.paddingBottom.shouldMatch(before.paddingBottom, SAME, tolerance, "padding bottom")
                            withClue("horizontal resize operations must not alter window height") {
                                val windowHeightBefore = before.let { it.keyboardHeight + it.paddingBottom }
                                val windowHeightAfter = after.let { it.keyboardHeight + it.paddingBottom }
                                windowHeightAfter shouldBe windowHeightBefore.plusOrMinus(tolerance)
                            }
                            withClue("resize operations must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all resizes on right handle") {
                checkAll(
                    Arb.rootInsetsWithHorizontalOffset(),
                    Arb.enum<ImeWindowMode.Fixed>(),
                ) { (rootInsets, offset), fixedMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = fixedMode),
                        backgroundScope,
                    ) { it.resizedBy(offset, ImeWindowResizeHandle.RIGHT, imeRowCount, smartBarRowCount) }
                        .assertAppliedGesture<ImeWindowSpec.Fixed> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.paddingRight.shouldFollow(
                                before.paddingRight,
                                offset.x,
                                tolerance,
                                "padding right",
                                inverse = true,
                            )

                            after.paddingLeft.shouldMatch(before.paddingLeft, SAME, tolerance, "padding left")
                            after.paddingBottom.shouldMatch(before.paddingBottom, SAME, tolerance, "padding bottom")
                            withClue("horizontal resize operations must not alter window height") {
                                val windowHeightBefore = before.let { it.keyboardHeight + it.paddingBottom }
                                val windowHeightAfter = after.let { it.keyboardHeight + it.paddingBottom }
                                windowHeightAfter shouldBe windowHeightBefore.plusOrMinus(tolerance)
                            }
                            withClue("resize operations must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }
        }

        context("for all root insets and floating modes") {
            test("for all resizes on top handle") {
                checkAll(
                    Arb.rootInsetsWithVerticalOffset(),
                    Arb.enum<ImeWindowMode.Floating>(),
                ) { (rootInsets, offset), floatingMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FLOATING, floatingMode = floatingMode),
                        backgroundScope,
                    ) { it.resizedBy(offset, ImeWindowResizeHandle.TOP, imeRowCount, smartBarRowCount) }
                        .assertAppliedGesture<ImeWindowSpec.Floating> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldFollow(
                                before.keyboardHeight,
                                offset.y,
                                tolerance,
                                "keyboard height",
                                inverse = true,
                            )
                            after.keyboardWidth.shouldMatch(before.keyboardWidth, SAME, tolerance, "keyboard width")
                            after.offsetLeft.shouldMatch(before.offsetLeft, SAME, tolerance, "offset left")
                            after.offsetBottom.shouldMatch(before.offsetBottom, SAME, tolerance, "offset bottom")
                            withClue("resize operations must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all resizes on bottom handle") {
                checkAll(
                    Arb.rootInsetsWithVerticalOffset(),
                    Arb.enum<ImeWindowMode.Floating>(),
                ) { (rootInsets, offset), floatingMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FLOATING, floatingMode = floatingMode),
                        backgroundScope,
                    ) { it.resizedBy(offset, ImeWindowResizeHandle.BOTTOM, imeRowCount, smartBarRowCount) }
                        .assertAppliedGesture<ImeWindowSpec.Floating> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            if (offset.y <= 0.dp) {
                                after.keyboardHeight.shouldMatch(
                                    before.keyboardHeight,
                                    NOT_GREATER,
                                    tolerance,
                                    "keyboard height",
                                )
                                after.offsetBottom.shouldMatch(
                                    before.offsetBottom,
                                    NOT_LESS,
                                    tolerance,
                                    "offset bottom",
                                )
                            }
                            if (offset.y >= 0.dp) {
                                after.keyboardHeight.shouldMatch(
                                    before.keyboardHeight,
                                    NOT_LESS,
                                    tolerance,
                                    "keyboard height",
                                )
                                after.offsetBottom.shouldMatch(
                                    before.offsetBottom,
                                    NOT_GREATER,
                                    tolerance,
                                    "offset bottom",
                                )
                            }
                            after.keyboardWidth.shouldMatch(before.keyboardWidth, SAME, tolerance, "keyboard width")
                            after.offsetLeft.shouldMatch(before.offsetLeft, SAME, tolerance, "offset left")
                            withClue("resize operations must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all resizes on left handle") {
                checkAll(
                    Arb.rootInsetsWithHorizontalOffset(),
                    Arb.enum<ImeWindowMode.Floating>(),
                ) { (rootInsets, offset), floatingMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FLOATING, floatingMode = floatingMode),
                        backgroundScope,
                    ) { it.resizedBy(offset, ImeWindowResizeHandle.LEFT, imeRowCount, smartBarRowCount) }
                        .assertAppliedGesture<ImeWindowSpec.Floating> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            if (offset.x <= 0.dp) {
                                after.keyboardWidth.shouldMatch(
                                    before.keyboardWidth,
                                    NOT_LESS,
                                    tolerance,
                                    "keyboard width",
                                )
                                after.offsetLeft.shouldMatch(before.offsetLeft, NOT_GREATER, tolerance, "offset left")
                            }
                            if (offset.x >= 0.dp) {
                                after.keyboardWidth.shouldMatch(
                                    before.keyboardWidth,
                                    NOT_GREATER,
                                    tolerance,
                                    "keyboard width",
                                )
                                after.offsetLeft.shouldMatch(before.offsetLeft, NOT_LESS, tolerance, "offset left")
                            }
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.offsetBottom.shouldMatch(before.offsetBottom, SAME, tolerance, "offset bottom")
                            withClue("resize operations must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all resizes on right handle") {
                checkAll(
                    Arb.rootInsetsWithHorizontalOffset(),
                    Arb.enum<ImeWindowMode.Floating>(),
                ) { (rootInsets, offset), floatingMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FLOATING, floatingMode = floatingMode),
                        backgroundScope,
                    ) { it.resizedBy(offset, ImeWindowResizeHandle.RIGHT, imeRowCount, smartBarRowCount) }
                        .assertAppliedGesture<ImeWindowSpec.Floating> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardWidth.shouldFollow(
                                before.keyboardWidth,
                                offset.x,
                                tolerance,
                                "keyboard width",
                            )
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.offsetLeft.shouldMatch(before.offsetLeft, SAME, tolerance, "offset left")
                            after.offsetBottom.shouldMatch(before.offsetBottom, SAME, tolerance, "offset bottom")
                            withClue("resize operations must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }
        }
    })
