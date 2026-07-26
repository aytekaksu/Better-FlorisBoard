# External autocorrect providers

FlorisBoard supports independently installed autocorrect engines through a versioned Android
bound-service protocol. This system is separate from `.flex` extensions: `.flex` packages provide
data such as layouts and themes, while an autocorrect provider executes its own suggestion logic in
its own application process.

The keyboard remains responsible for displaying candidates, validating replacement ranges, and
editing the target application. A provider receives only a bounded text snapshot and returns
candidate metadata. This keeps themes, accessibility, editor compatibility, and text-field safety
under the keyboard's control.

## Provider service

Build the API AAR with:

```shell
./gradlew :lib:autocorrect-api:assembleRelease
```

Add that AAR to the provider application, subclass
`org.florisboard.autocorrect.api.AutocorrectPluginService`, and implement `onSuggest`. All
provider APIs are engine-neutral; the provider owns its models, dictionaries, ranking, and
settings.

When consuming the raw AAR rather than this Gradle project, also add
`org.jetbrains.kotlinx:kotlinx-coroutines-android` to the provider application.

```kotlin
class MyAutocorrectService : AutocorrectPluginService() {
    override suspend fun onSuggest(
        request: AutocorrectRequest,
    ): List<AutocorrectCandidate> {
        val word = request.text.substring(
            request.currentWordStart,
            request.currentWordEnd,
        )
        return myEngine.suggest(word).mapIndexed { index, suggestion ->
            AutocorrectCandidate(
                id = suggestion.id,
                text = suggestion.text,
                confidence = suggestion.confidence,
                kind = AutocorrectCandidateKind.CORRECTION,
                autoCommit = index == 0 && suggestion.shouldAutocorrect,
                replacementStart = request.currentWordStart,
                replacementEnd = request.currentWordEnd,
            )
        }
    }
}
```

Declare the service in the provider manifest:

```xml
<service
    android:name=".MyAutocorrectService"
    android:exported="true">
    <intent-filter>
        <action android:name="org.florisboard.autocorrect.api.action.BIND_PROVIDER" />
    </intent-filter>
    <meta-data
        android:name="org.florisboard.autocorrect.api.PROTOCOL_VERSION"
        android:value="1" />
    <meta-data
        android:name="org.florisboard.autocorrect.api.SETTINGS_ACTIVITY"
        android:value=".AutocorrectSettingsActivity" />
</service>
```

The settings activity metadata is optional. If present, that activity must be exported so
FlorisBoard can open it explicitly. FlorisBoard then offers a button which opens the activity in
the provider package.

## Suggestions and learning

- Candidate replacement offsets refer to `AutocorrectRequest.text`. Use `-1` for both offsets to
  request FlorisBoard's normal composing-region behavior.
- `AutocorrectRequest.inputTrace` optionally supplies normalized key bounds and tap positions for
  proximity-aware correction. It contains no screen coordinates and is empty when the trace cannot
  be matched safely to the current word.
- Candidate IDs are opaque to FlorisBoard and should remain valid until the typing session ends.
  They are returned to the provider for accepted, reverted, and removal events.
- Candidate kinds let providers classify typed words, corrections, completions, and predictions
  for presentation without changing provider order.
- `AutocorrectSeparatorBehavior` lets the engine insert, omit, or defer separator behavior to the
  active language.
- `onTextEvent` reports plain typed and gesture words which did not originate from a provider
  candidate, plus words the user begins deleting. A reverted provider correction is reported only
  through the dedicated revert callback to avoid duplicate learning events. Together with
  accepted/reverted/removal callbacks
  and the provider's last request, this supports optional personal dictionaries, history learning,
  unlearning, and fine-tuning logs. A deletion at a separator can carry an empty word; providers
  can derive the affected word from their last request.
- The session's `allowPersonalizedLearning` flag reflects Android's no-personalized-learning
  editor option. FlorisBoard suppresses text events when it is false; providers must also avoid
  persistent learning from candidate callbacks in that session.
- Requests and replies are asynchronous. Providers must expect newer requests to cancel older
  work and should cooperate with coroutine cancellation.
- Providers may expose any implementation: dictionaries, finite-state algorithms, native code,
  or on-device language models. No engine-specific behavior is part of the host API.

