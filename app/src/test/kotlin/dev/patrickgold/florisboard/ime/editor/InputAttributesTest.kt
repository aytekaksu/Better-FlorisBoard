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

package dev.patrickgold.florisboard.ime.editor

import android.text.InputType
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class InputAttributesTest : FunSpec({
    context("input type classification") {
        withData(
            Case(InputType.TYPE_NULL, InputAttributes.Type.NULL, InputAttributes.Variation.NORMAL),
            Case(
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
                InputAttributes.Type.DATETIME,
                InputAttributes.Variation.DATE,
            ),
            Case(InputType.TYPE_CLASS_PHONE, InputAttributes.Type.PHONE, InputAttributes.Variation.NORMAL),
            Case(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL,
                InputAttributes.Type.NUMBER,
                InputAttributes.Variation.NORMAL,
            ),
            Case(InputType.TYPE_CLASS_TEXT, InputAttributes.Type.TEXT, InputAttributes.Variation.NORMAL),
            Case(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                InputAttributes.Type.TEXT,
                InputAttributes.Variation.URI,
            ),
            Case(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputAttributes.Type.TEXT,
                InputAttributes.Variation.EMAIL_ADDRESS,
            ),
            Case(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputAttributes.Type.TEXT,
                InputAttributes.Variation.PASSWORD,
                password = true,
            ),
            Case(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputAttributes.Type.TEXT,
                InputAttributes.Variation.VISIBLE_PASSWORD,
                password = true,
            ),
            Case(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputAttributes.Type.TEXT,
                InputAttributes.Variation.WEB_PASSWORD,
                password = true,
            ),
            Case(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                InputAttributes.Type.NUMBER,
                InputAttributes.Variation.PASSWORD,
                password = true,
            ),
        ) { case ->
            val attributes = InputAttributes.wrap(case.raw)
            attributes.type shouldBe case.type
            attributes.variation shouldBe case.variation
            attributes.isPassword shouldBe case.password
        }
    }

    test("no-suggestions editors retain word composing while passwords do not") {
        InputAttributes.wrap(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        ).allowsWordComposingRegion() shouldBe true
        InputAttributes.wrap(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ).allowsWordComposingRegion() shouldBe false
    }
})

private data class Case(
    val raw: Int,
    val type: InputAttributes.Type,
    val variation: InputAttributes.Variation,
    val password: Boolean = false,
)
