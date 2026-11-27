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
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardProfileEditTest {
    @Test fun `Save and Connect keeps DoH fields when using the Standard tab`() = runTest {
        verifySaveAndConnect(DnsProtocol.STANDARD)
    }

    @Test fun `Save and Connect cannot downgrade DoH while refreshing the list`() = runTest {
        verifySaveAndConnect(DnsProtocol.DOH)
    }

    private suspend fun TestScope.verifySaveAndConnect(protocol: DnsProtocol) {
        val profile = DnsServer(
            id = "mixed", name = "Resolver", primary = "192.0.2.53",
            ipv6Primary = "2001:db8::53", dohUrl = "https://resolver.example/dns-query",
            customBootstrapIp = "203.0.113.53", allowUntrustedCertificates = true,
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH),
            isCustom = true
        )
        val expected = profile.copy(name = "Renamed")
        val previous = DnsServer("old", "Standard only", "198.51.100.53")
        val vpn = mockk<VpnRepository>(relaxed = true)
        every { vpn.connectionState } returns MutableStateFlow(ConnectionState.Disconnected)
        val preferences = mockk<PreferencesDataStore>(relaxed = true)
        var savedId = previous.id
        var savedProtocol = DnsProtocol.STANDARD
        var protocolAtConnect: DnsProtocol? = null
        val protocolWritten = CompletableDeferred<Unit>()
        var firstProtocolRead = true
        coEvery { preferences.getSelectedDnsId() } coAnswers { savedId }
        coEvery { preferences.getSelectedDnsProtocol() } coAnswers {
            if (firstProtocolRead) firstProtocolRead = false else protocolWritten.await()
            savedProtocol
        }
        coEvery { preferences.setSelectedDnsId(profile.id) } coAnswers {
            // Let any reloader capture the previously selected Standard-only resolver.
            yield()
            savedId = profile.id
        }
        coEvery { preferences.setSelectedDnsProtocol(any()) } coAnswers {
            savedProtocol = firstArg()
            protocolWritten.complete(Unit)
            yield()
        }
        coEvery { vpn.connect(expected) } coAnswers { protocolAtConnect = savedProtocol }
        val dns = mockk<DnsRepository>(relaxed = true)
        coEvery { dns.getCustomServers() } returns listOf(profile)
        val list = mockk<GetDnsListUseCase>()
        coEvery { list() } returns listOf(previous, profile)

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
                expected.name,
                if (protocol == DnsProtocol.STANDARD) profile.primary else "",
                null,
                profile.dohUrl.takeIf { protocol == DnsProtocol.DOH },
                protocol,
                profile.customBootstrapIp.takeIf { protocol == DnsProtocol.DOH },
                protocol == DnsProtocol.DOH && profile.allowUntrustedCertificates
            )
            advanceUntilIdle()
            coVerifyOrder {
                dns.updateCustomServer(expected)
                preferences.setSelectedDnsProtocol(protocol)
                vpn.connect(expected)
            }
            coVerify(exactly = 1) { preferences.getSelectedDnsId() }
            assertEquals(profile.id, savedId)
            assertEquals(protocol, savedProtocol)
            assertEquals(protocol, protocolAtConnect)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
            unmockkStatic(Log::class)
        }
    }
}
