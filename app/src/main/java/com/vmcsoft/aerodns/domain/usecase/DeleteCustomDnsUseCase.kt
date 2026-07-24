package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.domain.repository.DnsRepository
import javax.inject.Inject

class DeleteCustomDnsUseCase @Inject constructor(
    private val dnsRepository: DnsRepository
) {
    suspend operator fun invoke(serverId: String): Result<Unit> {
        return try {
            dnsRepository.deleteCustomServer(serverId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
