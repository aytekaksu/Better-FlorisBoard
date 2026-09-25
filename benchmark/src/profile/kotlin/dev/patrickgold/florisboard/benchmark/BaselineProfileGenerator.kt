/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TargetPackage = "dev.patrickgold.florisboard.profile"
private const val TargetImeService = "$TargetPackage/dev.patrickgold.florisboard.FlorisImeService"
private const val EditorActivity = "dev.patrickgold.florisboard.benchmark/.ProfileEditorActivity"

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun appStartupAndImeFirstShow() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previousIme = device.executeShellCommand("settings get secure default_input_method").trim()
        val enabledImes = device.executeShellCommand("ime list -s")
            .lineSequence()
            .map(String::trim)
            .toSet()
        try {
            baselineProfileRule.collect(packageName = TargetPackage) {
                pressHome()
                // Select before opening setup, which otherwise brings itself back to
                // the foreground as soon as it notices the IME was enabled.
                device.executeShellCommand("ime enable $TargetImeService")
                device.executeShellCommand("ime set $TargetImeService")
                val selectedIme = device.executeShellCommand("settings get secure default_input_method").trim()
                check(selectedIme == TargetImeService) {
                    "Profile IME was not selected"
                }
                startActivityAndWait()
                device.executeShellCommand("am start -n $EditorActivity")
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                var imeVisible = false
                while (!imeVisible && SystemClock.elapsedRealtime() < deadline) {
                    val editorFocused = device.executeShellCommand("dumpsys activity activities")
                        .lineSequence()
                        .any { "topResumedActivity=" in it && EditorActivity in it }
                    val state = device.executeShellCommand("dumpsys input_method")
                    imeVisible = editorFocused && "mCurId=$TargetImeService" in state && "mInputShown=true" in state
                    if (!imeVisible) Thread.sleep(250L)
                }
                check(imeVisible) {
                    "Profile IME did not appear over the test editor"
                }
            }
        } finally {
            if ('/' in previousIme) {
                device.executeShellCommand("ime set $previousIme")
            }
            if (TargetImeService !in enabledImes && '/' in previousIme) {
                device.executeShellCommand("ime disable $TargetImeService")
            }
            device.pressHome()
        }
    }
}
