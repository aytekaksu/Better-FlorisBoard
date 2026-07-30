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
- Multi-key conversions cannot be implemented in `migrate()`. They require a
  validated payload or post-load migration with an explicit merge policy.

Malformed legacy Smartbar arrangements are dropped without aborting valid
sibling settings. Broader import validation and rollback belong to the
backup/restore coordinator because JetPref live import is not transactional.

Temporary exception: older window sizing, one-handed mode, and structural
Smartbar layouts combine several stored keys. Restore still falls back to
current defaults for those settings until the payload-level migration phase
lands. Their historical names are already reserved so they cannot be reused.

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
```

Run `./gradlew qualityGate` before merging a persistence change.
