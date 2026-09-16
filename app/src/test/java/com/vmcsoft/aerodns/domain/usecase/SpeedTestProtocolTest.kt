package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.data.vpn.NetworkPinger
import com.vmcsoft.aerodns.data.vpn.PingResult
import com.vmcsoft.aerodns.domain.model.*
import com.vmcsoft.aerodns.domain.repository.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedTestProtocolTest {
    private val server = DnsServer("dual", "Dual", "1.1.1.1", dohUrl = "https://dns.example/dns-query",
        supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH))
    private val pinger = mockk<NetworkPinger>()
    private fun useCase(servers: List<DnsServer> = listOf(server)): RunSpeedTestUseCase {
        val repository = mockk<DnsRepository>()
        every { repository.getPreDefinedServers() } returns servers
        coEvery { repository.getCustomServers() } returns emptyList()
        val network = mockk<NetworkCapabilitiesRepository>()
        coEvery { network.getActiveNetworkIpStack() } returns NetworkIpStack.IPv4_ONLY
        return RunSpeedTestUseCase(repository, pinger, network)
    }

    @Test fun `built in DoH selection uses HTTPS despite having UDP addresses`() = runTest {
        coEvery { pinger.measureDohQueryLatency(any(), any(), any(), any(), any(), any()) } returns PingResult.Success(42)
        coEvery { pinger.measureDnsQueryLatency(any(), any(), any()) } returns PingResult.Success(1)
        val result = useCase()(DnsProtocol.DOH).single()
        assertEquals(server.dohUrl, result.testedAddress)
        assertEquals(DnsProtocol.DOH, result.testedProtocol)
        assertEquals(10, result.sampleCount)
        coVerify(exactly = 10) { pinger.measureDohQueryLatency(server.dohUrl!!, listOf("1.1.1.1"), 500, any(), null, false) }
        coVerify(exactly = 0) { pinger.measureDnsQueryLatency(any(), any(), any()) }
    }

    @Test fun `DoH failure never falls back to UDP`() = runTest {
        coEvery { pinger.measureDohQueryLatency(any(), any(), any(), any(), any(), any()) } returns PingResult.Timeout
        val result = useCase()(DnsProtocol.DOH).single()
        assertFalse(result.isReachable)
        assertEquals(10, result.attemptedSampleCount)
        assertEquals("No successful replies", result.failureReason)
        coVerify(exactly = 0) { pinger.measureDnsQueryLatency(any(), any(), any()) }
    }

    @Test fun `Standard selection never tests a DoH only profile`() = runTest {
        coEvery { pinger.measureDnsQueryLatency(any(), any(), any()) } returns PingResult.Success(20)
        val onlyDoh = server.copy(id = "only-doh", primary = "", supportedProtocols = listOf(DnsProtocol.DOH))
        val results = useCase(listOf(server, onlyDoh))(DnsProtocol.STANDARD)
        assertEquals(10, results.first().sampleCount)
        assertEquals("Protocol unavailable", results.last().failureReason)
        assertEquals(0, results.last().attemptedSampleCount)
        coVerify(exactly = 0) { pinger.measureDohQueryLatency(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `DoH selection never tests a Standard only profile`() = runTest {
        val result = useCase(listOf(server.copy(supportedProtocols = listOf(DnsProtocol.STANDARD))))(DnsProtocol.DOH).single()
        assertEquals("Protocol unavailable", result.failureReason)
        coVerify(exactly = 0) { pinger.measureDnsQueryLatency(any(), any(), any()) }
        coVerify(exactly = 0) { pinger.measureDohQueryLatency(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `custom endpoint bootstrap and certificate opt in match connection config`() = runTest {
        val custom = server.copy(isCustom = true, primary = "", customBootstrapIp = "192.0.2.8", allowUntrustedCertificates = true)
        coEvery { pinger.measureDohQueryLatency(custom.dohUrl!!, emptyList(), 500, any(), "192.0.2.8", true) } returns PingResult.Success(30)
        assertEquals(10, useCase(listOf(custom))(DnsProtocol.DOH).single().sampleCount)
    }

    @Test fun `built in profiles cannot opt into custom bootstrap or untrusted TLS`() = runTest {
        val builtin = server.copy(customBootstrapIp = "192.0.2.8", allowUntrustedCertificates = true)
        coEvery { pinger.measureDohQueryLatency(builtin.dohUrl!!, listOf("1.1.1.1"), 500, any(), null, false) } returns PingResult.Success(30)
        assertEquals(10, useCase(listOf(builtin))(DnsProtocol.DOH).single().sampleCount)
    }

    @Test fun `server deadline preserves successful samples and reports incomplete attempts`() = runTest {
        var requests = 0
        coEvery { pinger.measureDnsQueryLatency(any(), any(), any()) } coAnswers {
            if (++requests <= 2) PingResult.Success(12) else { delay(6000); PingResult.Success(99) }
        }
        val result = useCase()(DnsProtocol.STANDARD).single()
        assertEquals(2, result.sampleCount)
        assertEquals(3, result.attemptedSampleCount)
        assertEquals(10, result.plannedSampleCount)
        assertTrue(result.timedOut)
        assertTrue(result.isReachable)
        assertEquals(12L, result.averageLatencyMs)
    }

    @Test fun `partial fast replies rank below a complete slower measurement`() = runTest {
        var attempts = 0
        coEvery { pinger.measureDnsQueryLatency("1.1.1.1", any(), any()) } coAnswers {
            if (++attempts == 1) PingResult.Success(1) else PingResult.Timeout
        }
        coEvery { pinger.measureDnsQueryLatency("9.9.9.9", any(), any()) } returns PingResult.Success(50)
        val results = useCase(listOf(server, server.copy(id = "reliable", primary = "9.9.9.9")))(DnsProtocol.STANDARD)
        assertEquals("reliable", results.first().server.id)
        assertEquals(1, results.last().sampleCount)
        assertEquals(10, results.last().attemptedSampleCount)
        assertFalse(results.last().timedOut)
    }

    @Test fun `cancelling the caller cancels measurement without reporting a failed provider`() = runTest {
        coEvery { pinger.measureDnsQueryLatency(any(), any(), any()) } coAnswers { awaitCancellation() }
        var progress = 0
        val job = launch { useCase()(DnsProtocol.STANDARD) { _, _, _ -> progress++ } }
        runCurrent(); job.cancelAndJoin()
        assertEquals(0, progress)
        coVerify(exactly = 1) { pinger.measureDnsQueryLatency(any(), any(), any()) }
    }

    @Test fun `invalid selected configuration and hidden DoT are not measured`() = runTest {
        val invalid = server.copy(dohUrl = null)
        assertEquals("Configuration unavailable", useCase(listOf(invalid))(DnsProtocol.DOH).single().failureReason)
        assertEquals("Protocol unavailable", useCase()(DnsProtocol.DOT).single().failureReason)
        coVerify(exactly = 0) { pinger.measureDnsQueryLatency(any(), any(), any()) }
    }
}
