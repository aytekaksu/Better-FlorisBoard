# Extensions

Keyboards, themes, and language data use `.flex` ZIP archives. Bundled packages
use the same manifests in trusted APK assets.

## Import and cleanup

[CacheManager](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/cache/CacheManager.kt) owns import workspaces and labels.
Provider names, MIME types, sizes, and bytes are untrusted; MIME never authorizes installation.

1. Accept only an unambiguous external `content://` URI with exact read access.
2. Read metadata and bytes in the [disposable worker](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/provider/DisposableExternalContentImporter.kt) with a 30-second deadline; cancellation terminates it.
3. Copy once into a generated private `.flex` file, counting the real stream and
   rejecting bytes above 64 MiB. Show only a bounded, sanitized provider label.
4. Validate the whole ZIP, then decode and validate strict-UTF-8
   `extension.json`, before extracting or exposing package data.
   Rejecting a package removes its source and extracted data.

A selection allows 64 files, 256 MiB source, 512 MiB expanded data, and 16,384
entries; reads/extraction preserve 128 MiB free-storage headroom. ZIP and
octet-stream import-list labels become the extension MIME type; others pass through.

Selection changes/disposal retire workspaces on app-owned I/O. Cancellation before
handoff awaits cleanup; installation retains extracted files until it finishes.
[Retirement](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/cache/ImportWorkspaceRetirement.kt) retries deletion once after a delay.
If both attempts fail, waiting operations receive the failure; the [janitor](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/cache/ImportWorkspaceJanitor.kt) retries only failed paths in bounded batches,
skipping active workspaces. Persistent failures log once without paths; steady retries are quiet.

After unlock, the [startup janitor](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/cache/StartupCacheJanitor.kt) retires recognized
import/export/editor/backup-restore workspaces and provider/share-preview
stages before settings, sharing, or clipboard cache users start, without blocking
the IME. Unknown entries and links remain. Runtime/theme roots are excluded and
cleaned by their managers on first use, protecting live direct-boot assets.
A failed sweep leaves the app usable and retries next process start.

## Archive and manifest validation

The [ZIP gate](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/io/ExtensionZipContainerGate.kt) rejects multi-disk/ZIP64 before Commons Compress builds its entry table. It validates
the latest end record, exact central directory, local/central agreement, bounded
metadata, and UTF-8 names. Shared record/path decoding does not share acceptance
policy: [backups](../backup-restore/README.md) allow single-disk ZIP64.

| Per-archive budget | Limit |
| --- | ---: |
| ZIP file | 64 MiB |
| Expanded data | 128 MiB |
| Entries | 4,096 |
| One entry | 64 MiB |
| Control-file read | 8 MiB |
| Path / segment | 512 / 255 UTF-8 bytes |
| Depth | 16 segments |

[Extraction](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/io/BoundedExtensionArchive.kt) rejects traversal, ambiguous/duplicate paths, file-directory conflicts, links,
special files, encryption, unsupported compression, incorrect sizes, and CRC mismatches.
Private sibling staging publishes by atomic move; failure preserves the previous
destination and removes owned staging. Trusted APK directory copies use the same
all-or-nothing publication.

[Manifest validation](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/ext/ExtensionImportValidation.kt) bounds input to 1 MiB plus JSON depth/containers/items/tokens. Type-aware
checks cover metadata/dependencies/links/maintainers, component counts/IDs/labels/authors,
type-local ID uniqueness, resource paths, and SQLite table identifiers. Filesystem
consumers use [trusted-root, no-link resolution](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/ext/SafeRelativePath.kt).
Invalid installed packages stay out of indexes without hiding healthy siblings or
stopping later refreshes; corrupted imports cannot be installed.

## Storage and failures

[ExtensionManager](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/ext/ExtensionManager.kt) serializes storage mutation. Imports cannot replace bundled IDs or change an
installed package's type. Internal writes/editor saves use a bounded ZIP writer
and atomic archive replacement; import staging never runs load/unload hooks.

Exports snapshot an installed package's original archive under the storage guard
before writing the destination; bundled exports use isolated materialization.
Temporary export workspaces are created/removed on I/O even on failure or
cancellation. SAF owns the final document, so the app cannot promise an atomic
rename there.

Expected failure UI/logs are content-free: no provider names, URIs, archive or
manifest paths, editor text, or exception messages. Only normal import lists may
show the sanitized provider label.

## Bundled assets

[Build generators](../../../app/build.gradle.kts) run once across requested variants.
Character templates support `@autoKeys("letters")`, `@caseKeys("lower", "upper")`,
and `@kanaKeys("hira", "full", "half")`, with optional padding. Selector alternatives
stay explicit, without Unicode case/kana/width conversion; unusual code-label
pairs and exceptional layouts remain literal. Layout IDs may share `arrangementFile`
without merging labels, modifiers, or subtype choices. Selectors have no text;
concrete keys supply display/input text, and currency keys output their visible symbol.

