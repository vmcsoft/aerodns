# Testing

## Automated tests

Run the complete local unit-test suite:

```bash
./gradlew testDebugUnitTest
```

Build the debug application:

```bash
./gradlew assembleDebug
```

Build the minified release APK and Android App Bundle:

```bash
./gradlew assembleRelease bundleRelease
```

## Device tests

Install the debug build:

```bash
./gradlew installDebug
```

Run the built-in DoH provider smoke test on a connected device:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vmcsoft.aerodns.DohDeviceSmokeTest
```

VPN permission must be granted interactively before tests that establish `VpnService`.

## Manual regression checklist

### Standard DNS

- Connect each built-in provider on IPv4, IPv6, and dual-stack networks where available.
- Confirm DNS resolution succeeds while direct network traffic remains reachable.
- Disconnect and confirm Android restores its previous DNS behavior.

### DNS-over-HTTPS

- Test every built-in provider.
- Confirm the virtual resolver is established and ordinary traffic remains outside TUN.
- Switch Wi-Fi and mobile networks while connected.
- Confirm certificate errors do not suggest enabling unsafe mode for built-in providers.

### Custom resolvers

- Validate IPv4 and IPv6 addresses.
- Validate HTTPS URL requirements.
- Test custom bootstrap addresses.
- Confirm edits, deletion, and persistence across restart.
- Confirm the untrusted-certificate warning must be explicitly accepted.

### Quick Settings tile

- Test initial permission flow from the tile.
- Connect and disconnect with the app closed.
- Verify labels on API 24 through 28 and subtitles on supported versions.

### Lifecycle

- Reboot with Always-on VPN enabled.
- Force-stop and reopen the application.
- Change networks during connection and during a speed test.
- Cancel a speed test and confirm the previous connection is restored.

## Logs

Development builds can be inspected with:

```bash
adb logcat | rg 'AeroDNS|DnsVpnService|DnsForwarder|DohDnsTransport|TunDnsPacketLoop'
```

Remove real resolver URLs, IP addresses, and other personal network details before attaching logs to an issue.
