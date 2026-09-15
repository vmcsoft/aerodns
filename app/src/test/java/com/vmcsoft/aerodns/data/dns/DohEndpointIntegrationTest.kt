package com.vmcsoft.aerodns.data.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.NetworkIpStack
import com.vmcsoft.aerodns.domain.model.buildDnsConnectionConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class DohEndpointIntegrationTest {
    @Test fun `URL-only profile reaches discovered HTTPS endpoint with original host and DNS payload`() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(HOST).build()
        MockWebServer().use { server ->
            startTls(server, cert)
            val response = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte())
            server.enqueue(MockResponse().setHeader("Content-Type", "application/dns-message")
                .setBody(Buffer().write(response)))
            val context = mockk<Context>()
            val manager = mockk<ConnectivityManager>()
            val network = mockk<Network>()
            val caps = mockk<NetworkCapabilities>()
            every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
            every { manager.activeNetwork } returns network
            every { manager.getNetworkCapabilities(network) } returns caps
            every { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns false
            every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { network.getAllByName(HOST) } returns arrayOf(InetAddress.getByName("127.0.0.1"))
            every { network.bindSocket(any<Socket>()) } returns Unit
            val transport = trustedTransport(DohEndpointResolver(context), cert)
            val config = buildDnsConnectionConfig(
                DnsServer("custom", "Custom", "", dohUrl = "https://$HOST:${server.port}/dns-query", isCustom = true),
                NetworkIpStack.IPv4_ONLY, DnsProtocol.DOH
            ).getOrThrow()
            val query = DnsWireMessage.buildAQuery(0x1234, "example.com")

            val result = transport.query(query, config.dohUrl!!, config.upstreamAddresses, 1000)

            assertTrue(result.toString(), result is DnsTransportResult.Success)
            assertArrayEquals(response, (result as DnsTransportResult.Success).payload)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("$HOST:${server.port}", request.getHeader(":authority") ?: request.getHeader("Host"))
            assertEquals("/dns-query", request.path)
            assertEquals("POST", request.method)
            assertArrayEquals(query, request.body.readByteArray())
            verify(exactly = 1) { network.getAllByName(HOST) }
            verify(atLeast = 1) { network.bindSocket(any<Socket>()) }
        }
    }

    @Test fun `explicit endpoint IP does not bypass HTTPS hostname verification`() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("wrong.example").build()
        MockWebServer().use { server ->
            startTls(server, cert)
            val transport = trustedTransport(DohEndpointResolver(mockk()), cert)
            val result = transport.query(
                byteArrayOf(1), "https://$HOST:${server.port}/dns-query", emptyList(), 1000,
                customBootstrapIp = "127.0.0.1"
            )
            assertTrue(result is DnsTransportResult.Error)
            assertEquals(DnsSecurityMessages.UNTRUSTED_CERTIFICATE, (result as DnsTransportResult.Error).message)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `untrusted certificate override works only on explicitly opted-in requests`() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(HOST).build()
        MockWebServer().use { server ->
            startTls(server, cert)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/dns-message")
                .setBody(Buffer().write(byteArrayOf(1, 2))))
            val transport = DohDnsTransport(DohEndpointResolver(mockk()))
            val url = "https://$HOST:${server.port}/dns-query"
            val rejected = transport.query(byteArrayOf(1), url, emptyList(), 1000, "127.0.0.1")
            assertTrue(rejected is DnsTransportResult.Error)
            val accepted = transport.query(byteArrayOf(1), url, emptyList(), 1000, "127.0.0.1", true)
            assertTrue(accepted.toString(), accepted is DnsTransportResult.Success)
            val rejectedAgain = transport.query(byteArrayOf(1), url, emptyList(), 1000, "127.0.0.1")
            assertTrue(rejectedAgain is DnsTransportResult.Error)
            assertEquals(1, server.requestCount)
        }
    }

    private fun startTls(server: MockWebServer, cert: HeldCertificate) {
        val certificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun trustedTransport(resolver: DohEndpointResolver, cert: HeldCertificate): DohDnsTransport {
        val certificates = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        return DohDnsTransport(resolver).apply {
            callFactoryBuilder = { endpoint, timeout, protector ->
                // Use the production DNS/socket/hostname policy; trust only this test server's root.
                (buildCallFactory(endpoint, timeout, protector) as OkHttpClient).newBuilder()
                    .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
                    .build()
            }
        }
    }

    private companion object {
        const val HOST = "resolver.example"
    }
}
