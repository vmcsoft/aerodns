package com.vmcsoft.aerodns.domain.model

enum class DnsProtocol {
    STANDARD,  // Traditional DNS (UDP/TCP port 53)
    DOH,       // DNS-over-HTTPS
    DOT        // DNS-over-TLS
}

fun DnsProtocol.requiresPacketLoop(): Boolean = this != DnsProtocol.STANDARD

fun DnsProtocol.isUserSelectable(): Boolean {
    return when (this) {
        DnsProtocol.STANDARD,
        DnsProtocol.DOH -> true
        DnsProtocol.DOT -> false
    }
}

fun DnsServer.selectableProtocols(): List<DnsProtocol> {
    return supportedProtocols
        .filter { it.isUserSelectable() }
        .ifEmpty { listOf(DnsProtocol.STANDARD) }
}

fun DnsServer.resolveSelectedProtocol(protocol: DnsProtocol): DnsProtocol {
    val selectableProtocols = selectableProtocols()
    return if (protocol in selectableProtocols) {
        protocol
    } else {
        selectableProtocols.first()
    }
}
