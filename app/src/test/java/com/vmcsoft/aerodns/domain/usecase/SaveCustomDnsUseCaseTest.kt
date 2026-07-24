package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveCustomDnsUseCaseTest {

    private val dnsRepository = mockk<DnsRepository>()
    private val useCase = SaveCustomDnsUseCase(dnsRepository)

    @Test
    fun `invalid primary IPv4 returns failure`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase("My DNS", "invalid", null)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `valid primary IPv4 returns success and saves server`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase("My DNS", "192.168.1.1", null)

        assertTrue(result.isSuccess)
        val server = result.getOrNull()
        assertNotNull(server)
        assertTrue(server!!.isCustom)
        assertEquals("My DNS", server.name)
        assertEquals("192.168.1.1", server.primary)
    }

    @Test
    fun `invalid secondary returns failure`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase("My DNS", "8.8.8.8", "not-valid")

        assertFalse(result.isSuccess)
    }

    @Test
    fun `valid DoH URL enables DoH support`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase(
            name = "My DoH",
            primary = "8.8.8.8",
            secondary = null,
            dohUrl = " https://dns.example/dns-query ",
            protocol = DnsProtocol.DOH
        )

        assertTrue(result.isSuccess)
        val server = result.getOrThrow()
        assertEquals("https://dns.example/dns-query", server.dohUrl)
        assertEquals(listOf(DnsProtocol.STANDARD, DnsProtocol.DOH), server.supportedProtocols)
    }

    @Test
    fun `non HTTPS DoH URL returns failure`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase("My DNS", "8.8.8.8", null, "http://dns.example/dns-query", DnsProtocol.DOH)

        assertFalse(result.isSuccess)
        assertEquals("DoH URL must be an HTTPS URL", result.exceptionOrNull()?.message)
    }

    @Test
    fun `DoH only server does not require primary DNS`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase(
            name = "DoH Only",
            primary = "",
            secondary = null,
            dohUrl = "https://dns.example/dns-query",
            protocol = DnsProtocol.DOH
        )

        assertTrue(result.isSuccess)
        val server = result.getOrThrow()
        assertEquals("", server.primary)
        assertEquals("https://dns.example/dns-query", server.dohUrl)
        assertEquals(listOf(DnsProtocol.DOH), server.supportedProtocols)
    }

    @Test
    fun `DoH server stores custom bootstrap IP and untrusted certificate setting`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase(
            name = "OpenNIC DoH",
            primary = "",
            secondary = null,
            dohUrl = "https://resolver.glue/dns-query",
            protocol = DnsProtocol.DOH,
            customBootstrapIp = "203.0.113.10",
            allowUntrustedCertificates = true
        )

        assertTrue(result.isSuccess)
        val server = result.getOrThrow()
        assertEquals("203.0.113.10", server.customBootstrapIp)
        assertTrue(server.allowUntrustedCertificates)
    }

    @Test
    fun `invalid custom bootstrap IP returns failure`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase(
            name = "OpenNIC DoH",
            primary = "",
            secondary = null,
            dohUrl = "https://resolver.glue/dns-query",
            protocol = DnsProtocol.DOH,
            customBootstrapIp = "not-an-ip"
        )

        assertFalse(result.isSuccess)
        assertEquals("Invalid custom bootstrap IP", result.exceptionOrNull()?.message)
    }

    @Test
    fun `DoH mode requires DoH URL`() = runTest {
        coJustRun { dnsRepository.saveCustomServer(any()) }

        val result = useCase("My DNS", "", null, null, DnsProtocol.DOH)

        assertFalse(result.isSuccess)
        assertEquals("DoH URL is required", result.exceptionOrNull()?.message)
    }
}
