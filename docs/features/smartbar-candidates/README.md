# Smartbar candidates

`SmartbarMainRow` collects the active suggestions once and passes the same
snapshot to either candidate-row layout. `CandidatesRow` hides clipboard
suggestions while the device is locked or the keyboard is incognito, then
applies the selected display mode. Classic mode shows the first three.

The app shares one `SharedActionsController` between NLP and the Smartbar UI.
It owns expansion writes and animation suppression; NLP still owns candidates.
Empty candidate and inline rows expand the actions, and live selection also
expands them, including pending editor edits. New intent retires queued older
writes. An admitted write finishes before the next one changes suppression.
Automatic changes skip content animation until the matching composition acknowledges
their token; stale acknowledgement cannot consume a newer token. User toggles
clear suppression and still work when automation is disabled.

Each candidate owns its press gesture. Replacing that candidate before release
cancels the press; it must not commit or remove either word. A stable candidate
uses the latest click and long-press callbacks, and a successful long press
does not also click. Changing the long-press delay applies to the next press.
Cancellation clears the pressed appearance.

Inline autofill chips belong to their native view, not their metadata or row
position. A new response replaces the old view even when its metadata matches,
without clearing the row first. Scrolling, clipping, and surface ordering stay
with the current row and views.

Candidate text stays in the keyboard process. Do not log suggestions or raw
pointer events.

The fast display-order checks run with `./gradlew :app:testDebugUnitTest`.
Expansion policy and write ordering use real local JetPref preferences:

```shell
./gradlew :app:testDebugUnitTest \
  --tests dev.patrickgold.florisboard.ime.smartbar.SharedActionsControllerTest
```

Native-view replacement runs on Robolectric SDK 34 without a device:

```shell
./gradlew :app:testDebugUnitTest \
  --tests dev.patrickgold.florisboard.ime.smartbar.InlineSuggestionsUiTest
```

Real press timing and replacement are covered by:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.smartbar.CandidateItemAndroidTest
```
