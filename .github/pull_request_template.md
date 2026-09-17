## Summary

Describe the problem and the solution.

## Validation

- [ ] `./gradlew testDebugUnitTest`
- [ ] `python3 -m unittest discover -s app/src/androidTest/fixtures -p 'test_*.py'`
- [ ] `./gradlew lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease`
- [ ] Relevant device testing, or an explanation of why it is not applicable
- [ ] Relevant docs and changelog updated

Include Android/device/network details and any skipped checks. Do not attach private
DNS history, credentials, or unredacted device logs.

## Security and privacy

Describe any effect on VPN routing, DNS transport, TLS validation, logs, or stored data. Write "None" when there is no impact.
