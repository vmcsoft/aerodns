package com.vmcsoft.aerodns.data.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class DnsVpnRoute(
    val address: String,
    val prefixLength: Int
)

object DnsVpnRoutePlanner {
    fun planRoutes(addresses: List<String>): List<DnsVpnRoute> {
        return addresses.mapNotNull { address ->
            runCatching {
                val inetAddress = InetAddress.getByName(address)
                when (inetAddress) {
                    is Inet4Address -> DnsVpnRoute(address, IPV4_HOST_PREFIX)
                    is Inet6Address -> DnsVpnRoute(address, IPV6_HOST_PREFIX)
                    else -> null
                }
            }.getOrNull()
        }
    }

    fun planIpv4Routes(addresses: List<String>): List<DnsVpnRoute> {
        return addresses.mapNotNull { address ->
            runCatching {
                val inetAddress = InetAddress.getByName(address)
                when (inetAddress) {
                    is Inet4Address -> DnsVpnRoute(address, IPV4_HOST_PREFIX)
                    else -> null
                }
            }.getOrNull()
        }
    }

    private const val IPV4_HOST_PREFIX = 32
    private const val IPV6_HOST_PREFIX = 128
}
