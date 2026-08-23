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

package org.florisboard.lib.snygg

import androidx.compose.ui.graphics.Color
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun parseAttributes(raw: String): SnyggAttributes =
    assertIs<SnyggElementRule>(SnyggRule.fromOrNull("key$raw")).attributes

class SnyggAttributesTest {
    @Test
    fun `all construction paths produce one canonical identity`() {
        val source = mutableListOf<Any>(10, 2, "01", 2)
        val expected = SnyggAttributes.of("code" to source, "mode" to listOf("x"))
        val variants = listOf(
            SnyggAttributes.of("mode" to listOf("x"), "code" to listOf(1, 2, 10)),
            SnyggAttributes.of("code" to listOf(2), "code" to listOf(10, 1), "mode" to listOf("x")),
            parseAttributes("[mode=`x`][code=2,10,`01`][code=2]"),
            SnyggAttributes.EMPTY.including("mode" to "x", "code" to 2, "code" to 10, "code" to "01"),
        )
        source.add(99)

        assertEquals("[code=1,10,2][mode=`x`]", expected.toString())
        assertEquals(listOf("code", "mode"), expected.keys.toList())
        assertEquals(listOf("1", "10", "2"), expected["code"])
        variants.forEach { actual ->
            assertEquals(expected, actual)
            assertEquals(expected.hashCode(), actual.hashCode())
            assertEquals(0, expected.compareTo(actual))
            assertEquals(0, actual.compareTo(expected))
            assertEquals(expected.toString(), actual.toString())
        }
    }

    @Test
    fun `hash ordering and serialization agree on rule identity`() {
        val rules = listOf(
            SnyggElementRule("key"),
            SnyggElementRule("key", SnyggAttributes.of("code" to listOf(1, 2))),
            SnyggElementRule("key", SnyggAttributes.of("code" to listOf(2, 1, 1))),
            SnyggElementRule("key", SnyggAttributes.of("code" to listOf(2, 3))),
            SnyggElementRule("key", SnyggAttributes.of("group" to listOf(1))),
            SnyggElementRule("key", selector = SnyggSelector.PRESSED),
        )

        rules.forEach { left ->
            rules.forEach { right ->
                assertEquals(left == right, left.compareTo(right) == 0)
                if (left.toString() == right.toString()) assertEquals(left, right)
            }
        }
        assertEquals(rules.toHashSet().size, rules.toSortedSet().size)
    }

    @Test
    fun `serialization keeps the established lexical value order`() {
        val cases = listOf(
            "[code=2,10]" to "[code=10,2]",
            "[code=2,1]" to "[code=1,2]",
            "[code=3,1,2]" to "[code=1..3]",
            "[code=-3,-1,-2]" to "[code=-1,-2,-3]",
            "[code=-201,-202,-203]" to "[code=-201,-202,-203]",
            "[code=-204,-205]" to "[code=-204,-205]",
            "[code=`str`,2,10]" to "[code=10,2,`str`]",
            "[code=`01`]" to "[code=1]",
        )
        cases.forEach { (raw, expected) ->
            assertEquals("key$expected", SnyggRule.fromOrNull("key$raw").toString())
        }
    }

    @Test
    fun `edits preserve canonical form and never retain caller storage`() {
        val source = mutableListOf<Any>(1, "02")
        val original = SnyggAttributes.of("code" to source)
        source.add(99)
        val included = original.including("code" to 2, "code" to 3, "code" to "03", "group" to 1)
        val restored = included.excluding("code" to "03", "group" to 1, "missing" to 4)

        assertEquals("[code=1,2]", original.toString())
        assertEquals("[code=1..3][group=1]", included.toString())
        assertEquals(original, restored)
        assertEquals(SnyggAttributes.EMPTY, SnyggAttributes.of("code" to listOf(1)).excluding("code" to "01"))
        assertFailsWith<IllegalArgumentException> { SnyggAttributes.of("bad_key" to listOf(1)) }
        assertFailsWith<IllegalArgumentException> { SnyggAttributes.of("code" to emptyList()) }
        assertFailsWith<IllegalArgumentException> { SnyggAttributes.of("code" to listOf("")) }
        assertFailsWith<IllegalArgumentException> { original.including("code" to "bad`value") }
    }

    @Test
    fun `parser rejects unsafe ranges without rejecting valid boundaries`() {
        val full = parseAttributes("[code=0..4095]")
        assertEquals(4_096, full["code"]?.size)
        assertEquals(full, full.including("code" to 0))
        assertFailsWith<IllegalArgumentException> { full.including("other" to 1) }
        assertNotNull(SnyggRule.fromOrNull("key[code=-2147483648..-2147483646]"))
        assertNotNull(SnyggRule.fromOrNull("key[code=2147483645..2147483647]"))
        assertEquals(listOf("a,b[].. c"), parseAttributes("[label=`a,b[].. c`]")["label"])

        listOf(
            "key[code=2..1]",
            "key[code=0..4096]",
            "key[code=0..4095][code=4096]",
            "key[code=0..4095][code=0]",
            "key[a=0..2047][b=0..2048]",
            "key[code=2147483648]",
            "key[code=-2147483649]",
        ).forEach { assertNull(SnyggRule.fromOrNull(it)) }
    }

    @Test
    fun `runtime matching normalizes values and requires every key`() {
        val attributes = SnyggAttributes.of("code" to listOf(1, 2), "mode" to listOf("active"))

        assertTrue(attributes.matches(mapOf("code" to "01", "mode" to "active", "extra" to true)))
        assertTrue(attributes.matches(mapOf("code" to 2, "mode" to "active")))
        assertFalse(attributes.matches(mapOf("code" to 3, "mode" to "active")))
        assertFalse(attributes.matches(mapOf("code" to 1)))
        assertFalse(attributes.matches(mapOf("code" to 1, "mode" to "bad`value")))
        assertTrue(SnyggAttributes.EMPTY.matches(emptyMap()))
    }

    @Test
    fun `equivalent editor rules replace cleanly and distinct sets survive JSON`() {
        val stylesheet = SnyggStylesheet.v2 {
            "key"("code" to listOf(2, 10, 2)) { "background" to rgbaColor(1, 0, 0) }
            "key"("code" to listOf(10, 2)) { "background" to rgbaColor(2, 0, 0) }
            "key"("code" to listOf(3)) { "background" to rgbaColor(3, 0, 0) }
        }
        val replacedRule = SnyggElementRule("key", SnyggAttributes.of("code" to listOf(2, 10)))
        val propertySet = assertIs<SnyggSinglePropertySet>(assertNotNull(stylesheet.rules[replacedRule]))

        assertEquals(2, stylesheet.rules.size)
        assertEquals(Color(2, 0, 0), assertIs<SnyggStaticColorValue>(propertySet.background).color)
        assertEquals(stylesheet, SnyggStylesheet.fromJson(stylesheet.toJson().getOrThrow()).getOrThrow())
    }
}