If an external provider returns no candidates, times out, disconnects, or crashes, FlorisBoard
falls back to its built-in language provider. Host-owned spelling, emoji, clipboard, glide typing,
candidate display, safe editor replacement, and callback behavior remain available.

## Provider settings UI

Providers can return an `AutocorrectPluginUi` from `onGetPluginUi`. It is a bounded declarative
schema which FlorisBoard renders using its own theme and accessibility behavior. A provider chooses
separate root pages for the settings app and keyboard, and pages can be shared by marking them
`BOTH`.

The schema supports:

- navigation pages, switches, sliders, choices, and text values;
- explicit actions with optional confirmation;
- informational rows and determinate or indeterminate progress;
- a restricted activity escape hatch for file pickers or other complex workflows.

Activity targets must be exported and belong to the provider package. Arbitrary provider Compose
code is never loaded into FlorisBoard. Text values are editable in the settings app; the keyboard
surface displays them read-only because an IME cannot reliably type into its own panel.

After `onSetPluginUiValue` or `onInvokePluginUiAction`, the service returns a fresh UI snapshot.
Long-running user actions can call `publishPluginUi` to push progress while a page is open. The
provider receives `onPluginUiClosed` when the last host page closes and must stop UI-only
observation. FlorisBoard does not poll.

The keyboard page is opened through the **Autocorrect provider** quick action. It is available in
the Smartbar action editor for existing and new configurations. Providers which implement only the
original suggestion protocol remain compatible; FlorisBoard shows a short unavailable message
after the optional UI request times out.

## Reference-engine coverage

The host contract was checked against
[FUTO Keyboard](https://github.com/futo-org/android-keyboard) at commit
`8a099cf24692b9047872beadd9f254d093d152f1` (2026-07-22). This is a compatibility map, not an
engine-specific dependency:

| Engine capability | Generic provider mechanism |
| --- | --- |
| Dictionary and transformer candidates | `onSuggest`, ordered candidates, confidence and kind |
| Autocorrect, completion and next-word UI | candidate kind, secondary text and auto-commit |
| Proximity-aware correction | normalized `inputTrace` key geometry and taps |
| Multilingual model selection | primary and secondary session language tags |
| Personal history and fine-tuning input | accepted/reverted/removal and text-commit callbacks |
| Prediction and personalization toggles | switch items |
| Thresholds, temperature and tuning values | slider and choice items |
| Model selection and per-language defaults | navigation pages and choices |
| Model import, export, download and deletion | confirmed actions or provider activities |
| Training/download status | progress items and push updates while visible |
| Personal dictionary and blacklist management | text, navigation and action items; candidate removal |

The provider adapter is responsible for translating these generic values into its engine's native
types. Another provider can implement an entirely different dictionary, neural model, remote
service, or hybrid pipeline without changing FlorisBoard.

## Privacy and battery contract

FlorisBoard never connects an external provider for password fields, fields which disable
suggestions, raw input editors, or incognito sessions. It does not send the target application's
package name. Editors can independently disable persistent personalization without disabling
suggestions.

The provider is bound only while an eligible input view or an explicit provider-settings page is
active. FlorisBoard uses a non-foreground binding, does not call `startService`, poll the provider,
acquire a wake lock for it, or schedule background work. It cancels superseded suggestion requests,
limits text context to 512 UTF-16 code units, accepts at most 16 candidates, bounds trace and UI
payloads, and unbinds when neither typing nor settings needs the service.

Provider implementations must not turn a typing session into a started or foreground service.
They should preload models in `onStartSession`, release session-specific state in
`onFinishSession`, persist learning incrementally, and cancel work promptly when their coroutine is
cancelled. Android may destroy the provider process after FlorisBoard unbinds, so cleanup callbacks
must not be the only place important data is saved.

Users explicitly select one installed provider under **Settings → Typing → Autocorrect provider**.
That component selection is a regular FlorisBoard preference and is included in its configuration
backup. Engine-specific settings remain owned by the provider app, and a restored selection is used
only when the same compatible provider is installed.

To avoid repeatedly loading a large immutable model when switching text fields, a provider may keep
it in an application-scoped lazy cache. That cache must not run work on its own; Android remains
free to reclaim the provider process after FlorisBoard unbinds.

Explicit model downloads, imports, exports, or training initiated from provider UI remain the
provider application's responsibility and must follow Android's normal background-work rules. They
must not be tied to or kept alive by ordinary typing sessions.
