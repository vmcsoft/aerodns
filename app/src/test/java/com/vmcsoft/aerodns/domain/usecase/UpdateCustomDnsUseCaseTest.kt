package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCustomDnsUseCaseTest {
    private val mixed = DnsServer(
        id = "mixed",
        name = "Dual-stack resolver",
        primary = "192.0.2.53",
        secondary = "198.51.100.53",
        ipv6Primary = "2001:db8::53",
        ipv6Secondary = "2001:db8:1::53",
        dohUrl = "https://resolver.example/dns-query",
        customBootstrapIp = "203.0.113.53",
        allowUntrustedCertificates = true,
        supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH),
        isCustom = true
    )
    private val repository = mockk<DnsRepository>()
    private val update = UpdateCustomDnsUseCase(repository)

    @Test fun `renaming from DoH retains all saved addresses and policies`() = runTest {
        coEvery { repository.getCustomServers() } returns listOf(mixed)
        coJustRun { repository.updateCustomServer(any()) }

        val result = update(
            mixed.id, "Renamed", primary = "", dohUrl = mixed.dohUrl,
            protocol = DnsProtocol.DOH, customBootstrapIp = mixed.customBootstrapIp,
            allowUntrustedCertificates = mixed.allowUntrustedCertificates
        )

        val expected = mixed.copy(name = "Renamed")
        assertEquals(expected, result.getOrThrow())
        coVerify(exactly = 1) { repository.updateCustomServer(expected) }
    }

    @Test fun `an explicit Standard edit preserves the other family and DoH settings`() = runTest {
        coEvery { repository.getCustomServers() } returns listOf(mixed)
        coJustRun { repository.updateCustomServer(any()) }

        val result = update(mixed.id, mixed.name, "203.0.113.41", secondary = null)

        val expected = mixed.copy(primary = "203.0.113.41", secondary = null)
        assertEquals(expected, result.getOrThrow())
        coVerify(exactly = 1) { repository.updateCustomServer(expected) }
    }

    @Test fun `changing primary family cannot overwrite the hidden address pair`() = runTest {
        coEvery { repository.getCustomServers() } returns listOf(mixed)

        assertTrue(update(mixed.id, mixed.name, "2001:db8::41").isFailure)
        coVerify(exactly = 0) { repository.updateCustomServer(any()) }
    }

    @Test fun `a stale edit cannot recreate a deleted profile`() = runTest {
        coEvery { repository.getCustomServers() } returns emptyList()

        assertTrue(update(mixed.id, mixed.name, mixed.primary).isFailure)
        coVerify(exactly = 0) { repository.updateCustomServer(any()) }
    }
}
