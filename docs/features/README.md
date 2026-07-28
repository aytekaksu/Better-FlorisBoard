# Feature map

These documents describe how user-facing behavior is implemented and protected.
They are written for maintainers and contributors: read the relevant document
before changing a feature, then update it in the same pull request when an
invariant, owner, data flow, failure rule, performance budget, or test command
changes.

| Feature | Main responsibility | Start here |
| --- | --- | --- |
| External autocorrect | Discover, bind, validate, and coordinate separately installed suggestion engines | [Autocorrect plugins](autocorrect-plugins/README.md) |
| Text-keyboard touch | Turn Android pointer streams into keys, popups, swipes, editor actions, and bounded input traces | [Touch input](touch-input/README.md) |
| Glide typing | Detect a word gesture, classify it externally or locally, and commit only a current result | [Glide typing](glide-typing/README.md) |

The [provider API guide](../../AUTOCORRECT_PLUGIN_API.md) is the authoritative
provider-facing protocol guide. Feature documents explain the host and do not
duplicate that contract.

Cross-cutting references:

- [Autocorrect host architecture](../architecture/autocorrect-host-core.md)
- [Testing strategy](../testing.md)
- [Debugging and diagnostics](../debugging.md)
- [Protocol version history](../protocol/version-history.md)
- [Upstream synchronization](../upstream-sync.md)

## Rules shared by every feature

- Keyboard input is sensitive. Logs and diagnostics must not contain typed
  text, candidates, clipboard contents, dictionary entries, raw touch paths, or
  target-application content.
- UI-thread input handling must not wait for disk, network, Binder replies, or
  model inference.
- Asynchronous results must prove that their editor generation, session,
  provider, layout, and request are still current before changing UI or text.
- Cancellation and lifecycle loss are normal states, not exceptional cleanup.
- Tests should assert observable behavior and state invariants. Use a device
  only when Android framework or cross-process behavior is the subject.
- Fork-specific code should reach upstream hot spots through the narrowest
  interface possible.

## Documentation review

A useful feature document answers three questions without requiring a code
search:

1. Where does an event enter and who owns its state?
2. What may happen when work fails, arrives late, or is cancelled?
3. Which command proves a behavior change is safe?

Delete obsolete guidance instead of appending a second explanation. Link exact
source files and keep build-tool versions in Gradle rather than copying them
into prose.
