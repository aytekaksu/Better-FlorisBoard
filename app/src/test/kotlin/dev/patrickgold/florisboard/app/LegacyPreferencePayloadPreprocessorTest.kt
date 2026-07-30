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

package dev.patrickgold.florisboard.app

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.patrickgold.florisboard.ime.window.ImeFormFactor
import dev.patrickgold.florisboard.ime.window.ImeInsets
import dev.patrickgold.florisboard.ime.window.ImeWindowConfig
import dev.patrickgold.florisboard.ime.window.ImeWindowConfigByType
import dev.patrickgold.florisboard.ime.window.ImeWindowConstraints
import dev.patrickgold.florisboard.ime.window.ImeWindowMode
import dev.patrickgold.florisboard.ime.window.ImeWindowProps
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class LegacyPreferencePayloadPreprocessorTest :
    FunSpec({
        test("unrelated and canonical payloads retain their exact bytes") {
            val unrelated = "b;correction__auto_capitalization;false\r\ninvalid\r\n"
            LegacyPreferencePayloadPreprocessor.process(unrelated) shouldBe unrelated

            val canonical = encodedPreferences(
                """x;keyboard__window_config;malformed""",
                """i;keyboard__height_factor_portrait;125""",
            )
            LegacyPreferencePayloadPreprocessor.process(canonical, sourceVersionCode = 104) shouldBe canonical
        }

        test("expected-type duplicates use the last physical value") {
            val invalidLast = encodedPreferences(
                """i;keyboard__height_factor_portrait;125""",
                """i;keyboard__height_factor_portrait;invalid""",
            )
            LegacyPreferencePayloadPreprocessor.process(invalidLast, sourceVersionCode = 104) shouldBe invalidLast

            val wrongTypeLast = encodedPreferences(
                """i;keyboard__height_factor_portrait;125""",
                """s;keyboard__height_factor_portrait;"75"""",
            )
            val config = migratedConfig(wrongTypeLast, sourceVersionCode = 104)
            val constraints = fixedConstraints(
                ImeFormFactor.Type.PHONE_PORTRAIT,
                ImeWindowMode.Fixed.NORMAL,
            )
            config.getValue(ImeFormFactor.Type.PHONE_PORTRAIT)
                .fixedProps
                .getValue(ImeWindowMode.Fixed.NORMAL)
                .keyboardHeight shouldBe constraints.defKeyboardHeight * 1.25f
        }

        test("combined one-handed settings preserve effective portrait geometry") {
            val processed = LegacyPreferencePayloadPreprocessor.process(
                payload = encodedPreferences(
                    """i;keyboard__height_factor_portrait;120""",
                    """i;keyboard__bottom_offset_portrait;12""",
                    """s;keyboard__one_handed_mode;"start"""",
                    """i;keyboard__one_handed_mode_scale_factor;80""",
                ),
                sourceVersionCode = 104,
            )
            val config = decodeWindowConfig(processed)
            config.keys shouldContainExactlyInAnyOrder setOf(
                ImeFormFactor.Type.PHONE_PORTRAIT,
                ImeFormFactor.Type.TABLET_PORTRAIT,
            )

            config.forEach { (type, windowConfig) ->
                val rootInsets = baselineRootInsets(type)
                val normalConstraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.NORMAL)
                val compactConstraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.COMPACT)
                val normal = windowConfig.fixedProps.getValue(ImeWindowMode.Fixed.NORMAL)
                val compact = windowConfig.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT)
                val expectedNormal = ImeWindowProps.Fixed(
                    keyboardHeight = normalConstraints.defKeyboardHeight * 1.2f,
                    paddingLeft = 0.dp,
                    paddingRight = 0.dp,
                    paddingBottom = 12.dp,
                ).constrained(normalConstraints)
                val expectedCompact = ImeWindowProps.Fixed(
                    keyboardHeight = expectedNormal.keyboardHeight * 0.8f,
                    paddingLeft = 0.dp,
                    paddingRight = rootInsets.boundsDp.width * (1f - 0.8f),
                    paddingBottom = 12.dp,
                ).constrained(compactConstraints)

                windowConfig.fixedMode shouldBe ImeWindowMode.Fixed.COMPACT
                normal shouldBe expectedNormal
                compact shouldBe expectedCompact
            }
        }

        test("split and unknown schemas do not accidentally enable one-handed mode") {
            val split = migratedConfig(
                encodedPreferences(
                    """s;keyboard__one_handed_mode;"END"""",
                    """i;keyboard__one_handed_mode_scale_factor;70""",
                ),
                sourceVersionCode = 105,
            )
            split.getValue(ImeFormFactor.Type.PHONE_PORTRAIT).fixedMode shouldBe ImeWindowMode.Fixed.NORMAL

            val unknown = migratedConfig(
                encodedPreferences(
                    """s;keyboard__one_handed_mode;"START"""",
                    """i;keyboard__one_handed_mode_scale_factor;90""",
                ),
            )
            val unknownPhone = unknown.getValue(ImeFormFactor.Type.PHONE_PORTRAIT)
            unknownPhone.fixedMode shouldBe ImeWindowMode.Fixed.NORMAL
            val compact = unknownPhone.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT)
            val rootInsets = baselineRootInsets(ImeFormFactor.Type.PHONE_PORTRAIT)
            compact shouldBe ImeWindowProps.Fixed(
                keyboardHeight = fixedConstraints(
                    ImeFormFactor.Type.PHONE_PORTRAIT,
                    ImeWindowMode.Fixed.NORMAL,
                ).defKeyboardHeight * 0.9f,
                paddingLeft = 0.dp,
                paddingRight = rootInsets.boundsDp.width * (1f - 0.9f),
                paddingBottom = 0.dp,
            ).constrained(ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.COMPACT))
        }

        test("version hints distinguish combined and split stores") {
            val combined = migratedConfig(
                encodedPreferences(
                    """s;internal__version_last_use;"0.4.6"""",
                    """s;keyboard__one_handed_mode;"END"""",
                ),
            )
            combined.getValue(ImeFormFactor.Type.PHONE_PORTRAIT).fixedMode shouldBe ImeWindowMode.Fixed.COMPACT

            val split = migratedConfig(
                encodedPreferences(
                    """s;internal__version_last_use;"0.5.2"""",
                    """s;keyboard__one_handed_mode;"END"""",
                ),
            )
            split.getValue(ImeFormFactor.Type.PHONE_PORTRAIT).fixedMode shouldBe ImeWindowMode.Fixed.NORMAL

            val current = encodedPreferences(
                """s;internal__version_last_use;"0.6.0-alpha02"""",
                """i;keyboard__height_factor_portrait;125""",
            )
            LegacyPreferencePayloadPreprocessor.process(current) shouldBe current
        }

        test("version boundaries and semantic names select the intended schema") {
            val combined = encodedPreferences("""s;keyboard__one_handed_mode;"END"""")
            migratedConfig(combined, sourceVersionCode = 104)
                .getValue(ImeFormFactor.Type.PHONE_PORTRAIT)
                .fixedMode shouldBe ImeWindowMode.Fixed.COMPACT
            migratedConfig(combined, sourceVersionCode = 105)
                .getValue(ImeFormFactor.Type.PHONE_PORTRAIT)
                .fixedMode shouldBe ImeWindowMode.Fixed.NORMAL
            migratedConfig(combined, sourceVersionCode = 117)
                .getValue(ImeFormFactor.Type.PHONE_PORTRAIT)
                .fixedMode shouldBe ImeWindowMode.Fixed.NORMAL
            LegacyPreferencePayloadPreprocessor.process(
                payload = combined,
                sourceVersionCode = 118,
            ) shouldBe combined

            migratedConfig(
                payload = combined,
                sourceVersionCode = 118,
                sourceVersionName = "0.5.2",
            ).getValue(ImeFormFactor.Type.PHONE_PORTRAIT)
                .fixedMode shouldBe ImeWindowMode.Fixed.NORMAL
        }

        test("split stores recover combined intent only with an old install marker") {
            val recovered = migratedConfig(
                encodedPreferences(
                    """s;internal__version_on_install;"0.4.6"""",
                    """s;internal__version_last_use;"0.5.2"""",
                    """s;keyboard__one_handed_mode;"START"""",
                ),
            )
            recovered.getValue(ImeFormFactor.Type.PHONE_PORTRAIT).fixedMode shouldBe
                ImeWindowMode.Fixed.COMPACT

            val nativeSplit = migratedConfig(
                encodedPreferences(
                    """s;internal__version_on_install;"0.5.0"""",
                    """s;internal__version_last_use;"0.5.2"""",
                    """s;keyboard__one_handed_mode;"START"""",
                ),
            )
            nativeSplit.getValue(ImeFormFactor.Type.PHONE_PORTRAIT).fixedMode shouldBe
                ImeWindowMode.Fixed.NORMAL
        }

        test("landscape settings cover five legacy form factors but never desktop") {
            val config = migratedConfig(
                encodedPreferences(
                    """i;keyboard__height_factor_portrait;999""",
                    """i;keyboard__bottom_offset_portrait;999""",
                    """i;keyboard__height_factor_landscape;999""",
                    """i;keyboard__bottom_offset_landscape;999""",
                ),
                sourceVersionCode = 104,
            )
            config.keys shouldContainExactlyInAnyOrder setOf(
                ImeFormFactor.Type.PHONE_PORTRAIT,
                ImeFormFactor.Type.TABLET_PORTRAIT,
                ImeFormFactor.Type.PHONE_LANDSCAPE,
                ImeFormFactor.Type.TABLET_LANDSCAPE,
                ImeFormFactor.Type.LARGE_TABLET,
            )

            config.forEach { (type, windowConfig) ->
                val constraints = fixedConstraints(type, ImeWindowMode.Fixed.NORMAL)
                val normal = windowConfig.fixedProps.getValue(ImeWindowMode.Fixed.NORMAL)
                val expected = ImeWindowProps.Fixed(
                    keyboardHeight = constraints.defKeyboardHeight * 1.5f,
                    paddingLeft = 0.dp,
                    paddingRight = 0.dp,
                    paddingBottom = 60.dp,
                ).constrained(constraints)
                normal shouldBe expected
            }
        }

        test("reconstruction uses historical one-handed defaults") {
            val config = migratedConfig(
                encodedPreferences("""s;keyboard__one_handed_mode;"START""""),
                sourceVersionCode = 104,
            )
            val type = ImeFormFactor.Type.PHONE_PORTRAIT
            val rootInsets = baselineRootInsets(type)
            val windowConfig = config.getValue(type)
            val normal = windowConfig.fixedProps.getValue(ImeWindowMode.Fixed.NORMAL)
            val compact = windowConfig.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT)

            windowConfig.fixedMode shouldBe ImeWindowMode.Fixed.COMPACT
            compact.keyboardHeight shouldBe normal.keyboardHeight * 0.87f
            compact.paddingLeft shouldBe 0.dp
            compact.paddingRight shouldBe rootInsets.boundsDp.width * 0.13f
            compact.paddingBottom shouldBe 0.dp
        }

        test("Merge keeps unrelated state and current compact proportions") {
            val portraitType = ImeFormFactor.Type.PHONE_PORTRAIT
            val desktop = ImeWindowConfig(
                mode = ImeWindowMode.FLOATING,
                floatingProps = mapOf(
                    ImeWindowMode.Floating.NORMAL to ImeWindowProps.Floating(
                        keyboardHeight = 210.dp,
                        keyboardWidth = 320.dp,
                        offsetLeft = 15.dp,
                        offsetBottom = 20.dp,
                    ),
                ),
            )
            val portrait = ImeWindowConfig(
                mode = ImeWindowMode.FLOATING,
                fixedMode = ImeWindowMode.Fixed.NORMAL,
                fixedProps = mapOf(
                    ImeWindowMode.Fixed.NORMAL to ImeWindowProps.Fixed(
                        keyboardHeight = 300.dp,
                        paddingLeft = 11.dp,
                        paddingRight = 13.dp,
                        paddingBottom = 17.dp,
                    ),
                    ImeWindowMode.Fixed.COMPACT to ImeWindowProps.Fixed(
                        keyboardHeight = 240.dp,
                        paddingLeft = 51.dp,
                        paddingRight = 0.dp,
                        paddingBottom = 19.dp,
                    ),
                    ImeWindowMode.Fixed.THUMBS to ImeWindowProps.Fixed(
                        keyboardHeight = 222.dp,
                        paddingLeft = 7.dp,
                        paddingRight = 8.dp,
                        paddingBottom = 9.dp,
                    ),
                ),
            )
            val base = mapOf(
                portraitType to portrait,
                ImeFormFactor.Type.DESKTOP to desktop,
            )
            val config = migratedConfig(
                payload = encodedPreferences("""i;keyboard__height_factor_portrait;120"""),
                base = base,
                sourceVersionCode = 104,
            )

            config.getValue(ImeFormFactor.Type.DESKTOP) shouldBe desktop
            val migrated = config.getValue(portraitType)
            migrated.mode shouldBe portrait.mode
            migrated.floatingProps shouldContainExactly portrait.floatingProps
            migrated.fixedMode shouldBe portrait.fixedMode
            migrated.fixedProps.getValue(ImeWindowMode.Fixed.THUMBS) shouldBe
                portrait.fixedProps.getValue(ImeWindowMode.Fixed.THUMBS)

            val normalConstraints = fixedConstraints(portraitType, ImeWindowMode.Fixed.NORMAL)
            val expectedNormal = portrait.fixedProps.getValue(ImeWindowMode.Fixed.NORMAL).copy(
                keyboardHeight = normalConstraints.defKeyboardHeight * 1.2f,
            ).constrained(normalConstraints)
            val compactConstraints = fixedConstraints(portraitType, ImeWindowMode.Fixed.COMPACT)
            val expectedCompact = portrait.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT).copy(
                keyboardHeight = expectedNormal.keyboardHeight * 0.8f,
            ).constrained(compactConstraints)
            migrated.fixedProps.getValue(ImeWindowMode.Fixed.NORMAL) shouldBe expectedNormal
            migrated.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT) shouldBe expectedCompact
        }

        test("Merge applies an explicit scale and side without replacing unrelated fields") {
            val type = ImeFormFactor.Type.PHONE_PORTRAIT
            val current = ImeWindowConfig(
                mode = ImeWindowMode.FIXED,
                fixedProps = mapOf(
                    ImeWindowMode.Fixed.NORMAL to ImeWindowProps.Fixed(
                        keyboardHeight = 300.dp,
                        paddingLeft = 0.dp,
                        paddingRight = 0.dp,
                        paddingBottom = 7.dp,
                    ),
                    ImeWindowMode.Fixed.COMPACT to ImeWindowProps.Fixed(
                        keyboardHeight = 240.dp,
                        paddingLeft = 40.dp,
                        paddingRight = 0.dp,
                        paddingBottom = 9.dp,
                    ),
                ),
            )
            val config = migratedConfig(
                payload = encodedPreferences(
                    """s;keyboard__one_handed_mode;"START"""",
                    """i;keyboard__one_handed_mode_scale_factor;70""",
                ),
                base = mapOf(type to current),
                sourceVersionCode = 104,
            )
            val migrated = config.getValue(type)
            val compact = migrated.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT)

            migrated.fixedMode shouldBe ImeWindowMode.Fixed.COMPACT
            compact.keyboardHeight shouldBe 210.dp
            compact.paddingLeft shouldBe 0.dp
            compact.paddingRight shouldBe baselineRootInsets(type).boundsDp.width * 0.3f
            compact.paddingBottom shouldBe 9.dp
        }

        test("split Merge preserves active state when the enabled field is absent") {
            val type = ImeFormFactor.Type.PHONE_PORTRAIT
            val current = ImeWindowConfig(
                mode = ImeWindowMode.FIXED,
                fixedMode = ImeWindowMode.Fixed.COMPACT,
            )
            val config = migratedConfig(
                payload = encodedPreferences("""s;keyboard__one_handed_mode;"START""""),
                base = mapOf(type to current),
                sourceVersionCode = 105,
            )

            config.getValue(type).fixedMode shouldBe ImeWindowMode.Fixed.COMPACT
        }

        test("partial Merge updates only represented Compact geometry") {
            val type = ImeFormFactor.Type.PHONE_PORTRAIT
            val rootInsets = baselineRootInsets(type)
            val normalConstraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.NORMAL)
            val compactConstraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.COMPACT)
            val current = ImeWindowConfig.Default

            val heightOnly = migratedConfig(
                payload = encodedPreferences("""i;keyboard__height_factor_portrait;120"""),
                base = mapOf(type to current),
                sourceVersionCode = 104,
            ).getValue(type)
            val heightCompact = heightOnly.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT)
            heightCompact shouldBe compactConstraints.defaultProps.copy(
                keyboardHeight = normalConstraints.defKeyboardHeight * 1.2f * 0.8f,
            ).constrained(compactConstraints)

            val offsetOnly = migratedConfig(
                payload = encodedPreferences("""i;keyboard__bottom_offset_portrait;12"""),
                base = mapOf(type to current),
                sourceVersionCode = 104,
            ).getValue(type)
            offsetOnly.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT) shouldBe
                compactConstraints.defaultProps.copy(paddingBottom = 12.dp).constrained(compactConstraints)

            val disabledOnly = migratedConfig(
                payload = encodedPreferences("""b;keyboard__one_handed_mode_enabled;false"""),
                base = mapOf(type to current),
                sourceVersionCode = 105,
            ).getValue(type)
            disabledOnly.fixedMode shouldBe ImeWindowMode.Fixed.NORMAL
            disabledOnly.fixedProps.getValue(ImeWindowMode.Fixed.COMPACT) shouldBe compactConstraints.defaultProps
        }

        test("Merge disables Compact without replacing newer fixed modes") {
            val type = ImeFormFactor.Type.PHONE_PORTRAIT
            val disabled = encodedPreferences("""b;keyboard__one_handed_mode_enabled;false""")

            val compact = migratedConfig(
                payload = disabled,
                base = mapOf(
                    type to ImeWindowConfig.Default.copy(fixedMode = ImeWindowMode.Fixed.COMPACT),
                ),
                sourceVersionCode = 105,
            )
            compact.getValue(type).fixedMode shouldBe ImeWindowMode.Fixed.NORMAL

            val thumbs = migratedConfig(
                payload = disabled,
                base = mapOf(
                    type to ImeWindowConfig.Default.copy(fixedMode = ImeWindowMode.Fixed.THUMBS),
                ),
                sourceVersionCode = 105,
            )
            thumbs.getValue(type).fixedMode shouldBe ImeWindowMode.Fixed.THUMBS
        }

        test("preprocessing is byte-idempotent and generated string data decodes") {
            val source = encodedPreferences(
                """i;keyboard__height_factor_portrait;115""",
                """s;keyboard__one_handed_mode;"END"""",
            )
            val once = LegacyPreferencePayloadPreprocessor.process(source, sourceVersionCode = 104)
            val twice = LegacyPreferencePayloadPreprocessor.process(once, sourceVersionCode = 104)

            twice shouldBe once
            once.lineSequence().count { it.contains("keyboard__window_config") } shouldBe 1
            decodeWindowConfig(once).isNotEmpty() shouldBe true
        }
    })

