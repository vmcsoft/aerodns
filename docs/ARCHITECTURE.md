# Architecture

AeroDNS is a native Android application written in Kotlin with Jetpack Compose. It follows a layered MVVM structure:

```text
presentation → domain ← data
```

- `presentation` contains Compose screens, UI state, the dashboard view model, and Quick Settings tile.
- `domain` contains resolver models, repository contracts, and use cases.
- `data` contains DataStore persistence, DNS transports, network monitoring, and the VPN service.
- `di` provides Hilt dependency bindings.

## DNS-only VPN model

AeroDNS uses Android's `VpnService` because Android does not expose a general per-device DNS-setting API to ordinary applications.

### Standard DNS

Standard mode configures the selected DNS addresses on the VPN interface without adding a default route. Android sends DNS traffic to the configured resolver while ordinary application traffic stays on the underlying network.

### DNS-over-HTTPS

DoH needs explicit packet forwarding:

1. The VPN advertises the virtual DNS address `10.0.0.1`.
2. Only `10.0.0.1/32` is routed into the TUN interface.
3. `TunDnsPacketLoop` reads IPv4 UDP DNS packets.
4. `DnsForwarder` sends the DNS payload through `DohDnsTransport`.
5. Protected sockets keep upstream HTTPS traffic outside the VPN.
6. The DNS response is encoded into an IPv4 UDP packet and returned through TUN.

This routing invariant prevents AeroDNS from becoming an accidental full-tunnel VPN.

## DNS transports

`DnsTransport` defines the forwarding contract. Implementations include:

- UDP for ordinary DNS
- TCP fallback for truncated ordinary DNS responses
- HTTPS using OkHttp for DoH
- A hidden DoT transport retained for development but not exposed in the interface

Custom DoH uses an explicit bootstrap IP when configured. Otherwise the endpoint hostname is resolved through a non-VPN Android network, and HTTPS sockets bind to that network before VPN protection and connection. This avoids discovery through the virtual resolver; the underlying network's DNS can see the endpoint hostname.

## State and persistence

`PreferencesDataStore` stores resolver selection, custom resolver profiles and protocol preference. Repositories expose state through Kotlin `Flow`; `DashboardViewModel` converts it into UI state.

The VPN service owns the actual active configuration and sampled DNS health. Establishment displays “Checking DNS…”; only a valid response through the active DNS path produces “Connected”. Dashboard, notification and tile consume the same service state.

A separate, versioned `VpnRecoveryStore` durably records the intended active configuration in SharedPreferences. Sticky/system starts restore it with a new request identity. Explicit disconnect, revocation and startup/forwarding failure clear it; the next selected profile does not overwrite it.

`VpnRecoveryService` is a small, unbound started service in the same process as the foreground VPN. Android can lose a VPN service's sticky restart bookkeeping when interface removal unbinds an already-dead process. The companion keeps a separate restart record and asks the VPN service to reread current recovery intent. It adds no timer, worker, network request, separate process or notification. The foreground VPN starts it after establishment and stops it on intentional teardown, including a temporary speed-test pause. Queued companion starts reread the store and the current service event. An already-active configuration needs no restore command; a stopped/failed event prevents delayed work from undoing a pause in the same process. A fresh process has no old event, so it restores durable intent.

The companion uses normal Android service scheduling. Process recovery is best effort, and force-stop must remain effective. Always-on boot behavior, OEM restrictions and upgrade acceptance require separate device checks; the helper is not a boot receiver.

## Current limitations

- The explicit TUN packet loop handles IPv4 UDP DNS.
- TCP DNS inside TUN is not implemented.
- IPv6 DNS traffic is not parsed by the explicit packet loop.
- DoT exists as transport code but remains hidden until independently stabilized.
- AeroDNS does not tunnel general application traffic.

Changes to these constraints should include routing, packet-codec, and device regression tests.
