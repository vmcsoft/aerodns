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

## Host-driven process recovery

Use `app/src/androidTest/fixtures/process_recovery_check.py` on an owned emulator with
an isolated `.validation` debug APK. The host remains alive while the app process is
killed or Android reboots; instrumentation inside that process cannot observe its own
restart. `SIGKILL` is a process-death test; force-stop has different Android semantics.

Build the isolated package with a temporary Gradle init script outside the checkout:

```groovy
allprojects { p ->
    p.plugins.withId('com.android.application') {
        p.android.buildTypes.debug.applicationIdSuffix = '.validation'
    }
}
```

Pass that script to `./gradlew -I /tmp/aerodns-validation.init.gradle assembleDebug`.
Select the owned emulator serial explicitly in every command below (example: `emulator-5556`).
Install `app/build/outputs/apk/debug/app-debug.apk` there. Start the fixture in a separate terminal:

```bash
python3 app/src/androidTest/fixtures/doh_response_server.py --port 18443
```

On this disposable emulator, preauthorize VPN consent and notifications, then connect:

```bash
adb -s emulator-5556 shell appops set com.vmcsoft.aerodns.validation ACTIVATE_VPN allow
# Android 13+ only:
adb -s emulator-5556 shell pm grant com.vmcsoft.aerodns.validation android.permission.POST_NOTIFICATIONS
adb -s emulator-5556 shell am broadcast --receiver-foreground \
  -n com.vmcsoft.aerodns.validation/com.vmcsoft.aerodns.validation.RecoveryControlReceiver \
  --es command connect
python3 app/src/androidTest/fixtures/process_recovery_check.py \
  --serial emulator-5556 --scenario process-death --output /tmp/process-recovery.json
```

Wait for the fixture connection to be healthy before invoking the observer. The broadcast
must return `submitted:connect`; `vpn-consent-required` means preparation is incomplete.
The debug receiver calls Android VPN preparation before connection, including after
Always-on settings revoke preparation. Its fixed test profile uses host alias `10.0.2.2`
with the local fixture certificate explicitly allowed. This survives reboot without
`adb reverse`. The receiver requires `android.permission.DUMP` and is absent from release.
For a separate Standard test, add `--ez standard true` to the connect broadcast; it uses
`8.8.8.8` and requires external UDP DNS access.

Run the process-death scenario once with Always-on disabled and separately with it enabled
in Android VPN Settings. Use `--scenario reboot` for Always-on startup, with
“Block connections without VPN” disabled. Use `--scenario disconnect-reboot` while
connected to check that the production disconnect command clears recovery and remains
off for 10 seconds before reboot and 30 seconds after boot.

Recovery requires a live process, active VPN, healthy service notification, a new request
identity, and unchanged saved configuration. The observer never launches the app or
sends a reconnect while waiting. A stale notification alone is a failure. It waits up to
90 seconds for recovery and writes failure evidence when a scenario assertion fails;
setup failures must be resolved before interpreting a run. Dumpsys parsing is currently
validated on API 36 and must be checked before using other Android versions.

Keep JSON/log evidence outside Git. Disconnect, clear the validation package's Always-on
setting, stop the fixture, and shut down the owned emulator after the run. A passing
emulator test does not establish OEM, low-memory eviction, force-stop, lockdown, or
production-upgrade behavior.

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
