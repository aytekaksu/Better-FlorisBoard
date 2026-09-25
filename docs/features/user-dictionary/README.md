# User dictionary

The settings route owns browsing state. Room stores Floris words; Android's
user-dictionary provider stores system words. The same screen uses both.

Queries, edits, and document import/export run off Main. Only the newest locale
query may update the screen. An accepted edit or document operation continues
if the route closes; a closed route cannot show a late result. Duplicate
submissions are ignored while an operation is active.

The existing combined-list format is unchanged: the first line is a header,
then each `;`-separated row carries word, frequency, optional locale, and
optional shortcut. Import updates the first matching word/locale or inserts a
new row. A malformed later row can leave earlier rows imported; this format
does not provide an all-or-nothing transaction.
Floris imports canonicalize only locale tags that can be represented without
losing subtags. Older simple hyphenated rows stay browsable and heal on
reimport. The Floris language list groups simple spelling aliases, but shows
script, extension, malformed, and Java language-alias tags as separate exact-tag
choices. Java and Android disagree on some language aliases, so imports retain
their exact tags rather than rewriting or merging distinct rows. Editing a
Floris word keeps tags it cannot represent; system dictionary behavior is
unchanged.
The format reserves `l=all` and `l=null` for no locale. Preexisting rows with
those literal locale values can be browsed, but cannot round-trip as literal
tags through this format.

Run the focused host and Android tests for state ordering, Room's Main-thread
guard, and an import/export round trip, then run `./gradlew qualityGate`. Device
tests must target a dedicated emulator because they touch app dictionary data.
