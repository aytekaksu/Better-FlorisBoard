# Backup and restore

`BackupArchive` defines the pure contract for deciding whether an archive is
structurally eligible for bounded staging and what a restore may change.
Preflight first consumes a bounded sequence of ZIP entry facts. Only after that
succeeds may the caller bounded-decode the exact metadata and optional manifest
entries and finalize inspection. This contract does not extract payloads or
mutate live app data.

## Stable archive layout

Archives from version code 64 onward use these paths:

| Path | Component |
| --- | --- |
| `backup_metadata.json` | Required archive provenance |
| `jetpref_datastore/florisboard-app-prefs.jetpref` | Preferences |
| `files/ime/keyboard/**` | Keyboard extensions |
| `files/ime/theme/**` | Theme extensions |
| `clipboard/clipboard_text_items.json` | Clipboard text |
| `clipboard/clipboard_images.json` | Clipboard images |
| `clipboard/clipboard_video.json` | Clipboard videos |
| `clipboard/clipboard_files/<numeric-id>` | Media shared by clipboard images and videos |

Clipboard entries first shipped in version code 95; some development archives
still report 94. Components are therefore recognized from their paths, not the
source app version. A component may be omitted from any archive. Omitted means
unavailable and must never be treated as empty or reset during restore. An
explicit empty `files/ime/keyboard/` or `files/ime/theme/` directory is a
present, empty component. Extension subtrees stay opaque, and explicit parent
directory entries are optional.

The retired `files/ime/spelling/**` tree and safe unknown entries are ignored
with aggregate warnings. They still count toward resource limits. This keeps
old and forward-compatible archives readable without letting unknown content
enter a restore plan.

New archives may add `backup_manifest.json`. When it is present, it is
authoritative: it must decode, use the supported format version, and its known
components must exactly match the known archive entries. A malformed,
unsupported, or mismatched manifest fails closed instead of falling back to
legacy path inference. A decoded manifest without its archive entry also
fails. Well-formed unknown component IDs remain forward-compatible and are
ignored with a warning.

## Validation boundary

Inspection rejects the whole archive before payload extraction or live
mutation when it finds an unsafe or ambiguous path, duplicate or conflicting
entries, unsupported entry types or compression, inconsistent size or checksum
facts, invalid metadata, or an archive with nothing restorable. Failures are
closed enum values; error and diagnostic text must not retain entry paths,
component IDs, payload values, or exception messages.

Default limits are:

| Budget | Limit |
| --- | ---: |
| ZIP file | 4 GiB |
| Entries | 10,000 |
| Expanded data | 8 GiB |
| One regular entry | 100 MB |
| Metadata / manifest | 16 KiB each |
| Preferences or one clipboard index | 32 MiB |
| Path / path segment | 1,024 / 255 UTF-8 bytes |

This static envelope keeps historically large archives inspectable; it is not
a promise that every device can stage the maximum. The ZIP adapter must lower
runtime budgets to available private storage and enforce actual copied and
expanded byte counts and checksums again while staging. Absolute limits are
used instead of a compression-ratio rule so legitimate, highly compressible
preferences and clipboard text remain compatible. The adapter must also expose
encryption and Unix entry-kind facts from the central directory;
`java.util.zip.ZipEntry` alone does not provide enough information.

## Restore plans

A plan is created only from a validated archive and a non-empty selection of
available components. Output order and declared-payload totals are
deterministic.

- `MERGE` applies the selected payloads without reset operations.
- `REPLACE_SELECTED` records a conditional reset scope for exactly the
  selected, structurally valid, present components.
- Apply order is preferences, keyboard extensions, themes, then clipboard
  text, images, and videos.

This structural plan is not a commit plan. The executor must stage and
semantically validate every selected payload first. Only then may a
rollback-safe commit run the recorded reset scope followed by the apply phase.

Clipboard media is not independently selectable. It is staged once when
clipboard images or videos are selected and is otherwise ignored. Its policy
requires the later executor to semantically validate selected indexes, copy
only their references during merge, and preserve references retained by
unselected indexes during replacement. A plan never authorizes blanket
deletion of shared media. `declaredPayloadBytes` is a validated lower bound,
not a free-space estimate.

The current backup and restore screens are not wired to this contract yet. The
next integration slice must add a ZIP scanner, selective extraction into a
private staging area, runtime quota and checksum checks, and rollback-safe
commit. Until that work lands, this contract must not be described as
protection provided by the live UI.

## Verification

Run the archive and plan contract tests with the app JVM suite:

```shell
./gradlew :app:testDebugUnitTest
```

Run `./gradlew qualityGate` before merging a backup-format or restore-policy
change.
