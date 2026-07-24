package com.vmcsoft.aerodns.data.dns

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpDnsTransportTest {

    @Test
    fun `query returns error when socket protection fails`() = runTest {
        val transport = UdpDnsTransport().apply {
            socketProtector = object : DnsSocketProtector {
                override fun protect(socket: java.net.DatagramSocket): Boolean = false
                override fun protect(socket: java.net.Socket): Boolean = true
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            upstreamAddress = "127.0.0.1",
            timeoutMs = 100
        )

        assertTrue(result is DnsTransportResult.Error)
    }
}
