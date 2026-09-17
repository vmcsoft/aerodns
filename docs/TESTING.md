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

## Dashboard and tile startup with real consent

`app/src/androidTest/fixtures/startup_entry_check.py` drives the Android consent dialog,
dashboard, speed-test activation and SystemUI tile from the host. Use an **owned disposable
emulator**, the isolated `.validation` APK, working internet, English UI, notifications
enabled and Always-on off. It revokes consent for the test package, force-stops its
process, and adds/removes its tile; do not use a saved personal emulator. It does not
pregrant VPN consent. Install the debug APK before running:

```bash
python3 app/src/androidTest/fixtures/startup_entry_check.py \
  --serial emulator-5558 --output /tmp/aerodns-startup.json
```

Use `--scenario dashboard`, `speed-test` or `tile` for a focused run. The full run checks
cold dashboard denial, repeated denial/retry, consent followed by healthy connection,
prepared cold dashboard startup, six seconds backgrounded, UI disconnect, speed-test
activation denial/retry, and unprepared/prepared cold tile starts. Each cold start
requires the process to be absent before the user action. Permission grant from the tile
opens the dashboard without auto-connecting. The prepared tile connects directly.

The benchmark selects the real Cloudflare result and requires it to be reachable; an
external endpoint failure is not a pass. Assertions require an actual active VPN and
healthy app notification, or both absent after stop/denial. The script requires current
English UI labels and validates the report from `dumpsys vpn_management`; check these
contracts before applying it to another image. It is not signed-upgrade, long-idle,
manufacturer-specific, or autonomous recovery testing. Keep JSON/logs outside Git.

## Controlled load and idle recovery

Use a disposable API 26+ emulator with the isolated `.validation` app and test APKs,
VPN/notification consent already granted, Private DNS off and Always-on off. Run the
loopback fixture separately:

```bash
python3 app/src/androidTest/fixtures/doh_load_server.py --port 18446
adb -s emulator-5556 reverse tcp:18446 tcp:18446
adb -s emulator-5556 shell am instrument -w \
  -e ownedLoadEmulator true -e loadVariant candidate -e loadPhase steady \
  -e loadSeconds 60 -e loadTrial trial1 \
  -e class com.vmcsoft.aerodns.DohLoadDeviceTest \
  com.vmcsoft.aerodns.validation.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell run-as com.vmcsoft.aerodns.validation \
  cat files/load-candidate-steady-trial1.json > /tmp/load-candidate-steady-trial1.json
```

Repeat with `loadPhase=mixed` and `burst`, force-stopping the validation package
between runs. The driver starts and disconnects the actual VPN. Steady sends 20 A
queries/second; mixed sends ten/second with every twentieth response delayed two
seconds. Burst sends 96 immediately, all delayed two seconds. Other measured replies
have 20 ms fixture delay. Each transaction/question is unique and must receive the
fixture's `192.0.2.42` answer. Duration is 5–300 seconds for steady/mixed; burst is
always 96 queries. Replies are observed until six seconds after the scheduled last
send, followed by a fresh-query recovery check and five-second resource cooldown.

Candidate acceptance requires no invalid replies, retained VPN, recovery within 15
seconds and clean disconnect. Non-overload phases also require at least 99% replies
and fast-query p95 below one second in this fixture. Burst intentionally overloads
the bounded queue, so missing responses are reported rather than required to succeed.
Inspect descriptor/PSS/thread samples for resource growth as well as the assertions.
CPU time includes the same-process test driver. Server `/stats` reports requests,
active handlers and distinct client ports; cancelled calls can leave handlers sleeping,
so handler concurrency is not a direct app-worker measurement.

For comparison, build the selected baseline in a detached temporary worktree with the
same package suffix. Install that app plus the **same current test APK**, set
`loadVariant=baseline`, and otherwise keep workload and emulator settings identical.
The driver uses shared service/configuration APIs and explicitly enables the baseline's
packet loop. It records baseline loss/recovery without enforcing candidate thresholds.
Record source identities, APK hashes, order and results; do not call this a comparison
with a Play-delivered binary unless that exact artifact was used. Never uninstall a
personal app or overwrite its data for this comparison.

For idle recovery, reinstall the candidate, grant consent and leave the emulator screen
on with its test VPN disconnected. The host observer reaches the same fixture through
`10.0.2.2`, backgrounds the app, simulates unplugging, switches the screen off and forces
deep idle. It checks the selected configuration and a fresh controlled system DNS
lookup after wake, then restores idle/battery/screen state and disconnects:

```bash
python3 app/src/androidTest/fixtures/idle_recovery_check.py \
  --serial emulator-5556 --seconds 180 --output /tmp/idle-recovery.json
```

