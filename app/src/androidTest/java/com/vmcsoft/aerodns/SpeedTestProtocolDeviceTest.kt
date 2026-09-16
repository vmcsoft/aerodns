package com.vmcsoft.aerodns

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.dns.DohDnsTransport
import com.vmcsoft.aerodns.data.dns.DohEndpointResolver
import com.vmcsoft.aerodns.data.dns.UdpDnsTransport
import com.vmcsoft.aerodns.data.vpn.NetworkPinger
import com.vmcsoft.aerodns.domain.model.*
import com.vmcsoft.aerodns.domain.repository.*
import com.vmcsoft.aerodns.domain.usecase.RunSpeedTestUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedTestProtocolDeviceTest {
    @Test fun selectedDohMeasuresHttpsAndHonorsCustomCertificatePolicy() = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("responseTestPort")?.toIntOrNull()
        assumeTrue("Start HTTPS fixture and pass responseTestPort", port != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A UDP address is deliberately present: the selected DoH path must override it.
        val custom = DnsServer("custom", "Custom HTTPS", "192.0.2.53",
            dohUrl = "https://localhost:$port/health-ok-speed", customBootstrapIp = "127.0.0.1",
            allowUntrustedCertificates = true, isCustom = true,
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH))
        val strict = custom.copy(id = "strict", allowUntrustedCertificates = false)
        val unsupported = custom.copy(id = "standard-only", supportedProtocols = listOf(DnsProtocol.STANDARD))
        val repository = object : DnsRepository {
            override fun getPreDefinedServers() = emptyList<DnsServer>()
            override suspend fun getCustomServers() = listOf(custom, strict, unsupported)
            override suspend fun saveCustomServer(server: DnsServer) = error("Not used")
            override suspend fun updateCustomServer(server: DnsServer) = error("Not used")
            override suspend fun deleteCustomServer(id: String) = error("Not used")
        }
        val network = object : NetworkCapabilitiesRepository {
            override suspend fun getActiveNetworkIpStack() = NetworkIpStack.IPv4_ONLY
        }
        val pinger = NetworkPinger(UdpDnsTransport(), DohDnsTransport(DohEndpointResolver(context)))
        val results = RunSpeedTestUseCase(repository, pinger, network)(DnsProtocol.DOH)
        val success = results.first { it.server.id == custom.id }
        assertEquals(custom.dohUrl, success.testedAddress)
        assertEquals(DnsProtocol.DOH, success.testedProtocol)
        assertEquals(10, success.sampleCount)
        assertEquals(10, success.attemptedSampleCount)
        assertFalse(success.timedOut)
        assertFalse(results.first { it.server.id == strict.id }.isReachable)
        val skipped = results.first { it.server.id == unsupported.id }
        assertEquals("Protocol unavailable", skipped.failureReason)
        assertEquals(0, skipped.attemptedSampleCount)
    }
}
