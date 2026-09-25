# Emoji palette

`MediaInputLayout` shows an empty palette while its bundled emoji data loads.
`EmojiData` owns asset lookup, reading, parsing, and caching. Asset work runs on
IO; the palette's composition owns only the current result and cancels its load
when the keyboard leaves composition or its context changes. A cancelled load
cannot replace the current palette. Emoji support filtering and rendering remain
in `EmojiPaletteView`.

The same loader serves emoji suggestions, so callers do not need to choose a
dispatcher. Check the IO and cancellation contract with
`./gradlew :app:testDebugUnitTest --tests dev.patrickgold.florisboard.ime.media.emoji.EmojiAssetIoTest`.
