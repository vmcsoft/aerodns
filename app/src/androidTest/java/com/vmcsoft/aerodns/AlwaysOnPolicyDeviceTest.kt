package com.vmcsoft.aerodns

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.*
import com.vmcsoft.aerodns.domain.model.*
import com.vmcsoft.aerodns.presentation.MainActivity
import com.vmcsoft.aerodns.presentation.tile.DnsTileService
import com.vmcsoft.aerodns.validation.ValidationComponentFactory
import com.vmcsoft.aerodns.validation.ValidationRepositories
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately with actual Android Always-on enabled; optionally policyLockdown=true. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AlwaysOnPolicyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val repository get() = EntryPointAccessors.fromApplication(context, ValidationRepositories::class.java).vpnRepository()
    private val component get() = ComponentName(context, DnsTileService::class.java).flattenToString()
    private var addedTile = false

    @After fun cleanup() {
        // Normal disconnect is deliberately forbidden. Retire the fixture through the
        // real revocation callback; the host restores the actual system settings.
        instrumentation.runOnMainSync { ValidationComponentFactory.vpn.get()?.onRevoke() }
        await { !hasVpn() && notifications.activeNotifications.none { it.id == 1001 } }
        shell("cmd statusbar collapse")
        if (addedTile) shell("cmd statusbar remove-tile $component")
    }

    @Test fun systemPolicyProtectsConnectionAndExplainsControls() = runBlocking {
        assertNull(VpnService.prepare(context))
        assertTrue(notifications.areNotificationsEnabled())
        val lockdown = InstrumentationRegistry.getArguments().getString("policyLockdown") == "true"
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("responseTestPort")).toInt()
        delay(1000)
        val config = DnsConnectionConfig("policy-fixture", "Policy resolver", DnsProtocol.DOH,
            emptyList(), "https://localhost:$port/health-ok/policy", "127.0.0.1", true,
            connectionRequestId = "policy-${System.nanoTime()}", enableExperimentalPacketLoop = true)
        connect(config)
        val service = requireNotNull(ValidationComponentFactory.vpn.get())
        assertTrue("Enable Always-on in Android VPN settings first", service.isAlwaysOn)
        assertEquals(lockdown, service.isLockdownEnabled)
        val state = withTimeout(8000) { repository.connectionState.first {
            it is ConnectionState.Connected && it.activeConfig == config && it.dnsHealth is DnsHealth.Healthy
        } } as ConnectionState.Connected
        assertEquals(VpnControlPolicy(true, lockdown), state.controlPolicy)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Connect/Disconnect").assertIsNotEnabled()
        compose.onNodeWithText("Speed Test").assertIsNotEnabled()
        compose.onNodeWithText("VPN settings").assertIsDisplayed()
        if (lockdown) compose.onNodeWithText("Android is blocking traffic").assertIsDisplayed()

        val previous = DnsVpnServiceEvents.events.replayCache.last()
        context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_DISCONNECT))
        withTimeout(5000) { DnsVpnServiceEvents.events.first { it !== previous && it is DnsVpnServiceEvent.Established } }
        repository.disconnect()
        assertTrue(runCatching { repository.pauseForSpeedTest() }.exceptionOrNull() is IllegalStateException)
        assertEquals(config, (repository.connectionState.value as ConnectionState.Connected).activeConfig)
        assertTrue(hasVpn())

        // Direct replacement must not emit Stopped while Android owns the connection.
        val events = mutableListOf<DnsVpnServiceEvent>()
        val collector = launch(Dispatchers.Unconfined) { DnsVpnServiceEvents.events.collect { events += it } }
        val server = DnsServer("policy-replacement", "Replacement policy resolver", "192.0.2.53",
            dohUrl = config.dohUrl, customBootstrapIp = "127.0.0.1", allowUntrustedCertificates = true,
            supportedProtocols = listOf(DnsProtocol.DOH), isCustom = true)
        repository.connect(server)
        val replaced = withTimeout(8000) { repository.connectionState.first {
            it is ConnectionState.Connected && it.server.id == server.id && it.dnsHealth is DnsHealth.Healthy
        } } as ConnectionState.Connected
        val replacement = requireNotNull(replaced.activeConfig)
        assertEquals(VpnControlPolicy(true, lockdown), replaced.controlPolicy)
        // Rejecting a malformed next profile must retain the real active snapshot.
        repository.connect(server.copy(id = "invalid-policy", dohUrl = null))
        assertEquals(replacement, (repository.connectionState.value as ConnectionState.Connected).activeConfig)
        collector.cancelAndJoin()
        assertFalse(events.any { it is DnsVpnServiceEvent.Stopped })
        await {
            notifications.activeNotifications.any { it.id == 1001 &&
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == replacement.statusDescription(state.dnsHealth, VpnControlPolicy(true, lockdown)) &&
                it.notification.actions?.map { action -> action.title.toString() } == listOf("VPN settings") }
        }
        val tiles = shell("settings get secure sysui_qs_tiles")
        addedTile = !tiles.contains(component)
        if (addedTile) shell("cmd statusbar add-tile $component")
        shell("cmd statusbar expand-settings")
        await { ValidationComponentFactory.tile.get()?.qsTile?.contentDescription?.toString() == replacement.statusDescription(state.dnsHealth, VpnControlPolicy(true, lockdown)) }
        assertEquals("Always-on DNS", ValidationComponentFactory.tile.get()?.qsTile?.label?.toString())
        shell("cmd statusbar click-tile $component")
        await { shell("dumpsys activity activities").lineSequence().any { it.contains("ResumedActivity") && it.contains("com.android.settings/") } }
        assertTrue(hasVpn())
        assertEquals(replacement, (repository.connectionState.value as ConnectionState.Connected).activeConfig)
    }

    private suspend fun connect(config: DnsConnectionConfig) {
        context.startForegroundService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_CONNECT
            putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
        })
        withTimeout(8000) { DnsVpnServiceEvents.events.first { it is DnsVpnServiceEvent.Established && it.config == config } }
    }
    private fun hasVpn(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
    private fun await(predicate: () -> Boolean) = runBlocking { withTimeout(12000) { while (!predicate()) delay(50) } }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