private fun migratedConfig(
    payload: String,
    base: ImeWindowConfigByType? = null,
    sourceVersionCode: Int? = null,
    sourceVersionName: String? = null,
): ImeWindowConfigByType {
    val processed = LegacyPreferencePayloadPreprocessor.process(
        payload = payload,
        baseWindowConfig = base,
        sourceVersionCode = sourceVersionCode,
        sourceVersionName = sourceVersionName,
    )
    return decodeWindowConfig(processed)
}

private fun decodeWindowConfig(payload: String): ImeWindowConfigByType {
    val entry = payload.lineSequence()
        .last { line -> line.substringAfter(';').substringBefore(';') == "keyboard__window_config" }
    val encodedValue = entry.substringAfter(';').substringAfter(';')
    return ImeWindowConfig.ByTypeSerializer.deserialize(decodeJetPrefString(encodedValue))
}

private fun decodeJetPrefString(rawValue: String): String {
    val value = rawValue.trim()
    return value.substring(1, value.lastIndex)
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\\\", "\\")
}

private fun fixedConstraints(type: ImeFormFactor.Type, mode: ImeWindowMode.Fixed): ImeWindowConstraints.Fixed =
    ImeWindowConstraints.of(baselineRootInsets(type), mode)

private fun baselineRootInsets(type: ImeFormFactor.Type): ImeInsets.Root {
    val size = ImeWindowConstraints.BaselineScreens.getValue(type)
    val bounds = DpRect(0.dp, 0.dp, size.width, size.height)
    val measured = ImeFormFactor.of(bounds)
    return ImeInsets.Root(
        boundsDp = bounds,
        boundsPx = IntRect.Zero,
        formFactor = measured.copy(typeGuess = type),
    )
}

private fun encodedPreferences(vararg lines: String): String = lines.joinToString(separator = "\n", postfix = "\n")
