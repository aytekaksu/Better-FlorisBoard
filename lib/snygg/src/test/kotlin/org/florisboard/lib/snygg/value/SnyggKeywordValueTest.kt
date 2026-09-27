package org.florisboard.lib.snygg.value

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnyggKeywordValueTest {
    @Test
    fun `keyword values keep their codec and singleton identity`() {
        for ((value, keyword) in listOf(
            SnyggInheritValue to "inherit",
            SnyggYesValue to "yes",
            SnyggNoValue to "no",
        )) {
            assertSame(value, value.defaultValue())
            assertSame(value, value.encoder())
            assertEquals(keyword, value.serialize(value).getOrThrow())
            // Keep the existing behavior: these encoders do not inspect the input value.
            assertEquals(keyword, value.serialize(SnyggUndefinedValue).getOrThrow())
            assertSame(value, value.deserialize(" $keyword ").getOrThrow())
            assertTrue(value.deserialize("other").isFailure)
        }
    }

    @Test
    fun `undefined remains an editor placeholder and rejects codec operations`() {
        assertSame(SnyggUndefinedValue, SnyggUndefinedValue.defaultValue())
        assertSame(SnyggUndefinedValue, SnyggUndefinedValue.encoder())
        assertTrue(SnyggUndefinedValue.serialize(SnyggUndefinedValue).isFailure)
        assertTrue(SnyggUndefinedValue.deserialize("undefined").isFailure)
    }
}
