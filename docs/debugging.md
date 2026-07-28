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

## Safe fault injection

Use synthetic fixtures to delay, duplicate, reorder, reject, or corrupt
provider replies; refuse binding; return a null Binder; die during a request;
and withhold finish acknowledgement. For touch and glide, inject cancellation,
pointer reuse, layout changes, stale previews, and empty classifier results.

Expected failures should be represented by typed results, not broad
`Throwable` logging. Unexpected exceptions may record only the exception class
in ordinary diagnostics; a local crash report remains the detailed developer
artifact.

## Log export

The developer logcat export is limited to the current app process, a bounded
line count, and a bounded byte size. Review it before sharing. It can still
contain data written by Android or third-party libraries, so remove editor
content, target-app identity, paths, accounts, and device details.

Never add temporary logs containing:

- typed, composing, candidate, clipboard, or dictionary text;
- raw `MotionEvent`, coordinates, traces, or protocol Bundles;
- `EditorInfo` summaries, private IME options, hint text, or action labels;
- full exception strings if their messages can include input.

The `privacySourceCheck` merge gate catches common unsafe interpolation
patterns, but review remains required.

## Performance investigation

Measure before changing an algorithm. Use trace sections around host-side bind,
session start, request serialization/send, response decode, fallback, touch
dispatch, and glide classification. Record p50/p95/p99, allocations, device,
API level, build variant, and cold/warm state. Provider inference is reported
separately from host overhead.
