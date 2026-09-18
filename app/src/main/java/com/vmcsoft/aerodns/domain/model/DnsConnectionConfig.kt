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

fun buildDnsConnectionConfig(
    server: DnsServer,
    stack: NetworkIpStack,
    protocol: DnsProtocol = DnsProtocol.STANDARD
): Result<DnsConnectionConfig> {
    // These are addresses of the selected resolver, never fallback DNS providers.
    // An empty DoH list means its endpoint must be discovered on an underlying network.
    val orderedAddresses = getOrderedDnsAddresses(server, stack)

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
