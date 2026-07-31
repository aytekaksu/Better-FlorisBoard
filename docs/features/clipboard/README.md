# Clipboard

## Ownership

`ClipboardManager` owns the current internal clip and history. All database,
system-clipboard, import, and media-retirement decisions run through one FIFO
actor. Android listener callbacks are coalesced, fenced through the main loop,
and checked again before a result changes actor state.

`ClipboardFileStorage` owns media copied into private no-backup storage. Its
Room metadata records the file state and durable system or paste roots.
`ClipboardMediaProvider` exposes only canonical, app-owned media URIs and opens
regular files without following links. Its query, MIME, and data surfaces
require the app UID or the exact live read grant.
Android 13 and older do not preserve the requesting UID for every cold
`getType()` call, so the private provider omits that optional MIME result there;
grant-bound stream types and data access remain available.

Android 8 cannot delegate a private provider URI through its clipboard service.
On API 26–27 only, a separate root-only provider is therefore enabled as a
capability endpoint: an ungranted caller must present an exact random URI which
is both the current durable system root and present in the live platform
clipboard. The regular provider stays private, and newer Android versions use
platform URI grants.

Foreign providers are read through bounded private staging in a disposable,
same-UID worker process. Imports accept at most 100 MB and 30 seconds of
provider work. A timeout or cancellation terminates only that worker; the next
import starts a clean process instead of retaining a blocked app worker. There
is no direct-process fallback. A hostile provider may retain its own blocked
thread, but it cannot pin FlorisBoard's clipboard actor or future imports.

The exported share activity accepts only a granted `content:` URI from another
UID, then publishes a rooted app-owned snapshot with validated declared,
decoded, and provider-reported MIME metadata. Preview decoding is bounded,
isolated from the main thread, and optional: a valid `image/*` share still
publishes when Android's bitmap decoder does not support its format.
Normalized source display names are preserved through the private snapshot but
never logged; imports or restores without a name use `Image` or `Video`.
Each share request also gets an opaque token bound to that request in saved
state and stored uniquely with its media row. A process-restored activity
reuses the same owned file and URI, so retrying across the system-clipboard
write cannot create another media row, root, or history item. Tokens are not
included in backups or diagnostics. A pre-publication snapshot remains
claimable for 15 minutes; maintenance removes it after that deadline.

## Media lifetime

A media file moves through these rules:

1. Copy to a private partial file, validate its size and MIME metadata, then
   publish the file and metadata.
2. Mark history, internal-primary, system-primary, backup, and active paste
   references before they can outlive a mutation.
3. Mark an unreferenced file retiring. Delete it only after an exact,
   generation-current system observation proves it is not selected.
4. Keep any capability exposed during the current or an unknown device boot.
   A later known boot may retire it after fresh reconciliation.

When Android hides or cannot read clipboard state, storage fails closed and
retains the media. Quarantined media still counts toward the storage budget.

Editor media paste uses a short admission receipt and an in-memory lease. A
rejection before editor dispatch rolls the admission back. Once dispatch
begins, even a false result or exception keeps the capability until it is safe
to retire because the editor may already hold the grant. Input barriers are
resolved, abandoned, or invalidated exactly once when the editor generation
changes.

## Synchronization and privacy

With the internal clipboard disabled, set and clear events always mirror the
system clipboard. With it enabled, the two configured sync directions decide
which set and clear events cross the boundary. Startup and periodic maintenance
also observe and converge missed callbacks when Android permits an exact read.

Device lock hides history, and sensitive items use content-free previews.
Clipboard suggestions stay out of incognito mode and recheck device and editor
state before commit. Opening the clipboard panel and selecting an item remains
an explicit user action in incognito mode. Clipboard text, URIs, provider
paths, editor metadata, and exception messages must never enter logs or
diagnostics.

## Backup and failure behavior

Clipboard restore stages selected indexes, semantically validates their records
to find canonical media references, then stages only matching files. Media-file
validation finishes before install, and selected history types commit in one
Room transaction. Install receipts keep newly copied media removable until that
transaction commits.

The wider restore snapshots preferences and extensions, commits clipboard last,
and rolls touched components back after an in-process failure. It is not
crash- or power-loss safe; see [Backup and restore](../backup-restore/README.md).

A provider timeout, malformed payload, unavailable database, failed metadata
write, opaque system state, cancellation, or stale generation rejects the
operation or retains data. Cleanup never guesses that an externally exposed
file is safe to unlink.

## Verification

Run pure policies and payload contracts with:

```shell
./gradlew :app:testDebugUnitTest
```

Run the Android storage, migration, URI, observation, paste, restore, and
input-ordering coverage on each API used for release verification:

```shell
./gradlew :app:connectedDebugAndroidTest
```

Run `./gradlew qualityGate` before merging clipboard persistence, provider, or
privacy changes.
