# Contributing to AeroDNS

Thank you for helping improve AeroDNS.

## Before starting

- Search existing issues and pull requests first.
- Open an issue before undertaking a large behavioral or architectural change.
- Keep changes focused. Unrelated cleanup should be submitted separately.
- Never include DNS histories, production logs, credentials, signing material, or personal network information.

## Development setup

Install JDK 17 and Android SDK Platform 36.1, then clone the repository:

```bash
git clone https://github.com/vmcsoft/aerodns.git
cd aerodns
./gradlew testDebugUnitTest assembleDebug
```

Android Studio can open the repository root directly.

## Pull requests

A pull request should:

- Explain the problem and the chosen solution.
- Include tests for behavior changes where practical.
- Pass `./gradlew testDebugUnitTest assembleDebug`.
- Update relevant documentation and `CHANGELOG.md`.
- Avoid changing the application ID, signing configuration, or release version unless the change was previously agreed with the maintainers.

Changes to VPN routing, packet parsing, TLS handling, or resolver validation require regression tests and a description of the security impact.

## Code style

- Follow existing Kotlin and Jetpack Compose conventions.
- Prefer small, testable domain and transport components.
- Preserve the DNS-only routing invariant unless a proposal explicitly changes the product model.
- Keep user-facing security warnings direct and unambiguous.

## Reporting problems

Use GitHub Issues for reproducible bugs and feature requests. Do not disclose vulnerabilities in public issues; follow [SECURITY.md](SECURITY.md) instead.
