package org.florisboard.lib.snygg

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.florisboard.lib.snygg.value.SnyggCircleShapeValue
import org.florisboard.lib.snygg.value.SnyggContentScaleValue
import org.florisboard.lib.snygg.value.SnyggDpSizeValue
import org.florisboard.lib.snygg.value.SnyggSpSizeValue
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.florisboard.lib.snygg.value.SnyggTextDecorationLineValue
import org.florisboard.lib.snygg.value.SnyggTextMaxLinesValue
import org.florisboard.lib.snygg.value.SnyggYesValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SnyggSinglePropertySetAccessorsTest {
    @Test
    fun `typed accessors return payloads and fall back for missing or wrong types`() {
        val populated = SnyggSinglePropertySet(mapOf(
            Snygg.Background to SnyggStaticColorValue(Color.Red),
            Snygg.ContentScale to SnyggContentScaleValue(ContentScale.Fit),
            Snygg.FontSize to SnyggSpSizeValue(18.sp),
            Snygg.ShadowElevation to SnyggDpSizeValue(4.dp),
            Snygg.TextDecorationLine to SnyggTextDecorationLineValue(TextDecoration.Underline),
            Snygg.TextMaxLines to SnyggTextMaxLinesValue(3),
            Snygg.Shape to SnyggCircleShapeValue(),
        ))
        assertEquals(Color.Red, populated.background())
        assertEquals(ContentScale.Fit, populated.contentScale())
        assertEquals(18.sp, populated.fontSize())
        assertEquals(4.dp, populated.shadowElevation())
        assertEquals(TextDecoration.Underline, populated.textDecorationLine())
        assertEquals(3, populated.textMaxLines())
        assertEquals(CircleShape, populated.shape())

        val missing = SnyggSinglePropertySet()
        assertEquals(Color.Unspecified, missing.background())
        assertEquals(ContentScale.Crop, missing.contentScale())
        assertEquals(TextUnit.Unspecified, missing.fontSize())
        assertEquals(Dp.Unspecified, missing.shadowElevation())
        assertEquals(null, missing.textDecorationLine())
        assertEquals(Int.MAX_VALUE, missing.textMaxLines())
        assertEquals(RectangleShape, missing.shape())

        val wrongType = SnyggSinglePropertySet(populated.properties.keys.associateWith { SnyggYesValue })
        listOf(missing, wrongType).forEach { set ->
            assertEquals(Color.Blue, set.background(Color.Blue))
            assertEquals(ContentScale.FillBounds, set.contentScale(ContentScale.FillBounds))
            assertEquals(30.sp, set.fontSize(30.sp))
            assertEquals(6.dp, set.shadowElevation(6.dp))
            assertEquals(TextDecoration.LineThrough, set.textDecorationLine(TextDecoration.LineThrough))
            assertEquals(7, set.textMaxLines(7))
            assertEquals(RectangleShape, set.shape())
        }
    }
}
