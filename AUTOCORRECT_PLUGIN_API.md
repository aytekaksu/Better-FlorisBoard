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
`org.florisboard.autocorrect.api.AutocorrectPluginService`, and implement `onSuggest`.

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

## Protocol behavior

- Candidate replacement offsets refer to `AutocorrectRequest.text`. Use `-1` for both offsets to
  request FlorisBoard's normal composing-region behavior.
- Candidate IDs are opaque to FlorisBoard and should remain valid until the typing session ends.
  They are returned to the provider for accepted, reverted, and removal events.
- Candidate kinds are engine-neutral presentation hints. They let the keyboard distinguish typed
  words, corrections, completions, and next-word predictions without knowing how they were ranked.
- `AutocorrectSeparatorBehavior` lets the engine insert, omit, or defer separator behavior to the
  active language.
- Requests and replies are asynchronous. Providers must expect newer requests to cancel older
  work and should cooperate with coroutine cancellation.
- Providers may expose any implementation: dictionaries, finite-state algorithms, native code,
  or on-device language models. No engine-specific behavior is part of the host API.

## Privacy and battery contract

FlorisBoard never connects an external provider for password fields, fields which disable
suggestions, raw input editors, or incognito sessions. It does not send the target application's
package name.

The provider is bound only while an eligible input view is active. FlorisBoard does not call
`startService`, poll the provider, acquire a wake lock for it, or schedule background work. It
cancels superseded suggestion requests, limits text context to 512 UTF-16 code units, accepts at
most 16 candidates, and unbinds when input finishes.

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

If the provider is missing, incompatible, crashes, or exceeds the response timeout, FlorisBoard
continues typing without external suggestions.