The idle observer's dumpsys parsing is validated on API 36. Load and idle use a local
certificate with the custom bypass explicitly enabled. Real HTTPS host tests separately
check connection reuse and rejection after switching back to strict validation. These
workloads do not establish public-provider latency, physical battery drain, overnight
survival or OEM behavior. Keep raw reports outside Git. Stop fixtures with Ctrl-C to
delete their temporary certificates, remove owned reverse ports and shut down the
disposable emulator after testing.

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

## Controlled resolver identity and network loss

`NetworkTransitionDeviceTest` is opt-in and requires an **owned disposable API 34+
emulator**. It disables cellular data and cycles Wi-Fi; do not run it on a personal
phone. Its shell stdout/stderr capture uses the API 34 `executeShellCommandRwe` API.
Use the separate `.validation` package and VPN/notification preparation described above,
with Always-on disabled. Build both `assembleDebug` and `assembleDebugAndroidTest` with
the external init script and install both APKs on the selected emulator.

Start `app/src/androidTest/fixtures/resolver_identity_server.py` on the host. The emulator
must reach UDP port **53** and HTTPS port **18445** at `10.0.2.2`. The script defaults to
unprivileged UDP 15353 and HTTPS 18444; supply explicit ports or use loopback-only container
port mappings (`127.0.0.1:53:15353/udp`, `127.0.0.1:18445:18444`). When containerized, use
`--listen 0.0.0.0` inside the container, keeping the published host ports loopback-only.
For a host runtime permitted to bind UDP 53, the direct command is:

```bash
python3 app/src/androidTest/fixtures/resolver_identity_server.py \
  --dns-port 53 --https-port 18445 --journal /tmp/aerodns-identity.jsonl
```

If binding 53 is denied, use a local container port mapping instead; do not change the
system DNS configuration. Generate the short-lived self-signed certificate locally, or
provide a matched `--cert` and `--key` pair when OpenSSL is unavailable in the fixture runtime. Store its `--journal`
and certificate/key outside the repository. Never replace another service using port 53.

The fixture answers fresh `*.aerodns.test` A queries with `192.0.2.42` via UDP or HTTPS
`/42`, and `192.0.2.43` via HTTPS `/43`. `/fail` returns SERVFAIL. It returns empty AAAA
answers, so this is IPv4 DNS transport coverage, not IPv6 resolver attribution. Only
generated test names are journaled; unrelated UDP questions are forwarded to Google DNS
for emulator connectivity validation without being logged. `example.com` health probes
receive the fixture answer. Test addresses are documentation-only; ICMP delivery is not
expected. Assertions inspect the OS-resolved address or explicit `unknown host` error.

Disable Android Private DNS on this disposable emulator for the controlled DNS path:

```bash
adb -s emulator-5556 shell settings put global private_dns_mode off
adb -s emulator-5556 shell am instrument -w \
  -e ownedNetworkEmulator true \
  -e class com.vmcsoft.aerodns.NetworkTransitionDeviceTest \
  com.vmcsoft.aerodns.validation.test/androidx.test.runner.AndroidJUnitRunner
```

First run with ordinary emulator DNS. The underlay control must fail to resolve the
fixture domain; selected Standard and DoH lookups must return their distinct answers.
Then restart the same owned emulator with `-dns-server 127.0.0.1`, keeping the host fixture
running, and repeat with `-e fixtureBootstrap true`. In that mode, the underlay control
returns `.42`, and the URL-only DoH hostname `bootstrap.aerodns.test` resolves through the
physical network to the fixture. DoH `/43` must still return `.43`; SERVFAIL and strict TLS
must fail despite the working underlay answer. No explicit bootstrap IP is configured.

The seven cases cover the underlay control, Standard attribution, six immediate Standard
lookups across replacements, DoH replacement/failure, certificate opt-in isolation, and offline/recovery plus explicit-stop persistence for
both protocols. The recovery checks require a new validated physical network, unchanged
profile settings, exactly one replacement request, and fresh system lookups immediately
after recovered health and four seconds later. The recovered request identity must remain
unchanged during that interval; a direct endpoint-resolver check also requires a
new network identity. The endpoint IP stays constant, so this does not test changing DNS
records. Correlate `ResolverAttributionTest` logcat entries with fixture journal names:
DoH probes must appear only on the selected HTTPS path, Standard probes on UDP, and strict
TLS must produce no accepted fixture query. Never count empty shell output as success.

Run fixture regressions with:

```bash
python3 -m unittest discover -s app/src/androidTest/fixtures -p 'test_*.py'
```

