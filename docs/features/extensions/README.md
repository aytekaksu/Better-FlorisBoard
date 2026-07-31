# Extensions

FlorisBoard packages keyboards, themes, and language data as `.flex` ZIP
archives. Bundled extensions use the same manifests but live in trusted APK
asset directories.

## Import boundary

Selected documents are untrusted, including their names and size metadata.
Import therefore follows one path:

1. Admit only an unambiguous external `content://` URI with exact read access.
2. Read provider metadata and bytes in a disposable app process with a hard
   deadline. Cancellation kills that worker instead of waiting for the provider.
3. Copy once into a generated private `.flex` file, count the real stream, and
   reject the first byte above 64 MiB.
4. Treat the provider name only as a bounded display label.
5. Validate the complete ZIP, then read `extension.json` as strict UTF-8.
6. Decode and structurally validate the manifest before extracting or exposing
   package data.

One selection is limited to 64 files, 256 MiB of source data, 512 MiB of
expanded data, and 16,384 entries. Reads and extraction also preserve 128 MiB
of storage headroom. Rejected packages remove their private source and
extraction; cancellation removes an undelivered workspace.

## Archive limits

The extension ZIP gate rejects multi-disk and ZIP64 containers before Commons
Compress builds its entry table. It also checks the latest end record, the
exact central-directory layout, local/central header agreement, bounded
metadata, and UTF-8 entry names.

| Budget | Limit |
| --- | ---: |
| ZIP file | 64 MiB |
| Entries | 4,096 |
| Expanded data | 128 MiB |
| One entry | 64 MiB |
| One control-file read | 8 MiB |
| Path / path segment | 512 / 255 UTF-8 bytes |
| Path depth | 16 segments |

Extraction rejects traversal, ambiguous or duplicate paths, file/directory
conflicts, links and special files, encryption, unsupported compression,
incorrect sizes, and CRC mismatches. It writes into a private sibling staging
directory and publishes only with an atomic move. A failure leaves the previous
destination unchanged and removes owned staging data.

APK assets are trusted input, but their directory copy still uses staged,
all-or-nothing publication.

## Manifest rules

Manifest validation is bounded and type-aware. It checks:

- a 1 MiB encoded size plus bounded JSON depth, containers, items, and tokens;
- package metadata, dependencies, links, maintainers, and component counts;
- component IDs, labels, authors, and uniqueness within their component type;
- portable relative paths for layouts, stylesheets, and language databases;
- safe SQLite table identifiers.

Filesystem consumers resolve manifest paths below a trusted root without
following symbolic links. Invalid installed packages stay out of extension
indexes; invalid imports appear as corrupted and cannot be installed.

An import cannot replace a bundled package or change the type of an installed
package with the same ID.

## Writes, exports, and failures

Installed-package writes and editor saves use a bounded ZIP writer and replace
the internal archive atomically. Import never runs load or unload hooks on its
staging directory. Exporting an installed extension copies its original
archive; exporting a bundled extension uses an isolated temporary
materialization.

The Storage Access Framework owns the final export document, so the app cannot
promise an atomic rename at that external destination.

Expected failures are content-free. Failure UI and logs must not include
provider names, URIs, archive paths, manifest paths, editor text, or exception
messages. Normal import lists may show a bounded, sanitized provider label.

## Runtime and editor ownership

Each loaded extension gets a random directory below `extension-runtime`.
`unload()` deletes that directory only when the extension instance created it;
importer and editor workspaces are detached without being mistaken for owned
runtime data.

Theme assets use a two-entry materialization cache. The manager and every
Compose consumer hold explicit leases, including editor previews. Retirement
waits for the last consumer before deleting assets, and abandoned compositions
release their lease.

The Han language provider serializes refresh, query, and teardown work. It
publishes only packs whose read-only database opened successfully, unloads
removed or replaced packs, and never refreshes in response to a keystroke.

Editor open, save, and close I/O runs off the UI thread. Save builds a bounded
archive, and closing a preview waits until the keyboard has released its assets
before deleting the workspace. Fonts and images selected in the editor cross
the same disposable provider boundary before entering that workspace.

## Verification

Run the full JVM suite and local gate:

```shell
./gradlew :app:testDebugUnitTest
./gradlew qualityGate
```

The URI, streamed-size, cancellation, and workspace contracts have focused
device tests:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
dev.patrickgold.florisboard.lib.cache.CacheManagerAndroidTest,\
dev.patrickgold.florisboard.lib.ext.ExtensionLifecycleAndroidTest,\
dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaImporterAndroidTest
```
