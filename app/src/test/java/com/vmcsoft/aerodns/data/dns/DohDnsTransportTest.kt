package com.vmcsoft.aerodns.data.dns

import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class DohDnsTransportTest {

    @Test
    fun `query posts DNS wire payload and returns DNS wire response`() = runTest {
        val requestPayload = byteArrayOf(1, 2, 3)
        val responsePayload = byteArrayOf(4, 5, 6)
        val fakeCallFactory = FakeCallFactory(
            responseCode = 200,
            responsePayload = responsePayload
        )
        val transport = DohDnsTransport().apply {
            callFactoryBuilder = { upstreamAddresses, timeoutMs, _ ->
                assertEquals(listOf("1.1.1.1"), upstreamAddresses)
                assertEquals(1000, timeoutMs)
                fakeCallFactory
            }
        }

        val result = transport.query(
            payload = requestPayload,
            dohUrl = "https://cloudflare-dns.com/dns-query",
            upstreamAddresses = listOf("1.1.1.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
        assertArrayEquals(responsePayload, (result as DnsTransportResult.Success).payload)
        val request = fakeCallFactory.request!!
        assertEquals("https://cloudflare-dns.com/dns-query", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("application/dns-message", request.header("Accept"))
        assertEquals("application/dns-message", request.body?.contentType().toString())

        val requestBody = Buffer()
        request.body!!.writeTo(requestBody)
        assertArrayEquals(requestPayload, requestBody.readByteArray())
    }

    @Test
    fun `query returns error for non-success HTTP status`() = runTest {
        val transport = DohDnsTransport().apply {
            callFactoryBuilder = { _, _, _ ->
                FakeCallFactory(
                    responseCode = 500,
                    responsePayload = ByteArray(0)
                )
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dohUrl = "https://cloudflare-dns.com/dns-query",
            upstreamAddresses = listOf("1.1.1.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Error)
    }

    @Test
    fun `query returns timeout when OkHttp call times out`() = runTest {
        val transport = DohDnsTransport().apply {
            callFactoryBuilder = { _, _, _ ->
                FakeCallFactory(exception = SocketTimeoutException("timed out"))
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dohUrl = "https://cloudflare-dns.com/dns-query",
            upstreamAddresses = listOf("1.1.1.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Timeout)
    }

    @Test
    fun `query maps SSL failures to untrusted certificate message`() = runTest {
        val transport = DohDnsTransport().apply {
            callFactoryBuilder = { _, _, _ ->
                FakeCallFactory(exception = SSLException("certificate path failed"))
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dohUrl = "https://resolver.glue/dns-query",
            upstreamAddresses = listOf("203.0.113.10"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Error)
        assertEquals(
            DnsSecurityMessages.UNTRUSTED_CERTIFICATE,
            (result as DnsTransportResult.Error).message
        )
    }

    @Test
    fun `query prefers IPv4 bootstrap addresses before IPv6 addresses`() = runTest {
        val fakeCallFactory = FakeCallFactory(
            responseCode = 200,
            responsePayload = byteArrayOf(4, 5, 6)
        )
        val transport = DohDnsTransport().apply {
            callFactoryBuilder = { upstreamAddresses, _, _ ->
                assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), upstreamAddresses)
                fakeCallFactory
            }
        }

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dohUrl = "https://cloudflare-dns.com/dns-query",
            upstreamAddresses = listOf("2606:4700:4700::1111", "1.1.1.1"),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Success)
    }

    @Test
    fun `query returns error when bootstrap addresses are empty`() = runTest {
        val transport = DohDnsTransport()

        val result = transport.query(
            payload = byteArrayOf(1, 2, 3),
            dohUrl = "https://cloudflare-dns.com/dns-query",
            upstreamAddresses = emptyList(),
            timeoutMs = 1000
        )

        assertTrue(result is DnsTransportResult.Error)
    }

    @Test
    fun `bootstrap DNS maps provider hostname to configured addresses`() {
        val dns = CustomDohBootstrapDns(
            customBootstrapIp = null,
            fallbackBootstrapAddresses = listOf("1.1.1.1", "2606:4700:4700::1111")
        )

        val result = dns.lookup("cloudflare-dns.com")

        assertEquals("1.1.1.1", result[0].hostAddress)
        assertEquals("2606:4700:4700:0:0:0:0:1111", result[1].hostAddress)
    }

    @Test
    fun `custom bootstrap DNS returns custom IP before fallback addresses`() {
        val dns = CustomDohBootstrapDns(
            customBootstrapIp = "203.0.113.10",
            fallbackBootstrapAddresses = listOf("1.1.1.1")
        )

        val result = dns.lookup("resolver.glue")

        assertEquals(1, result.size)
        assertEquals("203.0.113.10", result[0].hostAddress)
    }

    @Test
    fun `protected socket factory reports protection failure`() {
        val socketFactory = DohDnsTransport.ProtectedSocketFactory(
            object : DnsSocketProtector {
                override fun protect(socket: DatagramSocket): Boolean = true
                override fun protect(socket: Socket): Boolean = false
            }
        )

        val result = runCatching {
            socketFactory.createSocket()
        }

        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `protected socket factory binds socket before protection`() {
        var wasBoundWhenProtected = false
        val socketFactory = DohDnsTransport.ProtectedSocketFactory(
            object : DnsSocketProtector {
                override fun protect(socket: DatagramSocket): Boolean = true
                override fun protect(socket: Socket): Boolean {
                    wasBoundWhenProtected = socket.isBound
                    return true
                }
            }
        )

        socketFactory.createSocket().use { socket ->
            assertTrue(socket.isBound)
        }
        assertTrue(wasBoundWhenProtected)
    }

    private class FakeCallFactory(
        private val responseCode: Int = 200,
        private val responsePayload: ByteArray = ByteArray(0),
        private val exception: IOException? = null
    ) : Call.Factory {
        var request: Request? = null

        override fun newCall(request: Request): Call {
            this.request = request
            return FakeCall(request, responseCode, responsePayload, exception)
        }
    }

    private class FakeCall(
        private val request: Request,
        private val responseCode: Int,
        private val responsePayload: ByteArray,
        private val exception: IOException?
    ) : Call {
        override fun request(): Request = request

        override fun execute(): Response {
            exception?.let { throw it }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message(if (responseCode in 200..299) "OK" else "Error")
                .body(responsePayload.toResponseBody("application/dns-message".toMediaType()))
                .build()
        }

        override fun enqueue(responseCallback: Callback) = Unit

        override fun cancel() = Unit

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(request, responseCode, responsePayload, exception)
    }
}
