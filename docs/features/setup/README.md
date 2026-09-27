# Onboarding

`SetupScreen.kt` watches whether the keyboard is enabled and selected, plus
notification permission on Android 13+. It advances to the first unfinished
requirement and opens Android's settings or picker when the user asks.

`SetupStepLayout.kt` only draws the steps and saves a manually revisited step.
Reached headers can be reopened; future headers cannot. Selecting the current
automatic step clears the manual choice. Header numbers follow the visible
list, not step IDs, so Android 8–12 shows “Finish Up” as step 3.

Run `./gradlew :app:testDebugUnitTest --tests dev.patrickgold.florisboard.app.setup.SetupStepStateTest`
for progress and save/restore. Build `:app:assembleDebugAndroidTest` and run
`SetupStepLayoutAndroidTest` on a selected emulator for header behavior and
saveable state restoration. Check the full setup screen on API 26 and 33+ when
changing its step list or layout.
