# Contribution guidelines for this fork

Thanks for considering contributing to this FlorisBoard fork!

Issues and pull requests may be written or implemented with AI assistance. All
work is reviewed on its merits, and the person submitting it remains
responsible for its correctness, testing, licensing, and description.

All contributions must be in English and follow the
[code of conduct](CODE_OF_CONDUCT.md) and this fork's
[AI-assisted contribution policy](AI_POLICY.md). Anyone may propose a change,
but only [@aytekaksu](https://github.com/aytekaksu) may approve and merge it.

## Non-code contributions

### Translations

To make FlorisBoard accessible in as many languages as possible, the platform [Crowdin](https://crowdin.florisboard.org) is used to crowdsource and manage translations.  The list of languages in Crowdin covers a good range of languages, but feel free to email [florisboard@patrickgold.dev](mailto:florisboard@patrickgold.dev) to request a new language.

> [!IMPORTANT]
> This is the only source of translations - **PRs that add/update translations are not accepted.**

### Bug reporting

Bug reports show where the fork can improve stability and usability. Please use
the pre-made [bug report template](https://github.com/aytekaksu/better-florisboard/issues/new?template=bug_report.yml)
and include concise reproduction steps.

#### Capturing error logs

Logs are captured by FlorisBoard's crash handler, which lets you copy them into
the [crash report template](https://github.com/aytekaksu/better-florisboard/issues/new?template=crash_report.yml).
This is the preferred way to capture logs.

Alternatively, you can also use ADB (Android Debug Bridge) to capture the error log. This is recommended for experienced users only.

### Feature proposals

Use the [feature proposal template](https://github.com/aytekaksu/better-florisboard/issues/new?template=feature_request.yml)
to suggest an idea or improvement.

### Feedback

For general feedback, open an
[issue](https://github.com/aytekaksu/better-florisboard/issues/new) with enough
context to make it actionable.

## Code contributions

You are always welcome to contribute new features or work on existing issues, there are a lot to choose from :) It is always best to quickly ask if someone is already working on this issue to avoid duplicate issues.

> [!NOTE]
> If you intend to implement a bigger feature please coordinate with us so we can prevent that there's a major difference in expected implementation.

If you need help understanding the code, ask in the relevant issue or pull
request. Issues marked `good first issue` are intended to be approachable.

### System requirements for development

- Desktop PC with Linux or WSL2 (Windows)
  - MacOS and Windows without WSL2 probably works too however there's no official support
- At least 16GB of RAM (because of Android Studio / IntelliJ)
- The following tools must be installed:
  - Android Studio (bundles SDK and NDK) or IntelliJ with Android and Compose plugin
  - Java 17
  - CMake 3.22+
  - Clang 15+
  - Git
  - [Rust](https://www.rust-lang.org/tools/install)
- Utilities (optional)
  - Python 3.10+
  - Bash, realpath, grep, ...

> [!IMPORTANT]
> If using IntelliJ IDEA you have to enable `Future AGP Versions` otherwise AGP 9.0.0 will not work with your IDE.
> How to do this is described in this [comment on YouTrack](https://youtrack.jetbrains.com/issue/IDEA-348937/2024.1-Beta-missing-option-to-enable-sync-with-future-AGP-versions#focus=Comments-27-11721710.0-0)

### Manual build without Android Studio

If you want to manually build the project without Android Studio you must ensure that the Android SDK and NDK are properly installed on your system. Then issue

```./gradlew clean && ./gradlew assembleDebug```

and Gradle should take care of every build task.

## Donating

You can also show your support by buying me a coffee, so I can stay up all night and chase away bugs or add new cool stuff :)
See the `Sponsors` button for available options!
