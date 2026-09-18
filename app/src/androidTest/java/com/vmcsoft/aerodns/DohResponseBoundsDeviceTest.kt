package com.vmcsoft.aerodns

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.dns.DnsWireMessage
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsHealth
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Requires the loopback HTTPS fixture and an explicit responseTestPort instrumentation argument. */
@RunWith(AndroidJUnit4::class)
class DohResponseBoundsDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    @Test fun largeValidReplyAndBadResponsesAreIsolatedThroughTun() = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("responseTestPort")?.toIntOrNull()
        assumeTrue("Start the loopback fixture and pass responseTestPort", port != null && port in 1024..65535)
        assertNull("Accept VPN consent first", VpnService.prepare(context))
        assertFalse("Disconnect other VPNs first", connectivity.allNetworks.any(::isVpn))
        val config = DnsConnectionConfig("response-fixture", "Local response fixture", DnsProtocol.DOH,
            emptyList(), "https://localhost:$port/dns-query", "127.0.0.1", true,
            connectionRequestId = "bounds-${System.nanoTime()}", enableExperimentalPacketLoop = true)
        try {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_CONNECT
                putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            val event = withTimeout(8000) {
                DnsVpnServiceEvents.events.first {
                    it is DnsVpnServiceEvent.Established && it.config.connectionRequestId == config.connectionRequestId ||
                        it is DnsVpnServiceEvent.Failed && it.config?.connectionRequestId == config.connectionRequestId
                }
            }
            assertTrue(event.toString(), event is DnsVpnServiceEvent.Established)
            // This case measures response bounds, not first-packet route installation.
            // Interface visibility can precede DNS readiness; use the app's health contract.
            val ready = withTimeout(8000) {
                DnsVpnServiceEvents.events.first {
                    it is DnsVpnServiceEvent.Established && it.config == config && it.health != DnsHealth.Checking
                }
            } as DnsVpnServiceEvent.Established
            assertTrue("Fixture must be healthy before response-size probes: ${ready.health}", ready.health is DnsHealth.Healthy)
            val network = awaitVpn()
            assertEquals(4096, query(network, "medium", 8000).size)
            // Some kernels reject a near-64 KiB datagram with ENOBUFS. Either delivery
            // or a per-query drop is allowed; tearing down the VPN is never allowed.
            val largest = runCatching { query(network, "large", 1500) }
            if (largest.isSuccess) assertEquals(65_507, largest.getOrThrow().size)
            else assertTrue(largest.exceptionOrNull() is SocketTimeoutException)
            assertEquals(128, query(network, "small", 8000).size)
            for (name in listOf("overflow", "chunked", "short", "mismatch", "html")) {
                val failure = runCatching { query(network, name, 1500) }.exceptionOrNull()
                assertTrue("Expected a dropped $name response, got $failure", failure is SocketTimeoutException)
                assertEquals("Valid query after $name", 128, query(network, "small", 8000).size)
                assertTrue("VPN stopped after $name", isVpn(network))
                assertTrue(DnsVpnServiceEvents.events.replayCache.last() is DnsVpnServiceEvent.Established)
            }
        } finally {
            val previous = DnsVpnServiceEvents.events.replayCache.lastOrNull()
            context.startService(Intent(context, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_DISCONNECT })
            withTimeout(5000) {
                DnsVpnServiceEvents.events.first {
                    it is DnsVpnServiceEvent.Stopped && it !== previous
                }
            }
            // ConnectivityService removes its network asynchronously after the service
            // closes TUN and emits Stopped; do not leave the next test racing that removal.
            val deadline = System.nanoTime() + 5_000_000_000L
            while (connectivity.allNetworks.any(::isVpn) && System.nanoTime() < deadline) Thread.sleep(50)
            assertFalse("VPN network remained after disconnect", connectivity.allNetworks.any(::isVpn))
            Thread.sleep(150)
        }
    }

    private fun query(network: Network, label: String, timeout: Int): ByteArray = DatagramSocket().use { socket ->
        network.bindSocket(socket)
        socket.soTimeout = timeout
        socket.connect(InetAddress.getByName("10.0.0.1"), 53)
        val id = System.nanoTime().toInt() and 0xffff
        val query = DnsWireMessage.buildAQuery(id, "$label.boundary.test").apply { this[size - 3] = 16 } // TXT
        socket.send(DatagramPacket(query, query.size))
        val reply = DatagramPacket(ByteArray(65_535), 65_535)
        socket.receive(reply)
        assertTrue(DnsWireMessage.isSuccessfulResponse(reply.data, reply.length, id))
        reply.data.copyOf(reply.length)
    }

    private fun isVpn(network: Network) = connectivity.getNetworkCapabilities(network)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun awaitVpn(): Network {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            connectivity.activeNetwork?.takeIf(::isVpn)?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("VPN network did not become ready")
    }
}
