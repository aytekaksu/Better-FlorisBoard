# Preferences

`FlorisPreferenceModel` owns the app's stored settings. JetPref stores them as
typed key/value lines and calls `migrate()` once for each line before matching
it to the current schema.

## Compatibility rules

- A supported upgrade or backup restore must keep every setting with a current
  equivalent. Privacy settings must never become less strict during migration.
- Migration is entry-local and input order is unspecified. A rule must map
  directly to the final key, type, and value; migration chains do not run twice.
- Unknown or retired keys are ignored. Stored keys and types are reviewed in
  `floris-preference-schema.txt`; legacy and retired names remain as permanent
  tombstones.
- Multi-key conversions run on the full payload before JetPref loads it.
  Startup and backup restore share this path. A current compound key always
  wins over legacy inputs, while Merge keeps unrelated current fields.

Older window sizing and one-handed settings are reconstructed for the five
phone/tablet form factors they described. Desktop and unrelated current window
state are preserved.

Smartbar stores from version codes 63–88 are rebuilt from their historical row
settings. Legacy action JSON contributes only recognized key codes; labels,
text actions, popups, and unknown data are discarded. Merge keeps current-only
actions and unrelated layout state. Either current structural key blocks the
whole conversion, and correctly typed current scalar entries win over aliases
regardless of line order.

Broader import validation and rollback belong to the backup/restore
coordinator because JetPref live import is not transactional.

## Privacy and threat model

Preference data can reveal language choices, enabled integrations, incognito
behavior, and emoji history. Treat local stores and backup payloads as private.
Never log, attach, or commit a real store, and never include raw values in test
failure messages.

The main risks are a crafted or corrupt payload, a renamed key silently
resetting behavior, a retired key being reused, and a partial live import.
Required invariants are:

- only declared typed keys enter the live model;
- supported legacy values map to the same current behavior;
- malformed legacy values do not block unrelated valid settings;
- schema changes require an explicit contract update;
- fixtures use fixed synthetic values only.

## Verification

Run the focused contracts with:

```shell
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.app.FlorisPreferenceMigrationTest'
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.app.FlorisPreferenceSchemaContractTest'
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.app.LegacyPreferencePayloadPreprocessorTest'
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.app.LegacySmartbarPreferencePayloadTest'
./gradlew :app:testDebugUnitTest \
  --tests 'dev.patrickgold.florisboard.app.FlorisPreferencePersistenceTest'
```

Run `./gradlew qualityGate` before merging a persistence change.
