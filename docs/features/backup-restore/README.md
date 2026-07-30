# Backup and restore

`BackupArchive` defines what a valid archive may contain and what a restore may
change. The live restore screen now copies the selected document into private
storage, validates the ZIP, and stages only the selected components before it
touches live app data.

This is a structural and byte-integrity boundary, not a transactional restore.
The current commit step still has the important limitations listed below.

## Stable archive layout

Archives from version code 64 onward use these paths:

| Path | Component |
| --- | --- |
| `backup_metadata.json` | Required archive provenance |
| `backup_manifest.json` | Optional declared component list |
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

New backups emit a version 1 `backup_manifest.json` and explicitly create the
keyboard and theme roots when those components are selected. This preserves
the difference between an omitted component and a selected component that is
empty. Older archives may omit the manifest.

When a manifest is present, it is authoritative: it must decode, use the
supported format version, and its known components must exactly match the known
archive entries. A malformed, unsupported, or mismatched manifest fails closed
instead of falling back to legacy path inference. Well-formed unknown component
IDs remain forward-compatible and are ignored with a warning.

## Validation boundary

Backup creation uses the same path, entry, source-byte, per-file, and final ZIP
limits as restore. Source links and special files are rejected, copies and
compression cooperate with cancellation, and a completed internal archive is
published atomically. The generated archive is then opened through the normal
restore session before it can be shared or saved. Entry order and timestamps
are stable, so the same staged input produces the same ZIP bytes.

The live flow is deliberately narrow:

1. The selected `content:` document is read exactly once into a counted private
   snapshot. Provider size metadata is not trusted.
2. A bounded EOCD/ZIP64 scan checks the central directory before a ZIP reader is
   opened. The central directory is capped at 16 MiB, and entry names, extras,
   and comments are bounded before the ZIP reader can allocate them.
3. One session keeps the validated archive tied to the exact ZIP entries that
   produced it. Metadata and the optional manifest use strict UTF-8 decoding,
   bounded reads, exact sizes, and CRC checks.
4. The UI enables only components that the validated archive actually contains.
5. The restore plan stages only the selected entries. Staging checks actual
   byte counts and CRCs again, enforces live free-space limits, creates new
   files without following links, and atomically publishes the completed
   private tree.
6. Expected failures use closed, content-free values. Diagnostics do not retain
   archive paths, URIs, payload values, or exception messages.

Inspection rejects the whole archive before staging when it finds an unsafe or
ambiguous path, duplicate or conflicting entries, unsupported entry types or
compression, inconsistent size or checksum facts, invalid metadata, or nothing
restorable.

Default limits are:

| Budget | Limit |
| --- | ---: |
| ZIP file | 4 GiB |
| Entries | 10,000 |
| Central directory | 16 MiB |
| Expanded data | 8 GiB |
| One regular entry | 100 MB |
| Metadata / manifest | 16 KiB each |
| Preferences or one clipboard index | 32 MiB |
| Path / path segment | 1,024 / 255 UTF-8 bytes |
| ZIP extra / comment per entry | 4 KiB / 1 KiB |

The configured snapshot limit defaults to 4 GiB, but the runtime limit is lower
when private storage is tight and keeps a 128 MiB reserve. Staging separately
applies its expanded-data limit against current free space with the same
reserve. These limits are ceilings, not a promise that every device can stage
the maximum.

Absolute limits are used instead of a compression-ratio rule so legitimate,
highly compressible preferences and clipboard text remain compatible. The ZIP
session also checks encryption and Unix entry kinds from the central directory;
`java.util.zip.ZipEntry` alone does not expose enough information.

## Restore plans

A plan is created only from a validated archive and a non-empty selection of
available components. Output order and declared-payload totals are
deterministic.

- `MERGE` applies the selected payloads without reset operations.
- `REPLACE_SELECTED` records a conditional reset scope for exactly the
  selected, structurally valid, present components.
- Apply order is preferences, keyboard extensions, themes, then clipboard
  text, images, and videos.

This structural plan is not a commit plan. The live screen now completes
bounded staging before calling the existing executor, but the executor does not
yet provide semantic validation, rollback, or durable completion.

Clipboard media is not independently selectable. Candidate media is staged
once when clipboard images or videos are selected and is otherwise ignored. A
plan never authorizes blanket deletion of shared media.

## Important current limitations

The current restore commit step is **not transactional**. A structurally valid and
byte-complete stage can still fail after live state has started changing.
These parts remain deferred:

- Semantic validation of preferences, keyboard and theme extensions, and
  clipboard indexes before commit.
- Two-phase clipboard media handling: validate selected indexes first, then
  stage and copy only referenced media while preserving references kept by
  unselected indexes.
- Replacement of only the selected clipboard types. The existing executor can
  still clear broader clipboard history and shared media.
- A durable, suspendable commit that finishes database and provider work before
  reporting success.
- Rollback and process-death recovery for interrupted or failed commits.

Until those pieces land, staging protects the live app from malformed ZIP
structure, unsafe paths, resource overruns, and corrupt selected entry bytes. It
does not guarantee an all-or-nothing restore.

## Verification

Run the archive and plan contract tests with the app JVM suite:

```shell
./gradlew :app:testDebugUnitTest
```

Run `./gradlew qualityGate` before merging a backup-format or restore-policy
change.

The Android storage behavior has a focused connected test:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
dev.patrickgold.florisboard.app.settings.advanced.BackupArchiveStagerAndroidTest
```
