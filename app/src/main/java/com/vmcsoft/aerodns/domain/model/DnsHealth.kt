package com.vmcsoft.aerodns.domain.model

/** A sampled DNS-path check, separate from whether the VPN interface exists. */
sealed class DnsHealth {
    object Checking : DnsHealth()
    data class Healthy(val checkedAtMillis: Long, val latencyMs: Long) : DnsHealth()
    data class Unhealthy(val checkedAtMillis: Long) : DnsHealth()
}

val DnsHealth.statusText: String get() = when (this) {
    DnsHealth.Checking -> "Checking DNS…"
    is DnsHealth.Healthy -> "Connected"
    is DnsHealth.Unhealthy -> "DNS check failed"
}

val DnsProtocol.label: String get() = when (this) {
    DnsProtocol.STANDARD -> "Standard"
    DnsProtocol.DOH -> "DoH"
    DnsProtocol.DOT -> "DoT"
}

fun DnsConnectionConfig.statusDescription(health: DnsHealth, policy: VpnControlPolicy = VpnControlPolicy()): String =
    "${policy.statusText(health)} · $displayName · ${protocol.label}"
