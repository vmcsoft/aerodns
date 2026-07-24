package com.vmcsoft.aerodns.domain.model

/**
 * Represents the IP capabilities of the current network.
 * Used to choose which DNS addresses to use and in what order.
 */
enum class NetworkIpStack {
    /** Network has IPv4 only */
    IPv4_ONLY,

    /** Network has IPv6 only */
    IPv6_ONLY,

    /** Network has both IPv4 and IPv6 (dual-stack) */
    DUAL_STACK
}
