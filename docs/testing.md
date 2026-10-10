# Testing strategy

Use the cheapest test which proves the behavior. A large device scenario is
not stronger evidence for a pure state rule.

| Layer | Use for | Default command |
| --- | --- | --- |
| Pure JVM | Reducers, policies, Unicode/range logic, lifecycle invariants, deterministic editor behavior | `./gradlew test` or a module `test` task |
| Robolectric | Bundle codecs and small Android service helpers | `./gradlew :lib:autocorrect-api:testDebugUnitTest` |
| Instrumented | Real Binder process boundaries, `MotionEvent`, IME/service lifecycle | Focused `connectedDebugAndroidTest` class |
| Packaging | Shrinker rules, manifest/privacy invariants, benchmark source compilation | `./gradlew ciPackage` |
| Scheduled benchmark | Startup on a controlled device | Benchmark-module instrumentation |

`./gradlew qualityGate` is the local merge gate. It runs formatting, Detekt,
privacy checks, Android lint, JVM/Robolectric tests, debug packaging, a minified
beta build, profile/benchmark source compilation, and the autocorrect API checks.
The profile-capture test is compiled separately against an unminified,
non-debuggable `.profile` app variant; the startup benchmarks still measure the
minified `.bench` variant. Run profile capture only on a dedicated API 33+ device
with an explicit ADB serial. The gate checks source profile rules against code
defined in the profile and minified beta APKs. Exact-owner all-method selectors
also have a reviewed class/method/flag digest checked against AGP's actual
pre-R8 expansion; a dependency update cannot silently broaden their coverage.
Keep the profile's `wildcard-coverage-v1` header. Change its counts/hash only
after comparing a new literal control with the compact profile's expansion,
packaged profiles, API-routed metadata and mappings. Do not auto-refresh it
from the output being checked. The gate does not measure startup speed.
The startup benchmark selects the IME once per test, before its per-iteration
setup, so cold launches are not disrupted by repeatedly selecting the service.
Before replacing `app/src/main/baseline-prof.txt`, keep older rules that still
refer to live code but were missed by the new capture. Remove dependency
duplicates only when their class and method flags survive beta's actual
wildcard expansion. Compare the full and reduced profiles' packaged contents,
API-routed profile metadata, and mappings in both profile and minified beta
builds. Building the test APK alone does not refresh the checked-in file.
The platform-neutral host core additionally enforces minimum coverage of 85%
for lines and 65% for branches. These are regression floors, not a reason to
write tests which merely execute code without proving behavior.

## Test design

- Name one behavior per test and make failures local.
- Assert public behavior and invariants, not private implementation steps.
- Use fixed synthetic text, dictionaries, layouts, and gestures.
- Cover Unicode boundaries, empty state, cancellation, duplicate callbacks,
  stale generations, provider death, and malformed bounded inputs where
  relevant.
- Use fake clocks and explicit synchronization. Never wait with arbitrary
  sleeps.
- A regression test must fail for the old defect for the reason described.
- Property tests should print the seed and smallest failing sequence.
- Golden/API snapshots are reviewed compatibility contracts, not files to
  regenerate automatically on every change.

## Device-test policy

Keep PR device coverage small and focused. A fake provider process should prove
only behavior that cannot be represented faithfully in the core:

- successful bind/session/request/finish;
- sender identity and real `Messenger` serialization;
- death, null binding, disconnect, and rebind;
- one malformed or late reply rejection;
- operation from a minified host build.

Run broader touch matrices and performance tests separately. Record device/API
level and exact class filter when reporting results.

## Failure reports

CI uploads test XML/HTML, lint/Detekt output, mapping files, and built artifacts.
When a test fails, report the first causal failure, command, variant, and seed
or fixture. Do not paste typed input or raw keyboard logs into an issue.
