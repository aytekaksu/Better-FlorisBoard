# Upstream synchronization

Better FlorisBoard preserves upstream history but owns its fork-specific
architecture and quality rules. Synchronize in small, reviewable batches; do
not mix an upstream import with feature work.

## Before syncing

- Record the upstream and fork commit IDs.
- Ensure the fork branch passes `./gradlew qualityGate`.
- Review upstream changes to the toolchain, application lifecycle, editor,
  NLP, keyboard layout, preferences, resources, and build variants.
- Keep generated translations out of the sync unless the fork has explicitly
  adopted a translation source.

## Conflict policy

Preserve upstream fixes first, then reapply the smallest fork adapter. The
highest-conflict files are `FlorisImeService`, `KeyboardManager`, `NlpManager`,
`TextKeyboardLayout`, preferences, resources, and Gradle catalogs. New host
state, protocol, diagnostics, and feature documentation should remain in
fork-owned files whenever possible.

Do not resolve a conflict by copying either whole file without comparing
behavior. Check eligibility, private-input rules, editor-generation changes,
cancellation, fallback, and lifecycle cleanup explicitly.

## After syncing

Run:

```shell
./gradlew qualityGate
./gradlew :benchmark:assembleBenchmark
```

Run focused device tests when the import changes Android input dispatch,
service binding, IME lifecycle, or manifest components. Update feature
documentation if ownership, behavior, or commands changed. The sync commit
should describe upstream range, manual resolutions, and tests actually run.
