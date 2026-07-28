# Autocorrect plugins

## Purpose and boundaries

The host discovers a user-selected Android service, sends bounded typing
snapshots, validates its replies, renders its declarative settings, and applies
accepted edits. It also exposes controlled document-picker and Android personal
dictionary operations.

The provider owns its models, dictionaries, ranking, and persisted provider
settings. It does not own keyboard UI, editor operations, Android intents, or
FlorisBoard preferences. This feature is not the `.flex` extension system and
does not make a provider part of the keyboard process.

The wire format and provider responsibilities are documented in the
[external autocorrect provider guide](../../../AUTOCORRECT_PLUGIN_API.md).

## Source map

| Area | Source |
| --- | --- |
| Public message IDs, limits, models, and codecs | [`lib/autocorrect-api`](../../../lib/autocorrect-api/src/main/kotlin/org/florisboard/autocorrect/api/) |
| Provider-side Messenger service | [`AutocorrectPluginService.kt`](../../../lib/autocorrect-api/src/main/kotlin/org/florisboard/autocorrect/api/AutocorrectPluginService.kt) |
| Platform-neutral host state and transition rules | [`lib/autocorrect-host-core`](../../../lib/autocorrect-host-core/src/main/kotlin/org/florisboard/autocorrect/host/core/) |
| Request admission and stale-reply adapter | [`AutocorrectSuggestionRequestCoordinator.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectSuggestionRequestCoordinator.kt) |
| Android discovery, binding, session transport, and dictionary bridge | [`AutocorrectPluginManager.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginManager.kt) |
| Bounded content-free lifecycle diagnostics | [`AutocorrectPluginDiagnostics.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginDiagnostics.kt) |
| Host-rendered UI entry point | [`AutocorrectPluginUi.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginUi.kt) |
| App settings renderer | [`AutocorrectPluginUiApp.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginUiApp.kt) |
| Keyboard renderer | [`AutocorrectPluginUiKeyboard.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/plugin/AutocorrectPluginUiKeyboard.kt) |
| Provider selection and app settings surface | [`AutocorrectPluginScreen.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/typing/AutocorrectPluginScreen.kt) |
| Built-in suggestion fallback and candidate lifecycle | [`NlpManager.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/NlpManager.kt) |
| Tap trace capture | [`TextKeyboardLayout.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKeyboardLayout.kt) |
| Gesture trace and fallback | [`GlideTypingManager.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingManager.kt) |
| Deterministic editor fixture | [`DeterministicInputConnection.kt`](../../../app/src/test/kotlin/dev/patrickgold/florisboard/test/editor/DeterministicInputConnection.kt) |

The Android manager remains the public facade. The host core is authoritative
for suggestion request identity, supersession, cancellation, reply admission,
and circuit health; the coordinator executes that part of the reducer contract.
Discovery, binding handles, session transport, `Messenger`, document pickers,
and content resolvers remain Android adapters. Do not reimplement migrated
request rules in the manager or add new direct manager dependencies to core
keyboard code.

## Data and lifecycle

```text
editor + subtype + preferences
        │
        ▼
eligibility → discover exact protocol → bind selected component
        │                              │
        └──────── built-in fallback ◀──┤ failure/disconnect
                                       ▼
start session → suggest/callbacks → finish acknowledgement → unbind when idle
                       │
                       ▼
           validate identity + generation + ranges
                       │
                       ▼
          host candidates and editor operations
```

A typing session is eligible only when suggestions are enabled, a provider is
selected, and the editor is neither private/incognito, raw, nor a password
field. The session records normalized language, input, caps, learning, editor,
and emoji traits. A provider UI lease can keep the service bound without a
typing session.

The host binds but never starts a provider service. Session starts, admitted
callbacks, and finish work stay in wire order. Binding demand ends only after
typing, UI/document leases, and pending finish acknowledgements have ended.

## State and concurrency rules

- Each bind has an epoch and an expected package UID. Replies from another
  package, an old epoch, unknown request, or superseded request are ignored.
- Request and session IDs are opaque and monotonic within the host process.
- The editor generation changes when the editing context or provider changes.
  A result must match it before publication or commit.
- Mutable binding/session transport state currently belongs to
  `AutocorrectPluginManager`. Suggestion request state belongs to the
  synchronized request coordinator; provider UI, dictionary work, and host
  setting mutations have separate serialization guards.
- The reducer-issued request lease follows the reply into each visible
  candidate. Both publication and commit require the latest request ID, active
  session, admitted session, provider, and editor generation.
