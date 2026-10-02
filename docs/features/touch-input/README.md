# Text-keyboard touch input

## Purpose and boundaries

This feature converts Android `MotionEvent` streams into tap, long-press,
popup, swipe, multi-pointer, glide, feedback, and editor actions. It also
captures a bounded, normalized tap trace for an eligible external autocorrect
request.

It does not rank words, own editor text, or define provider behavior. Glide
classification is covered by the [glide feature](../glide-typing/README.md);
provider transport is covered by
[autocorrect plugins](../autocorrect-plugins/README.md).

## Source map

| Area | Source |
| --- | --- |
| Compose surface, event controller, pointer state, swipes, trace capture | [`TextKeyboardLayout.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKeyboardLayout.kt) |
| Pure key-transition policies | [`TextKeyboardInteractionPolicy.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKeyboardInteractionPolicy.kt) |
| Key layout and hit testing | [`TextKeyboard.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKeyboard.kt) |
| Key model and visible/touch bounds | [`TextKey.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKey.kt) |
| Popup ordering and drag hit testing | [`PopupUiController.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/popup/PopupUiController.kt) |
| Swipe detector | [`SwipeGesture.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/SwipeGesture.kt) |
| Glide detector | [`GlideTypingGesture.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingGesture.kt) |
| Semantic key dispatch | [`KeyboardManager.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard/KeyboardManager.kt) |
| Audio/haptic feedback and service-owned worker jobs | [`InputFeedbackController.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/input/InputFeedbackController.kt) |
| Localized IME-action labels and Android fallback | [`FlorisImeService.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt) |
| Fast state and hit-test tests | [`app/src/test/.../keyboard`](../../../app/src/test/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/) |
| Popup position tests | [`PopupUiControllerGeometryTest.kt`](../../../app/src/test/kotlin/dev/patrickgold/florisboard/ime/popup/PopupUiControllerGeometryTest.kt) |
| Real MotionEvent scenarios | [`TextKeyboardTouchE2eTest.kt`](../../../app/src/androidTest/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKeyboardTouchE2eTest.kt) |

## Data, state, and lifecycle

```text
MotionEvent → pointer registry → swipe/glide arbitration → key hit test
                    │                        │
                    │                        └─ gesture owner or ordinary touch
                    ▼
       active key + popup + long press → semantic input event → editor
                    │
                    └─ eligible word tap → bounded normalized trace
```

One controller belongs to one remembered `TextKeyboard`. It owns active
pointers, key press state, gesture detectors, popup state, selection dragging,
glide drawing data, and the current input-layout snapshot. Pause, disposal,
layout disablement, or `ACTION_CANCEL` must cancel pending input, release keys,
hide popups, and clear gesture state.

Every pointer has one active key at most. Moving outside the key plus hysteresis
either transfers ownership to another key or cancels it. Adding a pointer
commits applicable active text keys before admitting the new pointer. A glide
claims the sequence only after its threshold is met; ordinary key state is then
cancelled.
Extended popups keep the highest-priority choices near the held key. Left and
right anchors use the same hit rule, including the existing selection at cell
edges and a small margin outside the drawn popup.
Key iteration visits populated rows in layout order. Empty rows keep their
spacing but do not hide the keys after them.

## Concurrency and ordering

Pointer mutation and drawing state are main-thread owned. Expensive suggestion
or glide work is handed to feature managers. Long-press callbacks and delayed
work must re-check that their pointer and key are still active.

`FlorisImeService` supplies feedback its lifecycle scope. Audio and haptic
requests stay asynchronous on `Dispatchers.Default`. Destroying the service
cancels its feedback jobs; an already-entered platform call may still finish.
Hiding the keyboard does not destroy this scope.

Semantic input order is more important than callback completion order.
Multi-pointer transitions, gesture completion, and selection dragging must
resolve or cancel their pending editor event exactly once. A stale prediction
hint or trace may improve neither hit testing nor suggestions.
Each key callback sees the event history from before its own key; nested input
advances that history before the outer callback returns.
Forward deletion stages the unchanged cursor before editing, so its selection
acknowledgement can consume the expected content before the next input.

