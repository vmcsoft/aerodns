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
“Block connections without VPN” disabled. Use `--scenario disconnect-reboot` with
Always-on disabled and the fixture connected to check that the production disconnect command clears recovery and remains
off for 10 seconds before reboot and 30 seconds after boot. With Always-on enabled,
`--scenario always-on-disconnect` instead verifies the stop is refused for ten seconds
and the unchanged intended configuration recovers after reboot. `--scenario force-stop`
checks that an explicit Android force-stop leaves the process, VPN and notification
absent for 30 seconds; retained configuration is not treated as a running connection.

Recovery requires a live process, active VPN, healthy service notification, a new request
identity, and unchanged saved configuration. The observer never launches the app or
sends a reconnect while waiting. A stale notification alone is a failure. It waits up to
90 seconds for recovery and writes failure evidence when a scenario assertion fails;
setup failures must be resolved before interpreting a run. Dumpsys parsing is currently
validated on API 36 and must be checked before using other Android versions.

Keep JSON/log evidence outside Git. Disconnect, clear the validation package's Always-on
setting, stop the fixture, and shut down the owned emulator after the run. A passing
emulator test does not establish OEM, low-memory eviction, lockdown, or
production-upgrade behavior.

## Always-on and notification regressions

`NotificationBurstDeviceTest` rapidly replaces twenty resolver configurations, then
requires the latest healthy notification within twelve seconds. Teardown waits for
VPN/notification removal and checks that delayed work does not repost it. Run with
Always-on **off**, the HTTPS fixture running and `adb reverse tcp:18443 tcp:18443`.

`AlwaysOnPolicyDeviceTest` must run separately on Android 9+ with the actual Android
Always-on setting enabled for the validation package. It checks the public system
policy on Android 10+ and current per-user settings on Android 9, disabled dashboard controls, rejected stale disconnect and benchmark pause,
repository resolver replacement, notification action and a real SystemUI tile click.
Run once with lockdown off and once with it on, passing `policyLockdown=true` for the
latter. Use an owned emulator: lockdown deliberately interrupts ordinary networking.
On API 28, add the tile from the host **before** launching instrumentation. Adding and
immediately opening it inside the test can leave SystemUI's service binding without a
tile object. Keep it added across both policy runs, then remove this test-owned tile:

```bash
adb -s emulator-5562 shell cmd statusbar add-tile \
  com.vmcsoft.aerodns.validation/com.vmcsoft.aerodns.presentation.tile.DnsTileService
# Run the policy checks, then clean up:
# adb -s emulator-5562 shell cmd statusbar remove-tile \
#   com.vmcsoft.aerodns.validation/com.vmcsoft.aerodns.presentation.tile.DnsTileService
```

```bash
adb -s emulator-5556 shell am instrument -w -e responseTestPort 18443 \
  -e class com.vmcsoft.aerodns.NotificationBurstDeviceTest \
  com.vmcsoft.aerodns.validation.test/androidx.test.runner.AndroidJUnitRunner
# Enable Always-on through Android VPN settings before the separate policy run.
adb -s emulator-5556 shell am instrument -w -e responseTestPort 18443 \
  -e class com.vmcsoft.aerodns.AlwaysOnPolicyDeviceTest \
  com.vmcsoft.aerodns.validation.test/androidx.test.runner.AndroidJUnitRunner
```

The policy test cleans up through the real service revocation callback, not the now
forbidden disconnect command. Restore Always-on/lockdown settings after the run.
The normal suite requires both settings off. OEM behavior and signed upgrades remain
separate acceptance work. Do not count emulator policy checks as production-upgrade
or host-observed process-recovery evidence.

### Legacy Android policy changes and idle teardown

Run `LegacyVpnPolicyDeviceTest` on API 24–28. Its setting reads use the ordinary app UID;
no shell identity or extra permission is adopted. It verifies actual policy, notification
action, disconnect refusal/acceptance and removal of the real VPN after fixture cleanup.
Use the same HTTPS fixture on port 18443; this case uses emulator host alias `10.0.2.2`.

Run these separate configurations using Android VPN settings:

1. Always-on off: use the default false arguments below.
2. Connect first, then enable Always-on while the VPN is already active:
   pass `expectedAlwaysOn=true`.
3. Enable lockdown too: pass both arguments true. Some Android 7 images do not expose
   a lockdown switch; record the missing coverage instead of substituting a setting write.
4. With Always-on selected, force-stop the validation package. Turn Always-on off while
   the process remains absent, then run the test with both arguments false.

```bash
adb -s emulator-5558 shell am instrument -w \
  -e expectedAlwaysOn false -e expectedLockdown false \
  -e class com.vmcsoft.aerodns.LegacyVpnPolicyDeviceTest \
  com.vmcsoft.aerodns.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Then run `DnsHealthDeviceTest` and `VpnLifecycleDeviceTest` with both settings off.
The health cases cover real UDP/DoH traffic, replacement, cancellation, an unavailable
Standard resolver and a working selected address following an unreachable IPv6 address; API 28+
adds debug-component observation. Inspect JUnit results for failures/skips, since the
`am instrument` shell exit status alone does not establish a pass.

Fixture readiness counters are scoped to endpoint paths, so concurrent isolated runs
cannot satisfy each other's slow-request readiness. Validate the fixture separately:

```bash
python3 -m unittest discover -s app/src/androidTest/fixtures -p 'test_*.py'
```

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
