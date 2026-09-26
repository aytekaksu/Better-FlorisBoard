# Quality checks

Run the complete local merge gate with:

```shell
./gradlew qualityGate
```

`ciPackage` builds debug, beta, and profile app APKs, both benchmark APK
variants, and checks the release autocorrect API AAR. It rejects INTERNET
permission, debuggable mode, and the debug-only editor harness in the beta APK,
and verifies baseline profiles. Local `qualityGate` and CI run the same checks
without starting an emulator. `ciUnitTest` enforces host-core Kover floors and
generates XML and HTML coverage reports.

The root `build.gradle.kts` defines the exact Detekt (`qualityKotlinSources`)
and Spotless (`formattedKotlinSources`) scopes. Detekt covers autocorrect
plugins/API/host core, backup archives, extension hardening, shared policies,
and inherited platform utilities. Spotless formats a narrower set of fork-owned
code and selected tests. Expand coverage deliberately to avoid a noisy
repository-wide diff. Do not relax Detekt thresholds to hide old findings;
regenerate its baseline only after reviewing each changed finding.

## Documentation links

`documentationCheck` validates relative Markdown links in root documentation
and `docs/` without network access. External URLs and page anchors are skipped;
repository-relative paths, ordinary relative paths, fragments, and URL-encoded
local paths are resolved. Keep feature documentation links relative so they
remain valid in forks and local checkouts.

## Sensitive logging guard

`privacySourceCheck` is a name-based defense-in-depth check for common direct
diagnostic calls. It balances multiline Flog and Android
`Log.v/d/i/w/e/wtf` calls, inspects ordinary string templates, and rejects
likely-sensitive input, editor, clipboard, pointer, candidate, dictionary,
URI/path, message, and Throwable names. It also rejects logger import aliases,
static Android Log imports, direct `Flog.log`, and Android Log's three-argument
Throwable overloads.

This is not a Kotlin type checker and cannot prove that diagnostics are safe or
find every value passed through an indirect helper. Review every logging and
diagnostic-export change manually. The source check is a backstop, not a
replacement for that review.

The check also rejects generic `toString()`, exception messages, stack traces,
and `printStackTrace()`. Files declaring the devtools report entry points also
inspect balanced `append`/`appendLine` arguments. Devtools sources cannot invoke
raw process/logcat export paths.

Log content-free metadata instead: counts, booleans, opaque IDs, closed
state/type names, and duration buckets. Projections of a sensitive value are
accepted only when the whole template expression is a size, length, count,
simple class name, or null check. For example, `${suggestions.size}` and
`${error::class.simpleName}` are allowed; `${word.take(3).length}` is not.

If the name-based check mistakes a safe value for user data, place a full-line
comment immediately above the logging call. The `--` separator and a nonblank
reason are required:

```kotlin
// quality: allow-sensitive-log -- reports an enum name; it cannot contain input
flogDebug { "state=$stateName" }
```

Reviewers should reject unexplained markers and exceptions covering raw user
content.

All production `print`/`println` calls are rejected regardless of payload so
they cannot bypass structured logging review. `printStackTrace()` cannot be
suppressed.

## Temporary Android lint workaround

AGP 9.0.0 lint currently crashes in three K2/UAST detectors:
`UElementAsPsiDetector` (`UElementAsPsi`) in `Flog.kt`, then `IntentDetector`
(`IntentReset`) and `ToastDetector` (`ShowToast`) in `LaunchUtils.kt` as earlier
detectors are skipped. `app/lint.xml` disables these IDs globally because lint
initializes detectors before applying path-level ignores. To recheck after an
AGP/lint upgrade, remove the matching suppressions and run:

```shell
./gradlew :app:lintDebug --stacktrace
```

Re-enable each detector as soon as it completes; all others still fail the
quality gate normally. `app/lint-baseline.xml` separately records existing
findings as a debt ratchet: review and remove resolved findings rather than
assuming every baseline entry is harmless.

## Dependency update checks

Android lint's available-version findings are informational. Their messages
contain the exact latest release, so baselining them makes a passing gate expire
without any source change and block unrelated work. They remain visible in lint
reports, while dependency, Gradle wrapper, and Android Gradle Plugin upgrades
stay dedicated compatibility changes that must pass the full quality gate.
