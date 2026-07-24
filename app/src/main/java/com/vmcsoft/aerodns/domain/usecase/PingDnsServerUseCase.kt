package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.data.vpn.NetworkPinger
import com.vmcsoft.aerodns.data.vpn.PingResult
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.getPreferredPingAddress
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import javax.inject.Inject

class PingDnsServerUseCase @Inject constructor(
    private val networkPinger: NetworkPinger,
    private val networkCapabilitiesRepository: NetworkCapabilitiesRepository
) {
    suspend operator fun invoke(server: DnsServer): Long? {
        val stack = networkCapabilitiesRepository.getActiveNetworkIpStack()
        val address = getPreferredPingAddress(server, stack)
            ?: server.primary.takeIf { it.isNotBlank() }
            ?: server.ipv6Primary
            ?: return null

        return when (val result = networkPinger.ping(address)) {
            is PingResult.Success -> result.latencyMs
            else -> null
        }
    }
}
