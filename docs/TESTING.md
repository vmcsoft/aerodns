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

### Speed tests

- Select Standard or DoH before starting. Confirm results identify that protocol; unsupported profiles must be unavailable rather than measured with another transport.
- For a provider with both ordinary DNS addresses and a DoH URL, confirm a DoH run sends HTTPS DNS requests. A failed DoH request must not fall back to UDP.
- Verify custom endpoint discovery, explicit bootstrap overrides and certificate opt-in follow the same rules as connections.
- Simulate failed requests and a provider timeout. Confirm completed samples remain visible, partial results show successful replies out of ten, and more successful replies rank before lower latency.
- Activate a result and confirm the running service uses the measured protocol and profile.
- Cancel or retest while connected. Restore the prior configuration once if there is no newer choice; a newer selection or explicit disconnect must survive old cleanup.

The displayed latency uses successful DNS-query samples, trimming the fastest and slowest when at least three succeed. It is affected by resolver caching and connection reuse; it does not include DoH endpoint discovery or prove continuing DNS health.

## Logs

Development builds can be inspected with:

```bash
adb logcat | rg 'AeroDNS|DnsVpnService|DnsForwarder|DohDnsTransport|TunDnsPacketLoop'
```

Remove real resolver URLs, IP addresses, and other personal network details before attaching logs to an issue.
