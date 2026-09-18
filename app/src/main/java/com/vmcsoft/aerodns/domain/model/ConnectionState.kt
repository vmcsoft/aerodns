package com.vmcsoft.aerodns.domain.model

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(
        val server: DnsServer,
        val connectedAtMillis: Long,
        val currentPingMs: Long? = null,
        val activeConfig: DnsConnectionConfig? = null,
        val dnsHealth: DnsHealth = DnsHealth.Checking,
        val controlPolicy: VpnControlPolicy = VpnControlPolicy()
    ) : ConnectionState()
    object Disconnecting : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
