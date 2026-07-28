# Better FlorisBoard

Better FlorisBoard is a privacy-first Android keyboard derived from
[FlorisBoard](https://github.com/florisboard/florisboard). It is independently
maintained and is not endorsed by the upstream FlorisBoard maintainers.

> [!WARNING]
> This project is in active development. There are no supported releases yet.
> Build it from source only if you are comfortable testing unfinished keyboard
> software.

## What is different

The project keeps FlorisBoard's modern Kotlin and Compose foundation while
developing features and engineering safeguards independently. Current work
includes:

- a versioned API for external autocorrect and suggestion engines;
- richer tap, swipe, and glide input handling;
- stronger automated tests, static checks, and privacy checks;
- smaller, testable feature boundaries with written behavior contracts.

External autocorrect engines remain separate applications. They can own their
models and ranking logic without owning the keyboard UI or editor operations.
Provider authors should start with
[the external autocorrect API guide](AUTOCORRECT_PLUGIN_API.md). Contributors
should use the human-oriented [feature documentation](docs/features/README.md)
to find implementation entry points, invariants, and tests.

## Build and test

Install the Android SDK/NDK, Java 17, CMake, Clang, Git, and Rust, then run:

```shell
./gradlew assembleDebug
./gradlew qualityGate
```

The debug application ID is `dev.patrickgold.florisboard.debug`, so it can be
installed beside a normal FlorisBoard installation. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the full setup and review requirements.
`qualityGate` runs formatting, Detekt, Android lint, JVM/Robolectric tests, and
debug/minified packaging.

## Privacy

Keyboard input is sensitive. Better FlorisBoard is designed to work without
network access and keeps editor actions under keyboard control. External
autocorrect providers receive only the bounded session and text data defined by
the public protocol. They run as separately installed applications with their
own permissions and privacy responsibilities.

Changes which add a permission, new data flow, log content, persistence, or an
external intent must explain that change and add an appropriate test. Never put
typed text, candidates, clipboard contents, touch paths, or dictionary entries
in logs or diagnostics.

## Contributing

Human-written and AI-assisted contributions are reviewed by the same standard:
the change must be understandable, focused, maintainable, appropriately tested,
and honestly described. The submitter remains responsible for the result.

Read [CONTRIBUTING.md](CONTRIBUTING.md), [AI_POLICY.md](AI_POLICY.md), and the
relevant [feature document](docs/features/README.md) before opening a pull
request.

## Upstream and license

Better FlorisBoard preserves FlorisBoard's Git history, copyright notices, and
credits. FlorisBoard and this fork are distributed under the
[Apache License 2.0](LICENSE). The original project and its contributors remain
available at the
[upstream repository](https://github.com/florisboard/florisboard).
