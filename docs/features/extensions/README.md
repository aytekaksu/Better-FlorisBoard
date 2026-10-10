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

`CacheManager` owns the import-list MIME label: ZIP and octet-stream provider
types become the extension MIME type, while other types pass through. The label
does not decide whether an archive may be installed.

Import workspaces retire on app-owned I/O after selection changes or screen
disposal. Cancellation before handoff awaits cleanup; an install retains its
extracted files until it finishes. Deletion gets one delayed retry, then an
app-owned worker retries only failed paths in bounded batches, skipping active
workspaces. Exhausted failures reach waiters; persistent failures log once
without paths, and steady retries are quiet.

After unlock, startup cleanup retires recognized abandoned import, export,
editor, and backup/restore workspaces, plus provider/share-preview staging
files, before settings, sharing, or clipboard cache users start, without
blocking the IME. Unknown entries and symbolic links remain.
Theme materializations and loaded runtime files are excluded; their managers
retire stale roots on first use, protecting live assets during direct-boot
unlock. A failed sweep leaves the app usable and retries next process start.

One selection is limited to 64 files, 256 MiB of source data, 512 MiB of
expanded data, and 16,384 entries. Reads and extraction also preserve 128 MiB
of storage headroom. Rejected packages remove their private source and
extraction; cancellation removes an undelivered workspace.

## Archive limits

The extension ZIP gate rejects multi-disk and ZIP64 containers before Commons
Compress builds its entry table. It also checks the latest end record, the
exact central-directory layout, local/central header agreement, bounded
metadata, and UTF-8 entry names.

Record decoding is shared with backup ZIPs; extension limits and ZIP64 rejection stay separate.

| Budget | Limit |
| --- | ---: |
| ZIP file | 64 MiB |
| Entries | 4,096 |
| Expanded data | 128 MiB |
| One entry | 64 MiB |
| One control-file read | 8 MiB |
| Path / path segment | 512 / 255 UTF-8 bytes |
| Path depth | 16 segments |

Portable path syntax is shared with backup ZIPs and extension resource paths;
each caller keeps its own limits and failure handling.

Extraction rejects traversal, ambiguous or duplicate paths, file/directory
conflicts, links and special files, encryption, unsupported compression,
incorrect sizes, and CRC mismatches. It writes into a private sibling staging
directory and publishes only with an atomic move. A failure leaves the previous
destination unchanged and removes owned staging data.

Trusted APK assets still use staged, all-or-nothing directory publication.
Bundled layout IDs may share an arrangement via `arrangementFile` while keeping
distinct labels, modifiers, and subtype choices. Currency keys output their
visible symbol; metadata tests cover built-in slots. Layout selectors have no
text of their own; concrete keys still provide their display and input text.

Build-time generators expand `@autoKeys("letters")` character rows and explicit
paired `@caseKeys("lower", "upper")` rows, and `@kanaKeys("hira", "full", "half")`
rows, all with optional padding. Selector rows keep explicit alternatives without
Unicode case, kana, or width conversion. Unusual code/label pairs stay literal.
The generators also expand
digit-script numeric rows from Bengali, theme stylesheets from the
static day base and Material You/night/borderless overlays, subtype presets, and
shared punctuation popups. Each generator runs once for all requested app variants.
Exceptional character and numeric layouts and the Han/Bengali presets stay literal.
Generation preserves layout IDs and popups,
theme manifest paths, preset order, and Unicode code points in JSON; missing or
malformed input fails before packaging. Theme selectors replace whole rules, while
`@defines` merge by name. Replacing asset trees rejects links in task-owned
output paths and does not follow nested links during deletion. Run
`./gradlew :app:testLocalizationAssetGenerator :app:testDebugUnitTest` to check
generated assets and their metadata.

## Manifest rules

Manifest validation is bounded and type-aware. It checks:

- a 1 MiB encoded size plus bounded JSON depth, containers, items, and tokens;
- package metadata, dependencies, links, maintainers, and component counts;
- component IDs, labels, authors, and uniqueness within their component type;
- portable relative paths for layouts, stylesheets, and language databases;
- safe SQLite table identifiers.

Filesystem consumers resolve manifest paths below a trusted root without
following symbolic links. Invalid installed packages stay out of extension
indexes; one unreadable or changed package does not hide healthy packages or
stop later index refreshes. Invalid imports appear as corrupted and cannot be
installed.

An import cannot replace a bundled package or change the type of an installed
package with the same ID.

## Writes, exports, and failures

Installed-package writes and editor saves use a bounded ZIP writer and replace
the internal archive atomically. Import never runs load or unload hooks on its
staging directory. Exporting an installed extension copies its original
archive; exporting a bundled extension uses an isolated temporary
materialization. Export creates and removes its temporary workspace on I/O,
including on failure or caller cancellation. The installed
archive snapshot finishes under the storage guard before the destination write.

The Storage Access Framework owns the final export document, so the app cannot
promise an atomic rename at that external destination.

