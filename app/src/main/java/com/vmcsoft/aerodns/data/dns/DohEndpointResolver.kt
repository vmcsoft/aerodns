package com.vmcsoft.aerodns.data.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/** Addresses of the HTTPS endpoint, with the network used to discover them, if any. */
data class DohEndpoint(
    val hostname: String,
    val addresses: List<InetAddress>,
    val network: Network? = null
)

@Singleton
class DohEndpointResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun resolve(hostname: String, endpointAddresses: List<String>): DohEndpoint {
        if (endpointAddresses.isNotEmpty()) {
            // Built-in provider addresses and explicit custom bootstrap IPs are endpoint
            // overrides. They must not cause a hostname lookup through the active VPN.
            val addresses = endpointAddresses.map { address ->
                require(address.contains(':') || address.matches(Regex("[0-9.]+"))) {
                    "DoH endpoint override must be an IP address"
                }
                InetAddress.getByName(address)
            }
            return DohEndpoint(hostname, addresses)
        }

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = selectUnderlyingNetwork(manager)
            ?: throw UnknownHostException("No underlying network available for DoH endpoint discovery")
        // Network.getAllByName bypasses the process/default VPN resolver. Resolve on
        // every request; Android owns its per-network DNS cache, not a permanent app cache.
        val addresses = network.getAllByName(hostname).toList()
        if (addresses.isEmpty()) throw UnknownHostException("No addresses for DoH endpoint")
        return DohEndpoint(hostname, addresses, network)
    }

    private fun selectUnderlyingNetwork(manager: ConnectivityManager): Network? {
        fun capabilities(network: Network): NetworkCapabilities? {
            return manager.getNetworkCapabilities(network)?.takeIf {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }

        manager.activeNetwork?.let { active ->
            if (capabilities(active) != null) return active
        }

        // Public APIs do not expose a VPN's default underlay on all supported versions.
        // Prefer a validated physical network, then an unvalidated one (e.g. local DNS).
        // Bind HTTPS to the same selected network so discovery and connection agree.
        @Suppress("DEPRECATION")
        val candidates = manager.allNetworks.mapNotNull { network ->
            capabilities(network)?.let { network to it }
        }
        return candidates.firstOrNull {
            it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.first ?: candidates.firstOrNull()?.first
    }
}
