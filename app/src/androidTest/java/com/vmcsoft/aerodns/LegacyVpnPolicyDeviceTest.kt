package com.vmcsoft.aerodns

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.*
import com.vmcsoft.aerodns.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Host configures actual Android VPN settings before each isolated invocation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
class LegacyVpnPolicyDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var active: DnsConnectionConfig? = null

    @After fun cleanup(): Unit = runBlocking {
        active?.let { config ->
            val previous = DnsVpnServiceEvents.events.replayCache.lastOrNull()
            context.startService(Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_DISCONNECT
                putExtra(DnsVpnService.EXTRA_DISCONNECT_REQUEST_ID, config.connectionRequestId)
            })
            withTimeout(5000) { DnsVpnServiceEvents.events.first { it !== previous && it is DnsVpnServiceEvent.Stopped } }
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        withTimeout(5000) {
            while (connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) delay(50)
        }
    }

    @Test fun activePolicyMatchesCurrentAndroidSettings() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val alwaysOn = args.getString("expectedAlwaysOn") == "true"
        val lockdown = args.getString("expectedLockdown") == "true"
        assertNull("Prepare VPN consent", VpnService.prepare(context))
        // This read runs as the ordinary application UID without shell identity or
        // additional permissions; it also checks the compatibility source itself.
        assertEquals(alwaysOn, Settings.Secure.getString(context.contentResolver, "always_on_vpn_app") == context.packageName)
        assertEquals(lockdown, alwaysOn && Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown", 0) != 0)
        val config = DnsConnectionConfig("legacy-policy", "Legacy policy resolver", DnsProtocol.DOH,
            emptyList(), "https://localhost:18443/health-ok-legacy", "10.0.2.2", true,
            connectionRequestId = "legacy-${System.nanoTime()}", enableExperimentalPacketLoop = true)
        active = config
        val intent = Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_CONNECT
            putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        val state = withTimeout(8000) { DnsVpnServiceEvents.events.first {
            it is DnsVpnServiceEvent.Established && it.config == config
        } } as DnsVpnServiceEvent.Established
        assertEquals("Always-on must use current settings", alwaysOn, state.controlPolicy.alwaysOn)
        assertEquals("Lockdown must use current settings", lockdown, state.controlPolicy.lockdown)
        val notifications = context.getSystemService(NotificationManager::class.java)
        withTimeout(12000) {
            while (notifications.activeNotifications.none { posted ->
                posted.id == 1001 && posted.notification.actions?.singleOrNull()?.title?.toString() ==
                    (if (alwaysOn) "VPN settings" else "Disconnect") &&
                    (!lockdown || posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.startsWith("Android is blocking traffic") == true)
            }) delay(50)
        }
        val previous = DnsVpnServiceEvents.events.replayCache.last()
        context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_DISCONNECT))
        val result = withTimeout(5000) { DnsVpnServiceEvents.events.first { it !== previous } }
        if (alwaysOn) assertTrue(result is DnsVpnServiceEvent.Established)
        else { assertTrue(result is DnsVpnServiceEvent.Stopped); active = null }
    }
}
