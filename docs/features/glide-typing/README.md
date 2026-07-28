# Glide typing

## Purpose and boundaries

Glide typing recognizes a single-pointer path over character keys and commits a
word from either the selected external autocorrect provider or the built-in
statistical classifier. It can publish a local preview while the gesture is in
progress.

The feature does not decide general tap/swipe behavior, own the editor, or let
an external provider commit text directly. See [touch input](../touch-input/README.md)
for event arbitration and
[autocorrect plugins](../autocorrect-plugins/README.md) for provider transport.

## Source map

| Area | Source |
| --- | --- |
| Gesture threshold and point collection | [`GlideTypingGesture.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingGesture.kt) |
| Async coordination, provider fallback, preview, commit | [`GlideTypingManager.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingManager.kt) |
| Classifier boundary and key geometry | [`GlideTypingClassifier.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingClassifier.kt) |
| Built-in geometry/ranking algorithm | [`StatisticalGlideTypingClassifier.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/StatisticalGlideTypingClassifier.kt) |
| Touch ownership and trail drawing | [`TextKeyboardLayout.kt`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKeyboardLayout.kt) |
| Detector device tests | [`GlideTypingGestureDetectorTest.kt`](../../../app/src/androidTest/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingGestureDetectorTest.kt) |
| Classifier and lifecycle tests | [`app/src/test/.../gestures`](../../../app/src/test/kotlin/dev/patrickgold/florisboard/ime/text/gestures/) |

## Data, state, and lifecycle

```text
single-pointer MotionEvents
        │ distance + velocity + eligible start/end keys
        ▼
recognized gesture → immutable points + layout/subtype revision
        │
        ├─ selected provider handles → validate current → commit first candidate
        │
        └─ unhandled/failure ────────→ built-in classifier → commit first word
                                                       │
                                                       └─ publish alternatives
```

Glide is enabled only for a rich, non-password character editor. A second
pointer suppresses detection for the remainder of that touch sequence. The
detector starts as undecided, becomes a gesture after its distance and velocity
threshold, or becomes an ordinary touch after the detection window.

`GlideTypingManager` owns the active detector, layout revision, preview
generation, pending completion, and serialized built-in classifier. Layout,
subtype, editor generation, or editor content changes invalidate old work.
Disposal, pause, disablement, and newer semantic input cancel or supersede the
pending gesture.

## Concurrency and ordering

Detection and drawing state are main-thread owned. Provider and built-in
classification run off the main thread. A mutex serializes the mutable built-in
classifier. Editor publication and commit return to the main thread.

Input events after a completed glide are deferred behind its semantic-input
barrier. That barrier must resolve exactly once even if provider transport,
classification, candidate publication, or cancellation fails. A result may
commit only when the pending object, subtype, layout revision, editor
generation, selection, and content still match.

Preview and completion have different generations. A completion clears only
the preview it supersedes; an old preview must not erase newer suggestions.

## Privacy

The built-in classifier stays in process. An eligible external provider may
receive up to 128 sampled points with normalized coordinates and bounded
elapsed times, plus normalized key bounds. It does not receive physical screen
coordinates. Incognito, password, and raw-editor rules from the plugin feature
still apply.

Do not log or export paths, coordinates, classified words, alternatives,
editor content, or provider payloads. Accuracy fixtures must use synthetic
gestures and non-personal dictionaries.

## Failure and fallback

- An unrecognized path remains ordinary touch input.
- Cancellation clears preview and gesture state without committing.
- Provider `Unhandled`, transport failure, or an unusable current result falls
  back to the built-in classifier.
- A handled empty provider result is authoritative; it clears glide
  suggestions and does not invoke the built-in classifier.
- Empty or failed built-in classification resolves the input barrier without a
  commit.
- A stale result is discarded. It must never be rebased onto unrelated editor
  content.

Alternative candidates are rebased after the primary commit and remain
available only while their provider candidate can still be committed.

## Performance and accuracy budget

- Detection and trail drawing must not run model inference or block on Binder.
- Touch-path processing should stay below 8 ms p95 per event.
- Built-in preview work is rate-limited by the configured refresh delay and
  previous preview work is cancelled.
- Provider traces stay within 128 points; the built-in classifier uses bounded
  resampling, tokenization, and small caches.
- A classifier change must not reduce top-1 or top-3 accuracy on the fixed
  gesture corpus. A performance change must not regress p95 completion latency
  or allocations by more than 10% without a documented accuracy gain.

Do not tune thresholds or pruning constants using a few hand-drawn words.
Record the corpus, device, cold/warm state, latency distribution, and accuracy
before accepting an optimization.

## Verification

Fast classifier, timing, cancellation, and candidate-rebase tests:

```shell
./gradlew :app:testDebugUnitTest
```

Run the focused detector test after changing Android event interpretation:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.text.gestures.GlideTypingGestureDetectorTest
```

Run the touch E2E suite only when gesture arbitration or actual commit
integration changes:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardTouchE2eTest
```

## Debugging and fault injection

Use a fixed synthetic layout and gesture corpus. Test: a slow undecided path;
fast threshold crossing; start or end on an ineligible key; second pointer;
cancel at every phase; layout/subtype/editor replacement; later input queued
before provider completion; handled-empty and unhandled provider replies;
provider death; stale preview; empty dictionary; multi-code-point key outputs;
and combining-mark/locale aliases.

Safe diagnostics are gesture generation, point count, duration bucket, layout
revision, classifier source, candidate count, current/stale decision, fallback
reason, and total latency bucket. They must not include path coordinates or
words.

## Known limits and upstream hot spots

- The built-in classifier is stateful and protected by one mutex; concurrent
  previews and completions are serialized.
- There is no checked-in cross-language accuracy corpus or active benchmark
  gate yet.
- Provider completion currently waits for provider resolution unless newer
  input cancels the attempt; a measured presentation policy remains future
  work.
- Gesture and tap arbitration still lives inside the large keyboard layout
  controller.

`TextKeyboardLayout`, `KeyboardManager`, `NlpManager`, subtype/layout loading,
preferences, and editor state are upstream conflict hot spots. Keep classifier
math pure, pass immutable revisions, and expose only narrow commit and
suggestion ports.
