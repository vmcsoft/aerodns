package com.vmcsoft.aerodns.domain.repository

import com.vmcsoft.aerodns.domain.model.DnsServer

interface DnsRepository {
    fun getPreDefinedServers(): List<DnsServer>
    suspend fun getCustomServers(): List<DnsServer>
    suspend fun saveCustomServer(server: DnsServer)
    suspend fun updateCustomServer(server: DnsServer)
    suspend fun deleteCustomServer(id: String)
}
