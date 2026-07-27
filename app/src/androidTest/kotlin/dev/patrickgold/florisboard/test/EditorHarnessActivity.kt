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

package dev.patrickgold.florisboard.test

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class EditorHarnessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fields = listOf(
            Field("Normal autocorrect", R.id.editor_normal_autocorrect, text(autoCorrect = true)),
            Field(
                "Multiline autocorrect",
                R.id.editor_multiline_autocorrect,
                text(autoCorrect = true, multiline = true),
                EditorInfo.IME_ACTION_NONE,
                true,
            ),
            Field("Raw input", R.id.editor_raw, InputType.TYPE_NULL, EditorInfo.IME_ACTION_NONE),
            Field(
                "Phone",
                R.id.editor_phone,
                InputType.TYPE_CLASS_PHONE,
                EditorInfo.IME_ACTION_GO,
            ),
            Field(
                "Date",
                R.id.editor_date,
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
                EditorInfo.IME_ACTION_NEXT,
            ),
            Field(
                "Signed decimal number",
                R.id.editor_signed_decimal,
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL,
            ),
            Field(
                "App-completed text",
                R.id.editor_auto_complete,
                text() or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE,
                EditorInfo.IME_ACTION_NEXT,
            ),
            Field(
                "Text password",
                R.id.editor_text_password,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
            Field(
                "Visible password",
                R.id.editor_visible_password,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            ),
            Field(
                "Web password",
                R.id.editor_web_password,
                text(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            ),
            Field(
                "Numeric PIN/password",
                R.id.editor_numeric_password,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
            Field(
                "No suggestions",
                R.id.editor_no_suggestions,
                text() or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            ),
            Field(
                "No personalized learning",
                R.id.editor_no_personalized_learning,
                text(autoCorrect = true),
                EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
            Field(
                "URI",
                R.id.editor_uri,
                text(InputType.TYPE_TEXT_VARIATION_URI),
                EditorInfo.IME_ACTION_GO,
            ),
            Field(
                "Email",
                R.id.editor_email,
                text(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
                EditorInfo.IME_ACTION_SEND,
            ),
            Field(
                "Web editor",
                R.id.editor_web,
                text(InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT),
                EditorInfo.IME_ACTION_SEARCH,
            ),
            Field(
                "Web email",
                R.id.editor_web_email,
                text(InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
                EditorInfo.IME_ACTION_SEND,
            ),
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            isFocusableInTouchMode = true
            fields.forEach { addField(this, it) }
            requestFocus()
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun addField(container: LinearLayout, field: Field) {
        container.addView(TextView(container.context).apply {
            text = field.label
            setPadding(0, dp(8), 0, dp(2))
        })
        container.addView(EditText(container.context).apply {
            id = field.id
            contentDescription = field.label
            setSingleLine(!field.multiline)
            inputType = field.inputType
            imeOptions = field.imeOptions
            maxLines = if (field.multiline) 4 else 1
            hint = "Type here"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class Field(
        val label: String,
        val id: Int,
        val inputType: Int,
        val imeOptions: Int = EditorInfo.IME_ACTION_DONE,
        val multiline: Boolean = false,
    )

    companion object {
        private fun text(
            variation: Int = InputType.TYPE_TEXT_VARIATION_NORMAL,
            autoCorrect: Boolean = false,
            multiline: Boolean = false,
        ) = InputType.TYPE_CLASS_TEXT or variation or
            (if (autoCorrect) InputType.TYPE_TEXT_FLAG_AUTO_CORRECT else 0) or
            (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
    }
}
