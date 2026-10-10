package org.florisboard.lib.snygg

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.dynamicColorScheme
import org.florisboard.lib.snygg.value.SnyggCircleShapeValue
import org.florisboard.lib.snygg.value.SnyggDpSizeValue
import org.florisboard.lib.snygg.value.SnyggFontStyleValue
import org.florisboard.lib.snygg.value.SnyggFontWeightValue
import org.florisboard.lib.snygg.value.SnyggPaddingValue
import org.florisboard.lib.snygg.value.SnyggRectangleShapeValue
import org.florisboard.lib.snygg.value.SnyggSpSizeValue
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.florisboard.lib.snygg.value.SnyggTextMaxLinesValue
import org.florisboard.lib.snygg.value.SnyggUndefinedValue
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnyggThemeTest {
    val lightScheme = dynamicColorScheme(
        primary = Color.Yellow,
        isDark = false,
    ).copy(surfaceDim = Color.Cyan, surfaceBright = Color.Magenta)
    val darkScheme = dynamicColorScheme(
        primary = Color.Yellow,
        isDark = true,
    ).copy(surfaceDim = Color.Red, surfaceBright = Color.Green)

    private fun SnyggTheme.helperQuery(
        elementName: String,
        attributes: SnyggQueryAttributes = emptyMap(),
        selector: SnyggSelector = SnyggSelector.NONE,
        parentStyle: SnyggSinglePropertySet = SnyggSinglePropertySet(),
        fontSizeMultiplier: Float = 1.0f,
    ): SnyggSinglePropertySet = this.query(
        elementName,
        attributes,
        selector,
        parentStyle,
        dynamicLightColorScheme = lightScheme,
        dynamicDarkColorScheme = darkScheme,
        fontSizeMultiplier = fontSizeMultiplier,
    )

    @Test
    fun `basic theme compilation`() {
        val stylesheet = SnyggStylesheet.v2 {
            defines {
                "--primary" to dynamicDarkColor("primary")
                "--secondary" to dynamicLightColor("secondary")
                "--shape" to size(12.dp)
            }
            "keyboard" {
                background = dynamicLightColor("surfaceDim")
                foreground = `var`("--primary")
                borderColor = dynamicDarkColor("surfaceBright")
                shadowColor = dynamicLightColor("SurfaceDim")
                shape = `var`("--shape")
            }
            "key" {
                background = rgbaColor(255, 255, 255)
                foreground = `var`("--primary")
                borderColor = `var`("--secondary")
            }
            "key"(selector = SnyggSelector.PRESSED) {
                foreground = rgbaColor(255, 255, 255)
            }
            "key"(selector = SnyggSelector.FOCUS) {
                borderColor = `var`("--primary")
            }
            "key"(selector = SnyggSelector.DISABLED) {
                shadowElevation = size(2.dp)
                shadowColor = dynamicDarkColor("unknown-role")
            }
        }
        val theme = SnyggTheme.compileFrom(stylesheet)

        val keyboard = theme.helperQuery("keyboard")
        assertEquals(Color.Cyan, assertIs<SnyggStaticColorValue>(keyboard.background).color)
        assertEquals(Color.Green, assertIs<SnyggStaticColorValue>(keyboard.borderColor).color)
        assertEquals(lightScheme.primary, assertIs<SnyggStaticColorValue>(keyboard.shadowColor).color)

        val key = theme.helperQuery("key")
        val keyBackground = assertIs<SnyggStaticColorValue>(key.background)
        assertEquals(Color.White, keyBackground.color)
        val keyForeground = assertIs<SnyggStaticColorValue>(key.foreground)
        assertEquals(darkScheme.primary, keyForeground.color)
        val keyBorderColor = assertIs<SnyggStaticColorValue>(key.borderColor)
        assertEquals(lightScheme.secondary, keyBorderColor.color)

        val keyPressed = theme.helperQuery("key", selector = SnyggSelector.PRESSED)
        assertEquals(keyBackground, keyPressed.background)
        assertNotEquals(keyForeground, keyPressed.foreground)
        assertEquals(keyBorderColor, keyPressed.borderColor)
        val keyPressedForeground = assertIs<SnyggStaticColorValue>(keyPressed.foreground)
        assertEquals(Color(255, 255, 255), keyPressedForeground.color)

        val keyFocus = theme.helperQuery("key", selector = SnyggSelector.FOCUS)
        assertEquals(keyBackground, keyFocus.background)
        assertEquals(keyForeground, keyFocus.foreground)
        assertNotEquals(keyBorderColor, keyFocus.borderColor)
        val keyFocusBorderColor = assertIs<SnyggStaticColorValue>(keyFocus.borderColor)
        assertEquals(darkScheme.primary, keyFocusBorderColor.color)

        val keyDisabled = theme.helperQuery("key", selector = SnyggSelector.DISABLED)
        assertEquals(keyBackground, keyDisabled.background)
        assertEquals(keyForeground, keyDisabled.foreground)
        assertEquals(keyBorderColor, keyDisabled.borderColor)
        val keyDisabledShadowElevation = assertIs<SnyggDpSizeValue>(keyDisabled.shadowElevation)
        assertEquals(2.dp, keyDisabledShadowElevation.dp)
        assertEquals(darkScheme.primary, assertIs<SnyggStaticColorValue>(keyDisabled.shadowColor).color)
    }

    @Test
    fun `empty theme compilation`() {
        val stylesheet = SnyggStylesheet.v2 {
            // empty
        }
        val theme = SnyggTheme.compileFrom(stylesheet)

        val key = theme.helperQuery("key")
        assertTrue { key.properties.isEmpty() }
    }

    @Test
    fun `theme with attributes`() {
        val stylesheet = SnyggStylesheet.v2 {
            "key" {
                background = rgbaColor(0, 0, 0)
            }
            "key"("code" to listOf(1)) {
                foreground = rgbaColor(255, 255, 255)
            }
            "key"("group" to listOf(2)) {
                shape = rectangleShape()
            }
            "key"("code" to listOf(1), "group" to listOf(2)) {
                shape = circleShape()
            }
        }
        val theme = SnyggTheme.compileFrom(stylesheet)

        val key = theme.helperQuery("key")
        assertIs<SnyggStaticColorValue>(key.background)
        assertIs<SnyggUndefinedValue>(key.foreground)
        assertIs<SnyggUndefinedValue>(key.shape)

        val keyCode0 = theme.helperQuery("key", attributes = mapOf("code" to 0))
        assertEquals(key, keyCode0)

        val keyCode1 = theme.helperQuery("key", attributes = mapOf("code" to 1))
        assertIs<SnyggStaticColorValue>(keyCode1.background)
        assertIs<SnyggStaticColorValue>(keyCode1.foreground)
        assertIs<SnyggUndefinedValue>(keyCode1.shape)

        val keyGroup2 = theme.helperQuery("key", attributes = mapOf("group" to 2))
        assertIs<SnyggStaticColorValue>(keyGroup2.background)
        assertIs<SnyggUndefinedValue>(keyGroup2.foreground)
        assertIs<SnyggRectangleShapeValue>(keyGroup2.shape)

        val keyCode1Group2 = theme.helperQuery("key", attributes = mapOf("code" to 1, "group" to 2))
        assertIs<SnyggStaticColorValue>(keyCode1Group2.background)
        assertIs<SnyggStaticColorValue>(keyCode1Group2.foreground)
        assertIs<SnyggCircleShapeValue>(keyCode1Group2.shape)
    }

    @Test
    fun `numeric serialization leaves attribute cascade order unchanged`() {
        val stylesheet = SnyggStylesheet.v2 {
            "key"("code" to listOf(-204, -205)) { background = rgbaColor(255, 0, 0) }
            "key"("code" to listOf(-205)) { background = rgbaColor(0, 0, 255) }
        }

        val key = SnyggTheme.compileFrom(stylesheet).helperQuery("key", attributes = mapOf("code" to -205))

        assertEquals(Color.Blue, assertIs<SnyggStaticColorValue>(key.background).color)
    }

    @Test
    fun `theme with broken vars`() {
        val stylesheet = SnyggStylesheet.v2 {
            "key" {
                background = `var`("--not-existing")
            }
        }
        val theme = SnyggTheme.compileFrom(stylesheet)

        val key = theme.helperQuery("key")
        assertIs<SnyggUndefinedValue>(key.background)
    }

    @Test
    fun `undefined selector value removes an earlier property`() {
        val stylesheet = SnyggStylesheet.v2 {
            "key" {
                background = rgbaColor(255, 0, 0)
                foreground = rgbaColor(0, 0, 255)
            }
            "key"(selector = SnyggSelector.FOCUS) {
                background = `var`("--not-existing")
            }
        }
        val focused = SnyggTheme.compileFrom(stylesheet).helperQuery("key", selector = SnyggSelector.FOCUS)

        assertIs<SnyggUndefinedValue>(focused.background)
        assertEquals(Color.Blue, assertIs<SnyggStaticColorValue>(focused.foreground).color)
    }

    @Test
    fun `theme with font with zero source sets should not crash`() {
        val stylesheet = SnyggStylesheet.v2 {
            font("Comic Sans") {
                // empty
            }
            "key" {
                textMaxLines = textMaxLines(3)
            }
        }
        val theme = SnyggTheme.compileFrom(stylesheet)

        val key = theme.helperQuery("key")
        val maxLines = assertIs<SnyggTextMaxLinesValue>(key.textMaxLines)
        assertEquals(3, maxLines.maxLines)
    }

    private val inheritanceStylesheet = SnyggStylesheet.v2 {
        "parent" {
            background = rgbaColor(255, 0, 0)
            foreground = rgbaColor(0, 0, 255)
            borderColor = rgbaColor(255, 255, 255)
            borderWidth = size(2.dp)
            fontSize = fontSize(12.sp)
            fontStyle = fontStyle(FontStyle.Italic)
            fontWeight = fontWeight(FontWeight.Bold)
            margin = padding(0.dp)
            padding = padding(4.dp, 2.dp)
            shadowColor = rgbaColor(255, 255, 255)
            shadowElevation = size(2.dp)
            shape = circleShape()
        }
        listOf("middle-inherits-implicitly", "child-inherits-implicitly").forEach { name ->
            name { }
        }
        listOf(
            "middle-inherits-explicitly",
            "child-inherits-explicitly",
            "child-inherits-without-middle",
        ).forEach { name ->
            name {
                background = inherit()
                foreground = inherit()
                borderColor = inherit()
                borderWidth = inherit()
                fontSize = inherit()
                fontStyle = inherit()
                fontWeight = inherit()
                margin = inherit()
                padding = inherit()
                shadowColor = inherit()
                shadowElevation = inherit()
                shape = inherit()
            }
        }
    }

    private fun assertImplicitInheritance(style: SnyggSinglePropertySet, scenario: String) = with(style) {
        assertIs<SnyggUndefinedValue>(background, "$scenario background")
        assertIs<SnyggStaticColorValue>(foreground, "$scenario foreground")
        assertIs<SnyggUndefinedValue>(borderColor, "$scenario borderColor")
        assertIs<SnyggUndefinedValue>(borderWidth, "$scenario borderWidth")
        assertIs<SnyggSpSizeValue>(fontSize, "$scenario fontSize")
        assertIs<SnyggFontStyleValue>(fontStyle, "$scenario fontStyle")
        assertIs<SnyggFontWeightValue>(fontWeight, "$scenario fontWeight")
        assertIs<SnyggUndefinedValue>(margin, "$scenario margin")
        assertIs<SnyggUndefinedValue>(padding, "$scenario padding")
        assertIs<SnyggUndefinedValue>(shadowColor, "$scenario shadowColor")
        assertIs<SnyggUndefinedValue>(shadowElevation, "$scenario shadowElevation")
        assertIs<SnyggUndefinedValue>(shape, "$scenario shape")
    }

    private fun assertExplicitInheritance(style: SnyggSinglePropertySet, scenario: String) = with(style) {
        assertEquals(Color.Red, assertIs<SnyggStaticColorValue>(background, "$scenario background").color, scenario)
        assertIs<SnyggStaticColorValue>(foreground, "$scenario foreground")
        assertIs<SnyggStaticColorValue>(borderColor, "$scenario borderColor")
        assertIs<SnyggDpSizeValue>(borderWidth, "$scenario borderWidth")
        assertEquals(12.sp, assertIs<SnyggSpSizeValue>(fontSize, "$scenario fontSize").sp, scenario)
        assertIs<SnyggFontStyleValue>(fontStyle, "$scenario fontStyle")
        assertIs<SnyggFontWeightValue>(fontWeight, "$scenario fontWeight")
        assertIs<SnyggPaddingValue>(margin, "$scenario margin")
        assertIs<SnyggPaddingValue>(padding, "$scenario padding")
        assertIs<SnyggStaticColorValue>(shadowColor, "$scenario shadowColor")
        assertIs<SnyggDpSizeValue>(shadowElevation, "$scenario shadowElevation")
        assertIs<SnyggCircleShapeValue>(shape, "$scenario shape")
    }

    @Nested
    inner class InheritTests {
        @Test
        fun `single-level inherit behavior`() {
            val theme = SnyggTheme.compileFrom(inheritanceStylesheet)
            val parentStyle = theme.helperQuery("parent")

            val childImplicit = theme.helperQuery("child-inherits-implicitly", parentStyle = parentStyle)
            assertImplicitInheritance(childImplicit, "direct implicit")

            val childExplicit = theme.helperQuery("child-inherits-explicitly", parentStyle = parentStyle)
            assertExplicitInheritance(childExplicit, "direct explicit")
        }

        @Test
        fun `multi-level inherit behavior`() {
            val theme = SnyggTheme.compileFrom(inheritanceStylesheet)
            val parentStyle = theme.helperQuery("parent")
            val middleOneInheritsImplicitly = theme.helperQuery("middle-inherits-implicitly", parentStyle = parentStyle)
            val childImplicit = theme.helperQuery(
                "child-inherits-implicitly",
                parentStyle = middleOneInheritsImplicitly,
            )
            assertImplicitInheritance(childImplicit, "two-level implicit")

            val middleOneInheritsExplicitly = theme.helperQuery("middle-inherits-explicitly", parentStyle = parentStyle)
            val childExplicit = theme.helperQuery(
                "child-inherits-explicitly",
                parentStyle = middleOneInheritsExplicitly,
            )
            assertExplicitInheritance(childExplicit, "two-level explicit")

            val middleOneWithoutDefault = theme.helperQuery("middle-without-middle", parentStyle = parentStyle)
            val childImplicitWithoutDefault = theme.helperQuery(
                "child-inherits-without-middle",
                parentStyle = middleOneWithoutDefault,
            )
            assertImplicitInheritance(childImplicitWithoutDefault, "absent intermediate")
        }

        @Test
        fun `child only selector declared inherit behavior`() {
            val stylesheet = SnyggStylesheet.v2 {
                "parent" {
                    background = rgbaColor(30, 0, 0, 0f)
                    fontSize = fontSize(7.sp)
                }
                "child"(selector = SnyggSelector.FOCUS) {
                    background = rgbaColor(42, 0, 0, 0f)
                }
            }
            val theme = SnyggTheme.compileFrom(stylesheet)

            val parentStyle = theme.helperQuery("parent")
            val intermediateImplicitStyle = theme.helperQuery(
                elementName = "",
                parentStyle = parentStyle,
            )
            val childFocusStyle = theme.helperQuery(
                elementName = "child",
                parentStyle = intermediateImplicitStyle,
            )
            assertIs<SnyggUndefinedValue>(childFocusStyle.background)
            val fontSize = assertIs<SnyggSpSizeValue>(childFocusStyle.fontSize)
            assertEquals(7.sp, fontSize.sp)
        }
    }

    @Nested
    inner class FontSizeMultiplierTests {
        @Test
        fun `multiplier in single-level inheritance`() {
            val theme = SnyggTheme.compileFrom(inheritanceStylesheet)
            val fontSizeMultiplier = 0.75f

            val parentStyle = theme.helperQuery("parent", fontSizeMultiplier = fontSizeMultiplier)

            val childImplicit = theme.helperQuery(
                "child-inherits-implicitly",
                parentStyle = parentStyle,
                fontSizeMultiplier = fontSizeMultiplier,
            )
            val implicitFontSize = assertIs<SnyggSpSizeValue>(childImplicit.fontSize)
            assertEquals(9.sp, implicitFontSize.sp)

            val childExplicit = theme.helperQuery(
                "child-inherits-explicitly",
                parentStyle = parentStyle,
                fontSizeMultiplier = fontSizeMultiplier,
            )
            val explicitFontSize = assertIs<SnyggSpSizeValue>(childExplicit.fontSize)
            assertEquals(9.sp, explicitFontSize.sp)
        }

        @Test
        fun `multiplier in multi-level inheritance`() {
            val theme = SnyggTheme.compileFrom(inheritanceStylesheet)
            val fontSizeMultiplier = 0.75f

            val parentStyle = theme.helperQuery("parent", fontSizeMultiplier = fontSizeMultiplier)
            val middleOneInheritsImplicitly = theme.helperQuery(
                "middle-inherits-implicitly",
                parentStyle = parentStyle,
                fontSizeMultiplier = fontSizeMultiplier,
            )
            val childImplicit = theme.helperQuery(
                "child-inherits-implicitly",
                parentStyle = middleOneInheritsImplicitly,
                fontSizeMultiplier = fontSizeMultiplier,
            )
            val implicitFontSize = assertIs<SnyggSpSizeValue>(childImplicit.fontSize)
            assertEquals(9.sp, implicitFontSize.sp)

            val middleOneInheritsExplicitly = theme.helperQuery(
                "middle-inherits-explicitly",
                parentStyle = parentStyle,
                fontSizeMultiplier = fontSizeMultiplier,
            )
            val childExplicit = theme.helperQuery(
                "child-inherits-explicitly",
                parentStyle = middleOneInheritsExplicitly,
                fontSizeMultiplier = fontSizeMultiplier,
            )
            val explicitFontSize = assertIs<SnyggSpSizeValue>(childExplicit.fontSize)
            assertEquals(9.sp, explicitFontSize.sp)

            val middleOneWithoutDefault = theme.helperQuery(
                "middle-without-middle",
                parentStyle = parentStyle,
                fontSizeMultiplier = fontSizeMultiplier,
            )
            val childImplicitWithoutDefault = theme.helperQuery(
                "child-inherits-without-middle",
                parentStyle = middleOneWithoutDefault,
                fontSizeMultiplier = fontSizeMultiplier,
            )
            val explicitWithoutDefaultFontSize = assertIs<SnyggSpSizeValue>(childImplicitWithoutDefault.fontSize)
            assertEquals(9.sp, explicitWithoutDefaultFontSize.sp)
        }
    }
}