Cleanup reenables emulator Wi-Fi/data even on a failed assertion. After recording results,
stop only the owned emulator and fixture and delete temporary test keys. This suite does
not establish physical Wi-Fi/mobile handover, Android Private DNS interoperability,
lockdown, IPv6 transport, signed-upgrade compatibility, or OEM behavior.

### Physical-network callback regression coverage

`NetworkMonitorTest` exercises repeated arrival/capability bursts, interleaved Wi-Fi and
mobile callbacks, survivor/loss/revalidation transitions, immutable published membership,
VPN-default exclusion, callback cleanup, and physical reconnect readiness. These are host
contract tests with mocked Android objects. `NetworkTransitionDeviceTest` independently
checks the real callback-to-service path and counts replacement request IDs.

The recovery assertions do not claim zero DNS downtime while the physical network is
absent or during the ordinary reconnect's stop/start gap. Real Wi-Fi/mobile handover and
same-network DNS/address changes require separate acceptance evidence.

### Fresh physical IP-family discovery

`NetworkCapabilitiesRepositoryTest` covers immediate IPv4-to-IPv6 handover,
same-network address updates, physical selection while the VPN is default, missing
defaults/properties, validated preference, unvalidated fallback and VPN-only exclusion.

`NetworkFamilyDeviceTest` requires an **owned disposable emulator** with dual-stack
virtual Wi-Fi and IPv4-only simulated cellular service. Prepare the isolated package,
VPN consent and notifications as above, with Always-on off. The API 36.1 Google Play
ARM64 image provides the tested topology. Both APKs must be installed. Run:

```bash
adb -s emulator-5556 shell am instrument -w \
  -e ownedNetworkEmulator true \
  -e class com.vmcsoft.aerodns.NetworkFamilyDeviceTest \
  com.vmcsoft.aerodns.validation.test/androidx.test.runner.AndroidJUnitRunner
```

The two cases use one repository instance through Wi-Fi → mobile → Wi-Fi, checking the
first transition inside the former 60-second cache window. One case retains a real VPN
and additionally checks that losing all physical networks returns unknown rather than
the TUN's IPv4 family. A mismatched topology fails the prerequisite assertions. The
suite cycles Wi-Fi/data and reenables both in cleanup; never run it on a personal phone.

These are Android address-classification checks. They do not prove physical mobile
handover, automatic repository reconnection, IPv6 reachability, DNS attribution or
correct underlay selection during overlapping networks. Physical Wi-Fi/mobile checks
need working mobile data and USB ADB so disabling Wi-Fi keeps the controller connected.

### Physical Wi-Fi loss and recovery over USB

`PhysicalWifiRecoveryDeviceTest` is separately opt-in and cycles the phone's Wi-Fi.
Use it only on a user-authorized test phone with **verified USB ADB**, Wi-Fi connected,
mobile data already off, Always-on off, no active VPN, and prepared VPN consent for the
isolated `.validation` package. Confirm `adb devices -l` lists a USB transport and select
that serial explicitly. Both validation APKs must be installed. Do not use wireless ADB.

```bash
adb -s USB_SERIAL shell am instrument -w \
  -e physicalWifiRecovery true \
  -e class com.vmcsoft.aerodns.PhysicalWifiRecoveryDeviceTest \
  com.vmcsoft.aerodns.validation.test/androidx.test.runner.AndroidJUnitRunner
```

The Standard case uses Google DNS `8.8.8.8`; the URL-only DoH case uses
`https://dns.google/dns-query` with normal certificate verification and no bootstrap IP.
Each connects through the real service/repository, removes Wi-Fi until sampled DNS
health fails, and restores Wi-Fi. Recovery must preserve the exact configuration except
request identity, establish one replacement, resolve a system lookup, and remain stable
for a second lookup four seconds later. After an explicit disconnect, another Wi-Fi
cycle must leave the VPN off. These external endpoints must be reachable on the test
network; an environmental failure is not a pass.

Cleanup disconnects the test VPN and reenables Wi-Fi, without changing mobile data or
Private DNS. If instrumentation is interrupted or killed, the host must restore Wi-Fi
with `adb -s USB_SERIAL shell svc wifi enable` and disconnect the validation VPN.
Inspect JUnit results and `PhysicalWifiTest` logcat entries, then verify Wi-Fi connectivity
and absence of the validation service. Logs belong outside Git.

The system lookup uses a separate shell process but can receive cached DNS answers.
It does not establish resolver attribution, zero downtime, Wi-Fi/mobile handover,
IPv6 reachability, idle recovery or behavior on other manufacturers/Android versions.
