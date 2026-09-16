package com.vmcsoft.aerodns

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.VpnRecoveryService
import com.vmcsoft.aerodns.validation.ValidationComponentFactory
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnLifecycleDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private val recovery get() = context.getSharedPreferences("vpn_recovery", Context.MODE_PRIVATE)

    @Before
    fun prepare() {
        assertNull("Accept VPN consent first", VpnService.prepare(context))
        assertFalse("Disconnect the existing VPN before testing", hasVpn())
        recovery.edit().clear().commit()
    }

    @After
    fun cleanup() {
        command(DnsVpnService.ACTION_DISCONNECT)
        awaitNoVpn()
        recovery.edit().clear().commit()
    }

    @Test
    fun emptySystemStartStopsWithoutCreatingVpn() {
        val result = command(null)
        assertTrue(result is DnsVpnServiceEvent.Stopped)
        assertFalse(hasVpn())
    }

    @Test
    fun unknownCommandStopsAnIdleService() {
        assertTrue(command("invalid-command") is DnsVpnServiceEvent.Stopped)
        assertFalse(hasVpn())
    }

    @Test
    fun missingConnectConfigPublishesFailureInsteadOfTimeout() {
        assertTrue(command(DnsVpnService.ACTION_CONNECT) is DnsVpnServiceEvent.Failed)
        assertFalse(hasVpn())
    }

    @Test
    fun newServiceRestoresPersistedDohSettings() {
        val config = standard().copy(
            protocol = DnsProtocol.DOH, upstreamAddresses = emptyList(),
            dohUrl = "https://dns.google/dns-query", customBootstrapIp = "8.8.8.8",
            enableExperimentalPacketLoop = true
        )
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, config))
        // The same durable handoff used by network reconnection. stopService() alone
        // cannot destroy a VPN that Android still binds; this is not a process-kill test.
        assertTrue(command(DnsVpnService.ACTION_DISCONNECT, preserveRecovery = true) is DnsVpnServiceEvent.Stopped)
        awaitNoVpn()
        val restored = assertEstablished(command(VpnService.SERVICE_INTERFACE))
        assertEquals(config.copy(connectionRequestId = ""), restored.copy(connectionRequestId = ""))
        assertNotEquals(config.connectionRequestId, restored.connectionRequestId)
    }

    @Test
    fun explicitDisconnectPreventsSubsequentSystemRestoration() {
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, standard()))
        assertTrue(command(DnsVpnService.ACTION_DISCONNECT) is DnsVpnServiceEvent.Stopped)
        awaitNoVpn()
        assertTrue(command(null) is DnsVpnServiceEvent.Stopped)
        assertFalse(hasVpn())
        assertTrue(recovery.all.isEmpty())
    }

    @Test
    fun corruptRecoveryStopsWithoutFallback() {
        recovery.edit().putInt("schema", 1).putString("protocol", "FUTURE").commit()
        assertTrue(command(VpnService.SERVICE_INTERFACE) is DnsVpnServiceEvent.Stopped)
        assertTrue(recovery.all.isEmpty())
        assertFalse(hasVpn())
    }

    @Test
    fun secondConnectActuallyReplacesTheActiveResolver() {
        val original = standard()
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, original))
        awaitDns("8.8.8.8")
        val replacement = original.copy(serverId = "replacement", displayName = "Replacement",
            upstreamAddresses = listOf("9.9.9.9"), connectionRequestId = "replacement-${System.nanoTime()}")
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, replacement))
        awaitDns("9.9.9.9")
    }

    @Test
    fun invalidReplacementClearsConnectionAndRecovery() {
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, standard()))
        val failed = command(DnsVpnService.ACTION_CONNECT, standard().copy(upstreamAddresses = emptyList()))
        assertTrue(failed is DnsVpnServiceEvent.Failed)
        awaitNoVpn()
        assertTrue(recovery.all.isEmpty())
        assertTrue(command(null) is DnsVpnServiceEvent.Stopped)
    }

    @Test
    fun delayedTimeoutCleanupCannotDisconnectAReplacement() {
        val original = standard()
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, original))
        val replacement = standard().copy(upstreamAddresses = listOf("9.9.9.9"))
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, replacement))
        context.startService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_DISCONNECT
            putExtra(DnsVpnService.EXTRA_DISCONNECT_REQUEST_ID, original.connectionRequestId)
        })
        // This later start is processed after the stale disconnect command.
        assertEquals(replacement, assertEstablished(command(null)))
        awaitDns("9.9.9.9")
        assertEquals(replacement.connectionRequestId, recovery.getString("requestId", null))
    }

    @Test
    fun queuedConnectSurvivesThePreviousDisconnect() = runBlocking {
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, standard()))
        repeat(10) {
            val replacement = standard()
            // Queue both commands while the app main thread is occupied. The stop
            // must honor its startId instead of taking down the pending start.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                context.startService(Intent(context, DnsVpnService::class.java).apply {
                    action = DnsVpnService.ACTION_DISCONNECT
                })
                val intent = Intent(context, DnsVpnService::class.java).apply {
                    action = DnsVpnService.ACTION_CONNECT
                    putExtra(DnsVpnService.EXTRA_DNS_CONFIG, replacement)
                }
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
            withTimeout(5000) { DnsVpnServiceEvents.events.first {
                it is DnsVpnServiceEvent.Established && it.config == replacement
            } }
            kotlinx.coroutines.delay(250)
            awaitDns("8.8.8.8")
            assertEquals(replacement, (DnsVpnServiceEvents.events.replayCache.last() as DnsVpnServiceEvent.Established).config)
        }
    }

    @Test
    fun delayedCompanionStartCannotRestoreAnExplicitDisconnect() {
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, standard()))
        awaitRecoveryService(true)
        assertTrue(command(DnsVpnService.ACTION_DISCONNECT) is DnsVpnServiceEvent.Stopped)
        awaitNoVpn()
        context.startService(Intent(context, VpnRecoveryService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
        awaitRecoveryService(false)
        assertFalse(hasVpn())
        assertTrue(recovery.all.isEmpty())
    }

    @Test
    fun delayedCompanionStartDoesNotEndAnIntentionalPause() {
        val config = standard()
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, config))
        assertTrue(command(DnsVpnService.ACTION_DISCONNECT, preserveRecovery = true) is DnsVpnServiceEvent.Stopped)
        awaitNoVpn()
        context.startService(Intent(context, VpnRecoveryService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
        awaitRecoveryService(false)
        assertFalse(hasVpn())
        assertEquals(config.connectionRequestId, recovery.getString("requestId", null))
    }

    @Test
    fun companionDoesNotReannounceAnActiveConnection() = runBlocking {
        val config = standard()
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, config))
        val settled = withTimeout(8000) { DnsVpnServiceEvents.events.first {
            it is DnsVpnServiceEvent.Established && it.config == config &&
                it.health != com.vmcsoft.aerodns.domain.model.DnsHealth.Checking
        } }
        context.startService(Intent(context, VpnRecoveryService::class.java))
        kotlinx.coroutines.delay(500)
        assertSame("Tracking a live connection must not resend its startup/status", settled,
            DnsVpnServiceEvents.events.replayCache.last())
        awaitRecoveryService(true)
    }

    @Test
    fun companionUsesTheLatestConfigurationWithoutReplacingItsRequest() {
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, standard()))
        val latest = standard().copy(serverId = "latest", upstreamAddresses = listOf("9.9.9.9"))
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, latest))
        context.startService(Intent(context, VpnRecoveryService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
        awaitRecoveryService(true)
        assertEquals(latest.connectionRequestId, recovery.getString("requestId", null))
        assertEquals(latest, (DnsVpnServiceEvents.events.replayCache.last() as DnsVpnServiceEvent.Established).config)
        awaitDns("9.9.9.9")
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 28)
    fun revocationRetiresTheCompanionAndRecoveryIntent() {
        assertEstablished(command(DnsVpnService.ACTION_CONNECT, standard()))
        awaitRecoveryService(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            requireNotNull(ValidationComponentFactory.vpn.get()).onRevoke()
        }
        awaitNoVpn()
        assertTrue(recovery.all.isEmpty())
    }

    @Test
    fun corruptCompanionRecoveryStopsWithoutStartingVpn() {
        recovery.edit().putInt("schema", 1).putString("protocol", "FUTURE").commit()
        context.startService(Intent(context, VpnRecoveryService::class.java))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (recovery.all.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(recovery.all.isEmpty())
        awaitRecoveryService(false)
        assertFalse(hasVpn())
    }

    @Suppress("DEPRECATION") // Android exposes this app's own services to its test process.
    private fun awaitRecoveryService(running: Boolean) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val deadline = System.nanoTime() + 5_000_000_000L
        fun present() = manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == VpnRecoveryService::class.java.name && it.started
        }
        while (present() != running && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals("Unexpected recovery companion lifetime", running, present())
    }

    private fun standard() = DnsConnectionConfig("lifecycle-test", "Lifecycle test", DnsProtocol.STANDARD,
        listOf("8.8.8.8"), connectionRequestId = "test-${System.nanoTime()}")

    private fun command(
        action: String?,
        config: DnsConnectionConfig? = null,
        preserveRecovery: Boolean = false
    ): DnsVpnServiceEvent = runBlocking {
        val previous = DnsVpnServiceEvents.events.replayCache.lastOrNull()
        val intent = Intent(context, DnsVpnService::class.java).apply {
            this.action = action
            putExtra(DnsVpnService.EXTRA_APP_START, true)
            if (config != null) putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
            if (preserveRecovery) putExtra(DnsVpnService.EXTRA_PRESERVE_RECOVERY, true)
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        // Identity, not equality: a later Stopped(null) is still a new terminal event.
        withTimeout(3000) { DnsVpnServiceEvents.events.first {
            it !== previous && when {
                action == DnsVpnService.ACTION_DISCONNECT -> it is DnsVpnServiceEvent.Stopped
                action == DnsVpnService.ACTION_CONNECT -> when (it) {
                    is DnsVpnServiceEvent.Established -> it.config == config
                    is DnsVpnServiceEvent.Failed -> it.config == config
                    is DnsVpnServiceEvent.Stopped -> false
                }
                else -> true
            }
        } }
    }

    private fun assertEstablished(event: DnsVpnServiceEvent): DnsConnectionConfig {
        assertTrue("Expected establishment, got $event", event is DnsVpnServiceEvent.Established)
        return (event as DnsVpnServiceEvent.Established).config
    }

    private fun hasVpn() = connectivity.allNetworks.any {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun awaitNoVpn() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (hasVpn() && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("VPN interface not removed", hasVpn())
        // Let Service.onDestroy finish before starting a new instance.
        Thread.sleep(150)
        awaitRecoveryService(false)
    }

    private fun awaitDns(address: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val network = connectivity.activeNetwork
            if (network != null && connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                connectivity.getLinkProperties(network)?.dnsServers?.map { it.hostAddress } == listOf(address)) return
            Thread.sleep(50)
        }
        fail("Selected resolver was not advertised: $address")
    }
}
