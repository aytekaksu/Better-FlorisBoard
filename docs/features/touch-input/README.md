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
| Swipe detector | [`SwipeGesture.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/SwipeGesture.kt) |
| Glide detector | [`GlideTypingGesture.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingGesture.kt) |
| Semantic key dispatch | [`KeyboardManager.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard/KeyboardManager.kt) |
| Fast state and hit-test tests | [`app/src/test/.../keyboard`](../../../app/src/test/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/) |
| Deterministic editor fixture | [`DeterministicInputConnection.kt`](../../../app/src/test/kotlin/dev/patrickgold/florisboard/test/editor/DeterministicInputConnection.kt) |
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

## Concurrency and ordering

Pointer mutation and drawing state are main-thread owned. Expensive suggestion
or glide work is handed to feature managers. Long-press callbacks and delayed
work must re-check that their pointer and key are still active.

Semantic input order is more important than callback completion order.
Multi-pointer transitions, gesture completion, and selection dragging must
resolve or cancel their pending editor event exactly once. A stale prediction
hint or trace may improve neither hit testing nor suggestions.

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

No cleanup path may commit a key merely to make internal state consistent.

## Performance budget

- `ACTION_DOWN`, `MOVE`, and `UP` processing must not perform disk access,
  Binder waits, model inference, or blocking coroutine work.
- Pointer event handling should remain below 8 ms p95 and 16 ms p99 on a
  reference development device.
- Allocation count per move event must remain effectively constant with
  gesture length; bounded history is processed incrementally.
- A touch-path change must not regress measured p95 latency or allocations by
  more than 10%. Record before/after data until a checked-in benchmark baseline
  is available.

## Verification

Fast state, hit-test, and transition rules:

```shell
./gradlew :app:testDebugUnitTest
```

Run the focused device suite only when real Android touch dispatch, timing,
multi-pointer behavior, popups, or editor integration changes:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardTouchE2eTest
```

Keep E2E scenarios independent and named. Do not place unrelated gestures in
one test, because an early failure prevents later behavior from being checked.

## Debugging and fault injection

Use synthetic layouts and text. Exercise: down in key gaps; boundary
hysteresis; move outside all keys; long press followed by move; popup
selection; second pointer with an active key; pointer ID reuse; missing pointer
index; cancellation at every action; layout replacement mid-touch; delete and
spacebar selection drags; accessibility; and stale prediction hints.

Useful safe observations are pointer count, opaque pointer ID, activation
source, key code category, state transition, gesture owner, elapsed-duration
bucket, and cancellation reason. Never include emitted text or coordinates.

## Known limits and upstream hot spots

- Rendering, touch control, glide trail, swipe actions, selection drag, popups,
  and autocorrect trace capture still share one large source file.
- Some cleanup catches broad failures to protect the IME; this makes defects
  hard to distinguish without typed invariant reporting.
- Full MotionEvent coverage is instrumented and therefore slower than the
  desired semantic JVM fixture.

`TextKeyboardLayout`, `TextKeyboard`, `KeyboardManager`, popup controllers,
`FlorisImeService`, preferences, and editor code are upstream conflict hot
spots. Extract pure transition decisions first; keep Compose and Android event
adaptation thin.
