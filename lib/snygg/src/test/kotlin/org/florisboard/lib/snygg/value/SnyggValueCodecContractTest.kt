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

package org.florisboard.lib.snygg.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SnyggValueCodecContractTest {
    @Test
    fun `custom font keeps backticks and rejects invalid values`() {
        val value = SnyggCustomFontFamilyValue("Fira Code_2")
        assertEquals("`Fira Code_2`", SnyggCustomFontFamilyValue.serialize(value).getOrThrow())
        assertEquals(value, SnyggCustomFontFamilyValue.deserialize("`Fira Code_2`").getOrThrow())
        assertTrue(SnyggCustomFontFamilyValue.deserialize("Fira Code_2").isFailure)
        assertIs<IllegalArgumentException>(
            SnyggCustomFontFamilyValue.serialize(SnyggDefinedVarValue("--font")).exceptionOrNull(),
        )
    }

    @Test
    fun `defined variable keeps function form and rejects invalid values`() {
        val value = SnyggDefinedVarValue("--surface-2")
        assertEquals("var(--surface-2)", SnyggDefinedVarValue.serialize(value).getOrThrow())
        assertEquals(value, SnyggDefinedVarValue.deserialize("var(--surface-2)").getOrThrow())
        assertTrue(SnyggDefinedVarValue.deserialize("var(surface-2)").isFailure)
        assertIs<IllegalArgumentException>(
            SnyggDefinedVarValue.serialize(SnyggCustomFontFamilyValue("Fira Code")).exceptionOrNull(),
        )
    }

    @Test
    fun `max lines preserves numbers none and validation failures`() {
        assertEquals("3", SnyggTextMaxLinesValue.serialize(SnyggTextMaxLinesValue(3)).getOrThrow())
        assertEquals(SnyggTextMaxLinesValue(3), SnyggTextMaxLinesValue.deserialize("3").getOrThrow())
        val unlimited = SnyggTextMaxLinesValue(Int.MAX_VALUE)
        assertEquals("none", SnyggTextMaxLinesValue.serialize(unlimited).getOrThrow())
        assertEquals(unlimited, SnyggTextMaxLinesValue.deserialize("none").getOrThrow())
        assertTrue(SnyggTextMaxLinesValue.deserialize("0").isFailure)
        assertTrue(SnyggTextMaxLinesValue.deserialize("-1").isFailure)
        assertEquals(
            "Failed requirement.",
            assertIs<IllegalArgumentException>(
                SnyggTextMaxLinesValue.serialize(SnyggTextMaxLinesValue(0)).exceptionOrNull(),
            ).message,
        )
        assertIs<IllegalArgumentException>(
            SnyggTextMaxLinesValue.serialize(SnyggDefinedVarValue("--lines")).exceptionOrNull(),
        )
    }

    @Test
    fun `uri keeps backticks and validates its parsed value`() {
        val value = SnyggUriValue("flex:/fonts/roboto%20regular.ttf")
        assertEquals("uri(`flex:/fonts/roboto%20regular.ttf`)", SnyggUriValue.serialize(value).getOrThrow())
        assertEquals(value, SnyggUriValue.deserialize("uri(`flex:/fonts/roboto%20regular.ttf`)").getOrThrow())
        assertTrue(SnyggUriValue.deserialize("uri(`https:/fonts/roboto.ttf`)").isFailure)
        assertIs<IllegalArgumentException>(
            SnyggUriValue.deserialize("uri(`flex:/bad%zz.ttf`)").exceptionOrNull(),
        )
        assertIs<IllegalArgumentException>(
            SnyggUriValue.serialize(SnyggDefinedVarValue("--image")).exceptionOrNull(),
        )
    }
}
