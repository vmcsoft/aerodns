package com.vmcsoft.aerodns.data.vpn

import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import com.vmcsoft.aerodns.data.dns.DohDnsTransport
import com.vmcsoft.aerodns.data.dns.UdpDnsTransport
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPingerTest {

    @Test
    fun `measureDnsQueryLatency returns success for valid DNS response`() = runTest {
        val transport = mockk<UdpDnsTransport>()
        coEvery { transport.query(any(), "1.1.1.1", 1000) } answers {
            val query = invocation.args[0] as ByteArray
            val queryId = readQueryId(query)
            DnsTransportResult.Success(
                payload = successfulResponse(queryId),
                latencyMs = 14
            )
        }

        val result = networkPinger(transport).measureDnsQueryLatency(
            ipAddress = "1.1.1.1",
            timeoutMs = 1000
        )

        assertTrue(result is PingResult.Success)
        assertEquals(14L, (result as PingResult.Success).latencyMs)
    }

    @Test
    fun `measureDnsQueryLatency returns error for mismatched DNS response`() = runTest {
        val transport = mockk<UdpDnsTransport>()
        coEvery { transport.query(any(), "1.1.1.1", 1000) } returns DnsTransportResult.Success(
            payload = successfulResponse(0xCAFE),
            latencyMs = 14
        )

        val result = networkPinger(transport).measureDnsQueryLatency(
            ipAddress = "1.1.1.1",
            timeoutMs = 1000
        )

        assertTrue(result is PingResult.Error)
    }

    @Test
    fun `measureDnsQueryLatency forwards timeout result`() = runTest {
        val transport = mockk<UdpDnsTransport>()
        coEvery { transport.query(any(), "1.1.1.1", 1000) } returns DnsTransportResult.Timeout

        val result = networkPinger(transport).measureDnsQueryLatency(
            ipAddress = "1.1.1.1",
            timeoutMs = 1000
        )

        assertTrue(result is PingResult.Timeout)
    }

    @Test
    fun `measureDohQueryLatency returns success for valid DNS response`() = runTest {
        val udpTransport = mockk<UdpDnsTransport>()
        val dohTransport = mockk<DohDnsTransport>()
        coEvery {
            dohTransport.query(
                any(),
                "https://dns.example/dns-query",
                listOf("1.1.1.1"),
                1000,
                null,
                false
            )
        } answers {
            val query = invocation.args[0] as ByteArray
            val queryId = readQueryId(query)
            DnsTransportResult.Success(
                payload = successfulResponse(queryId),
                latencyMs = 42
            )
        }

        val result = NetworkPinger(udpTransport, dohTransport).measureDohQueryLatency(
            dohUrl = "https://dns.example/dns-query",
            upstreamAddresses = listOf("1.1.1.1"),
            timeoutMs = 1000
        )

        assertTrue(result is PingResult.Success)
        assertEquals(42L, (result as PingResult.Success).latencyMs)
    }

    private fun networkPinger(transport: UdpDnsTransport): NetworkPinger {
        return NetworkPinger(
            udpDnsTransport = transport,
            dohDnsTransport = mockk(relaxed = true)
        )
    }

    private fun readQueryId(query: ByteArray): Int {
        return ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF)
    }

    private fun successfulResponse(queryId: Int): ByteArray {
        return ByteArray(12).also { response ->
            response[0] = ((queryId ushr 8) and 0xFF).toByte()
            response[1] = (queryId and 0xFF).toByte()
            response[2] = 0x81.toByte()
            response[3] = 0x80.toByte()
        }
    }
}
