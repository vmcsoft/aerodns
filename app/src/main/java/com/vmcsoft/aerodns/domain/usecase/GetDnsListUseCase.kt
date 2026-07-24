package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import javax.inject.Inject

class GetDnsListUseCase @Inject constructor(
    private val dnsRepository: DnsRepository
) {
    suspend operator fun invoke(): List<DnsServer> {
        val predefined = dnsRepository.getPreDefinedServers()
        val custom = dnsRepository.getCustomServers()
        return predefined + custom
    }
}
