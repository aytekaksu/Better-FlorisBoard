# Smartbar candidates

`SmartbarMainRow` collects the active suggestions once and passes the same
snapshot to either candidate-row layout. `CandidatesRow` hides clipboard
suggestions while the device is locked or the keyboard is incognito, then
applies the selected display mode. Classic mode shows the first three.

Each candidate owns its press gesture. Replacing that candidate before release
cancels the press; it must not commit or remove either word. A stable candidate
uses the latest click and long-press callbacks, and a successful long press
does not also click. Changing the long-press delay applies to the next press.
Cancellation clears the pressed appearance.

Candidate text stays in the keyboard process. Do not log suggestions or raw
pointer events.

The fast display-order checks run with `./gradlew :app:testDebugUnitTest`.
Real press timing and replacement are covered by:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.smartbar.CandidateItemAndroidTest
```