- Provider work runs asynchronously. Never wait for it on the main thread and
  never hold a state lock across Binder, editor, disk, or content-resolver work.
- Superseding input cancels obsolete suggestions. Cancellation must remain
  distinguishable from provider failure.

## Privacy and trust boundary

- The request text is capped at 512 UTF-16 code units and candidates at 16.
  Candidate fields, traces, language tags, dictionary pages, UI structures,
  and URLs have independent limits in `AutocorrectPluginContract`.
- Tap and gesture coordinates are normalized to the keyboard layout. Physical
  screen coordinates and target application identity are not part of the
  protocol.
- Incognito, password, and raw editors do not open a typing session.
- `NO_PERSONALIZED_LEARNING` suppresses persistent-learning text events and is
  also reported to the provider.
- The keyboard validates replacement ranges and performs the edit. Provider
  UI is data, not executable Android UI; external links are HTTPS-only and
  document access uses a short-lived host-opened descriptor.
- Do not record request text, candidate text, dictionary data, touch traces, or
  raw Bundles in logs, tests based on real input, or diagnostic exports.

## Failure and fallback

`Unhandled`, discovery failure, bind failure, disconnection, provider death,
send failure, malformed data, or a rejected stale reply allows the built-in
language provider to handle ordinary suggestions. A handled empty result is
authoritative and must stay empty.

Glide uses the built-in classifier when the external provider does not handle a
gesture or its current result cannot be committed. UI mutations keep the last
valid snapshot or expose a typed error state; they must not change typing
state. Closing a page cancels UI-only observation and picker ownership.

There is currently no wall-clock suggestion timeout: newer input or lifecycle
cleanup cancels obsolete work. Do not add an arbitrary timeout. A future
presentation deadline must use measured latency, preserve a valid late provider
result policy, and have a separately tested stuck-binding/finalization
watchdog.

## Performance budget

- No synchronous provider, disk, or dictionary wait on the main thread.
- Never exceed the public payload limits or create an unbounded pending-request
  collection.
- Serialization, validation, and state reduction should add less than 2 ms
  p95 on a reference development device; provider inference is measured
  separately.
- A change to the request path must not regress host-side p95 latency or
  allocations by more than 10% against the checked-in benchmark baseline.
  Until that baseline exists, report before/after measurements instead of
  claiming an optimization.

## Verification

Fast host behavior:

```shell
./gradlew :lib:autocorrect-host-core:test :app:testDebugUnitTest
```

The app uses Kotest dynamic tests, so Gradle class filters may incorrectly
report that no tests matched. Run the complete app JVM task unless a narrower
command has been verified locally.

Public API codecs and Robolectric service lifecycle:

```shell
./gradlew :lib:autocorrect-api:testDebugUnitTest
```

Touch/provider integration when the trace or gesture path changes:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardTouchE2eTest
```

Also build the minified host and API AAR after protocol or consumer-rule
changes:

```shell
./gradlew :app:assembleBeta :lib:autocorrect-api:assembleRelease
```

## Debugging and fault injection

Use synthetic text only. A provider fixture should be able to return handled,
empty, unhandled, malformed, oversized, duplicate, late, and out-of-order
replies; refuse binding; return a null Binder; die mid-request; and withhold a
finish acknowledgement. For each case inspect typed state transitions, opaque
IDs, epoch, payload counts, duration bucket, and failure category—not payload
contents.

Inspect `diagnosticsSnapshot()` in a debugger when a candidate appears wrong.
Check in order: editor generation, selected and bound component, binding
epoch/UID, admitted session, latest request, decoded range, and whether the
result was handled. When a service remains bound, check typing, UI/picker,
document, and finish-acknowledgement demand. The diagnostic ring is in-memory,
bounded, and intentionally cannot hold arbitrary text or exceptions.

## Known limits and upstream hot spots

- Discovery currently requires the exact protocol version; there is no
  capability negotiation or supported-version range.
- Cross-process provider failure coverage is still smaller than codec/unit
  coverage.
- The host facade coordinates too many concerns while staged extraction is in
  progress.
- A provider which never acknowledges session finish can retain binding demand;
  watchdog behavior needs an explicit compatibility decision.

Changes in
[`FlorisImeService.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt),
[`KeyboardManager.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard/KeyboardManager.kt),
`NlpManager`, `TextKeyboardLayout`, Smartbar, preferences, and editor code have
high upstream-conflict risk. Prefer adding behavior in the plugin/core packages
and adapting through a narrow interface.
