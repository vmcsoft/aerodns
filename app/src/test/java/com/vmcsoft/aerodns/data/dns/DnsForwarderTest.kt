package com.vmcsoft.aerodns.data.dns

import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsForwarderTest {

    @Test
    fun `standard forwarding returns first successful upstream response`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        val response = byteArrayOf(4, 5, 6)
        val udpTransport = mockk<UdpDnsTransport>()
        val tcpTransport = mockk<TcpDnsTransport>()
        coEvery { udpTransport.query(payload, "1.1.1.1", 1000) } returns DnsTransportResult.Success(
            payload = response,
            latencyMs = 12
        )

        val result = DnsForwarder(udpTransport, tcpTransport, mockk(), mockk()).forward(
            payload = payload,
            config = standardConfig("1.1.1.1", "1.0.0.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertArrayEquals(response, (result as DnsTransportResult.Success).payload)
        assertEquals(12L, result.latencyMs)
        coVerify(exactly = 0) { udpTransport.query(payload, "1.0.0.1", 1000) }
        coVerify(exactly = 0) { tcpTransport.query(any(), any(), any()) }
    }

    @Test
    fun `standard forwarding falls back to secondary upstream`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        val response = byteArrayOf(4, 5, 6)
        val udpTransport = mockk<UdpDnsTransport>()
        val tcpTransport = mockk<TcpDnsTransport>()
        coEvery { udpTransport.query(payload, "1.1.1.1", 1000) } returns DnsTransportResult.Timeout
        coEvery { udpTransport.query(payload, "1.0.0.1", 1000) } returns DnsTransportResult.Success(
            payload = response,
            latencyMs = 18
        )

        val result = DnsForwarder(udpTransport, tcpTransport, mockk(), mockk()).forward(
            payload = payload,
            config = standardConfig("1.1.1.1", "1.0.0.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertArrayEquals(response, (result as DnsTransportResult.Success).payload)
        assertEquals(18L, result.latencyMs)
    }

    @Test
    fun `standard forwarding reports empty upstream list`() = runTest {
        val udpTransport = mockk<UdpDnsTransport>()
        val tcpTransport = mockk<TcpDnsTransport>()

        val result = DnsForwarder(udpTransport, tcpTransport, mockk(), mockk()).forward(
            payload = byteArrayOf(1, 2, 3),
            config = standardConfig(),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Error)
    }

    @Test
    fun `DoH forwarding reports missing URL`() = runTest {
        val udpTransport = mockk<UdpDnsTransport>()
        val tcpTransport = mockk<TcpDnsTransport>()

        val result = DnsForwarder(udpTransport, tcpTransport, mockk(), mockk()).forward(
            payload = byteArrayOf(1, 2, 3),
            config = DnsConnectionConfig(
                serverId = "cloudflare",
                displayName = "Cloudflare",
                protocol = DnsProtocol.DOH,
                upstreamAddresses = listOf("1.1.1.1")
            ),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Error)
    }

    @Test
    fun `DoH forwarding uses configured DoH URL`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        val response = byteArrayOf(4, 5, 6)
        val dohTransport = mockk<DohDnsTransport>()
        coEvery {
            dohTransport.query(payload, "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1"), 1000)
        } returns DnsTransportResult.Success(response, 24)

        val result = DnsForwarder(mockk(), mockk(), dohTransport, mockk()).forward(
            payload = payload,
            config = DnsConnectionConfig(
                serverId = "cloudflare",
                displayName = "Cloudflare",
                protocol = DnsProtocol.DOH,
                upstreamAddresses = listOf("1.1.1.1"),
                dohUrl = "https://cloudflare-dns.com/dns-query"
            ),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertArrayEquals(response, (result as DnsTransportResult.Success).payload)
        assertEquals(24L, result.latencyMs)
    }

    @Test
    fun `standard forwarding retries truncated UDP response over TCP`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        val tcpResponse = byteArrayOf(4, 5, 6)
        val udpTransport = mockk<UdpDnsTransport>()
        val tcpTransport = mockk<TcpDnsTransport>()
        coEvery { udpTransport.query(payload, "1.1.1.1", 1000) } returns DnsTransportResult.Success(
            payload = truncatedResponse(),
            latencyMs = 12
        )
        coEvery { tcpTransport.query(payload, "1.1.1.1", 1000) } returns DnsTransportResult.Success(
            payload = tcpResponse,
            latencyMs = 30
        )

        val result = DnsForwarder(udpTransport, tcpTransport, mockk(), mockk()).forward(
            payload = payload,
            config = standardConfig("1.1.1.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertArrayEquals(tcpResponse, (result as DnsTransportResult.Success).payload)
        assertEquals(30L, result.latencyMs)
    }

    @Test
    fun `DoT forwarding reports missing hostname`() = runTest {
        val result = DnsForwarder(mockk(), mockk(), mockk(), mockk()).forward(
            payload = byteArrayOf(1, 2, 3),
            config = DnsConnectionConfig(
                serverId = "cloudflare",
                displayName = "Cloudflare",
                protocol = DnsProtocol.DOT,
                upstreamAddresses = listOf("1.1.1.1")
            ),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Error)
    }

    @Test
    fun `DoT forwarding uses configured DoT hostname and upstreams`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        val response = byteArrayOf(4, 5, 6)
        val dotTransport = mockk<DotDnsTransport>()
        coEvery {
            dotTransport.query(payload, "cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1"), 1000)
        } returns DnsTransportResult.Success(response, 28)

        val result = DnsForwarder(mockk(), mockk(), mockk(), dotTransport).forward(
            payload = payload,
            config = DnsConnectionConfig(
                serverId = "cloudflare",
                displayName = "Cloudflare",
                protocol = DnsProtocol.DOT,
                upstreamAddresses = listOf("1.1.1.1", "1.0.0.1"),
                dotHostname = "cloudflare-dns.com"
            ),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertArrayEquals(response, (result as DnsTransportResult.Success).payload)
        assertEquals(28L, result.latencyMs)
    }

    private fun standardConfig(vararg addresses: String): DnsConnectionConfig {
        return DnsConnectionConfig(
            serverId = "test",
            displayName = "Test",
            protocol = DnsProtocol.STANDARD,
            upstreamAddresses = addresses.toList()
        )
    }

    private fun truncatedResponse(): ByteArray {
        return ByteArray(12).also { response ->
            response[2] = 0x82.toByte() // response + truncated
            response[3] = 0x00
        }
    }
}
