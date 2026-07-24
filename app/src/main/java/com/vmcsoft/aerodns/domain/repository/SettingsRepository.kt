package com.vmcsoft.aerodns.domain.repository

import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.DnsProtocol

interface SettingsRepository {
    suspend fun getSelectedDnsId(): String?
    suspend fun setSelectedDnsId(id: String)
    suspend fun getSelectedDnsProtocol(): DnsProtocol
    suspend fun setSelectedDnsProtocol(protocol: DnsProtocol)
    suspend fun getLastConnectedDnsId(): String?
    suspend fun setLastConnectedDnsId(id: String)
    suspend fun getLastConnectedServer(): DnsServer?
    suspend fun isExperimentalPacketLoopEnabled(): Boolean
    suspend fun setExperimentalPacketLoopEnabled(enabled: Boolean)
}
