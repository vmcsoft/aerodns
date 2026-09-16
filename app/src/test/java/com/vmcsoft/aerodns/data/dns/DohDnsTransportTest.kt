package com.vmcsoft.aerodns.data.dns

import android.net.Network
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import javax.net.ssl.SSLException

class DohDnsTransportTest {

    @Test fun `cancellation cancels a blocked HTTP call without waiting for its timeout`() = kotlinx.coroutines.runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val call = mockk<Call>()
        every { call.execute() } answers {
            entered.complete(Unit)
            check(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "Call was not cancelled" }
            throw IOException("cancelled")
        }
        every { call.cancel() } answers { cancelled.countDown() }
        val factory = mockk<Call.Factory>()
        every { factory.newCall(any()) } returns call
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
            callFactoryBuilder = { _, _, _ -> factory }
        }
        val job = launch {
            transport.query(byteArrayOf(1, 2, 3), "https://resolver.example/dns-query", listOf("1.1.1.1"), 5000)
        }
        entered.await()
        job.cancel()
        kotlinx.coroutines.withTimeout(1000) { job.join() }
        assertTrue(job.isCancelled)
        verify(exactly = 1) { call.cancel() }
    }

    @Test fun `cancellation remains attached while reading HTTP response body`() = kotlinx.coroutines.runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val source = mockk<okio.BufferedSource>()
        every { source.read(any<Buffer>(), any()) } answers {
            entered.complete(Unit)
            check(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS))
            throw IOException("cancelled body")
        }
        every { source.close() } returns Unit
        val body = object : okhttp3.ResponseBody() {
            override fun contentType() = "application/dns-message".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        val call = mockk<Call>()
        every { call.execute() } returns Response.Builder().request(Request.Builder().url("https://resolver.example/").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        every { call.cancel() } answers { cancelled.countDown() }
        val factory = mockk<Call.Factory>()
        every { factory.newCall(any()) } returns call
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
            callFactoryBuilder = { _, _, _ -> factory }
        }
        val job = launch {
            transport.query(byteArrayOf(1, 2, 3), "https://resolver.example/dns-query", listOf("1.1.1.1"), 5000)
        }
        entered.await()
        job.cancel()
        kotlinx.coroutines.withTimeout(1000) { job.join() }
        assertTrue(job.isCancelled)
        verify(exactly = 1) { call.cancel() }
        verify(exactly = 1) { source.close() }
    }

    @Test
    fun `query posts DNS wire payload and returns DNS wire response`() = runTest {
        val requestPayload = byteArrayOf(1, 2, 3)
        val responsePayload = byteArrayOf(4, 5, 6)
        val fakeCallFactory = FakeCallFactory(
            responseCode = 200,
            responsePayload = responsePayload
        )
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
            callFactoryBuilder = { endpoint, timeoutMs, _ ->
                assertEquals(listOf("1.1.1.1"), endpoint.addresses.map { it.hostAddress })
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
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
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
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
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
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
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
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
            callFactoryBuilder = { endpoint, _, _ ->
                assertEquals(listOf("1.1.1.1", "2606:4700:4700:0:0:0:0:1111"), endpoint.addresses.map { it.hostAddress })
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
    fun `URL-only query uses discovered endpoint and keeps original HTTPS hostname`() = runTest {
        val resolver = mockk<DohEndpointResolver>()
        val endpoint = endpoint("dns.google", "8.8.8.8")
        every { resolver.resolve("dns.google", emptyList()) } returns endpoint
        val callFactory = FakeCallFactory(responsePayload = byteArrayOf(1, 2, 3))
        val transport = DohDnsTransport(resolver).apply {
            callFactoryBuilder = { actualEndpoint, _, _ ->
                assertEquals(endpoint, actualEndpoint)
                callFactory
            }
        }

        val result = transport.query(byteArrayOf(1), "https://dns.google/dns-query", emptyList(), 1000)

        assertTrue(result is DnsTransportResult.Success)
        assertEquals("dns.google", callFactory.request!!.url.host)
        verify(exactly = 1) { resolver.resolve("dns.google", emptyList()) }
    }

    @Test
    fun `endpoint discovery failure does not start an HTTPS request or substitute provider`() = runTest {
        val resolver = mockk<DohEndpointResolver>()
        every { resolver.resolve(any(), any()) } throws UnknownHostException("unavailable")
        val transport = DohDnsTransport(resolver).apply {
            callFactoryBuilder = { _, _, _ -> throw AssertionError("Must not connect") }
        }
        val result = transport.query(byteArrayOf(1), "https://dns.example/dns-query", emptyList(), 1000)
        assertTrue(result is DnsTransportResult.Error)
    }

    @Test
    fun `explicit bootstrap overrides profile addresses`() = runTest {
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
            callFactoryBuilder = { actualEndpoint, _, _ ->
                assertEquals("resolver.example", actualEndpoint.hostname)
                assertEquals(listOf("203.0.113.10"), actualEndpoint.addresses.map { it.hostAddress })
                FakeCallFactory(responsePayload = byteArrayOf(1))
            }
        }
        val result = transport.query(
            byteArrayOf(1), "https://resolver.example/dns-query", listOf("1.1.1.1"), 1000,
            customBootstrapIp = "203.0.113.10"
        )
        assertTrue(result is DnsTransportResult.Success)
    }

    @Test
    fun `bootstrap only maps the intended endpoint hostname`() {
        val dns = CustomDohBootstrapDns(endpoint("resolver.example", "203.0.113.10"))
        assertEquals("203.0.113.10", dns.lookup("resolver.example").single().hostAddress)
        assertTrue(runCatching { dns.lookup("unrelated.example") }.exceptionOrNull() is UnknownHostException)
    }

    @Test
    fun `cached clients change when network or discovered answers change`() {
        val transport = DohDnsTransport(DohEndpointResolver(mockk()))
        val wifi = mockk<Network>(name = "wifi")
        val mobile = mockk<Network>(name = "mobile")
        val first = endpoint("resolver.example", "203.0.113.10").copy(network = wifi)
        val wifiClient = transport.buildCallFactory(first, 1000, NoopDnsSocketProtector)
        assertSame(wifiClient, transport.buildCallFactory(first, 1000, NoopDnsSocketProtector))
        val mobileEndpoint = first.copy(network = mobile)
        val mobileClient = transport.buildCallFactory(mobileEndpoint, 1000, NoopDnsSocketProtector)
        assertNotSame(wifiClient, mobileClient)
        val changed = endpoint("resolver.example", "203.0.113.11").copy(network = mobile)
        val changedClient = transport.buildCallFactory(changed, 1000, NoopDnsSocketProtector) as OkHttpClient
        assertNotSame(mobileClient, changedClient)
        assertEquals("203.0.113.11", changedClient.dns.lookup("resolver.example").single().hostAddress)
        // Returning to Wi-Fi must not resurrect the removed pool.
        assertNotSame(wifiClient, transport.buildCallFactory(first, 1000, NoopDnsSocketProtector))
    }

    @Test
    fun `unsafe client uses same endpoint mapping without weakening normal client`() {
        val endpoint = endpoint("resolver.example", "203.0.113.10")
        val transport = DohDnsTransport(DohEndpointResolver(mockk()))
        val normal = transport.buildCallFactory(endpoint, 1000, NoopDnsSocketProtector) as OkHttpClient
        val unsafe = CustomDohUnsafeClientFactory.build(endpoint, 1000, NoopDnsSocketProtector)
        assertEquals(normal.dns.lookup(endpoint.hostname), unsafe.dns.lookup(endpoint.hostname))
        assertNotSame(normal.hostnameVerifier, unsafe.hostnameVerifier)
        assertSame(normal, transport.buildCallFactory(endpoint, 1000, NoopDnsSocketProtector))
        assertFalse(normal.followRedirects)
        assertFalse(unsafe.followRedirects)
    }

    @Test
    fun `protected socket binds discovered network after VPN protection and before connect`() {
        val network = mockk<Network>()
        var protected = false
        val protector = object : DnsSocketProtector {
            override fun protect(socket: DatagramSocket) = true
            override fun protect(socket: Socket): Boolean {
                assertTrue(socket.isBound)
                protected = true
                return true
            }
        }
        every { network.bindSocket(any<Socket>()) } answers {
            assertTrue(protected)
            assertFalse(firstArg<Socket>().isConnected)
        }
        DohDnsTransport.ProtectedSocketFactory(protector, network).createSocket().use { }
        verify(exactly = 1) { network.bindSocket(any<Socket>()) }
    }

    private fun endpoint(hostname: String, address: String) =
        DohEndpoint(hostname, listOf(InetAddress.getByName(address)))

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
