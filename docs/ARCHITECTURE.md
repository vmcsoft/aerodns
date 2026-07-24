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

Custom DoH bootstrap addresses are resolved locally so nonstandard resolver hostnames can be reached without recursively depending on the active DNS path.

## State and persistence

`PreferencesDataStore` stores resolver selection, custom resolver profiles, protocol preference, and reconnect state. Repositories expose state through Kotlin `Flow`; `DashboardViewModel` converts it into UI state.

The VPN service is the authority for connection state. The interface does not report a successful connection until service establishment completes.

## Current limitations

- The explicit TUN packet loop handles IPv4 UDP DNS.
- TCP DNS inside TUN is not implemented.
- IPv6 DNS traffic is not parsed by the explicit packet loop.
- DoT exists as transport code but remains hidden until independently stabilized.
- AeroDNS does not tunnel general application traffic.

Changes to these constraints should include routing, packet-codec, and device regression tests.
