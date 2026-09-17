package com.vmcsoft.aerodns.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.vmcsoft.aerodns.domain.model.NetworkIpStack
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkCapabilitiesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkCapabilitiesRepository {

    override suspend fun getActiveNetworkIpStack(): NetworkIpStack = withContext(Dispatchers.IO) {
        // Connections and benchmark runs need the current physical LinkProperties.
        // A time-only cache can carry the previous network's family across handover.
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkToUse = resolveNetworkForStackDetection(connectivityManager)
        val linkProperties = networkToUse?.let { connectivityManager.getLinkProperties(it) }
        if (linkProperties == null) {
            Log.d(TAG, "No link properties, defaulting to DUAL_STACK")
            return@withContext NetworkIpStack.DUAL_STACK
        }
        val linkAddresses: List<LinkAddress> = linkProperties.linkAddresses
        val hasIPv4 = linkAddresses.any { it.address is Inet4Address }
        val hasIPv6 = linkAddresses.any { it.address is Inet6Address }
        val result = when {
            hasIPv4 && hasIPv6 -> {
                Log.d(TAG, "Network: DUAL_STACK")
                NetworkIpStack.DUAL_STACK
            }
            hasIPv6 -> {
                Log.d(TAG, "Network: IPv6_ONLY")
                NetworkIpStack.IPv6_ONLY
            }
            hasIPv4 -> {
                Log.d(TAG, "Network: IPv4_ONLY")
                NetworkIpStack.IPv4_ONLY
            }
            else -> {
                Log.d(TAG, "Network: no addresses, defaulting to DUAL_STACK")
                NetworkIpStack.DUAL_STACK
            }
        }
        result
    }

    /** Match endpoint discovery's physical selection; never classify the VPN's TUN address. */
    @Suppress("DEPRECATION")
    private fun resolveNetworkForStackDetection(manager: ConnectivityManager): Network? {
        fun physicalCapabilities(network: Network): NetworkCapabilities? =
            manager.getNetworkCapabilities(network)?.takeIf {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

        manager.activeNetwork?.let { active ->
            if (physicalCapabilities(active) != null) return active
        }
        val candidates = manager.allNetworks.mapNotNull { network ->
            physicalCapabilities(network)?.let { network to it }
        }
        return candidates.firstOrNull {
            it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.first ?: candidates.firstOrNull()?.first
    }

    companion object {
        private const val TAG = "NetworkCapabilities"
    }
}
