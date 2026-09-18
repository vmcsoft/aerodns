package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.data.vpn.NetworkPinger
import com.vmcsoft.aerodns.data.vpn.PingResult
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.NetworkIpStack
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RunSpeedTestUseCaseTest {

    @Test
    fun `results are sorted by average latency ascending`() = runTest {
        val google = DnsServer(id = "google", name = "Google", primary = "8.8.8.8", secondary = "8.8.4.4")
        val cloudflare = DnsServer(id = "cloudflare", name = "Cloudflare", primary = "1.1.1.1", secondary = "1.0.0.1")
        val dnsRepository = mockk<DnsRepository>()
        every { dnsRepository.getPreDefinedServers() } returns listOf(google, cloudflare)
        coEvery { dnsRepository.getCustomServers() } returns emptyList()

        val networkPinger = mockk<NetworkPinger>()
        coEvery { networkPinger.measureDnsQueryLatency("8.8.8.8", any(), any()) } returns PingResult.Success(50)
        coEvery { networkPinger.measureDnsQueryLatency("1.1.1.1", any(), any()) } returns PingResult.Success(20)

        val networkCapabilitiesRepository = mockk<NetworkCapabilitiesRepository>()
        coEvery { networkCapabilitiesRepository.getActiveNetworkIpStack() } returns NetworkIpStack.IPv4_ONLY

        val useCase = RunSpeedTestUseCase(dnsRepository, networkPinger, networkCapabilitiesRepository)
        val results = useCase(DnsProtocol.STANDARD)

        assertEquals(2, results.size)
        assertEquals("cloudflare", results[0].server.id)
        assertEquals(20L, results[0].averageLatencyMs)
        assertEquals("google", results[1].server.id)
        assertEquals(50L, results[1].averageLatencyMs)
    }

    @Test
    fun `uses the connection IPv6 first ordering on dual stack`() = runTest {
        val cloudflare = DnsServer(
            id = "cloudflare",
            name = "Cloudflare",
            primary = "1.1.1.1",
            secondary = "1.0.0.1",
            ipv6Primary = "2606:4700:4700::1111",
            ipv6Secondary = "2606:4700:4700::1001"
        )
        val dnsRepository = mockk<DnsRepository>()
        every { dnsRepository.getPreDefinedServers() } returns listOf(cloudflare)
        coEvery { dnsRepository.getCustomServers() } returns emptyList()

        val networkPinger = mockk<NetworkPinger>()
        coEvery {
            networkPinger.measureDnsQueryLatency("2606:4700:4700::1111", any(), any())
        } returns PingResult.Success(18)

        val networkCapabilitiesRepository = mockk<NetworkCapabilitiesRepository>()
        coEvery { networkCapabilitiesRepository.getActiveNetworkIpStack() } returns NetworkIpStack.DUAL_STACK

        val useCase = RunSpeedTestUseCase(dnsRepository, networkPinger, networkCapabilitiesRepository)
        val results = useCase(DnsProtocol.STANDARD)

        assertEquals(1, results.size)
        assertEquals(true, results[0].isReachable)
        assertEquals(18L, results[0].averageLatencyMs)
        assertEquals("2606:4700:4700::1111", results[0].testedAddress)
        coVerify(exactly = 0) {
            networkPinger.measureDnsQueryLatency("1.1.1.1", any(), any())
        }
        coVerify(exactly = 0) {
            networkPinger.measureDnsQueryLatency("2606:4700:4700::1001", any(), any())
        }
    }

    @Test
    fun `includes custom DNS servers in speed test`() = runTest {
        val cloudflare = DnsServer(id = "cloudflare", name = "Cloudflare", primary = "1.1.1.1")
        val custom = DnsServer(
            id = "custom-1",
            name = "Home DNS",
            primary = "192.0.2.53",
            isCustom = true
        )
        val dnsRepository = mockk<DnsRepository>()
        every { dnsRepository.getPreDefinedServers() } returns listOf(cloudflare)
        coEvery { dnsRepository.getCustomServers() } returns listOf(custom)

        val networkPinger = mockk<NetworkPinger>()
        coEvery { networkPinger.measureDnsQueryLatency("1.1.1.1", any(), any()) } returns PingResult.Success(20)
        coEvery { networkPinger.measureDnsQueryLatency("192.0.2.53", any(), any()) } returns PingResult.Success(35)

        val networkCapabilitiesRepository = mockk<NetworkCapabilitiesRepository>()
        coEvery { networkCapabilitiesRepository.getActiveNetworkIpStack() } returns NetworkIpStack.IPv4_ONLY

        val useCase = RunSpeedTestUseCase(dnsRepository, networkPinger, networkCapabilitiesRepository)
        val progressResults = mutableListOf<String>()
        val results = useCase(DnsProtocol.STANDARD) { completed, total, result ->
            assertEquals(2, total)
            assertEquals(true, completed in 1..2)
            progressResults.add(result.server.id)
        }

        assertEquals(2, results.size)
        assertEquals(setOf("cloudflare", "custom-1"), results.map { it.server.id }.toSet())
        assertEquals(2, progressResults.size)
        coVerify { dnsRepository.getCustomServers() }
    }

    @Test
    fun `tests custom DoH only DNS servers using endpoint discovery`() = runTest {
        val customDoh = DnsServer(
            id = "custom-doh",
            name = "Custom DoH",
            primary = "",
            dohUrl = "https://dns.example/dns-query",
            supportedProtocols = listOf(DnsProtocol.DOH),
            isCustom = true
        )
        val dnsRepository = mockk<DnsRepository>()
        every { dnsRepository.getPreDefinedServers() } returns emptyList()
        coEvery { dnsRepository.getCustomServers() } returns listOf(customDoh)

        val networkPinger = mockk<NetworkPinger>()
        coEvery {
            networkPinger.measureDohQueryLatency("https://dns.example/dns-query", any(), any(), any(), any(), any())
        } returns PingResult.Success(44)

        val networkCapabilitiesRepository = mockk<NetworkCapabilitiesRepository>()
        coEvery { networkCapabilitiesRepository.getActiveNetworkIpStack() } returns NetworkIpStack.IPv4_ONLY

        val useCase = RunSpeedTestUseCase(dnsRepository, networkPinger, networkCapabilitiesRepository)
        val results = useCase(DnsProtocol.DOH)

        assertEquals(1, results.size)
        assertEquals(true, results[0].isReachable)
        assertEquals(44L, results[0].averageLatencyMs)
        assertEquals("https://dns.example/dns-query", results[0].testedAddress)
        coVerify(exactly = 0) { networkPinger.measureDnsQueryLatency(any(), any(), any()) }
        coVerify { networkPinger.measureDohQueryLatency("https://dns.example/dns-query", any(), any(), any(), any(), any()) }
    }
}
