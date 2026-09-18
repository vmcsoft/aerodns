# Contributing to AeroDNS

Thank you for helping improve AeroDNS.

## Before starting

- Search existing issues and pull requests first.
- Open an issue before undertaking a large behavioral or architectural change.
- Keep changes focused. Unrelated cleanup should be submitted separately.
- Never include DNS histories, production logs, credentials, signing material, or personal network information.

## Development setup

Install JDK 17, Android SDK Platform 36.1 and Build Tools 36.0.0, then clone the repository:

```bash
git clone https://github.com/vmcsoft/aerodns.git
cd aerodns
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Android Studio can open the repository root directly.
Set `ANDROID_HOME` or configure the SDK path in your local `local.properties` file.
Keep that file out of Git. Python 3 and OpenSSL are needed for the local HTTPS test
fixtures; Android instrumentation additionally requires a device or emulator.

Use the [isolated validation package](docs/TESTING.md#isolated-validation-package)
when keeping a development build alongside a Play installation.

## Pull requests

A pull request should:

- Explain the problem and the chosen solution.
- Include tests for behavior changes where practical.
- Pass the host checks documented in [Testing](docs/TESTING.md#automated-tests).
- Update relevant documentation and `CHANGELOG.md`.
- Avoid changing the application ID, signing configuration, or release version unless the change was previously agreed with the maintainers.

Changes to VPN routing, packet parsing, TLS handling, or resolver validation require regression tests and a description of the security impact.

CI runs host tests, Python fixture tests, lint for both build variants, and debug/test
and minified release builds. It retains reports for 14 days. Device tests run separately:
report the Android API, device/emulator, protocol, network setup and any skips. A green
host build does not establish VPN consent, recovery, hardware battery or upgrade behavior.
Some network tests require live public resolvers; distinguish endpoint failures from
deterministic fixture failures.

## Release and dependency changes

Keep releases focused and record user-visible changes under the unreleased version in
the changelog. A source version or successful build does not mean an update is live on
Google Play. Release artifacts must be signed and verified by the maintainers; CI does
not have signing keys and does not publish builds.

Review dependency updates for Android API compatibility, Compose/Kotlin compatibility,
behavior changes and licensing. Merge them after the applicable CI/device checks pass.
GitHub Actions are pinned to commit hashes and maintained through Dependabot.

## Code style

- Follow existing Kotlin and Jetpack Compose conventions.
- Prefer small, testable domain and transport components.
- Preserve the DNS-only routing invariant unless a proposal explicitly changes the product model.
- Keep user-facing security warnings direct and unambiguous.

## Reporting problems

Use GitHub Issues for reproducible bugs and feature requests. Do not disclose vulnerabilities in public issues; follow [SECURITY.md](SECURITY.md) instead.
