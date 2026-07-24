package com.vmcsoft.aerodns.data.dns

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Socket

class DotDnsTransportTest {

    @Test
    fun `query writes DNS-over-TLS framed payload and returns DNS response`() = runTest {
        val requestPayload = byteArrayOf(1, 2, 3)
        val responsePayload = byteArrayOf(4, 5, 6)
        val socket = FakeSocket(responsePayload)
        val transport = DotDnsTransport().apply {
            connectionFactory = { dotHostname, connectAddress, timeoutMs, _ ->
                assertEquals("cloudflare-dns.com", dotHostname)
                assertEquals("1.1.1.1", connectAddress)
                assertEquals(1000, timeoutMs)
                socket
            }
        }

        val result = transport.query(
            payload = requestPayload,
            dotHostname = "cloudflare-dns.com",
            upstreamAddresses = listOf("1.1.1.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertArrayEquals(responsePayload, (result as DnsTransportResult.Success).payload)
        assertArrayEquals(byteArrayOf(0, 3, 1, 2, 3), socket.writtenPayload())
        assertEquals(1000, socket.recordedSoTimeout)
    }

    @Test
    fun `query tries next upstream address after network error`() = runTest {
        val attempts = mutableListOf<String>()
        val socket = FakeSocket(byteArrayOf(4, 5, 6))
        val transport = DotDnsTransport().apply {
            connectionFactory = { _, connectAddress, _, _ ->
                attempts += connectAddress
                if (connectAddress == "1.1.1.1") {
                    throw java.io.IOException("first address failed")
                }
                socket
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dotHostname = "cloudflare-dns.com",
            upstreamAddresses = listOf("1.1.1.1", "1.0.0.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), attempts)
    }

    @Test
    fun `query tries next upstream address after timeout`() = runTest {
        val attempts = mutableListOf<String>()
        val socket = FakeSocket(byteArrayOf(4, 5, 6))
        val transport = DotDnsTransport().apply {
            connectionFactory = { _, connectAddress, _, _ ->
                attempts += connectAddress
                if (connectAddress == "1.1.1.1") {
                    throw java.net.SocketTimeoutException("first address timed out")
                }
                socket
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dotHostname = "cloudflare-dns.com",
            upstreamAddresses = listOf("1.1.1.1", "1.0.0.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), attempts)
    }

    @Test
    fun `query prefers IPv4 upstreams before IPv6 upstreams`() = runTest {
        val attempts = mutableListOf<String>()
        val socket = FakeSocket(byteArrayOf(4, 5, 6))
        val transport = DotDnsTransport().apply {
            connectionFactory = { _, connectAddress, _, _ ->
                attempts += connectAddress
                socket
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dotHostname = "cloudflare-dns.com",
            upstreamAddresses = listOf("2606:4700:4700::1111", "1.1.1.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertEquals(listOf("1.1.1.1"), attempts)
    }

    @Test
    fun `query returns error when socket protection fails`() = runTest {
        val transport = DotDnsTransport().apply {
            socketProtector = object : DnsSocketProtector {
                override fun protect(socket: DatagramSocket): Boolean = true
                override fun protect(socket: Socket): Boolean = false
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dotHostname = "cloudflare-dns.com",
            upstreamAddresses = listOf("127.0.0.1"),
            timeoutMs = 100
        )

        assertTrue(result is DnsTransportResult.Error)
    }

    private class FakeSocket(responsePayload: ByteArray) : Socket() {
        private val input = ByteArrayInputStream(
            byteArrayOf(
                (responsePayload.size shr 8).toByte(),
                responsePayload.size.toByte()
            ) + responsePayload
        )
        private val output = ByteArrayOutputStream()
        var recordedSoTimeout: Int = 0

        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): OutputStream = output

        override fun setSoTimeout(timeout: Int) {
            recordedSoTimeout = timeout
        }

        fun writtenPayload(): ByteArray = output.toByteArray()
    }
}
