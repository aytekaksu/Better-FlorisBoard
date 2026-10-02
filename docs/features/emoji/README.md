# Emoji palette

`MediaInputLayout` shows an empty palette while its bundled emoji data loads.
`EmojiData` owns asset lookup, reading, parsing, and caching. Asset work runs on
IO; the palette's composition owns only the current result and cancels its load
when the keyboard leaves composition or its context changes. A cancelled load
cannot replace the current palette. Emoji support filtering runs in a background
worker and is cancelled when its data, EmojiCompat instance, or editor metadata
changes. The palette shows empty category grids until the current result is
ready. Rendering remains in `EmojiPaletteView`.

`FlorisEmojiCompat` loads one default font through AndroidX's background loader
and publishes it only after metadata is ready. Without a provider, or when
loading fails, the palette keeps its system-font fallback. `EmojiText` processes
each glyph with the current editor's replacement strategy: all supported emoji,
or only those missing from the system font. It does not switch the global
instance or require a different view when the strategy changes.

Variation popups keep every supported choice except the emoji shown on the
key. Choices wrap after six columns, scroll when taller than four rows, and
stay within the window. Mixed skin-tone choices remain available.

A key commits its displayed skin tone after a live preference change. The tap
handler reads the current choice without restarting an active gesture. An open
variation popup also commits its current displayed choice after a tone change;
choices use normal click semantics without adding press visuals.

The palette's root list uses the glyphs and categories from `en.txt` but drops
names and keywords as the old generated `root.txt` did. The root asset is no
longer stored twice. A golden hash test guards the exact original root rows.

The same loader serves emoji suggestions, so callers do not need to choose a
dispatcher. Suggestion ranking runs on Default and stops scoring when a newer
input cancels the request; equal scores keep asset order. The root data, IO,
and ranking contracts are covered by
`./gradlew :app:testDebugUnitTest`.

Run `EmojiTextAndroidTest` with a selected device to check real emoji spans,
plain-text fallback, and replacement-strategy changes:
`ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.media.emoji.EmojiTextAndroidTest`.
Its bundled metadata/font dependency is test-only; the app still uses the
device's default provider rather than shipping an emoji font.

The real-IME key/popup tone and cancelled-press regression is
`TextKeyboardTouchE2eTest#liveEmojiToneCommitsTheDisplayedChoice`. Run it with
`ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardTouchE2eTest#liveEmojiToneCommitsTheDisplayedChoice`.
