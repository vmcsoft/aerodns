package com.vmcsoft.aerodns

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.*
import com.vmcsoft.aerodns.validation.ValidationRepositories
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical Wi-Fi outage checks. The host must select a verified USB ADB transport. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class PhysicalWifiRecoveryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(ConnectivityManager::class.java)
    private val repository get() = EntryPointAccessors.fromApplication(context, ValidationRepositories::class.java).vpnRepository()
    private var prepared = false

    @Before fun prepare() = runBlocking {
        assumeTrue("Explicit physical Wi-Fi test opt-in required",
            InstrumentationRegistry.getArguments().getString("physicalWifiRecovery") == "true")
        check(shell("getprop sys.usb.state").trim().split(',').contains("adb")) { "USB debugging required" }
        check(shell("settings get global wifi_on").trim() == "1") { "Start with Wi-Fi enabled" }
        check(shell("settings get global mobile_data").trim() == "0") { "Disable mobile data before this Wi-Fi-only outage test" }
        check(shell("settings get secure always_on_vpn_app").trim().let { it == "null" || it.isEmpty() }) { "Always-on must be off" }
        assertNull("Prepare VPN consent first", VpnService.prepare(context))
        assertFalse("Disconnect VPNs before running", hasVpn())
        assertTrue("A validated Wi-Fi network is required", validatedWifi())
        assertTrue("Only Wi-Fi may provide internet", physical().all {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        })
        prepared = true
        repository
        delay(1500)
    }

    @After fun cleanup(): Unit = runBlocking {
        if (prepared) {
            try {
                repository.disconnect()
                await(10000) { !hasVpn() }
            } finally {
                shell("svc wifi enable")
            }
        }
    }

    @Test fun standardRecoversAndExplicitStopSurvivesWifiReturn() = runBlocking {
        lossAndRecovery(DnsConnectionConfig("physical-standard", "Wi-Fi Standard check", DnsProtocol.STANDARD,
            listOf("8.8.8.8"), connectionRequestId = "wifi-${System.nanoTime()}"))
    }

    @Test fun urlOnlyDohRecoversAndExplicitStopSurvivesWifiReturn() = runBlocking {
        lossAndRecovery(DnsConnectionConfig("physical-doh", "Wi-Fi DoH check", DnsProtocol.DOH,
            emptyList(), dohUrl = "https://dns.google/dns-query",
            connectionRequestId = "wifi-${System.nanoTime()}", enableExperimentalPacketLoop = true))
    }

    private suspend fun lossAndRecovery(config: DnsConnectionConfig): Unit = coroutineScope {
        context.startForegroundService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_CONNECT
            putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
        })
        val initial = healthy(config.serverId, 20000)
        assertEquals(config, initial.activeConfig)
        assertSystemLookup()
        val before = physical().toSet()
        val replacements = linkedSetOf<String>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            DnsVpnServiceEvents.events.collect { event ->
                if (event is DnsVpnServiceEvent.Established && event.config.serverId == config.serverId &&
                    event.config.connectionRequestId != config.connectionRequestId) replacements += event.config.connectionRequestId
            }
        }
        try {
            shell("svc wifi disable")
            await(15000) { physical().isEmpty() }
            val failed = withTimeout(42000) { repository.connectionState.first {
                it is ConnectionState.Connected && it.dnsHealth is DnsHealth.Unhealthy
            } } as ConnectionState.Connected
            assertEquals(config, failed.activeConfig)
            assertTrue("Failed health must retain the selected VPN", hasVpn())
            Log.i("PhysicalWifiTest", "${config.protocol}: outage reported unhealthy; profile retained")
            val start = SystemClock.elapsedRealtime()
            shell("svc wifi enable")
            await(45000) { validatedWifi() && physical().any { it !in before } }
            val recovered = withTimeout(45000) { repository.connectionState.first {
                it is ConnectionState.Connected && it.server.id == config.serverId && it.dnsHealth is DnsHealth.Healthy &&
                    it.activeConfig?.connectionRequestId != config.connectionRequestId
            } } as ConnectionState.Connected
            val elapsed = SystemClock.elapsedRealtime() - start
            assertEquals(config.copy(connectionRequestId = ""), recovered.activeConfig!!.copy(connectionRequestId = ""))
            assertSystemLookup()
            delay(4000)
            assertEquals("One Wi-Fi return must produce one replacement", 1, replacements.size)
            assertEquals(recovered.activeConfig, (repository.connectionState.value as ConnectionState.Connected).activeConfig)
            assertSystemLookup()
            Log.i("PhysicalWifiTest", "${config.protocol}: healthy after ${elapsed}ms; one stable replacement; system lookups resolved")
            repository.disconnect()
            await(10000) { !hasVpn() }
            shell("svc wifi disable")
            await(15000) { physical().isEmpty() }
            shell("svc wifi enable")
            await(45000) { validatedWifi() }
            delay(4000)
            assertFalse("Wi-Fi return must not undo an explicit stop", hasVpn())
            assertTrue(repository.connectionState.value is ConnectionState.Disconnected)
            Log.i("PhysicalWifiTest", "${config.protocol}: explicit stop survived Wi-Fi return")
        } finally {
            observer.cancel()
        }
    }

    private suspend fun healthy(id: String, timeout: Long) = withTimeout(timeout) {
        repository.connectionState.first { it is ConnectionState.Connected && it.server.id == id && it.dnsHealth is DnsHealth.Healthy }
    } as ConnectionState.Connected
    @Suppress("DEPRECATION")
    private fun physical() = manager.allNetworks.filter {
        val caps = manager.getNetworkCapabilities(it)
        caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun validatedWifi() = physical().any {
        val caps = manager.getNetworkCapabilities(it)!!
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    @Suppress("DEPRECATION")
    private fun hasVpn() = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    private fun assertSystemLookup() {
        // A separate shell process checks Android resolution; cached answers are possible.
        // This is reachability sampling, not resolver attribution or continuous availability.
        val result = shell("ping -c 1 -W 1 example.com")
        assertTrue("System lookup must resolve example.com", result.contains("PING example.com ("))
    }
    private suspend fun await(timeout: Long, predicate: () -> Boolean) = withTimeout(timeout) { while (!predicate()) delay(100) }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