Expected failures are content-free. Failure UI and logs must not include
provider names, URIs, archive paths, manifest paths, editor text, or exception
messages. Normal import lists may show a bounded, sanitized provider label.

## Runtime and editor ownership

The app-owned `KeyboardExtensionRepository` publishes metadata and sources together
per keyboard index refresh, including replacements with unchanged manifests. Layouts,
the subtype editor, NLP, and settings read it directly; `SubtypeManager` owns
saved subtypes and active selection. `KeyboardManager` clears computed
keyboards, and `LayoutManager` drops decoded layouts and popups from
older generations. Later components with the same ID win, unknown layout types
are skipped, and preset order and missing-component fallbacks stay unchanged.

Each loaded extension gets a random directory below `extension-runtime`.
`unload()` deletes that directory only when the extension instance created it;
importer and editor workspaces are detached without being mistaken for owned
runtime data.

Theme assets use a two-entry materialization cache and explicit manager/Compose
leases, including previews. Retirement waits for all leases; abandoned
compositions release theirs. Deletion gets one retry; if both attempts fail, a
content-free warning is logged. The next installed-theme load in a new process
cleans stale assets.
Styles and file fonts compile off Compose before publication. Preview compilation
holds a separate lease; obsolete work is cancelled or discarded, the current
theme stays visible until replacement is ready, and damaged fonts fall back.
Background-image paths resolve off Main; changing the theme drops the old path
while the new one resolves.

The Han language provider serializes refresh, query, and teardown work. It
publishes only packs whose read-only database opened successfully, unloads
removed or replaced packs, and never refreshes in response to a keystroke.
Language-pack lists and theme selectors render their observed index snapshot,
keeping each package's title and components together during refreshes.
The locale chooser appends language-pack locales absent from Android's list,
deduplicating by exact locale tag while keeping system entries first.
The subtype editor saves unfinished drafts and validates required choices only
on final Save; both paths build the subtype from the same field snapshot.

Editor open/save/close I/O, stylesheet reads/parsing, and asset-list reloads run
off Main. Saving builds a bounded archive; preview close waits for the keyboard
to release assets before workspace deletion. Editor-selected fonts and images
cross the disposable provider boundary before entering that workspace.

Canceling an asset import or leaving the file manager retires its temporary
file off Main. The next import waits for that cleanup; invalid names keep the
same staged file for retry. Installation holds the workspace close guard.

Stylesheet and asset-list loads show loading states and discard stale or
cancelled results; invalid stylesheets allow lenient or empty retry. The file
manager and property picker share one listing path. Renames and deletes run off
Main under the workspace close guard; unsafe names, links, or collisions leave
files alone.

Only the registered workspace owner may use its files. Closing or replacing it
rejects stale operations and cannot delete a live replacement. Failed deletion
still allows cleanup retries; creation, file operations, and close share one guard.

The theme shape editor displays corners from the current property value. Changing
shape type resets the preview and chips to the new type's value, and a corner edit
updates that value before the property can be saved.
Sizes use dp or sp. Percentages are supported for corner shapes, not bare sizes
or size variables.
Padding chips also read the current draft; resetting its type cannot leave old
sizes visible. One-side and all-side edits update the draft before Apply.
Adding a theme property with an existing name leaves the current value and
workspace unchanged; editing that property can replace its value.
An open property draft belongs to the theme action, so rotation keeps its target,
value, and unfinished size input. Apply and Delete only touch the original
property set while it still belongs to the rule. This does not recover an unsaved
editor workspace after process death.
Committed rule and property changes refresh visible rows and variable previews
without reopening the component.

## Verification

Run the full JVM suite and local gate:

```shell
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.lib.ext.ExtensionIndexIsolationTest'
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.lib.ext.ExtensionExportWorkspaceTest'
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.app.ext.EditorAssetFilesTest'
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.app.settings.theme.ThemePropertyEditTest'
./gradlew :app:testDebugUnitTest
./gradlew qualityGate
```

Theme refresh, URI, streamed-size, cancellation, and workspace contracts have
focused device tests:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
dev.patrickgold.florisboard.app.settings.theme.ThemeEditorScreenAndroidTest,\
dev.patrickgold.florisboard.lib.cache.CacheManagerAndroidTest,\
dev.patrickgold.florisboard.lib.ext.ExtensionIndexAndroidTest,\
dev.patrickgold.florisboard.lib.ext.ExtensionLifecycleAndroidTest,\
dev.patrickgold.florisboard.ime.keyboard.BundledNumericRowAssetsAndroidTest,\
dev.patrickgold.florisboard.ime.keyboard.LayoutCacheRefreshAndroidTest,\
dev.patrickgold.florisboard.ime.theme.BundledThemeAssetsAndroidTest,\
dev.patrickgold.florisboard.ime.theme.ThemeFontCompilationAndroidTest,\
dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaImporterAndroidTest

./gradlew :lib:snygg:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
org.florisboard.lib.snygg.SnyggFileFontAndroidTest,\
org.florisboard.lib.snygg.ui.SnyggBackgroundImagePathAndroidTest
```
