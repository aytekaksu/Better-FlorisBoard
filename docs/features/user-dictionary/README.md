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

Run the focused host and Android tests for state ordering, Room's Main-thread
guard, and an import/export round trip, then run `./gradlew qualityGate`. Device
tests must target a dedicated emulator because they touch app dictionary data.
