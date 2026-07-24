package com.vmcsoft.aerodns.domain.repository

import com.vmcsoft.aerodns.domain.model.NetworkIpStack

/**
 * Provides the current network's IP stack (IPv4-only, IPv6-only, or dual-stack).
 * Used to choose and order DNS addresses before activating VPN or running speed test.
 */
interface NetworkCapabilitiesRepository {

    /**
     * Detects whether the active network has IPv4 only, IPv6 only, or both.
     * Returns [NetworkIpStack.DUAL_STACK] when unknown or no network.
     */
    suspend fun getActiveNetworkIpStack(): NetworkIpStack
}
