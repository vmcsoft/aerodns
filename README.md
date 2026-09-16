<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="AeroDNS app icon" width="120" />
</p>

<h1 align="center">AeroDNS</h1>

<p align="center">
  A lightweight, open-source DNS changer for Android.
</p>

<p align="center">
  <a href="https://github.com/vmcsoft/aerodns/actions/workflows/android.yml"><img alt="Android CI" src="https://github.com/vmcsoft/aerodns/actions/workflows/android.yml/badge.svg" /></a>
  <a href="LICENSE"><img alt="Apache-2.0 license" src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" /></a>
  <img alt="Android 7.0+" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84.svg" />
</p>

AeroDNS changes the device DNS resolver through Android's `VpnService` API. It is designed to route only DNS traffic, keeping ordinary application traffic on the device's underlying network.

## Features

- One-tap DNS connect and disconnect
- Standard DNS and DNS-over-HTTPS (DoH)
- Built-in Cloudflare, Google, AdGuard, OpenDNS, and Quad9 profiles
- Custom IPv4, IPv6, and DoH resolvers
- Parallel DNS latency testing
- Quick Settings tile
- Automatic recovery after network changes
- OLED-friendly Jetpack Compose interface
- No ads, analytics, accounts, or VMCSoft-operated DNS servers

## How it works

For standard DNS, AeroDNS establishes a VPN interface with the selected DNS servers and no default route for normal application traffic.

For DoH, the app exposes a virtual DNS address at `10.0.0.1`, routes only that address into the VPN interface, and forwards DNS packets to the selected HTTPS resolver through protected sockets on the underlying network.

> [!IMPORTANT]
> AeroDNS is a DNS changer, not an anonymity service or a full-tunnel VPN. Your selected DNS provider can observe your DNS queries, and non-DNS traffic does not pass through AeroDNS.

See [Architecture](docs/ARCHITECTURE.md) for the technical model and current protocol limitations.

## Always-on VPN

When Android Always-on VPN is selected, turn it off in Android VPN settings before
disconnecting or running a speed test. You can still choose another DNS resolver.
Leave **Block connections without VPN** off: AeroDNS routes DNS only, so this Android
option blocks ordinary app traffic. Android 10+ can report that setting directly;
older Android behavior still needs broader device validation.

## Build from source

Requirements:

- JDK 17
- Android SDK Platform 36.1
- Android Studio or the included Gradle wrapper

```bash
git clone https://github.com/vmcsoft/aerodns.git
cd aerodns
./gradlew assembleDebug
```

Install a connected-device build:

```bash
./gradlew installDebug
```

Run the local test suite:

```bash
./gradlew testDebugUnitTest
```

More validation scenarios are documented in [Testing](docs/TESTING.md).

## Security and privacy

AeroDNS does not operate a backend and does not collect telemetry. DNS queries are sent directly to the resolver selected by the user. Read [PRIVACY.md](PRIVACY.md) for the complete data-flow summary.

Custom DoH profiles include an advanced, opt-in certificate-verification override for resolvers that cannot use Android's normal trust store. It is disabled by default, isolated from built-in providers, and exposes DNS traffic to interception when enabled. See [SECURITY.md](SECURITY.md) before using or modifying this feature.

Please report vulnerabilities privately through [GitHub Security Advisories](https://github.com/vmcsoft/aerodns/security/advisories/new).

## Contributing

Bug reports, documentation improvements, tests, and focused code contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Copyright 2026 VMCSoft and AeroDNS contributors.

Licensed under the [Apache License 2.0](LICENSE).
