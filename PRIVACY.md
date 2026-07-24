# Privacy

Last updated: July 24, 2026

AeroDNS is designed to process DNS traffic locally without a VMCSoft-operated backend.

## Data AeroDNS does not collect

The official source code contains no:

- Analytics or advertising SDKs
- User accounts
- Telemetry upload
- Crash-reporting service
- VMCSoft-operated DNS relay

## Data processed on the device

AeroDNS stores the selected resolver, custom resolver configurations, protocol preference, and connection state in Android DataStore. Application backup is disabled. Uninstalling the app removes this locally stored data according to Android's normal application-data behavior.

Debug builds can write diagnostic information to Android logcat for development and troubleshooting. Release builds remove Android logging calls during code shrinking.

## DNS providers

DNS queries are sent to the provider selected by the user. That provider—and networks carrying unencrypted standard DNS—may process or retain information according to their own policies. AeroDNS does not control third-party resolver policies.

## Network permissions

AeroDNS uses Android's VPN and network APIs to establish DNS routing, detect network changes, test resolver reachability, and display connection status. It does not route ordinary application traffic through a VMCSoft server.

Builds distributed by third parties may modify this behavior. Review the source and distributor before installing an unofficial build.
