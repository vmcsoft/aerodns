package com.vmcsoft.aerodns.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
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

    @Volatile
    private var cachedStack: NetworkIpStack? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    override suspend fun getActiveNetworkIpStack(): NetworkIpStack = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedStack?.let { stack ->
            if ((now - cachedAtMs) < CACHE_TTL_MS) return@withContext stack
        }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkToUse = resolveNetworkForStackDetection(connectivityManager)
        val linkProperties = networkToUse?.let { connectivityManager.getLinkProperties(it) }
        if (linkProperties == null) {
            Log.d(TAG, "No link properties, defaulting to DUAL_STACK")
            return@withContext cacheAndReturn(NetworkIpStack.DUAL_STACK)
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
        cacheAndReturn(result)
    }

    private fun cacheAndReturn(stack: NetworkIpStack): NetworkIpStack {
        cachedStack = stack
        cachedAtMs = System.currentTimeMillis()
        return stack
    }

    /**
     * Returns the network whose LinkProperties should be used to detect IPv4/IPv6/dual-stack.
     * When the default active network is our VPN, it only has an IPv4 address (e.g. 10.0.0.2),
     * so we use the underlying physical network (WiFi/cellular) instead to get the real stack.
     */
    private fun resolveNetworkForStackDetection(connectivityManager: ConnectivityManager): Network? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val activeCaps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return activeNetwork

        // If the active network is not a VPN, use it (real WiFi/cellular)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return activeNetwork
            }
        }

        // Active network is VPN (e.g. our DNS VPN). Use underlying network for stack detection.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (network in connectivityManager.allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue
                return network
            }
        }

        return activeNetwork
    }

    companion object {
        private const val TAG = "NetworkCapabilities"
        /** Cache TTL so we don't hit ConnectivityManager every 5s from ping monitoring. */
        private const val CACHE_TTL_MS = 60_000L
    }
}
