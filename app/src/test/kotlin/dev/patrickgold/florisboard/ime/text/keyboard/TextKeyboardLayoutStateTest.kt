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

package dev.patrickgold.florisboard.ime.text.keyboard

import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.DefaultComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.florisboard.autocorrect.api.AutocorrectKeyGeometry

class TextKeyboardLayoutStateTest : FunSpec({
    context("autocorrect trace layout compatibility") {
        fun snapshot(text: String, right: Float = 1f) = AutocorrectInputLayoutSnapshot(
            mode = KeyboardMode.CHARACTERS,
            width = 100f,
            height = 50f,
            keys = listOf(AutocorrectKeyGeometry(text, 0f, 0f, right, 1f)),
        )

        test("ordinary and Turkish case transitions preserve a word trace") {
            snapshot("A").isTraceCompatibleWith(snapshot("a")) shouldBe true
            snapshot("İ").isTraceCompatibleWith(snapshot("i")) shouldBe true
        }

        test("semantic key or geometry changes invalidate a word trace") {
            snapshot(";").isTraceCompatibleWith(snapshot(":")) shouldBe false
            snapshot("a").isTraceCompatibleWith(snapshot("a", right = 0.9f)) shouldBe false
        }
    }

    context("spacebar movement fallback") {
        test("raw cursor movement falls back when direct movement is unavailable") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = true,
                directMovementSucceeded = false,
            ) shouldBe true
        }

        test("raw selection movement falls back when direct movement is unavailable") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = true,
                directMovementSucceeded = false,
            ) shouldBe true
        }

        test("rich cursor boundary does not send a fallback command") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = false,
                directMovementSucceeded = true,
            ) shouldBe false
        }

        test("rich selection boundary does not send a fallback command") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = false,
                directMovementSucceeded = true,
            ) shouldBe false
        }

        test("rich direct movement failure does not send a fallback command") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = false,
                directMovementSucceeded = false,
            ) shouldBe false
        }
    }

    context("glide drawing state") {
        test("points are collected only while glide trail drawing is enabled") {
            shouldCollectGlideDrawingPoint(isGlideEnabled = true, showTrail = true) shouldBe true
            shouldCollectGlideDrawingPoint(isGlideEnabled = true, showTrail = false) shouldBe false
            shouldCollectGlideDrawingPoint(isGlideEnabled = false, showTrail = true) shouldBe false
        }

        test("completed visible trail moves active points into the fading trail") {
            val activePoints = mutableListOf(1, 2)
            val fadingPoints = mutableListOf(0)

            finishGlideDrawingState(
                showTrail = true,
                activePoints = activePoints,
                fadingPoints = fadingPoints,
            ) shouldBe true

            activePoints.shouldBeEmpty()
            fadingPoints shouldBe listOf(1, 2)
        }

        test("completed hidden trail clears active and stale fading points") {
            val activePoints = mutableListOf(1, 2)
            val fadingPoints = mutableListOf(0)

            finishGlideDrawingState(
                showTrail = false,
                activePoints = activePoints,
                fadingPoints = fadingPoints,
            ) shouldBe false

            activePoints.shouldBeEmpty()
            fadingPoints.shouldBeEmpty()
        }
    }

    context("key movement ownership") {
        val n = movementTestKey(TextKeyData(code = 'n'.code, label = "n"))
        val v = movementTestKey(TextKeyData(code = 'v'.code, label = "v"))
        val space = movementTestKey(TextKeyData.SPACE)
        val cjkSpace = movementTestKey(
            TextKeyData(code = KeyCode.CJK_SPACE, label = "cjk_space"),
        )
        n.setTestBounds(0f, 0f, 52f, 40f)
        v.setTestBounds(40f, 0f, 80f, 40f)
        space.setTestBounds(0f, 40f, 120f, 80f)
        cjkSpace.setTestBounds(0f, 40f, 120f, 80f)
        n.visibleBounds.apply {
            left = 4f
            top = 4f
            right = 36f
            bottom = 36f
        }

        test("the same candidate remains owned") {
            resolveKeyMoveAction(
                activeKey = n,
                candidateKey = n,
                pointerX = 60f,
                pointerY = 45f,
                hysteresisDistance = 8f,
            ) shouldBe KeyMoveAction.KEEP
        }

        test("visible bounds retain ownership only within hysteresis") {
            for (distance in listOf(0f, 2f, 7.99f)) {
                resolveKeyMoveAction(
                    activeKey = n,
                    candidateKey = space,
                    pointerX = n.visibleBounds.center.x,
                    pointerY = n.visibleBounds.bottom + distance,
                    hysteresisDistance = 8f,
                ) shouldBe KeyMoveAction.KEEP
            }
        }

        test("space and CJK space receive ownership at and beyond hysteresis") {
            for (candidate in listOf(space, cjkSpace)) {
                for (distance in listOf(8f, 12f)) {
                    resolveKeyMoveAction(
                        activeKey = n,
                        candidateKey = candidate,
                        pointerX = n.visibleBounds.center.x,
                        pointerY = n.visibleBounds.bottom + distance,
                        hysteresisDistance = 8f,
                    ) shouldBe KeyMoveAction.TRANSFER
                }
            }
        }

        test("movement uses visible bounds instead of the wider touch bounds") {
            for (distance in listOf(8f, 10f)) {
                resolveKeyMoveAction(
                    activeKey = n,
                    candidateKey = v,
                    pointerX = n.visibleBounds.right + distance,
                    pointerY = n.visibleBounds.center.y,
                    hysteresisDistance = 8f,
                ) shouldBe KeyMoveAction.TRANSFER
            }
        }

        test("crossing a character boundary beyond hysteresis transfers") {
            resolveKeyMoveAction(
                activeKey = n,
                candidateKey = v,
                pointerX = v.touchBounds.center.x,
                pointerY = v.touchBounds.center.y,
                hysteresisDistance = 8f,
            ) shouldBe KeyMoveAction.TRANSFER
        }

        test("leaving the keyboard beyond hysteresis cancels ownership") {
            resolveKeyMoveAction(
                activeKey = n,
                candidateKey = null,
                pointerX = n.visibleBounds.center.x,
                pointerY = n.visibleBounds.bottom + 8f,
                hysteresisDistance = 8f,
            ) shouldBe KeyMoveAction.CANCEL
        }
    }

    context("additional pointer admission") {
        test("text outputs release ownership before the next pointer is admitted") {
            val multiText = MultiTextKeyData(
                codePoints = intArrayOf(2332, 2381, 2334),
                label = "ज्ञ",
            )
            for (
                data in listOf(
                    TextKeyData(code = 'n'.code, label = "n"),
                    TextKeyData(type = KeyType.NUMERIC, code = '1'.code, label = "1"),
                    multiText,
                )
            ) {
                data.shouldCommitBeforeAdditionalPointer() shouldBe true
            }
        }

        test("spaces and controls retain single-owner behavior") {
            for (
                data in listOf(
                    TextKeyData.SPACE,
                    TextKeyData(type = KeyType.FUNCTION, code = KeyCode.CJK_SPACE),
                    TextKeyData.DELETE,
                )
            ) {
                data.shouldCommitBeforeAdditionalPointer() shouldBe false
            }
        }
    }

    context("precise delete swipe completion") {
        test("delete modes commit the selected range") {
            shouldCommitDeleteSwipeSelection(
                SwipeAction.DELETE_CHARACTERS_PRECISELY,
            ) shouldBe true
            shouldCommitDeleteSwipeSelection(
                SwipeAction.DELETE_WORDS_PRECISELY,
            ) shouldBe true
        }

        test("selection modes preserve the selected range") {
            shouldCommitDeleteSwipeSelection(
                SwipeAction.SELECT_CHARACTERS_PRECISELY,
            ) shouldBe false
            shouldCommitDeleteSwipeSelection(
                SwipeAction.SELECT_WORDS_PRECISELY,
            ) shouldBe false
        }
    }
})

private fun movementTestKey(data: TextKeyData): TextKey {
    val key = TextKey(data)
    val keyboard = TextKeyboard(
        arrangement = arrayOf(arrayOf(key)),
        mode = KeyboardMode.CHARACTERS,
        extendedPopupMapping = null,
        extendedPopupMappingDefault = null,
    )
    val evaluator = object : ComputingEvaluator by DefaultComputingEvaluator {
        override val keyboard = keyboard
    }
    key.compute(evaluator)
    return key
}
