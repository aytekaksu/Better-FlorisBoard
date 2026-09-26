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

package dev.patrickgold.florisboard.ime.window

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

internal data class EditorGestureResult(
    val before: ImeWindowSpec,
    val calculated: ImeWindowSpec,
    val after: ImeWindowSpec,
)

internal inline fun <reified T : ImeWindowSpec> EditorGestureResult.assertAppliedGesture(
    crossinline check: (T, T) -> Unit,
) = assertSoftly {
    val typedBefore = before.shouldBeInstanceOf<T>()
    val typedAfter = after.shouldBeInstanceOf<T>()
    calculated.shouldBeInstanceOf<T>().shouldBe(typedAfter)
    check(typedBefore, typedAfter)
}

internal suspend fun runEditorGesture(
    rootInsets: ImeInsets.Root,
    config: ImeWindowConfig,
    scope: CoroutineScope,
    transform: (ImeWindowSpec) -> ImeWindowSpec,
): EditorGestureResult {
    val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
    val controller = ImeWindowController(prefs, scope)
    controller.updateRootInsets(rootInsets)
    controller.updateWindowConfig { config }

    controller.editor.beginMoveGesture()
    val before = controller.activeWindowSpec.first { it !== ImeWindowSpec.Fallback }
    val calculated = transform(before)
    controller.editor.onSpecUpdated(calculated)
    val after = controller.activeWindowSpec.value
    controller.editor.endMoveGesture(after)
    return EditorGestureResult(before, calculated, after)
}
