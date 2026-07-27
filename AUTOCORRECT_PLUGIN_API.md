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
persisted settings, while FlorisBoard owns their presentation.

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
        android:value="4" />
</service>
```

Protocol version 4 has no settings-activity metadata or provider-activity UI item. Provider
settings are declarative and are always rendered by FlorisBoard. It acknowledges completed session
shutdown so the host can keep the service bound until admitted learning callbacks and
`onFinishSession` finish, and exposes the Android personal dictionary through the keyboard host.
A provider can therefore ship as a service-only package with no launcher activity and must not
depend on a companion keyboard application being installed.

## Suggestions and learning

- Candidate replacement offsets refer to `AutocorrectRequest.text`. Use `-1` for both offsets to
  request FlorisBoard's normal composing-region behavior.
- `AutocorrectRequest.inputTrace` optionally supplies normalized key bounds and tap positions for
  proximity-aware correction. For a gesture request it instead carries a bounded, timed,
  single-pointer path and sets `mode` to `GESTURE`. It contains no physical screen coordinates.
- `AutocorrectRequest.capsMode` reports the keyboard's live shift state for that request, including
  automatic sentence shift, manual shift, and caps lock. It defaults to `UNSPECIFIED` when an older
  host omits it.
- `onSuggestResult` may additionally return a bounded set of Unicode code points which remain
  valid continuations of the current word. FlorisBoard uses these optional hints to expand only
  those character keys during the next hit test. Hints are ignored for non-character keys and
  while Android accessibility is active. Candidate-only providers can keep overriding `onSuggest`.
- `AutocorrectSuggestionResult.Empty` means the provider handled the request and intentionally
  produced no candidates. Return `AutocorrectSuggestionResult.Unhandled` when FlorisBoard should
  ask its built-in language provider instead. This distinction lets a provider disable one of its
  features without accidentally re-enabling equivalent host suggestions.
- Candidate IDs are opaque to FlorisBoard and should remain valid until the typing session ends.
  They are returned to the provider for accepted, reverted, and removal events. Acceptance reports
  whether the user tapped a candidate, separator-triggered autocorrection selected it, or a gesture
  result was committed.
- Candidate kinds let providers classify typed words, corrections, completions, predictions, and
  emoji for presentation without changing provider order.
- A candidate with `visible == false` remains eligible for separator-triggered autocorrection but
  is omitted from FlorisBoard's candidate row. This lets a provider independently honor its
  “show suggestions” and autocorrection settings without owning any keyboard UI.
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
- `AutocorrectSession.editorFlags` contains only normalized behavior traits (`CODE_LIKE` and
  `WEB_FIELD`). Unknown bits are discarded and an omitted field means no traits. Providers must
  not infer a target application's identity from these flags.
- Requests and replies are asynchronous. Providers must expect newer requests to cancel older
  work and should cooperate with coroutine cancellation.
- Session starts, admitted learning callbacks, and session finishes run in wire order. A finish
  acknowledgement is sent only after `onFinishSession` returns; FlorisBoard keeps the provider
  bound until that acknowledgement or a bounded timeout.
- Providers may expose any implementation: dictionaries, finite-state algorithms, native code,
  or on-device language models. No engine-specific behavior is part of the host API.
- Providers which retain sensitive personalization can override `isHostAuthorized` and accept
  only expected host package names. The default remains open so independently developed keyboards
  and providers can interoperate without a shared signing key.

FlorisBoard falls back to its built-in language provider when the external provider returns
`Unhandled`, times out, disconnects, or crashes. A successful handled result remains authoritative
even when its candidate list is empty. Host-owned spelling, emoji, clipboard, candidate display,
safe editor replacement, and callback behavior remain available.

## Provider settings UI

Providers return an `AutocorrectPluginUi` from `onGetPluginUi(languageTags)`. It is a bounded
declarative schema which FlorisBoard renders using its own components, theme, navigation, and
accessibility behavior. The provider supplies labels, values, options, validation rules, and
actions; it does not supply Android views, Compose code, or an activity to launch.

`languageTags` is the bounded, distinct set of language tags configured in FlorisBoard when the UI
was requested. Providers can use it to construct per-language dictionary and model pages without
reading another keyboard application's configuration. Suggestion requests still use
`AutocorrectSession.primaryLanguageTag` and `secondaryLanguageTags` as the typing-session language
source.

A provider chooses separate root pages for the FlorisBoard settings app and keyboard, and pages can
be shared by marking them `BOTH`. All provider settings and management workflows must remain usable
through these host-rendered pages, including when the package contains only the provider service.
Item IDs are operation identifiers and therefore global to one provider UI. If an ID appears on
more than one page or surface, every occurrence must represent the same setting or action and
declare the same host setting.

The schema supports:

- navigation pages, switches, sliders, choices, and text values;
- explicit actions with optional confirmation;
- informational rows and determinate or indeterminate progress;
- external HTTPS links launched by the host;
- document imports and exports through the host-owned Android system document picker.

For `EXTERNAL_LINK`, set `target` to an absolute, hierarchical HTTPS URL with a non-empty host, no
user information, and either no explicit port or a port from 1 through 65,535. FlorisBoard validates
the URL again when clicked and asks Android to open it with a browsable `ACTION_VIEW` intent; it
never asks the provider to create or launch an intent and does not send an action callback. Invalid
and unsupported targets remain inert. Other URI schemes are deliberately not accepted from
provider content. Like navigation targets, external-link targets are bounded to 256 characters.
Overlong external links are discarded rather than truncated.
Provider discovery requires the exact current protocol version, so hosts never reinterpret this
item with an older schema.

A switch can optionally declare an `AutocorrectPluginHostSetting`. This is for behavior which must
run before a provider receives a request, such as enabling glide recognition or increasing its
sensitivity. FlorisBoard owns and persists the authoritative value, substitutes that value into
every returned page, and applies the behavior itself. The provider still receives the ordinary
`onSetPluginUiValue` callback so it can mirror the value, but it must not treat its mirrored copy as
an independent gate. Unknown host settings are ignored, and a host setting on a non-switch item is
discarded. These host-owned preferences are normal FlorisBoard preferences, so they remain
available without a provider installed and participate in FlorisBoard configuration backup and
restore.

For `DOCUMENT_IMPORT`, set `documentMimeTypes` to the accepted MIME types. For
`DOCUMENT_EXPORT`, also set `documentSuggestedName` when a useful default filename is known.
FlorisBoard opens the Storage Access Framework picker and passes the selected document to
`onPluginUiDocument` as an `AutocorrectPluginDocument`. Its `ParcelFileDescriptor` is opened for
the requested direction (`write == false` for import and `true` for export), is valid only during
that callback, and is closed by the base service afterward. The provider performs the actual
bounded read or write and does not need broad storage permission.

Text values are editable in the settings app; the keyboard surface displays them read-only because
an IME cannot reliably type into its own panel.

After `onSetPluginUiValue`, `onInvokePluginUiAction`, or `onPluginUiDocument`, the service returns a
fresh UI snapshot. The callback should return `false` for an unknown item, invalid value, or failed
operation. Long-running user actions can call `publishPluginUi` to push progress while a page is
open. The provider receives `onPluginUiClosed` when the last host page closes and must stop UI-only
observation. FlorisBoard does not poll.

### Android personal dictionary

Providers can read the Android system personal dictionary through `hostUserDictionary`. Only the
selected provider can read while its host-rendered UI is visible; typing reads require that same
provider to own the currently admitted session. The keyboard performs `ContentResolver` access
under its own enabled-IME identity, so a service-only provider needs no user-dictionary permission.
Disabling FlorisBoard's system-dictionary preference returns `DENIED` without touching the store.
If FlorisBoard is not an enabled input method or the platform store cannot be reached, the result is
`UNAVAILABLE`.

During typing, queries are restricted to the active primary and secondary language families plus
language-neutral rows, represented only by `languageTag = null`. A visible provider settings page
may request all languages. Results use stable host row IDs and bounded pages of at most 128 entries.
Providers should load a page only from a service callback (or its structured child coroutine) when
a session or settings view starts, then cache it for that lifetime; the contract provides no
observer, polling, wake-lock, or background-sync mechanism.

Dictionary writes are available only through the `AutocorrectUserDictionaryEditor` passed to the
two-argument `onInvokePluginUiAction` callback. The provider may perform multiple bounded reads and
writes during that explicit action, which supports generic bulk editors. The editor and its
provider-bound host capability are revoked when the callback returns, the action times out, the
last provider page closes, or either process disconnects. Locale values are strict canonical
BCP-47 tags; scripts, regions, variants, extensions, and private-use subtags survive conversion to
and from Android's storage format.
Frequency values range from 0 through 255; zero remains valid for structured entries such as
Japanese reading metadata stored in the shortcut field. Providers must treat `UNAVAILABLE`,
`DENIED`, and `INVALID` as recoverable results and continue ordinary suggestions without the
personal dictionary.

The keyboard page is opened through the **Autocorrect provider** quick action. It is available in
the Smartbar action editor for existing and new configurations. Providers which do not publish
settings pages can remain suggestion-only; FlorisBoard shows a short unavailable message after the
optional UI request times out.

## Reference-engine coverage

The host contract was checked against
[FUTO Keyboard](https://github.com/futo-org/android-keyboard) at commit
`8a099cf24692b9047872beadd9f254d093d152f1` (2026-07-22). This is a compatibility map, not an
engine-specific dependency:

| Engine capability | Generic provider mechanism |
| --- | --- |
| Dictionary and transformer candidates | `onSuggest`, ordered candidates, confidence and kind |
| Autocorrect, completion and next-word UI | candidate kind, secondary text and auto-commit |
| Emoji suggestions | emoji candidate kind with provider acceptance feedback |
| Proximity-aware correction | normalized `inputTrace` key geometry and taps |
| Dictionary-aware key hit testing | optional bounded valid-next-code-point hints |
| Swipe decoding and gesture candidates | timed normalized gesture path with host fallback |
| Swipe recognition enablement and sensitivity | optional host-owned switch settings |
| Multilingual model selection | primary and secondary session language tags |
| Personal history and fine-tuning input | accepted/reverted/removal and text-commit callbacks |
| Prediction and personalization toggles | switch items |
| Thresholds, temperature and tuning values | slider and choice items |
| Model selection and per-language defaults | navigation pages and choices |
| Model or dictionary import and export | host-picked `DOCUMENT_IMPORT` and `DOCUMENT_EXPORT` items |
| Model download, deletion and default selection | actions, navigation, progress and choices |
| Training/download status | progress items and push updates while visible |
| Android personal dictionary | host-brokered, language-scoped bounded pages and action-scoped CRUD |
| Provider blacklist management | text, navigation and action items; candidate removal |

The provider adapter is responsible for translating these generic values into its engine's native
types. Another provider can implement an entirely different dictionary, neural model, remote
service, or hybrid pipeline without changing FlorisBoard. A FUTO-derived reference adapter lives
in the separate, appropriately licensed
[`aytekaksu/android-keyboard`](https://github.com/aytekaksu/android-keyboard) fork; FUTO source is
not included in this Apache-licensed repository.

## Privacy and battery contract

FlorisBoard never connects an external provider for password fields, fields which disable
suggestions, raw input editors, or incognito sessions. It does not send the target application's
package name. Compatibility cases which FlorisBoard can recognize locally, including browser and
code-editor behavior, are reduced to the generic editor flags before crossing the service boundary.
Editors can independently disable persistent personalization without disabling
suggestions.

The provider is bound only while an eligible input view or an explicit provider-settings page is
active. FlorisBoard uses a non-foreground binding, does not call `startService`, poll the provider,
acquire a wake lock for it, or schedule background work. It cancels superseded suggestion requests,
limits text context to 512 UTF-16 code units, accepts at most 16 candidates, bounds tap and gesture
traces and UI payloads, and unbinds when neither typing nor settings needs the service.

Provider implementations must not turn a typing session into a started or foreground service.
They should preload models in `onStartSession`, release session-specific state in
`onFinishSession`, persist learning incrementally, and cancel work promptly when their coroutine is
cancelled. Android may destroy the provider process after FlorisBoard unbinds, so cleanup callbacks
must not be the only place important data is saved.

Users explicitly select one installed provider under **Settings → Typing → Autocorrect provider**.
That component selection is a regular FlorisBoard preference and is included in its configuration
backup. Engine-specific values remain in provider-owned storage, while Android personal-dictionary
rows remain in the system store accessed by FlorisBoard. Both are configured through FlorisBoard's
host-rendered pages. A restored selection is used only when the same compatible provider service
is installed.

To avoid repeatedly loading a large immutable model when switching text fields, a provider may keep
it in an application-scoped lazy cache. That cache must not run work on its own; Android remains
free to reclaim the provider process after FlorisBoard unbinds.

For imports and exports, FlorisBoard owns the document-picker interaction and the provider handles
I/O only through the descriptor supplied to its callback. Explicit downloads or training initiated
from a provider page remain provider operations and must follow Android's normal background-work
rules. None of these operations may be tied to or kept alive by an ordinary typing session.