Generators also expand Bengali-based digit-script rows, ordered subtype presets
(Han/Bengali exceptions stay literal), shared punctuation popups, and themes from
the static day base plus Material You/night/borderless overlays. Both theme families
apply the shared borderless layer before family overrides. Selectors replace whole
rules; `@defines` merge by name. Generation preserves layout IDs/popups, theme
manifest paths, preset order, and JSON Unicode code points; malformed/missing input
fails before packaging. Output replacement rejects task-owned path links and never
follows nested links while deleting.

## Runtime owners

[KeyboardExtensionRepository](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard/KeyboardExtensionRepository.kt) publishes metadata/sources together, including archive-only
replacements. Layouts, subtype editing, NLP, and settings consume it directly;
`SubtypeManager` owns saved subtypes/selection. `KeyboardManager` clears computed
keyboards and [LayoutManager](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard/LayoutManager.kt)
invalidates older decoded layouts/popups. Later duplicate component IDs win;
unknown layout types are skipped; preset order and missing-component fallbacks remain.

[Extension](../../../app/src/main/kotlin/dev/patrickgold/florisboard/lib/ext/Extension.kt) uses a random directory under `extension-runtime` for each load. Unload deletes only its own
runtime directory; importer/editor workspaces are detached, never mistaken for it.

[ThemeManager](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/theme/ThemeManager.kt) keeps a two-entry materialization cache with manager/Compose/preview leases. Retirement
waits for every lease; abandoned compositions release theirs. Deletion retries once,
then logs content-free failure; first installed-theme load next process cleans stale
assets. Styles/file fonts compile off Compose before publication, preview compilation
holds its own lease, stale work is cancelled/discarded, and the current theme stays
visible during replacement. Damaged fonts fall back. [Background paths](../../../lib/snygg/src/main/kotlin/org/florisboard/lib/snygg/ui/SnyggBox.kt)
resolve off Main and clear immediately when the theme changes.

The [Han provider](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/nlp/han/HanShapeBasedLanguageProvider.kt) serializes refresh/query/teardown, publishes only opened read-only
packs, unloads removals/replacements, and never refreshes per keystroke. Language-pack
lists/theme selectors keep each observed package title and components together.
Locale merging keeps system entries for duplicate exact tags and adds missing
pack locales; the chooser sorts by display name. Subtype drafts/final Save share
a field snapshot; only final Save validates required choices.

## Editor ownership

[ExtensionEditScreen](../../../app/src/main/kotlin/dev/patrickgold/florisboard/app/ext/ExtensionEditScreen.kt) opens/saves/closes off Main and builds bounded saves. Preview close waits for the
keyboard's asset release before deleting the workspace. One restore-aware effect
closes a saved route and schedules cleanup. Local theme copies preserve stylesheet,
Material You palette/contrast/color specification and choose an unused component ID.

Only the registered workspace owner may operate on files. Creation, operations,
and close share a guard; stale operations cannot use or delete a replacement.
Failed deletion remains retryable. Selected fonts/images cross the disposable
provider boundary; cancel/exit retires their temporary file off Main, and the next
import waits. Invalid names retain the stage for retry; installation holds the guard.

[Stylesheets](../../../app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/theme/ThemeEditorScreen.kt) and shared [asset listings](../../../app/src/main/kotlin/dev/patrickgold/florisboard/app/ext/EditorAssetFiles.kt)
load off Main, show loading, discard stale/cancelled results, and offer lenient/empty
stylesheet retry. Rename/delete hold the close guard; unsafe names, links,
or collisions leave files alone.

[Property drafts](../../../app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/theme/EditPropertyDialog.kt) belong to the theme action: rotation retains target/value/unfinished size input,
not an unsaved workspace after process death. Apply/Delete require the original
property set still attached to its rule. Duplicate Add leaves value/workspace
unchanged; Edit may replace it. Shape corners/padding chips read the current draft;
type resets and one/all-side edits update it before Apply. Committed changes refresh
rows/variable previews without reopening the component.

## Verification

Follow [testing strategy](../../testing.md). Run focused JVM owners while iterating,
then generated-asset/metadata checks, the full app JVM suite, and the local gate:

```shell
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.lib.ext.ExtensionIndexIsolationTest' \
  --tests 'dev.patrickgold.florisboard.lib.ext.ExtensionExportWorkspaceTest' \
  --tests 'dev.patrickgold.florisboard.app.ext.EditorAssetFilesTest' \
  --tests 'dev.patrickgold.florisboard.app.settings.theme.ThemePropertyEditTest'
./gradlew :app:testLocalizationAssetGenerator :app:testDebugUnitTest
./gradlew qualityGate
```

Use focused device coverage for theme refresh, URI/stream limits, cancellation,
workspace lifecycles, generated assets, and font/background resolution:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
dev.patrickgold.florisboard.app.ext.ExtensionEditScreenAndroidTest,\
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
