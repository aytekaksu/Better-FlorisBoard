# Quality checks

Run the complete local merge gate with:

```shell
./gradlew qualityGate
```

The fast package lane compiles the debug app, minified beta app, benchmark APK,
and the release autocorrect API AAR. It does not start an emulator.
The unit-test lane also enforces the host-core Kover floors and generates XML
and HTML coverage reports.

The first enforcement scope covers the fork-owned autocorrect plugin packages,
the public autocorrect API, their tests, and the host core. Expand
`qualityKotlinSources` in the root build file as inherited packages are cleaned
up. Spotless covers the new host core, small fork-owned coordinators and
policies, the split plugin UI, deterministic editor fixtures, their focused
tests, and the Gradle files changed as part of this foundation. Add existing
packages only as deliberate formatting cleanups, avoiding a noisy
whole-repository diff. Do not relax Detekt thresholds to make old findings
disappear; regenerate the baseline only after reviewing each changed finding.

## Documentation links

`documentationCheck` validates relative Markdown links in root documentation
and `docs/` without network access. External URLs and page anchors are skipped;
repository-relative paths, ordinary relative paths, fragments, and URL-encoded
local paths are resolved. Keep feature documentation links relative so they
remain valid in forks and local checkouts.

## Sensitive logging guard

`privacySourceCheck` rejects logging calls in production keyboard and NLP code
when they contain raw motion/pointer objects or interpolate likely typed text,
candidates, dictionary entries, words, or composing text. Log redacted counts,
opaque identifiers, state names, and duration buckets instead.

The check is deliberately conservative rather than a Kotlin parser. If it
mistakes a safe value for user data, place this comment immediately above the
logging call and explain why the value is safe:

```kotlin
// quality: allow-sensitive-log -- reports an enum name; it cannot contain input
flogDebug { "state=$stateName" }
```

Reviewers should reject unexplained markers and exceptions covering raw user
content.

## Temporary Android lint workaround

Android lint from AGP 9.0.0 currently crashes while K2/UAST resolves lambdas in
three independent detectors:

- `UElementAsPsiDetector` (`UElementAsPsi`) in `Flog.kt`.
- `IntentDetector` (`IntentReset`) in `LaunchUtils.kt`, after the first
  detector is disabled.
- `ToastDetector` (`ShowToast`) in `LaunchUtils.kt`, after the preceding
  detector is skipped.

The failures can be reproduced by removing the matching entries from
`app/lint.xml` and running:

```shell
./gradlew :app:lintDebug --stacktrace
```

Lint still initializes a detector before applying a path-level ignore, so these
three issue IDs must currently be disabled globally. The lint task and every
other detector still fail the quality gate normally. When upgrading AGP/lint,
remove the suppressions and rerun the command. Re-enable each detector as soon
as it completes successfully.

`app/lint.xml` is the detector configuration. `app/lint-baseline.xml` is the
separate, generated record of findings present when this gate was introduced.
The baseline is a debt ratchet, not an assertion that each finding is harmless:
fix entries incrementally and regenerate it only to remove resolved findings.