`FlorisLocale` gates automatic shift and default suggestion/phantom spacing.
Japanese `ja`, including regional variants, disables both; explicit suggestion
separator choices still apply.

`FlorisApplication` owns one lazy keyboard state and input dispatcher.
`KeyboardManager` receives both and registers as the dispatcher's receiver
before handling input. The editor reads the same state and uses a live
pressed-Shift reader; it never constructs or looks up `KeyboardManager`.
Shift rechecks remain at the editor's existing content-publication and
invalid-selection paths.
`FlorisApplication` also supplies the editor a lazy composing policy and a live
subtype getter. NLP still selects the active language provider, while the editor
reads punctuation rules from its existing keyboard-extension snapshot.
The application supplies a live nullable input-connection reader as well. Each
editor operation resolves the current IME service connection; neither the editor
nor the application callback retains a service instance or connection.
It also supplies `KeyboardManager` a live nullable IME-action reader. Each key
event uses one current service snapshot for feedback, window controls, IME
switching, and Settings; key-up still does nothing when no service is active.
The evaluator reads the same port for the floating-window icon and treats a
missing service as fixed mode.

## Privacy

Raw `MotionEvent` objects and physical coordinates stay inside the keyboard
process. The optional autocorrect tap trace contains only normalized key bounds,
normalized tap positions, and emitted key text; it is cleared when content,
layout, session, or eligibility no longer matches. Password, raw, and incognito
input never opens an external typing session.

Do not log `MotionEvent.toString()`, key output, coordinates, popup text, editor
content, or a trace. Debug views may draw touch bounds on the local device but
must not persist or export input.

## Failure and fallback

- Missing hit target after hysteresis cancels the active key.
- `ACTION_CANCEL`, pause, disposal, or an unexpected fresh down resets the
  entire pointer sequence.
- A long press suppresses incompatible glide ownership.
- Invalid selection-drag state cancels the gesture rather than guessing an
  editor range.
- A trace mismatch discards the trace; normal typing and built-in suggestions
  continue.
- External prediction hints are optional and leased to a pointer. Ordinary hit
  testing remains the fallback.
- Missing Android IME-action labels fall back to the platform label; `NONE`
  has no label.

No cleanup path may commit a key merely to make internal state consistent.

## Performance budget

- `ACTION_DOWN`, `MOVE`, and `UP` processing must not perform disk access,
  Binder waits, model inference, or blocking coroutine work.
- Allocation count per move event must remain effectively constant with
  gesture length; bounded history is processed incrementally.

## Verification

Fast state, hit-test, and transition rules:

```shell
./gradlew :app:testDebugUnitTest
```

For feedback's audio output, worker routing, and lifecycle-job ownership:

```shell
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.ime.input.InputFeedbackControllerTest'
```

Run the focused device suite only when real Android touch dispatch, timing,
multi-pointer behavior, popups, or editor integration changes:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardTouchE2eTest
```

For editor-to-keyboard state wiring and shift callback ordering:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.editor.EditorKeyboardStateAndroidTest
```

Keep E2E scenarios independent and named. Do not place unrelated gestures in
one test, because an early failure prevents later behavior from being checked.

## Debugging and fault injection

Use the [debugging guide](../../debugging.md) and the linked real-MotionEvent
suite. Exercise down in key gaps, boundary hysteresis, long-press/popup
transitions, missing pointer indexes, delete/spacebar selection drags,
accessibility, and stale prediction hints.

## Known limits

- View-based haptics still run off Main. Moving them needs separate responsiveness
  proof: API26 uses synchronous Binder here. The JVM audio test does not prove
  haptic hardware, fallback behavior, or timing.
- The real-touch fixture currently assumes a fixed keyboard window; floating mode adds a caption row below the keys.
- Rendering, touch control, glide trail, swipe actions, selection drag, popups,
  and autocorrect trace capture still share one large source file.
- Some cleanup catches broad failures to protect the IME; this makes defects
  hard to distinguish without typed invariant reporting.
- Full MotionEvent coverage is instrumented and therefore slower than the
  desired semantic JVM fixture.
