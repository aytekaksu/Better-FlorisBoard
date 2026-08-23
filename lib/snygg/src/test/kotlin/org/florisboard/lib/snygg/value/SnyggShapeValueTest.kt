/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
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

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class ShapeCodecCase(
    val name: String,
    val encoder: SnyggValueEncoder,
    val defaultValue: SnyggValue,
    val value: SnyggValue,
    val serialized: String,
)

private val SHAPE_CODEC_CASES = listOf(
    ShapeCodecCase(
        "rectangle",
        SnyggRectangleShapeValue,
        SnyggRectangleShapeValue(),
        SnyggRectangleShapeValue(),
        "rectangle()",
    ),
    ShapeCodecCase("circle", SnyggCircleShapeValue, SnyggCircleShapeValue(), SnyggCircleShapeValue(), "circle()"),
    ShapeCodecCase(
        "cut corner dp",
        SnyggCutCornerDpShapeValue,
        SnyggCutCornerDpShapeValue(0.dp, 0.dp, 0.dp, 0.dp),
        SnyggCutCornerDpShapeValue(0.dp, 0.5.dp, 12.dp, 4.25.dp),
        "cut-corner(0dp,0.5dp,12dp,4.25dp)",
    ),
    ShapeCodecCase(
        "cut corner percent",
        SnyggCutCornerPercentShapeValue,
        SnyggCutCornerPercentShapeValue(0, 0, 0, 0),
        SnyggCutCornerPercentShapeValue(0, 1, 99, 100),
        "cut-corner(0%,1%,99%,100%)",
    ),
    ShapeCodecCase(
        "rounded corner dp",
        SnyggRoundedCornerDpShapeValue,
        SnyggRoundedCornerDpShapeValue(0.dp, 0.dp, 0.dp, 0.dp),
        SnyggRoundedCornerDpShapeValue(4.25.dp, 12.dp, 0.5.dp, 0.dp),
        "rounded-corner(4.25dp,12dp,0.5dp,0dp)",
    ),
    ShapeCodecCase(
        "rounded corner percent",
        SnyggRoundedCornerPercentShapeValue,
        SnyggRoundedCornerPercentShapeValue(0, 0, 0, 0),
        SnyggRoundedCornerPercentShapeValue(100, 99, 1, 0),
        "rounded-corner(100%,99%,1%,0%)",
    ),
)

class SnyggShapeValueTest {
    @Test
    fun `number specs retain concrete types and custom syntax`() {
        val intSpec: SnyggIntValueSpec = SnyggValueSpecBuilder.Instance.int(
            id = "value",
            prefix = "pre-",
            suffix = "-post",
            unit = "px",
            numberPattern = """[0-9]{2}""".toRegex(),
        )
        val floatSpec: SnyggFloatValueSpec = SnyggValueSpecBuilder.Instance.float(
            id = "value",
            prefix = "pre-",
            suffix = "-post",
            unit = "dp",
            numberPattern = """[0-9]+[.][0-9]{2}""".toRegex(),
        )

        listOf(
            Triple(intSpec, "pre-01-postpx", "01"),
            Triple(floatSpec, "pre-01.25-postdp", "01.25"),
        ).forEach { (spec, serialized, value) ->
            val arguments = snyggIdToValueMapOf()
            spec.parse(serialized, arguments)

            assertEquals(value, arguments.getValue("value"))
            assertEquals(serialized, spec.pack(arguments))
        }
    }

    @Test
    fun `shape codecs retain defaults encoders and wire forms`() {
        SHAPE_CODEC_CASES.forEach { case ->
            assertEquals(case.defaultValue, case.encoder.defaultValue(), "${case.name} default")
            assertSame(case.encoder, case.value.encoder(), "${case.name} encoder")
            assertEquals(case.serialized, case.encoder.serialize(case.value).getOrThrow(), "${case.name} serialization")
            assertEquals(
                case.value,
                case.encoder.deserialize(case.serialized).getOrThrow(),
                "${case.name} deserialization",
            )
        }
    }

    @Test
    fun `shape codecs reject sibling types and wire forms`() {
        SHAPE_CODEC_CASES.forEach { target ->
            SHAPE_CODEC_CASES.filterNot { it.encoder === target.encoder }.forEach { sibling ->
                assertTrue(target.encoder.serialize(sibling.value).isFailure, "${target.name} accepted ${sibling.name}")
                assertTrue(
                    target.encoder.deserialize(sibling.serialized).isFailure,
                    "${target.name} decoded ${sibling.name}",
                )
            }
        }
    }

    @Test
    fun `dp corners retain accepted whitespace and decimal forms`() {
        val decoded = SnyggCutCornerDpShapeValue.deserialize(" cut-corner( 0.0dp , 1.dp , 2dp , 3dp ) ").getOrThrow()

        assertEquals(SnyggCutCornerDpShapeValue(0.dp, 1.dp, 2.dp, 3.dp), decoded)
    }

    @Test
    fun `corner serialization rejects values outside the grammar`() {
        val invalidDp = SnyggCutCornerDpShapeValue(0.dp, 0.dp, 0.dp, 0.dp).copy(topStart = (-1).dp)
        val invalidPercent = SnyggRoundedCornerPercentShapeValue(0, 0, 0, 0).copy(topStart = 101)

        assertTrue(
            SnyggCutCornerDpShapeValue.serialize(invalidDp).isFailure,
        )
        assertTrue(
            SnyggRoundedCornerPercentShapeValue.serialize(invalidPercent).isFailure,
        )
    }

    @Test
    fun `corner grammar retains dp and percentage bounds`() {
        listOf(
            SnyggCutCornerDpShapeValue to "cut-corner(-1dp,0dp,0dp,0dp)",
            SnyggRoundedCornerDpShapeValue to "rounded-corner(.5dp,0dp,0dp,0dp)",
            SnyggCutCornerPercentShapeValue to "cut-corner(101%,0%,0%,0%)",
            SnyggRoundedCornerPercentShapeValue to "rounded-corner(1.5%,0%,0%,0%)",
        ).forEach { (encoder, serialized) ->
            assertTrue(encoder.deserialize(serialized).isFailure, serialized)
        }
    }
}
