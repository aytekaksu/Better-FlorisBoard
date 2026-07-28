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

package dev.patrickgold.florisboard.ime.nlp.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiOption

class AutocorrectPluginUiFormattingTest :
    FunSpec({
        test("slider values are normalized to their declared step") {
            slider(minimum = 0.0, maximum = 100.0, step = 0.1)
                .formattedSliderValue(97.799995f) shouldBe "97.8"
            slider(minimum = 0.05, maximum = 1.05, step = 0.1)
                .formattedSliderValue(0.14999999f) shouldBe "0.15"
            slider(minimum = 0.0, maximum = 1.0, step = 0.05)
                .formattedSliderValue(0.349999f) shouldBe "0.35"
            slider(minimum = 0.0, maximum = 100.0, step = 1.0)
                .formattedSliderValue(97.799995f) shouldBe "98"
        }

        test("non-divisible slider ranges preserve their endpoint") {
            val item = slider(minimum = 0.0, maximum = 1.0, step = 0.3)

            item.sliderSteps() shouldBe 0
            item.normalizedSliderValue(0.31f) shouldBe 0.3f
            item.normalizedSliderValue(0.98f) shouldBe 1f
            item.formattedSliderValue(1f) shouldBe "1"
        }

        test("tiny slider steps do not overflow position calculations") {
            val item = slider(minimum = 0.0, maximum = 1.0, step = 1e-30)

            item.sliderSteps() shouldBe 0
            item.formattedSliderValue(0.5f) shouldBe "0.5"
        }

        test("current slider values are formatted before display") {
            slider(
                minimum = 0.0,
                maximum = 100.0,
                step = 0.1,
                value = "97.799995",
            ).formattedCurrentSliderValue() shouldBe "97.8"
        }

        test("current slider values fall back to the provider summary when invalid") {
            slider(0.0, 1.0, 0.1, value = "0").formattedCurrentSliderValue() shouldBe "0"
            slider(0.0, 1.0, 0.1, value = "").formattedCurrentSliderValue() shouldBe null
            slider(0.0, 1.0, 0.1, value = "NaN").formattedCurrentSliderValue() shouldBe null
            slider(0.0, 1.0, 0.1, value = "Infinity").formattedCurrentSliderValue() shouldBe null
            slider(0.0, 1.0, 0.1, value = "invalid").formattedCurrentSliderValue() shouldBe null
        }

        test("app choices retain provider context after the selected label") {
            val item = AutocorrectPluginUiItem(
                id = "savedWords",
                kind = AutocorrectPluginUiItemKind.CHOICE,
                title = "Saved words",
                summary = "1–20 of 135",
                value = "entry-1",
                options = listOf(AutocorrectPluginUiOption("entry-1", "Example")),
            )

            item.appChoiceSupportingText() shouldBe AppChoiceSupportingText(
                label = "Example",
                summary = "1–20 of 135",
            )
        }
    })

private fun slider(minimum: Double, maximum: Double, step: Double, value: String? = null) = AutocorrectPluginUiItem(
    id = "slider",
    kind = AutocorrectPluginUiItemKind.SLIDER,
    title = "Slider",
    value = value,
    minimum = minimum,
    maximum = maximum,
    step = step,
)
