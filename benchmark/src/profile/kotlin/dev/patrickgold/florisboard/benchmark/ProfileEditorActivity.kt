/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.benchmark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** An empty editor in the test APK; it is never packaged in the user app. */
class ProfileEditorActivity : Activity() {
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        setContentView(editor)
        editor.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showKeyboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showKeyboard()
    }

    private fun showKeyboard() {
        editor.requestFocus()
        editor.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
