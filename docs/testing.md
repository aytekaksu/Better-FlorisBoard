# Testing strategy

Use the cheapest test which proves the behavior. A large device scenario is
not stronger evidence for a pure state rule.

| Layer | Use for | Default command |
| --- | --- | --- |
| Pure JVM | Reducers, policies, Unicode/range logic, lifecycle invariants, deterministic editor behavior | `./gradlew test` or a module `test` task |
| Robolectric | Bundle codecs and small Android service helpers | `./gradlew :lib:autocorrect-api:testDebugUnitTest` |
| Instrumented | Real Binder process boundaries, `MotionEvent`, IME/service lifecycle | Focused `connectedDebugAndroidTest` class |
| Packaging | Shrinker rules, manifest/privacy invariants, benchmark source compilation | `./gradlew ciPackage` |
| Scheduled benchmark | Startup and measured hot paths on a controlled device | Benchmark-module instrumentation |

`./gradlew qualityGate` is the local merge gate. It runs formatting, Detekt,
privacy checks, Android lint, JVM/Robolectric tests, debug packaging, a minified
beta build, benchmark source compilation, and the autocorrect API checks.
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

## Deterministic editor fixture

The test `InputConnection` models text, selection, composing ranges, batch
edits, delayed extracted updates, Unicode, key events, and selected failures.
Use it for editor change plans before reaching for instrumentation. It must
remain deterministic and must not silently emulate Android behavior it does
not implement.

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
