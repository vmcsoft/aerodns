# Privacy

Last updated: September 17, 2026

AeroDNS is designed to process DNS traffic locally without a VMCSoft-operated backend.

## Data AeroDNS does not collect

The official source code contains no:

- Analytics or advertising SDKs
- User accounts
- Telemetry upload
- Crash-reporting service
- VMCSoft-operated DNS relay

## Data processed on the device

AeroDNS stores resolver selection, custom profiles, protocol preferences and custom
certificate settings locally in Android DataStore. A separate SharedPreferences record
stores the intended active connection so Android can restore it after a process restart.
These records can contain custom resolver URLs and bootstrap IP addresses. They are not
uploaded to VMCSoft. Application backup is disabled. Uninstalling removes the app's
local data according to Android's normal application-data behavior.

Debug builds can write diagnostic information to Android logcat for development and troubleshooting. Release builds remove Android logging calls during code shrinking.

## DNS providers

DNS queries are sent to the provider selected by the user. That provider—and networks carrying unencrypted standard DNS—may process or retain information according to their own policies. AeroDNS does not control third-party resolver policies.

### Connection checks and speed tests

While connected, AeroDNS queries the fixed name `example.com` through the selected
DNS path. It checks on connection and starts the next check 30 seconds after the
previous result. The provider can observe these queries. Results update the dashboard,
notification and tile locally; they are not sent to a monitoring service.

Speed tests send DNS queries to the eligible built-in and custom providers being
measured, using the selected Standard or DoH protocol. Those providers can observe
the test queries even when they are not your currently selected resolver. Testing may
temporarily pause the active AeroDNS connection and restore it if no newer user action
supersedes it. Speed tests do not upload results to VMCSoft.

### Custom DoH discovery and TLS

Without an explicit bootstrap IP, Android resolves a custom DoH endpoint's hostname
through an underlying non-VPN network. That network's DNS can therefore observe the
endpoint hostname before the HTTPS connection is established. An explicit bootstrap
IP overrides this endpoint-discovery step.

DoH uses normal certificate and hostname verification by default. The advanced
custom-profile option to allow untrusted certificates disables both checks for that
profile and allows interception of its DNS traffic. It requires confirmation and is
not used by built-in providers. See [Security](SECURITY.md).

## Network permissions

AeroDNS uses Android's VPN and network APIs to establish DNS routing, detect network changes, test resolver reachability, and display connection status. It does not route ordinary application traffic through a VMCSoft server.

Applications that implement their own DNS can send queries outside Android's selected
system resolver. AeroDNS does not provide anonymity or control the privacy practices
of other apps or DNS providers.

Builds distributed by third parties may modify this behavior. Review the source and distributor before installing an unofficial build.
