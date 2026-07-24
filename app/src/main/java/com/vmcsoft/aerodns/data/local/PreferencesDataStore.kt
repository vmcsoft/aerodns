package com.vmcsoft.aerodns.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aerodns_preferences")

@Singleton
class PreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val SELECTED_DNS_ID = stringPreferencesKey("selected_dns_id")
        val SELECTED_DNS_PROTOCOL = stringPreferencesKey("selected_dns_protocol")
        val LAST_CONNECTED_DNS_ID = stringPreferencesKey("last_connected_dns_id")
        val CUSTOM_DNS_JSON = stringPreferencesKey("custom_dns_json")
        val EXPERIMENTAL_PACKET_LOOP_ENABLED = booleanPreferencesKey("experimental_packet_loop_enabled")
    }

    val selectedDnsId: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[SELECTED_DNS_ID] }

    suspend fun setSelectedDnsId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_DNS_ID] = id
        }
    }

    val lastConnectedDnsId: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[LAST_CONNECTED_DNS_ID] }

    suspend fun setLastConnectedDnsId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_CONNECTED_DNS_ID] = id
        }
    }

    suspend fun getSelectedDnsId(): String? {
        return selectedDnsId.first()
    }

    val selectedDnsProtocol: Flow<DnsProtocol> = context.dataStore.data
        .map { preferences ->
            preferences[SELECTED_DNS_PROTOCOL]?.let { protocolName ->
                runCatching { DnsProtocol.valueOf(protocolName) }.getOrNull()
            } ?: DnsProtocol.STANDARD
        }

    suspend fun setSelectedDnsProtocol(protocol: DnsProtocol) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_DNS_PROTOCOL] = protocol.name
        }
    }

    suspend fun getSelectedDnsProtocol(): DnsProtocol {
        return selectedDnsProtocol.first()
    }

    suspend fun getLastConnectedDnsId(): String? {
        return lastConnectedDnsId.first()
    }

    suspend fun getCustomDnsJson(): String? {
        return context.dataStore.data.first()[CUSTOM_DNS_JSON]
    }

    suspend fun setCustomDnsJson(json: String) {
        context.dataStore.edit { preferences ->
            preferences[CUSTOM_DNS_JSON] = json
        }
    }

    val experimentalPacketLoopEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[EXPERIMENTAL_PACKET_LOOP_ENABLED] ?: false }

    suspend fun setExperimentalPacketLoopEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[EXPERIMENTAL_PACKET_LOOP_ENABLED] = enabled
        }
    }

    suspend fun isExperimentalPacketLoopEnabled(): Boolean {
        return experimentalPacketLoopEnabled.first()
    }
}
