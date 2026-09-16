package com.vmcsoft.aerodns

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.*
import com.vmcsoft.aerodns.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationBurstDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private fun hasVpn(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
    @Before fun prepare() {
        assertNull(VpnService.prepare(context))
        assertFalse(hasVpn())
        assertTrue(notifications.areNotificationsEnabled())
    }
    @After fun cleanup() = runBlocking {
        context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_DISCONNECT))
        withTimeout(5000) { while (hasVpn() || notifications.activeNotifications.any { it.id == 1001 }) delay(50) }
        delay(1500)
        assertFalse("A retired update reposted the VPN notification", notifications.activeNotifications.any { it.id == 1001 })
    }
    @Test fun latestHealthSurvivesRapidProviderReplacement() = runBlocking {
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("responseTestPort")).toInt()
        var latest: DnsConnectionConfig? = null
        repeat(20) { index ->
            val config = DnsConnectionConfig("burst-$index", "Burst resolver $index", DnsProtocol.DOH,
                emptyList(), "https://localhost:$port/health-ok-burst/$index", "127.0.0.1", true,
                connectionRequestId = "burst-${System.nanoTime()}", enableExperimentalPacketLoop = true)
            latest = config
            context.startForegroundService(Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_CONNECT
                putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
            })
            withTimeout(5000) { DnsVpnServiceEvents.events.first { it is DnsVpnServiceEvent.Established && it.config == config } }
        }
        val config = requireNotNull(latest)
        val healthy = withTimeout(8000) { DnsVpnServiceEvents.events.first {
            it is DnsVpnServiceEvent.Established && it.config == config && it.health is DnsHealth.Healthy
        } } as DnsVpnServiceEvent.Established
        withTimeout(12000) {
            while (notifications.activeNotifications.none {
                it.id == 1001 && it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == config.statusDescription(healthy.health)
            }) delay(50)
        }
        assertTrue(hasVpn())
    }
}
