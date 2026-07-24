# Security Policy

## Supported versions

Security fixes are provided for the latest released version of AeroDNS. Older releases may receive a fix when the affected code is still shared with the current version.

## Reporting a vulnerability

Please use [GitHub's private vulnerability reporting](https://github.com/vmcsoft/aerodns/security/advisories/new). Include:

- The affected version and Android version
- The DNS protocol and configuration involved
- Reproduction steps or a minimal proof of concept
- The expected and observed security boundary
- Any suggested mitigation

Do not include real DNS histories, credentials, signing keys, or unrelated personal data. Please do not open a public issue until maintainers have assessed the report.

## Security model

AeroDNS is a local DNS-routing utility. It is not a full-tunnel VPN and does not provide anonymity.

- Standard DNS can be observed or modified by networks between the device and the selected resolver.
- DoH protects DNS transport only between the device and the selected HTTPS resolver.
- The selected resolver can observe DNS queries.
- Normal application traffic does not pass through AeroDNS.
- DNS responses are forwarded; AeroDNS is not a malware scanner or content-verification service.

## Untrusted-certificate mode

Custom DoH profiles can explicitly disable certificate-chain and hostname verification for that profile. This mode:

- Is disabled by default
- Is never used by built-in providers
- Requires an explicit warning confirmation
- Does not follow HTTP or HTTPS redirects
- Permits man-in-the-middle interception of DNS queries

This capability exists for advanced custom resolver configurations and should not be enabled for ordinary public DoH endpoints.
