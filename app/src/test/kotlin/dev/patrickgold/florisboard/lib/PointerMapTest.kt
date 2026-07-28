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

package dev.patrickgold.florisboard.lib

import dev.patrickgold.florisboard.ime.text.gestures.SwipeGesture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class PointerMapTest : FunSpec({
    test("an active pointer id cannot be added twice") {
        val pointers = PointerMap(capacity = 2) { TestPointer() }
        val first = pointers.add(id = 17, index = 0)!!

        pointers.add(id = 17, index = 1).shouldBeNull()
        pointers.size shouldBe 1
        pointers.findById(17) shouldBe first
    }

    test("the unused pointer sentinel is never exposed as an active id") {
        val pointers = PointerMap(capacity = 1) { TestPointer() }

        pointers.findById(-1).shouldBeNull()
        pointers.add(id = -1, index = 0).shouldBeNull()
        pointers.removeById(-1) shouldBe false
        pointers.size shouldBe 0
    }

    test("a pointer id can be reused after its previous lifecycle ends") {
        val pointers = PointerMap(capacity = 2) { TestPointer() }
        val first = pointers.add(id = 17, index = 0)!!
        first.gestureUnits = 42
        pointers.removeById(17) shouldBe true
        first.gestureUnits = 7
        val second = pointers.add(id = 17, index = 0)!!

        second shouldBeSameInstanceAs first
        second.index shouldBe 0
        second.gestureUnits shouldBe 0
        pointers.size shouldBe 1
    }

    test("reused swipe pointers cannot leak movement units into the next gesture") {
        val pointers = PointerMap(capacity = 1) { SwipeGesture.Detector.GesturePointer() }
        val first = pointers.add(id = 0, index = 0)!!
        first.absUnitCountX = 5
        first.absUnitCountY = -3
        pointers.removeById(0) shouldBe true

        // This models the former ACTION_UP order, which wrote terminal units after removal.
        first.absUnitCountX = 9
        first.absUnitCountY = 7
        val second = pointers.add(id = 0, index = 0)!!

        second.absUnitCountX shouldBe 0
        second.absUnitCountY shouldBe 0
    }
})

private class TestPointer : Pointer() {
    var gestureUnits = 0

    override fun reset() {
        super.reset()
        gestureUnits = 0
    }
}
