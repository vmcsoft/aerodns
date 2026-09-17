package com.vmcsoft.aerodns.data.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

sealed class NetworkState {
    data class Available(val networks: Set<Network>) : NetworkState()
    object Lost : NetworkState()
}

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    val networkState: Flow<NetworkState> = callbackFlow {
        // Callback membership already means INTERNET + VALIDATED + NOT_VPN.
        // Capability notifications (including those immediately following onAvailable)
        // do not imply another physical-network arrival.
        val networks = linkedSetOf<Network>()
        fun publish() {
            trySend(if (networks.isEmpty()) NetworkState.Lost else NetworkState.Available(networks.toSet()))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Physical network available: $network")
                if (networks.add(network)) publish()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // Still satisfies the request. Validation loss/recovery is delivered as
                // onLost/onAvailable; bandwidth or metering changes need no VPN restart.
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Physical network lost: $network")
                if (networks.remove(network)) publish()
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        publish()
        // Registration delivers onAvailable for existing matching networks too.
        // Do not seed from activeNetwork: it may be the VPN itself.
        connectivityManager.registerNetworkCallback(networkRequest, callback)

        awaitClose {
            Log.d(TAG, "Unregistering network callback")
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    @Suppress("DEPRECATION")
    fun isNetworkAvailable(): Boolean = connectivityManager.allNetworks.any { network ->
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        capabilities != null &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
