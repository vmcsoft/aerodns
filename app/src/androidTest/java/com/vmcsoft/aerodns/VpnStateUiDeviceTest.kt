package com.vmcsoft.aerodns

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.dns.DohDnsTransport
import com.vmcsoft.aerodns.data.dns.DohEndpointResolver
import com.vmcsoft.aerodns.data.dns.DnsWireMessage
import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsHealth
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.SpeedTestResult
import com.vmcsoft.aerodns.presentation.dashboard.DashboardViewModel
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.statusDescription
import com.vmcsoft.aerodns.presentation.MainActivity
import com.vmcsoft.aerodns.presentation.tile.DnsTileService
import com.vmcsoft.aerodns.validation.ValidationRepositories
import com.vmcsoft.aerodns.validation.ValidationComponentFactory
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real activity, repository, service, notification records and SystemUI tile on a prepared debug device. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class VpnStateUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val repository get() = EntryPointAccessors.fromApplication(context, ValidationRepositories::class.java).vpnRepository()
    private val component get() = ComponentName(context, DnsTileService::class.java).flattenToString()
    private var addedTile = false

    @Before fun prepare() {
        assertNull("Prepare VPN consent first", VpnService.prepare(context))
        assertFalse("Disconnect other VPNs first", hasVpn())
        assertTrue("Enable validation-app notifications", notifications.areNotificationsEnabled())
        // Let initial underlay callbacks and profile loading settle before starting the VPN.
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.waitForIdle()
        runBlocking { delay(1000) }
    }

    @After fun cleanup() {
        try { disconnect() } finally {
            compose.activityRule.scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            shell("cmd statusbar collapse")
            if (addedTile) shell("cmd statusbar remove-tile $component")
        }
    }

    @Test fun activatingSpeedTestResultConnectsWithItsMeasuredProtocol() {
        val config = fixture("health-ok")
        val server = DnsServer("measured-doh", "Measured HTTPS resolver", "192.0.2.53",
            dohUrl = config.dohUrl, customBootstrapIp = "127.0.0.1", allowUntrustedCertificates = true,
            isCustom = true, supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH))
        compose.activityRule.scenario.onActivity { activity ->
            val viewModel = ViewModelProvider(activity)[DashboardViewModel::class.java]
            viewModel.onDnsProtocolSelected(DnsProtocol.STANDARD)
            viewModel.onSpeedTestResultSelected(SpeedTestResult(server, 20, true, sampleCount = 10,
                testedProtocol = DnsProtocol.DOH))
        }
        waitState { it is ConnectionState.Connected && it.server.id == server.id && it.dnsHealth is DnsHealth.Healthy }
        val active = (repository.connectionState.value as ConnectionState.Connected).activeConfig!!
        assertEquals(DnsProtocol.DOH, active.protocol)
        assertEquals(server.dohUrl, active.dohUrl)
        assertEquals("127.0.0.1", active.customBootstrapIp)
        assertTrue(active.allowUntrustedCertificates)
        assertDashboard("Connected")
        compose.onNode(hasText("DoH") and hasAnySibling(hasText("Protocol")), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test fun dashboardAndTileUseActiveConfigurationAcrossRecreationAndDisconnect() {
        val config = fixture("health-ok")
        connect(config)
        val healthy = health(config)
        assertTrue(healthy.toString(), healthy is DnsHealth.Healthy)
        assertDashboard("Connected", config.displayName)
        compose.onNode(hasText("DoH") and hasAnySibling(hasText("Protocol")), useUnmergedTree = true)
            .assertIsDisplayed()
        assertNotification(config, healthy)
        screenshot("healthy-dashboard")
        compose.activityRule.scenario.recreate()
        assertDashboard("Connected", config.displayName)
        showTile()
        assertTile(config.statusDescription(healthy))
        shell("cmd statusbar click-tile $component")
        waitState { it is ConnectionState.Disconnected }
        await { !hasVpn() }
        await { tileDescription() == "Tap to connect" }
        shell("cmd statusbar collapse")
        assertDashboard("Disconnected")
        // Exercise the app's touch handler, rather than calling the view model directly.
        compose.onNodeWithContentDescription("Connect/Disconnect").performTouchInput { click() }
        waitState { it is ConnectionState.Connected && it.dnsHealth is DnsHealth.Healthy }
        compose.onNodeWithContentDescription("Connect/Disconnect").performTouchInput { click() }
        waitState { it is ConnectionState.Disconnected }
        await { !hasVpn() }
    }

    @Test fun failedHealthIsVisibleAndTileCanDisconnectIt() {
        val config = fixture("health-fail")
        connect(config)
        val unhealthy = health(config)
        assertTrue(unhealthy.toString(), unhealthy is DnsHealth.Unhealthy)
        assertDashboard("DNS check failed", config.displayName)
        assertNotification(config, unhealthy)
        showTile()
        assertTile(config.statusDescription(unhealthy))
        shell("cmd statusbar click-tile $component")
        waitState { it is ConnectionState.Disconnected }
        await { !hasVpn() }
        shell("cmd statusbar collapse")
        assertDashboard("Disconnected")
    }

    @Test fun descriptorFailureClearsVpnRecoveryNotificationAndConnectedUi() {
        val config = fixture("health-slow")
        connect(config)
        // The fixture holds the probe reply for two seconds. Close the real TUN
        // descriptor while forwarding is live; its next read/write must fail.
        awaitForwarding(config)
        val service = requireNotNull(ValidationComponentFactory.vpn.get())
        instrumentation.runOnMainSync { descriptor(service).close() }
        val failure = runBlocking { withTimeout(8000) { DnsVpnServiceEvents.events.first {
            it is DnsVpnServiceEvent.Failed && it.config == config
        } } } as DnsVpnServiceEvent.Failed
        assertTrue(failure.message, failure.message.startsWith("DNS forwarding"))
        waitState { it is ConnectionState.Error }
        await { !hasVpn() && notifications.activeNotifications.none { it.id == 1001 } }
        assertTrue(context.getSharedPreferences("vpn_recovery", Context.MODE_PRIVATE).all.isEmpty())
        compose.waitUntil(5000) { compose.onAllNodesWithText("DNS forwarding", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Connected", substring = false).assertCountEquals(0)
        screenshot("failed-dashboard")
        showTile()
        await { tileDescription()?.startsWith("Error: DNS forwarding") == true }
        // A later system start must not restore the failed configuration.
        val previous = DnsVpnServiceEvents.events.replayCache.last()
        context.startForegroundService(Intent(context, DnsVpnService::class.java))
        runBlocking { withTimeout(5000) { DnsVpnServiceEvents.events.first { it !== previous && it is DnsVpnServiceEvent.Stopped } } }
        assertFalse(hasVpn())
    }

    @Test fun lateFailureFromReplacedInterfaceCannotOverwriteHealthyUi() {
        val old = fixture("health-ok")
        connect(old)
        assertTrue(health(old) is DnsHealth.Healthy)
        val service = requireNotNull(ValidationComponentFactory.vpn.get())
        val oldDescriptor = descriptor(service)
        val replacement = fixture("health-ok").copy(displayName = "Replacement health resolver")
        connect(replacement)
        assertTrue(health(replacement) is DnsHealth.Healthy)
        // Invoke the real service's delayed-failure handler with the retired identity.
        val callback = DnsVpnService::class.java.getDeclaredMethod("handlePacketLoopFailure",
            ParcelFileDescriptor::class.java, DnsConnectionConfig::class.java, String::class.java)
        callback.isAccessible = true
        instrumentation.runOnMainSync { callback.invoke(service, oldDescriptor, old, "Injected retired failure") }
        runBlocking { delay(300) }
        val state = repository.connectionState.value as ConnectionState.Connected
        assertEquals(replacement, state.activeConfig)
        assertTrue(hasVpn())
        assertDashboard("Connected", replacement.displayName)
    }

    private fun awaitForwarding(config: DnsConnectionConfig) = runBlocking {
        val transport = DohDnsTransport(DohEndpointResolver(context))
        withTimeout(5000) {
            while (true) {
                val result = transport.query(DnsWireMessage.buildAQuery(42, "inflight.fixture.test"),
                    requireNotNull(config.dohUrl), emptyList(), 1000, "127.0.0.1", true)
                if (result is DnsTransportResult.Success && result.payload.last().toInt() > 0) break
                delay(50)
            }
        }
    }

    private fun fixture(path: String): DnsConnectionConfig {
        val port = InstrumentationRegistry.getArguments().getString("responseTestPort")?.toIntOrNull()
        assumeTrue("Start HTTPS fixture and pass responseTestPort", port != null && port in 1024..65535)
        return DnsConnectionConfig("ui-health-fixture", "UI health resolver", DnsProtocol.DOH, emptyList(),
            "https://localhost:$port/$path/${System.nanoTime()}", "127.0.0.1", true,
            connectionRequestId = "ui-${System.nanoTime()}", enableExperimentalPacketLoop = true)
    }
    private fun connect(config: DnsConnectionConfig) = runBlocking {
        context.startForegroundService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_CONNECT
            putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
        })
        withTimeout(8000) { DnsVpnServiceEvents.events.first { it is DnsVpnServiceEvent.Established && it.config == config } }
    }
    private fun health(config: DnsConnectionConfig): DnsHealth = runBlocking {
        (withTimeout(8000) { DnsVpnServiceEvents.events.first {
            it is DnsVpnServiceEvent.Established && it.config == config && it.health != DnsHealth.Checking
        } } as DnsVpnServiceEvent.Established).health
    }
    private fun waitState(predicate: (ConnectionState) -> Boolean) = runBlocking {
        withTimeout(10000) { repository.connectionState.first(predicate) }
    }
    private fun assertDashboard(status: String, provider: String? = null) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(status, substring = false).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(status).assertIsDisplayed()
        if (provider != null) compose.onNodeWithText(provider).assertIsDisplayed()
    }
    private fun assertNotification(config: DnsConnectionConfig, health: DnsHealth) = await {
        notifications.activeNotifications.any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == config.statusDescription(health) }
    }
    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun showTile() {
        val tiles = shell("settings get secure sysui_qs_tiles")
        val alreadyPresent = tiles.trim().split(',').any {
            it.startsWith("custom(") && ComponentName.unflattenFromString(it.removePrefix("custom(").removeSuffix(")")) ==
                ComponentName(context, DnsTileService::class.java)
        }
        if (!alreadyPresent) {
            addedTile = true
            shell("cmd statusbar add-tile $component")
        }
        shell("cmd statusbar expand-settings")
        await { ValidationComponentFactory.tile.get()?.qsTile != null }
    }
    private fun tileDescription(): String? = ValidationComponentFactory.tile.get()?.qsTile?.contentDescription?.toString()
    private fun assertTile(description: String) = await { tileDescription() == description }
    private fun descriptor(service: DnsVpnService): ParcelFileDescriptor {
        val field = DnsVpnService::class.java.getDeclaredField("vpnInterface")
        field.isAccessible = true
        return field.get(service) as ParcelFileDescriptor
    }
    private fun disconnect() = runBlocking {
        val previous = DnsVpnServiceEvents.events.replayCache.lastOrNull()
        context.startService(Intent(context, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_DISCONNECT })
        withTimeout(5000) { DnsVpnServiceEvents.events.first { it !== previous && it is DnsVpnServiceEvent.Stopped } }
        withTimeout(5000) { while (hasVpn()) delay(50) }
        delay(150)
    }
    private fun hasVpn() = connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    private fun await(predicate: () -> Boolean) = runBlocking { withTimeout(5000) { while (!predicate()) delay(50) } }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
