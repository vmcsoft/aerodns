package com.vmcsoft.aerodns.validation

import com.vmcsoft.aerodns.domain.repository.VpnRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Allows device assertions against the same singleton used by the dashboard and tile. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ValidationRepositories {
    fun vpnRepository(): VpnRepository
}
