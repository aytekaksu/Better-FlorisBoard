# Emoji palette

`MediaInputLayout` shows an empty palette while its bundled emoji data loads.
`EmojiData` owns asset lookup, reading, parsing, and caching. Asset work runs on
IO; the palette's composition owns only the current result and cancels its load
when the keyboard leaves composition or its context changes. A cancelled load
cannot replace the current palette. Emoji support filtering and rendering remain
in `EmojiPaletteView`.

The palette's root list uses the glyphs and categories from `en.txt` but drops
names and keywords as the old generated `root.txt` did. The root asset is no
longer stored twice. A golden hash test guards the exact original root rows.

The same loader serves emoji suggestions, so callers do not need to choose a
dispatcher. The root data and IO contracts are covered by
`./gradlew :app:testDebugUnitTest`.
