# Repository guide for coding agents

Treat the checked-in documentation and tests as the source of truth. Do not
infer keyboard behavior from a single call site.

Before changing code:

1. Read [`CONTRIBUTING.md`](CONTRIBUTING.md).
2. Read the relevant entry in [`docs/features`](docs/features/README.md).
3. For external autocorrect, also read
   [`docs/architecture/autocorrect-host-core.md`](docs/architecture/autocorrect-host-core.md)
   and [`AUTOCORRECT_PLUGIN_API.md`](AUTOCORRECT_PLUGIN_API.md).
4. Inspect existing tests beside the code before adding a new abstraction.

Keep keyboard data private. Never log typed text, candidates, dictionary
entries, clipboard content, raw pointer events, coordinates, editor metadata,
or raw protocol bundles. Use opaque IDs, counts, state names, duration buckets,
and typed failure categories.

Prefer pure Kotlin tests over Robolectric, and Robolectric over device tests.
Use a device only for behavior that depends on Android event dispatch or a real
cross-process Binder boundary. Run the narrow feature command while iterating,
then `./gradlew qualityGate` before handing off a change.

Keep changes to upstream hot spots small. Put new state rules in fork-owned
core or feature files and use narrow adapters in `FlorisImeService`,
`KeyboardManager`, `NlpManager`, and `TextKeyboardLayout`. Update the relevant
feature document when ownership, invariants, failure behavior, privacy, or test
commands change.
