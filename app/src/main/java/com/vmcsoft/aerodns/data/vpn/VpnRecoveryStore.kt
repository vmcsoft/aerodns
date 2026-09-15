package com.vmcsoft.aerodns.data.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol

/** Only the intended active connection, separate from the next profile selected in the UI. */
internal class VpnRecoveryStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("vpn_recovery", Context.MODE_PRIVATE))

    fun load(): DnsConnectionConfig? = try {
        if (preferences.getInt("schema", 0) != 1) null else DnsConnectionConfig(
            serverId = requireNotNull(preferences.getString("serverId", null)),
            displayName = requireNotNull(preferences.getString("displayName", null)),
            protocol = DnsProtocol.valueOf(requireNotNull(preferences.getString("protocol", null))),
            upstreamAddresses = preferences.getString("addresses", "").orEmpty().split('\n').filter { it.isNotEmpty() },
            dohUrl = preferences.getString("dohUrl", null),
            customBootstrapIp = preferences.getString("bootstrapIp", null),
            allowUntrustedCertificates = preferences.getBoolean("untrustedCertificates", false),
            dotHostname = preferences.getString("dotHostname", null),
            connectionRequestId = preferences.getString("requestId", "").orEmpty(),
            enableExperimentalPacketLoop = preferences.getBoolean("packetLoop", false)
        ).also(::validateRecoveryConfig)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    // These tiny, infrequent records are committed after foreground promotion. A successful
    // return means restart/disconnect intent is durable before changing the live interface.
    @SuppressLint("UseKtx") // KTX edit discards the commit result needed to detect persistence failure.
    fun save(config: DnsConnectionConfig): Boolean {
        validateRecoveryConfig(config)
        return preferences.edit().clear()
            .putInt("schema", 1)
            .putString("serverId", config.serverId)
            .putString("displayName", config.displayName)
            .putString("protocol", config.protocol.name)
            .putString("addresses", config.upstreamAddresses.joinToString("\n"))
            .putString("dohUrl", config.dohUrl)
            .putString("bootstrapIp", config.customBootstrapIp)
            .putBoolean("untrustedCertificates", config.allowUntrustedCertificates)
            .putString("dotHostname", config.dotHostname)
            .putString("requestId", config.connectionRequestId)
            .putBoolean("packetLoop", config.enableExperimentalPacketLoop)
            .commit()
    }

    @SuppressLint("UseKtx") // Keep the persistence result visible to the service.
    fun clear(): Boolean = preferences.edit().clear().commit()
}

internal fun validateRecoveryConfig(config: DnsConnectionConfig) {
    require(config.serverId.isNotBlank() && config.displayName.isNotBlank()) { "Missing DNS profile identity" }
    require(config.upstreamAddresses.none { it.isBlank() || '\n' in it }) { "Invalid DNS addresses" }
    when (config.protocol) {
        DnsProtocol.STANDARD -> require(config.upstreamAddresses.isNotEmpty()) { "Missing DNS addresses" }
        DnsProtocol.DOH -> {
            require(config.dohUrl?.startsWith("https://", ignoreCase = true) == true) { "Missing HTTPS endpoint" }
            require(config.enableExperimentalPacketLoop) { "DoH requires DNS forwarding" }
        }
        DnsProtocol.DOT -> {
            require(!config.dotHostname.isNullOrBlank() && config.upstreamAddresses.isNotEmpty()) { "Missing DoT endpoint" }
            require(config.enableExperimentalPacketLoop) { "DoT requires DNS forwarding" }
        }
    }
}
