package com.vmcsoft.aerodns.domain.repository

import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsServer
import kotlinx.coroutines.flow.StateFlow

interface VpnRepository {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(server: DnsServer)
    suspend fun disconnect()
    fun isVpnPrepared(): Boolean
}
