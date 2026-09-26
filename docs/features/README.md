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
| Preferences | Define stored settings and preserve supported upgrade and restore behavior | [Preferences](preferences/README.md) |
| Backup and restore | Validate archive contents and plan exactly which selected data may change | [Backup and restore](backup-restore/README.md) |
| Clipboard | Synchronize text and media while keeping history, URI grants, and private files consistent | [Clipboard](clipboard/README.md) |
| Emoji palette | Load bundled emoji data without blocking keyboard composition | [Emoji palette](emoji/README.md) |
| User dictionary | Browse, edit, import, and export internal and system words without blocking settings | [User dictionary](user-dictionary/README.md) |
| Extensions | Import, validate, install, edit, export, and load keyboard, theme, and language packages | [Extensions](extensions/README.md) |

The [provider API guide](../../AUTOCORRECT_PLUGIN_API.md) is the authoritative
provider-facing protocol guide. Feature documents explain the host and do not
duplicate that contract.

Cross-cutting references:

- [Autocorrect host architecture](../architecture/autocorrect-host-core.md)
- [Testing strategy](../testing.md)
- [Debugging and diagnostics](../debugging.md)
- [Protocol version history](../protocol/version-history.md)
- [Upstream synchronization](../upstream-sync.md)

Never block UI-thread input on disk, network, Binder replies, or model
inference. Follow the [contribution rules](../../CONTRIBUTING.md); each
feature defines its own freshness, cancellation, and failure checks.

## Documentation review

A useful feature document answers three questions without requiring a code
search:

1. Where does an event enter and who owns its state?
2. What may happen when work fails, arrives late, or is cancelled?
3. Which command proves a behavior change is safe?

Delete obsolete guidance instead of appending a second explanation. Link exact
source files and keep build-tool versions in Gradle rather than copying them
into prose.
