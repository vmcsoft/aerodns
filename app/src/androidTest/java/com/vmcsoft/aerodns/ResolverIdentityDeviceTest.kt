package com.vmcsoft.aerodns

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.dns.DnsSecurityMessages
import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import com.vmcsoft.aerodns.data.dns.DohDnsTransport
import com.vmcsoft.aerodns.data.dns.DohEndpointResolver
import com.vmcsoft.aerodns.data.local.DnsProviderData
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Live-network acceptance probes. Run on an idle test device with VPN consent prepared. */
@RunWith(AndroidJUnit4::class)
class ResolverIdentityDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connectivity: ConnectivityManager
        get() = context.getSystemService(ConnectivityManager::class.java)
    private var startedVpn = false

    @Before
    fun requirePreparedIdleDevice() {
        assertNull("Open the validation app and accept Android's VPN prompt first", VpnService.prepare(context))
        assertFalse("Disconnect the existing VPN before testing", connectivity.allNetworks.any(::isVpn))
    }

    @After
    fun disconnectTestVpn() {
        if (startedVpn) {
            context.startService(Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_DISCONNECT
            })
            val deadline = System.nanoTime() + 5_000_000_000L
            while (connectivity.allNetworks.any(::isVpn) && System.nanoTime() < deadline) Thread.sleep(50)
            assertFalse("Test VPN did not stop", connectivity.allNetworks.any(::isVpn))
        }
    }

    @Test
    fun standardIpv4OnlyProfileAdvertisesOnlyItsSelectedResolver() {
        val server = DnsServer("device-standard", "Device Standard", "8.8.8.8", isCustom = true)
        connect(buildDnsConnectionConfig(server, NetworkIpStack.DUAL_STACK).getOrThrow())
        val properties = connectivity.getLinkProperties(awaitVpn())!!
        assertEquals(listOf("8.8.8.8"), properties.dnsServers.map { it.hostAddress })
        // A real response, beyond a service-established event. This explicit request does
        // not establish attribution for Android's cached/system resolver; test that separately.
        assertDnsAnswer(queryUdp("8.8.8.8"))
        assertIpv6Reachable()
    }

    @Test
    fun urlOnlyGoogleDohDeliversFreshDnsThroughTun() {
        connect(customDoh("https://dns.google/dns-query"))
        assertEquals(listOf("10.0.0.1"), connectivity.getLinkProperties(awaitVpn())!!.dnsServers.map { it.hostAddress })
        assertDnsAnswer(queryUdp("10.0.0.1"))
        assertIpv6Reachable()
    }

    @Test
    fun urlOnlyAdGuardDohDeliversFreshDnsThroughTun() {
        connect(customDoh("https://dns.adguard-dns.com/dns-query"))
        awaitVpn()
        assertDnsAnswer(queryUdp("10.0.0.1"))
    }

    @Test
    fun explicitIpv4DohEndpointDeliversFreshDnsThroughTun() {
        connect(customDoh("https://dns.google/dns-query").copy(customBootstrapIp = "8.8.8.8"))
        awaitVpn()
        assertDnsAnswer(queryUdp("10.0.0.1"))
    }

    @Test
    fun explicitIpv6DohEndpointDeliversFreshDnsThroughTun() {
        connect(customDoh("https://dns.google/dns-query").copy(customBootstrapIp = "2001:4860:4860::8888"))
        awaitVpn()
        assertDnsAnswer(queryUdp("10.0.0.1"))
    }

    @Test
    fun builtInDohProvidersDeliverFreshDnsThroughTun() {
        val failures = mutableListOf<String>()
        DnsProviderData.providers.forEach { provider ->
            try {
                connect(buildDnsConnectionConfig(provider, NetworkIpStack.DUAL_STACK, DnsProtocol.DOH)
                    .getOrThrow().copy(enableExperimentalPacketLoop = true))
                awaitVpn()
                assertDnsAnswer(queryUdp("10.0.0.1"))
            } catch (failure: Exception) {
                failures += "${provider.name}: ${failure.message}"
            } catch (failure: AssertionError) {
                failures += "${provider.name}: ${failure.message}"
            } finally {
                disconnectTestVpn()
                startedVpn = false
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun wrongHostnameFailsCertificateVerificationOnDevice() = runBlocking {
        val transport = DohDnsTransport(DohEndpointResolver(context))
        val result = transport.query(dnsQuery(), "https://wrong-host.invalid/dns-query", emptyList(), 4000, "8.8.8.8")
        assertEquals(DnsTransportResult.Error(DnsSecurityMessages.UNTRUSTED_CERTIFICATE), result)
    }

    @Test
    fun nonexistentEndpointFailsDiscoveryWithoutProviderSubstitution() = runBlocking {
        val transport = DohDnsTransport(DohEndpointResolver(context))
        val result = transport.query(dnsQuery(), "https://aerodns-device-check.invalid/dns-query", emptyList(), 4000)
        assertTrue("Expected an endpoint-discovery error, got $result", result is DnsTransportResult.Error && result.message.startsWith("Unknown DoH bootstrap host:"))
    }

    private fun customDoh(url: String): DnsConnectionConfig = buildDnsConnectionConfig(
        DnsServer("device-doh", "Device DoH", "", dohUrl = url, isCustom = true),
        NetworkIpStack.DUAL_STACK, DnsProtocol.DOH
    ).getOrThrow().copy(enableExperimentalPacketLoop = true)

    private fun connect(config: DnsConnectionConfig) = runBlocking {
        val identified = config.copy(connectionRequestId = "device-${System.nanoTime()}")
        startedVpn = true
        val intent = Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_CONNECT
            putExtra(DnsVpnService.EXTRA_DNS_CONFIG, identified)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        val event = withTimeout(8000) {
            DnsVpnServiceEvents.events.first {
                when (it) {
                    is DnsVpnServiceEvent.Established -> it.config.connectionRequestId == identified.connectionRequestId
                    is DnsVpnServiceEvent.Failed -> it.config?.connectionRequestId == identified.connectionRequestId
                    is DnsVpnServiceEvent.Stopped -> false
                }
            }
        }
        assertTrue("VPN establishment failed: $event", event is DnsVpnServiceEvent.Established)
    }

    private fun isVpn(network: Network) = connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun awaitVpn(): Network {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            connectivity.allNetworks.firstOrNull(::isVpn)?.let { network ->
                if (connectivity.activeNetwork == network &&
                    connectivity.getLinkProperties(network)?.dnsServers?.isNotEmpty() == true) return network
            }
            Thread.sleep(50)
        }
        throw AssertionError("No VPN network with DNS configuration appeared")
    }

    private fun dnsQuery(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(0x4a31)
            out.writeShort(0x0100)
            out.writeShort(1)
            repeat(3) { out.writeShort(0) }
            for (label in "example.com".split('.')) { out.writeByte(label.length); out.writeBytes(label) }
            out.writeByte(0)
            out.writeShort(1)
            out.writeShort(1)
        }
    }.toByteArray()

    private fun queryUdp(address: String): ByteArray = DatagramSocket().use { socket ->
        socket.soTimeout = 8000
        // Establishment and LinkProperties can precede default-network propagation.
        // Route the acceptance probe through the VPN explicitly, rather than racing
        // a new default socket against ConnectivityService's asynchronous update.
        if (address == "10.0.0.1") awaitVpn().bindSocket(socket)
        socket.connect(InetAddress.getByName(address), 53)
        val query = dnsQuery()
        socket.send(DatagramPacket(query, query.size))
        val reply = DatagramPacket(ByteArray(4096), 4096)
        socket.receive(reply)
        reply.data.copyOf(reply.length)
    }

    private fun assertDnsAnswer(reply: ByteArray) {
        assertTrue("DNS reply too short", reply.size >= 12)
        assertEquals(0x4a, reply[0].toInt() and 255)
        assertEquals(0x31, reply[1].toInt() and 255)
        assertTrue("Not a DNS response", reply[2].toInt() and 128 != 0)
        assertEquals("Nonzero DNS error code", 0, reply[3].toInt() and 15)
        assertTrue("DNS answer section is empty", (reply[6].toInt() and 255) * 256 + (reply[7].toInt() and 255) > 0)
    }

    private fun assertIpv6Reachable() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName("2606:4700:4700::1111"), 443), 4000)
            assertTrue("IPv6 passthrough failed", socket.isConnected)
        }
    }
}
