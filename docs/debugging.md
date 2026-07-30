# Debugging and diagnostics

Start with the smallest observable boundary: editor fixture, pure host state,
protocol codec, Android adapter, then device interaction. This avoids treating
every incorrect suggestion as a Binder or model problem.

## External autocorrect checklist

Inspect in this order:

1. editor generation and eligibility;
2. selected provider and discovery result;
3. binding epoch and expected UID;
4. admitted session and configuration;
5. latest request lease;
6. decoded payload bounds and replacement range;
7. accepted, rejected, cancelled, or fallback decision;
8. provider-health state and recovery timer.

The in-memory diagnostics ring stores only typed events, opaque IDs, counts,
state, duration buckets, and error categories. It is bounded and process-local.
It must never accept arbitrary strings, protocol objects, text, candidates,
dictionaries, touch data, editor packages, or exception messages.

The clipboard, input-state, and spelling overlays follow the same rule: they
show only content-free types, states, flags, and counts.

## Safe fault injection

Use synthetic fixtures to delay, duplicate, reorder, reject, or corrupt
provider replies; refuse binding; return a null Binder; die during a request;
and withhold finish acknowledgement. For touch and glide, inject cancellation,
pointer reuse, layout changes, stale previews, and empty classifier results.

Expected failures should be represented by typed results, not broad
`Throwable` logging. Unexpected exceptions may record only the exception class
in ordinary diagnostics; a local crash report remains the detailed developer
artifact.

## Diagnostic report export

The developer report uses a bounded, process-local snapshot of app-owned
diagnostics in every build. Release builds retain this snapshot without writing
it to Logcat and capture only warnings and errors to keep input paths cheap. The
report never reads raw logcat, so Android and third-party messages cannot enter
it. Review device and configuration details before sharing.

Never add temporary logs containing:

- typed, composing, candidate, clipboard, or dictionary text;
- raw `MotionEvent`, coordinates, traces, or protocol Bundles;
- `EditorInfo` summaries, private IME options, hint text, or action labels;
- full exception strings if their messages can include input.

The `privacySourceCheck` merge gate catches common unsafe interpolation
patterns, but review remains required.

Run it directly with `./gradlew privacySourceCheck`.

## Performance investigation

Measure before changing an algorithm. Use trace sections around host-side bind,
session start, request serialization/send, response decode, fallback, touch
dispatch, and glide classification. Record p50/p95/p99, allocations, device,
API level, build variant, and cold/warm state. Provider inference is reported
separately from host overhead.
