package com.vmcsoft.aerodns.data.repository

import androidx.datastore.preferences.core.edit
import com.vmcsoft.aerodns.data.local.DnsProviderData
import com.vmcsoft.aerodns.data.local.PreferencesDataStore
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DnsRepositoryImpl @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore
) : DnsRepository {

    override fun getPreDefinedServers(): List<DnsServer> {
        return DnsProviderData.providers
    }

    override suspend fun getCustomServers(): List<DnsServer> {
        val customDnsJson = preferencesDataStore.getCustomDnsJson()

        if (customDnsJson.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val jsonArray = JSONArray(customDnsJson)
            val servers = mutableListOf<DnsServer>()

            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val primary = json.optString("primary", "")
                servers.add(
                    DnsServer(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        primary = primary,
                        secondary = json.optString("secondary").takeIf { it.isNotEmpty() },
                        ipv6Primary = json.optString("ipv6Primary").takeIf { it.isNotEmpty() },
                        ipv6Secondary = json.optString("ipv6Secondary").takeIf { it.isNotEmpty() },
                        dohUrl = json.optString("dohUrl").takeIf { it.isNotEmpty() },
                        customBootstrapIp = json.optString("customBootstrapIp").takeIf { it.isNotEmpty() },
                        allowUntrustedCertificates = json.optBoolean("allowUntrustedCertificates", false),
                        supportedProtocols = supportedProtocolsFor(json.optString("dohUrl"), primary),
                        isCustom = true
                    )
                )
            }
            servers
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun saveCustomServer(server: DnsServer) {
        val currentServers = getCustomServers().toMutableList()
        currentServers.add(server)

        val jsonArray = JSONArray()
        currentServers.forEach { srv ->
            val json = JSONObject().apply {
                put("id", srv.id)
                put("name", srv.name)
                put("primary", srv.primary)
                srv.secondary?.let { put("secondary", it) }
                srv.ipv6Primary?.let { put("ipv6Primary", it) }
                srv.ipv6Secondary?.let { put("ipv6Secondary", it) }
                srv.dohUrl?.let { put("dohUrl", it) }
                srv.customBootstrapIp?.let { put("customBootstrapIp", it) }
                put("allowUntrustedCertificates", srv.allowUntrustedCertificates)
            }
            jsonArray.put(json)
        }

        preferencesDataStore.setCustomDnsJson(jsonArray.toString())
    }

    override suspend fun updateCustomServer(server: DnsServer) {
        val currentServers = getCustomServers().toMutableList()
        val index = currentServers.indexOfFirst { it.id == server.id }

        if (index != -1) {
            currentServers[index] = server
        } else {
            // If not found, add it (fallback behavior)
            currentServers.add(server)
        }

        val jsonArray = JSONArray()
        currentServers.forEach { srv ->
            val json = JSONObject().apply {
                put("id", srv.id)
                put("name", srv.name)
                put("primary", srv.primary)
                srv.secondary?.let { put("secondary", it) }
                srv.ipv6Primary?.let { put("ipv6Primary", it) }
                srv.ipv6Secondary?.let { put("ipv6Secondary", it) }
                srv.dohUrl?.let { put("dohUrl", it) }
                srv.customBootstrapIp?.let { put("customBootstrapIp", it) }
                put("allowUntrustedCertificates", srv.allowUntrustedCertificates)
            }
            jsonArray.put(json)
        }

        preferencesDataStore.setCustomDnsJson(jsonArray.toString())
    }

    override suspend fun deleteCustomServer(id: String) {
        val currentServers = getCustomServers().filter { it.id != id }

        val jsonArray = JSONArray()
        currentServers.forEach { srv ->
            val json = JSONObject().apply {
                put("id", srv.id)
                put("name", srv.name)
                put("primary", srv.primary)
                srv.secondary?.let { put("secondary", it) }
                srv.ipv6Primary?.let { put("ipv6Primary", it) }
                srv.ipv6Secondary?.let { put("ipv6Secondary", it) }
                srv.dohUrl?.let { put("dohUrl", it) }
                srv.customBootstrapIp?.let { put("customBootstrapIp", it) }
                put("allowUntrustedCertificates", srv.allowUntrustedCertificates)
            }
            jsonArray.put(json)
        }

        preferencesDataStore.setCustomDnsJson(jsonArray.toString())
    }

    private fun supportedProtocolsFor(dohUrl: String?, primary: String): List<DnsProtocol> {
        return when {
            dohUrl.isNullOrBlank() -> listOf(DnsProtocol.STANDARD)
            primary.isBlank() -> listOf(DnsProtocol.DOH)
            else -> listOf(DnsProtocol.STANDARD, DnsProtocol.DOH)
        }
    }
}
