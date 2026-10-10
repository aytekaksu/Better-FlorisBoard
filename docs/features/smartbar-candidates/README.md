# Smartbar candidates

`SmartbarCandidateController` owns handed-off provider candidates, clipboard
suggestions, visible row, and hidden auto-commit choice. NLP keeps provider
requests, fallback, and request freshness. The app wires both owners without
asking either to construct the other.

`SmartbarMainRow` collects the controller's active suggestions once and passes
the same snapshot to either candidate-row layout. `CandidatesRow` hides clipboard
suggestions while the device is locked or the keyboard is incognito, then
applies the selected display mode. Classic mode shows the first three.

The app shares one `SharedActionsController` between candidate publication and
the Smartbar UI. It owns expansion writes and animation suppression.
Empty candidate and inline rows expand the actions, and live selection also
expands them, including pending editor edits. New intent retires queued older
writes. An admitted write finishes before the next one changes suppression.
Automatic changes skip content animation until the matching composition acknowledges
their token; stale acknowledgement cannot consume a newer token. User toggles
clear suppression and still work when automation is disabled.

Only the latest assembly may publish. Clearing retires an assembly already in
progress and immediately clears both visible and auto-commit outputs. Clipboard
and preference changes can still refresh the row after a clear. Publication
rechecks live incognito and both device locks. Its auto-commit choice includes
hidden candidates without changing provider order. Candidate publication
reads the current inline row. Inline changes and Smartbar re-enabling also
refresh presentation through this owner, without replaying UI snapshots.

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

The real candidate-owner priority, copy suppression, privacy, and publication
races run on Robolectric SDK 34:

```shell
./gradlew :app:testDebugUnitTest \
  --tests dev.patrickgold.florisboard.ime.smartbar.SmartbarCandidateControllerTest
```

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

Application-to-row and keyboard-to-editor wiring use the existing real IME
fixture. Run this only on a dedicated test serial; it temporarily changes the
default IME and restores it afterwards. Build and install both debug APKs on
that serial first, then run:

```shell
adb -s <owned-serial> shell am instrument -w \
  -e class dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardTouchE2eTest#appCandidateOwnerFeedsTheVisibleRowAndHiddenSeparatorCommit \
  dev.patrickgold.florisboard.debug.test/androidx.test.runner.AndroidJUnitRunner
```
