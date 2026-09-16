package com.vmcsoft.aerodns

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import com.vmcsoft.aerodns.validation.ValidationComponentFactory
import com.vmcsoft.aerodns.validation.ValidationRepositories
import dagger.hilt.android.EntryPointAccessors
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.data.dns.DohDnsTransport
import com.vmcsoft.aerodns.data.dns.DohEndpointResolver
import com.vmcsoft.aerodns.data.dns.DnsWireMessage
import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.DnsHealth
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.statusDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class DnsHealthDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Before fun prepare() {
        assertNull("Accept VPN consent first", VpnService.prepare(context))
        assertFalse("Disconnect other VPNs first", hasVpn())
    }
    @After fun cleanup() = runBlocking { disconnect() }

    @Test fun standardConnectionChecksItsAdvertisedResolver() = runBlocking {
        val config = DnsConnectionConfig("health-standard", "Health Standard", DnsProtocol.STANDARD,
            listOf("8.8.8.8"), connectionRequestId = "health-${System.nanoTime()}")
        connect(config)
        val health = awaitHealth(config)
        assertTrue("Expected live Standard DNS answer: $health", health is DnsHealth.Healthy)
    }

    @Test fun dohHealthFailureAndRecoveryKeepTheSameInterface() = runBlocking {
        val config = fixture("health-cycle")
        connect(config)
        val healthy = awaitHealth(config)
        assertTrue(healthy.toString(), healthy is DnsHealth.Healthy)
        val network = connectivity.allNetworks.first { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val failed = awaitHealth(config, healthy, 38_000)
        assertTrue(failed.toString(), failed is DnsHealth.Unhealthy)
        val recovered = awaitHealth(config, failed, 38_000)
        assertTrue(recovered.toString(), recovered is DnsHealth.Healthy)
        assertTrue("Health checks must not rebuild or switch the VPN", network in connectivity.allNetworks)
    }

    @Test fun failingResolverRemainsSelectedWithoutFallback() = runBlocking {
        val config = fixture("health-fail")
        connect(config)
        val failed = awaitHealth(config)
        assertTrue(failed.toString(), failed is DnsHealth.Unhealthy)
        assertTrue(hasVpn())
        assertEquals(config.connectionRequestId, context.getSharedPreferences("vpn_recovery", Context.MODE_PRIVATE).getString("requestId", null))
    }

    @Test fun replacedAndDisconnectedProbesCannotPublishLateResults() = runBlocking {
        val old = fixture("health-slow")
        connect(old)
        delay(500)
        assertEquals(DnsHealth.Checking, (DnsVpnServiceEvents.events.replayCache.last() as DnsVpnServiceEvent.Established).health)
        val replacement = fixture("health-ok")
        connect(replacement)
        assertTrue(awaitHealth(replacement) is DnsHealth.Healthy)
        delay(5_500)
        assertEquals(replacement, (DnsVpnServiceEvents.events.replayCache.last() as DnsVpnServiceEvent.Established).config)
        disconnect()
        val terminal = DnsVpnServiceEvents.events.replayCache.last()
        delay(5_500)
        assertSame(terminal, DnsVpnServiceEvents.events.replayCache.last())
        assertFalse(hasVpn())
    }

    @Test @SdkSuppress(minSdkVersion = 28)
    fun speedTestPauseRestoresActualConfigurationOnlyOnce() = runBlocking {
        val repository = EntryPointAccessors.fromApplication(context, ValidationRepositories::class.java).vpnRepository()
        delay(1000)
        val config = fixture("health-ok")
        connect(config)
        withTimeout(8000) { repository.connectionState.first { it is ConnectionState.Connected && it.dnsHealth is DnsHealth.Healthy } }
        val token = requireNotNull(repository.pauseForSpeedTest())
        repository.restoreAfterSpeedTest(token)
        val restored = withTimeout(8000) { repository.connectionState.first {
            it is ConnectionState.Connected && it.dnsHealth is DnsHealth.Healthy
        } } as ConnectionState.Connected
        assertEquals(config.copy(connectionRequestId = ""), restored.activeConfig!!.copy(connectionRequestId = ""))
        assertNotEquals(config.connectionRequestId, requireNotNull(restored.activeConfig).connectionRequestId)
        repository.restoreAfterSpeedTest(token)
        delay(300)
        assertEquals(restored.activeConfig, (repository.connectionState.value as ConnectionState.Connected).activeConfig)
    }

    @Test @SdkSuppress(minSdkVersion = 28)
    fun newerRepositoryConnectionWinsOverSpeedTestCleanup() = runBlocking {
        val repository = EntryPointAccessors.fromApplication(context, ValidationRepositories::class.java).vpnRepository()
        delay(1000)
        connect(fixture("health-ok"))
        withTimeout(8000) { repository.connectionState.first { it is ConnectionState.Connected && it.dnsHealth is DnsHealth.Healthy } }
        val token = requireNotNull(repository.pauseForSpeedTest())
        val replacement = fixture("health-ok")
        val server = DnsServer("speed-choice", "Speed test choice", "", dohUrl = replacement.dohUrl,
            customBootstrapIp = "127.0.0.1", allowUntrustedCertificates = true,
            supportedProtocols = listOf(DnsProtocol.DOH), isCustom = true)
        repository.connect(server)
        val chosen = withTimeout(8000) { repository.connectionState.first {
            it is ConnectionState.Connected && it.server.id == server.id && it.dnsHealth is DnsHealth.Healthy
        } } as ConnectionState.Connected
        repository.restoreAfterSpeedTest(token)
        delay(300)
        assertEquals(chosen.activeConfig, (repository.connectionState.value as ConnectionState.Connected).activeConfig)
    }

    @Test @SdkSuppress(minSdkVersion = 28)
    fun explicitDisconnectDuringSpeedTestPauseRemainsDisconnected() = runBlocking {
        val repository = EntryPointAccessors.fromApplication(context, ValidationRepositories::class.java).vpnRepository()
        delay(1000)
        connect(fixture("health-ok"))
        withTimeout(8000) { repository.connectionState.first { it is ConnectionState.Connected && it.dnsHealth is DnsHealth.Healthy } }
        val token = requireNotNull(repository.pauseForSpeedTest())
        repository.disconnect()
        repository.restoreAfterSpeedTest(token)
        withTimeout(5000) { while (hasVpn()) delay(50) }
        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
    }

    @Test fun fastQueryFinishesWhileAnEarlierQueryIsStillWaitingThroughTun() = runBlocking {
        val config = fixture("health-ok")
        connect(config)
        assertTrue(awaitHealth(config) is DnsHealth.Healthy)
        val network = requireNotNull(connectivity.activeNetwork)
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetAddress.getByName("10.0.0.1"), 53)
            val slow = DnsWireMessage.buildAQuery(101, "slow.fixture.test")
            socket.send(DatagramPacket(slow, slow.size))
            val transport = DohDnsTransport(DohEndpointResolver(context))
            withTimeout(2000) {
                while (true) {
                    val result = transport.query(DnsWireMessage.buildAQuery(42, "inflight.fixture.test"),
                        requireNotNull(config.dohUrl), emptyList(), 1000, "127.0.0.1", true)
                    if (result is DnsTransportResult.Success && result.payload.last().toInt() > 0) break
                    delay(20)
                }
            }
            val fast = DnsWireMessage.buildAQuery(102, "fast.fixture.test")
            val start = System.nanoTime()
            socket.send(DatagramPacket(fast, fast.size))
            socket.soTimeout = 2000 // Slow fixture takes four seconds.
            val reply = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(reply)
            assertTrue("Fast reply must arrive first", DnsWireMessage.isSuccessfulResponse(reply.data, reply.length, 102))
            println("Concurrent fast query latencyMs=${(System.nanoTime() - start) / 1_000_000}")
            socket.soTimeout = 5000
            reply.length = reply.data.size
            socket.receive(reply)
            assertTrue("Slow reply must retain its own identity", DnsWireMessage.isSuccessfulResponse(reply.data, reply.length, 101))
        }
    }

    @Test fun rapidReplacementsEachPublishHealthyAsTheirFirstResult() = runBlocking {
        repeat(6) { index ->
            val config = fixture("health-ok").copy(displayName = "Rapid replacement $index")
            connect(config)
            val health = awaitHealth(config)
            assertTrue("Replacement $index first result: $health", health is DnsHealth.Healthy)
        }
    }

    @Test fun droppedFirstProbeRetriesWithinTheInitialHealthCheck() = runBlocking {
        val config = fixture("health-drop-first")
        connect(config)
        val health = awaitHealth(config)
        assertTrue("First result must recover the dropped query: $health", health is DnsHealth.Healthy)
        assertEquals(2, fixtureQueryCount(config))
    }

    @Test fun silentResolverHasOnlyOneRetryAndStillReportsUnhealthy() = runBlocking {
        val config = fixture("health-drop-all")
        connect(config)
        val health = awaitHealth(config)
        assertTrue(health.toString(), health is DnsHealth.Unhealthy)
        assertEquals(2, fixtureQueryCount(config))
        assertTrue(hasVpn())
    }

    @Test fun notificationMatchesCheckingHealthyAndFailedServiceSnapshots() = runBlocking {
        assumeTrue("Enable notifications for the validation app to verify notification delivery", notifications.areNotificationsEnabled())
        val config = fixture("health-slow")
        connect(config)
        assertNotification(config, DnsHealth.Checking)
        val healthy = awaitHealth(config)
        assertTrue(healthy.toString(), healthy is DnsHealth.Healthy)
        assertNotification(config, healthy)
        val failing = fixture("health-fail")
        connect(failing)
        val failed = awaitHealth(failing)
        assertTrue(failed.toString(), failed is DnsHealth.Unhealthy)
        assertNotification(failing, failed)
    }

    @Test @SdkSuppress(minSdkVersion = 28)
    fun liveDescriptorFailureClearsServiceAndRepositoryWithoutAnActivity() = runBlocking {
        val repository = EntryPointAccessors.fromApplication(context, ValidationRepositories::class.java).vpnRepository()
        delay(1000) // Initial physical-network callbacks must settle before connection.
        val config = fixture("health-slow")
        connect(config)
        val transport = DohDnsTransport(DohEndpointResolver(context))
        withTimeout(5000) {
            while (true) {
                val response = transport.query(DnsWireMessage.buildAQuery(42, "inflight.fixture.test"),
                    requireNotNull(config.dohUrl), emptyList(), 1000, "127.0.0.1", true)
                if (response is DnsTransportResult.Success && response.payload.last().toInt() > 0) break
                delay(50)
            }
        }
        val service = requireNotNull(ValidationComponentFactory.vpn.get())
        val field = DnsVpnService::class.java.getDeclaredField("vpnInterface").apply { isAccessible = true }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            (field.get(service) as ParcelFileDescriptor).close()
        }
        val failed = withTimeout(8000) { DnsVpnServiceEvents.events.first {
            it is DnsVpnServiceEvent.Failed && it.config == config
        } } as DnsVpnServiceEvent.Failed
        assertTrue(failed.message, failed.message.startsWith("DNS forwarding"))
        withTimeout(5000) { repository.connectionState.first { it is ConnectionState.Error } }
        withTimeout(5000) {
            while (hasVpn() || notifications.activeNotifications.any { it.id == 1001 }) delay(50)
        }
        assertTrue(context.getSharedPreferences("vpn_recovery", Context.MODE_PRIVATE).all.isEmpty())
    }

    private suspend fun fixtureQueryCount(config: DnsConnectionConfig): Int {
        val result = DohDnsTransport(DohEndpointResolver(context)).query(
            DnsWireMessage.buildAQuery(42, "count.fixture.test"), requireNotNull(config.dohUrl),
            emptyList(), 1000, "127.0.0.1", true
        )
        assertTrue(result.toString(), result is DnsTransportResult.Success)
        return (result as DnsTransportResult.Success).payload.last().toInt() and 0xff
    }

    private fun fixture(path: String): DnsConnectionConfig {
        val port = InstrumentationRegistry.getArguments().getString("responseTestPort")?.toIntOrNull()
        assumeTrue("Start the loopback HTTPS fixture and pass responseTestPort", port != null && port in 1024..65535)
        return DnsConnectionConfig("health-fixture", "Health DoH", DnsProtocol.DOH, emptyList(),
            "https://localhost:$port/$path/${System.nanoTime()}", "127.0.0.1", true,
            connectionRequestId = "health-${System.nanoTime()}", enableExperimentalPacketLoop = true)
    }
    private suspend fun connect(config: DnsConnectionConfig) {
        val intent = Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_CONNECT
            putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        withTimeout(8000) { DnsVpnServiceEvents.events.first { it is DnsVpnServiceEvent.Established && it.config == config } }
    }
    private suspend fun awaitHealth(config: DnsConnectionConfig, previous: DnsHealth = DnsHealth.Checking, timeout: Long = 8000): DnsHealth {
        val event = withTimeout(timeout) { DnsVpnServiceEvents.events.first {
            it is DnsVpnServiceEvent.Established && it.config == config && it.health != DnsHealth.Checking && it.health != previous
        } } as DnsVpnServiceEvent.Established
        return event.health
    }
    private suspend fun assertNotification(config: DnsConnectionConfig, health: DnsHealth) {
        // NotificationManager updates its snapshot asynchronously.
        withTimeout(3000) {
            while (notifications.activeNotifications.none { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == config.statusDescription(health) }) delay(50)
        }
    }
    private suspend fun disconnect() {
        val previous = DnsVpnServiceEvents.events.replayCache.lastOrNull()
        context.startService(Intent(context, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_DISCONNECT })
        withTimeout(5000) { DnsVpnServiceEvents.events.first { it is DnsVpnServiceEvent.Stopped && it !== previous } }
        withTimeout(5000) { while (hasVpn()) delay(50) }
        delay(150)
    }
    private fun hasVpn() = connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
}
