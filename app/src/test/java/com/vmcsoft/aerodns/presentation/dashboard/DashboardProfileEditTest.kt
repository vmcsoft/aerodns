package com.vmcsoft.aerodns.presentation.dashboard

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.vmcsoft.aerodns.data.local.PreferencesDataStore
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import com.vmcsoft.aerodns.domain.repository.VpnRepository
import com.vmcsoft.aerodns.domain.usecase.GetDnsListUseCase
import com.vmcsoft.aerodns.domain.usecase.UpdateCustomDnsUseCase
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardProfileEditTest {
    @Test fun `Save and Connect preserves DoH settings and connects using the edited Standard tab`() =
        runTest {
            val profile = DnsServer(
                id = "mixed", name = "Resolver", primary = "192.0.2.53",
                ipv6Primary = "2001:db8::53", dohUrl = "https://resolver.example/dns-query",
                customBootstrapIp = "203.0.113.53", allowUntrustedCertificates = true,
                supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH),
                isCustom = true
            )
            val expected = profile.copy(name = "Renamed")
            val vpn = mockk<VpnRepository>(relaxed = true)
            every { vpn.connectionState } returns MutableStateFlow(ConnectionState.Disconnected)
            val preferences = mockk<PreferencesDataStore>(relaxed = true)
            coEvery { preferences.getSelectedDnsId() } returns profile.id
            coEvery { preferences.getSelectedDnsProtocol() } returns DnsProtocol.DOH
            val dns = mockk<DnsRepository>(relaxed = true)
            coEvery { dns.getCustomServers() } returns listOf(profile)
            val list = mockk<GetDnsListUseCase>()
            coEvery { list() } returns listOf(profile)

            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            val viewModel = DashboardViewModel(
                vpn, list, mockk(), mockk(), UpdateCustomDnsUseCase(dns), mockk(), preferences
            )
            try {
                advanceUntilIdle()
                viewModel.onShowCustomDnsDialog(profile)
                viewModel.onSaveAndConnectCustomDns(
                    expected.name, profile.primary, null, null, DnsProtocol.STANDARD, null, false
                )
                advanceUntilIdle()
                coVerifyOrder {
                    dns.updateCustomServer(expected)
                    preferences.setSelectedDnsProtocol(DnsProtocol.STANDARD)
                    vpn.connect(expected)
                }
            } finally {
                viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
                unmockkStatic(Log::class)
            }
        }
}
