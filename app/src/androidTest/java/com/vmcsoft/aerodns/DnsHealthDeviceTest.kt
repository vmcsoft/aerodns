package com.vmcsoft.aerodns

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
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
