package com.vmcsoft.aerodns.di

import com.vmcsoft.aerodns.data.repository.NetworkCapabilitiesRepositoryImpl
import com.vmcsoft.aerodns.data.repository.SettingsRepositoryImpl
import com.vmcsoft.aerodns.data.repository.VpnRepositoryImpl
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import com.vmcsoft.aerodns.domain.repository.SettingsRepository
import com.vmcsoft.aerodns.domain.repository.VpnRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindVpnRepository(
        vpnRepositoryImpl: VpnRepositoryImpl
    ): VpnRepository

    @Binds
    @Singleton
    abstract fun bindDnsRepository(
        dnsRepositoryImpl: com.vmcsoft.aerodns.data.repository.DnsRepositoryImpl
    ): com.vmcsoft.aerodns.domain.repository.DnsRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        settingsRepositoryImpl: SettingsRepositoryImpl
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindNetworkCapabilitiesRepository(
        impl: NetworkCapabilitiesRepositoryImpl
    ): NetworkCapabilitiesRepository
}
