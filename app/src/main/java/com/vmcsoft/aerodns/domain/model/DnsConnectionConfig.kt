package com.vmcsoft.aerodns.domain.model

import java.io.Serializable

/**
 * Runtime DNS configuration passed to the VPN layer.
 *
 * This is intentionally narrower than DnsServer: it contains only the values
 * needed to start a connection for one selected protocol.
 */
data class DnsConnectionConfig(
    val serverId: String,
    val displayName: String,
    val protocol: DnsProtocol,
    val upstreamAddresses: List<String>,
    val dohUrl: String? = null,
    val customBootstrapIp: String? = null,
    val allowUntrustedCertificates: Boolean = false,
    val dotHostname: String? = null,
    val connectionRequestId: String = "",
    val enableExperimentalPacketLoop: Boolean = false
) : Serializable

private val DEFAULT_DUAL_STACK_IPV6_FALLBACK = listOf(
    "2606:4700:4700::1111",
    "2606:4700:4700::1001"
)

private val DEFAULT_DOH_BOOTSTRAP_ADDRESSES = listOf(
    "1.1.1.1",
    "1.0.0.1",
    "2606:4700:4700::1111",
    "2606:4700:4700::1001"
)

fun buildDnsConnectionConfig(
    server: DnsServer,
    stack: NetworkIpStack,
    protocol: DnsProtocol = DnsProtocol.STANDARD,
    dualStackIpv6Fallback: List<String> = DEFAULT_DUAL_STACK_IPV6_FALLBACK,
    dohBootstrapFallback: List<String> = DEFAULT_DOH_BOOTSTRAP_ADDRESSES
): Result<DnsConnectionConfig> {
    val orderedAddresses = buildOrderedAddresses(
        server = server,
        stack = stack,
        protocol = protocol,
        dualStackIpv6Fallback = dualStackIpv6Fallback,
        dohBootstrapFallback = dohBootstrapFallback
    )

    return when (protocol) {
        DnsProtocol.STANDARD -> {
            if (orderedAddresses.isEmpty()) {
                Result.failure(IllegalArgumentException("No compatible DNS addresses for current network"))
            } else {
                Result.success(
                    DnsConnectionConfig(
                        serverId = server.id,
                        displayName = server.name,
                        protocol = protocol,
                        upstreamAddresses = orderedAddresses
                    )
                )
            }
        }
        DnsProtocol.DOH -> {
            val dohUrl = server.dohUrl?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalArgumentException("DNS-over-HTTPS is not available for ${server.name}"))
            Result.success(
                DnsConnectionConfig(
                    serverId = server.id,
                    displayName = server.name,
                    protocol = protocol,
                    upstreamAddresses = orderedAddresses,
                    dohUrl = dohUrl,
                    customBootstrapIp = server.customBootstrapIp?.takeIf { server.isCustom && it.isNotBlank() },
                    allowUntrustedCertificates = server.isCustom && server.allowUntrustedCertificates
                )
            )
        }
        DnsProtocol.DOT -> {
            val dotHostname = server.dotHostname?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalArgumentException("DNS-over-TLS is not available for ${server.name}"))
            Result.success(
                DnsConnectionConfig(
                    serverId = server.id,
                    displayName = server.name,
                    protocol = protocol,
                    upstreamAddresses = orderedAddresses,
                    dotHostname = dotHostname
                )
            )
        }
    }
}

private fun buildOrderedAddresses(
    server: DnsServer,
    stack: NetworkIpStack,
    protocol: DnsProtocol,
    dualStackIpv6Fallback: List<String>,
    dohBootstrapFallback: List<String>
): List<String> {
    val orderedAddresses = getOrderedDnsAddresses(server, stack)
    if (protocol == DnsProtocol.DOH) {
        return orderedAddresses.ifEmpty { dohBootstrapFallback }
    }
    if (protocol == DnsProtocol.DOT) {
        return orderedAddresses
    }

    return if (
        stack == NetworkIpStack.DUAL_STACK &&
        orderedAddresses.isNotEmpty() &&
        orderedAddresses.none { it.isIpv6Address() }
    ) {
        orderedAddresses + dualStackIpv6Fallback
    } else {
        orderedAddresses
    }
}

private fun String.isIpv6Address(): Boolean = contains(':')
