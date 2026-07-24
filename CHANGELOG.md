# Changelog

Notable changes to AeroDNS are recorded here.

## [1.5.2] - 2026-07-23

- Target Android 16 / API level 36.
- Compile against Android SDK Platform 36.1.
- Validate unit tests, minified release APK assembly, and Android App Bundle assembly.

## 1.5.1 - 2026-06-01

- Promote VPN startup to a foreground service immediately.
- Guard Quick Settings tile subtitles on Android 7 through 9.
- Add regression coverage for foreground startup and tile API compatibility.

## 1.5.0 - 2026-05-25

- Support custom DoH endpoints using an optional bootstrap IP.
- Add an isolated, explicitly confirmed untrusted-certificate mode for custom DoH.
- Improve certificate errors and custom DNS form validation.

## 1.4.2 - 2026-05-11

- Stream parallel resolver speed-test results.
- Include custom standard DNS and DoH resolvers in speed tests.
- Require a real DNS response when validating custom resolvers.

## 1.4.1 - 2026-05-07

- Add custom DNS-over-HTTPS profiles.
- Simplify standard DNS and DoH configuration tabs.

## 1.4.0 - 2026-04-23

- Refresh the dashboard and resolver-selection interface.
- Stabilize the DoH packet loop and protected-socket transport.

## 1.3.0 - 2026-03-14

- Add IPv4, IPv6, and dual-stack network detection.
- Improve reconnection behavior and reduce background ping frequency.

## 1.2.0 - 2026-02-17

- Validate custom DNS connectivity before connection.
- Add save-and-connect and unsaved-change confirmation flows.

## 1.1.0 - 2026-02-11

- Add the Android Quick Settings tile.
- Improve timeout and VPN state handling during speed tests.

## 1.0.0 - 2026-01-06

- Initial release with DNS-only VPN routing, speed testing, custom resolvers, and network monitoring.

[1.5.2]: https://github.com/vmcsoft/aerodns/releases/tag/v1.5.2
