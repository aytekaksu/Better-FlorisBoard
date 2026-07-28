# Contributing to Better FlorisBoard

Thank you for helping improve the project. Issues and pull requests may use AI
assistance, but the person submitting the work is responsible for its
correctness, testing, licensing, and description.

All contributions must be in English and follow the
[code of conduct](CODE_OF_CONDUCT.md) and
[AI-assisted contribution policy](AI_POLICY.md). Anyone may propose a change;
only [@aytekaksu](https://github.com/aytekaksu) may approve and merge it.

## Before writing code

- Search [open and closed issues](https://github.com/aytekaksu/Better-FlorisBoard/issues).
- For a large behavior or architecture change, open an issue before investing
  heavily so the expected result can be agreed.
- Read the relevant [feature document](docs/features/README.md). Update it when
  behavior, ownership, privacy, failure handling, or test commands change.
- Keep fork-specific behavior behind narrow interfaces where upstream code is
  involved. This reduces merge conflicts and accidental coupling.

## Development setup

Install:

- Android Studio, or IntelliJ IDEA with the Android and Compose plugins;
- Java 17;
- Android SDK and NDK versions declared by the project;
- CMake 3.22 or newer and Clang 15 or newer;
- Git and Rust;
- optionally Python 3.10 or newer and standard Unix command-line tools.

Linux, macOS, and WSL2 are the main development environments. If IntelliJ
cannot sync the current Android Gradle Plugin, enable its support for future
AGP versions or use a compatible Android Studio release.

Run the same complete merge-safety gate as CI:

```shell
./gradlew qualityGate
```

During development, use the narrower commands listed in each
[feature document](docs/features/README.md).

Run device tests when changing Android service binding, real touch dispatch,
the input method lifecycle, or other behavior not represented by JVM tests:

```shell
./gradlew connectedDebugAndroidTest
```

Do not add `clean` to routine commands. It makes local and CI builds slower
without improving correctness.

## Change requirements

Keep each pull request focused and describe:

- the user-visible or architectural result;
- the behavior and privacy invariants affected;
- failure and cancellation behavior;
- the exact verification commands that actually ran;
- any tests not run and why.

Prefer tests at the cheapest level which proves the behavior:

1. Pure unit or property test.
2. Robolectric or deterministic editor integration test.
3. Instrumented fake-provider or touch test.
4. Manual APK check for behavior automation cannot yet prove.

Never claim a command passed if it did not run. Do not make tests reproduce the
same implementation algorithm; assert observable results and invariants.

## Privacy and diagnostics

Do not log or export typed text, candidate text, clipboard contents, dictionary
entries, raw touch paths, or target-application content. Diagnostics should use
bounded counts, opaque IDs, state transitions, duration buckets, and typed
failure categories.

Any change to permissions, exported components, provider payloads, persistence,
or external intents must explain its threat model and include a regression
test.

## Translations

This fork is not currently connected to the upstream FlorisBoard Crowdin
project. Do not contact upstream maintainers or use their Crowdin project for
fork-specific text.

During this development phase, change only the English source file at
`app/src/main/res/values/strings.xml`. Do not edit generated/localized
`values-*/strings.xml` files in ordinary pull requests. Open an issue before
translation work; a fork-owned translation workflow will be documented when it
is ready.

## Bug reports and proposals

- Use the [bug report](https://github.com/aytekaksu/Better-FlorisBoard/issues/new?template=bug_report.yml)
  or [crash report](https://github.com/aytekaksu/Better-FlorisBoard/issues/new?template=crash_report.yml)
  template for defects.
- Use the [feature proposal](https://github.com/aytekaksu/Better-FlorisBoard/issues/new?template=feature_request.yml)
  template for new behavior.
- Use a normal [issue](https://github.com/aytekaksu/Better-FlorisBoard/issues/new)
  for other actionable feedback.

Prefer the app's crash report when available. Before sharing ADB output, remove
typed text, clipboard data, application content, paths, account data, and other
personal information.
