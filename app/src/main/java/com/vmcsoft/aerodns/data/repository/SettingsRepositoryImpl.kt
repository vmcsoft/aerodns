package com.vmcsoft.aerodns.data.repository

import com.vmcsoft.aerodns.data.local.PreferencesDataStore
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import com.vmcsoft.aerodns.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore,
    private val dnsRepository: DnsRepository
) : SettingsRepository {

    override suspend fun getSelectedDnsId(): String? {
        return preferencesDataStore.selectedDnsId.first()
    }

    override suspend fun setSelectedDnsId(id: String) {
        preferencesDataStore.setSelectedDnsId(id)
    }

    override suspend fun getSelectedDnsProtocol(): DnsProtocol {
        return preferencesDataStore.selectedDnsProtocol.first()
    }

    override suspend fun setSelectedDnsProtocol(protocol: DnsProtocol) {
        preferencesDataStore.setSelectedDnsProtocol(protocol)
    }

    override suspend fun getLastConnectedDnsId(): String? {
        return preferencesDataStore.lastConnectedDnsId.first()
    }

    override suspend fun setLastConnectedDnsId(id: String) {
        preferencesDataStore.setLastConnectedDnsId(id)
    }

    override suspend fun getLastConnectedServer(): DnsServer? {
        val lastId = getSelectedDnsId() ?: return null

        // Try to find in predefined servers first
        val predefinedServer = dnsRepository.getPreDefinedServers().find { it.id == lastId }
        if (predefinedServer != null) {
            return predefinedServer
        }

        // Try custom servers
        return dnsRepository.getCustomServers().find { it.id == lastId }
    }

    override suspend fun isExperimentalPacketLoopEnabled(): Boolean {
        return preferencesDataStore.isExperimentalPacketLoopEnabled()
    }

    override suspend fun setExperimentalPacketLoopEnabled(enabled: Boolean) {
        preferencesDataStore.setExperimentalPacketLoopEnabled(enabled)
    }
}
