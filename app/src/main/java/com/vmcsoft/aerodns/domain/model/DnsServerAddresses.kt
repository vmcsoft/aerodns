package com.vmcsoft.aerodns.domain.model

/**
 * Returns DNS server addresses in the order they should be applied to the VPN,
 * based on current network type. Prefers IPv6 when the network supports it.
 */
fun getOrderedDnsAddresses(server: DnsServer, stack: NetworkIpStack): List<String> {
    val primary = server.primary.takeIf { it.isNotBlank() }
    val secondary = server.secondary
    val ipv6Primary = server.ipv6Primary
    val ipv6Secondary = server.ipv6Secondary

    return when (stack) {
        NetworkIpStack.IPv4_ONLY -> {
            listOfNotNull(primary, secondary).ifEmpty {
                listOfNotNull(ipv6Primary, ipv6Secondary)
            }
        }
        NetworkIpStack.IPv6_ONLY -> {
            listOfNotNull(ipv6Primary, ipv6Secondary).ifEmpty {
                listOfNotNull(primary, secondary)
            }
        }
        NetworkIpStack.DUAL_STACK -> {
            // Prefer IPv6 first when dual-stack
            listOfNotNull(ipv6Primary, ipv6Secondary, primary, secondary)
        }
    }
}

/**
 * Returns the single address to use for ping/speed test/validation,
 * based on current network type. Prefers IPv6 when available.
 */
fun getPreferredPingAddress(server: DnsServer, stack: NetworkIpStack): String? {
    val primary = server.primary.takeIf { it.isNotBlank() }
    return when (stack) {
        NetworkIpStack.IPv4_ONLY -> primary ?: server.ipv6Primary
        NetworkIpStack.IPv6_ONLY -> server.ipv6Primary ?: primary
        NetworkIpStack.DUAL_STACK -> server.ipv6Primary ?: primary
    }
}
