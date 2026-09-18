package com.vmcsoft.aerodns

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.repository.NetworkCapabilitiesRepositoryImpl
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the owned emulator's dual-stack Wi-Fi and IPv4-only simulated mobile network. */
@RunWith(AndroidJUnit4::class)
class NetworkFamilyDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(ConnectivityManager::class.java)
    private var prepared = false

    @Before fun prepare() = runBlocking {
        assumeTrue("Disposable emulator opt-in required",
            InstrumentationRegistry.getArguments().getString("ownedNetworkEmulator") == "true")
        check(shell("getprop ro.kernel.qemu").trim() == "1")
        assertNull(VpnService.prepare(context))
        assertFalse(vpnPresent())
        prepared = true
        shell("svc data disable")
        shell("svc wifi enable")
        await(30000) { onlyPhysical(NetworkCapabilities.TRANSPORT_WIFI) }
    }

    @After fun cleanup(): Unit = runBlocking {
        if (prepared) {
            try {
                context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_DISCONNECT))
                await(8000) { !vpnPresent() }
            } finally {
                shell("svc wifi enable")
                shell("svc data enable")
            }
        }
    }

    @Test fun familyTracksWifiMobileWifiWithoutTtl() = runBlocking { transitions(withVpn = false) }
    @Test fun vpnAddressCannotMaskPhysicalFamilyOrOfflineState() = runBlocking { transitions(withVpn = true) }

    private suspend fun transitions(withVpn: Boolean) {
        if (withVpn) {
            val config = DnsConnectionConfig("family-probe", "Family probe", DnsProtocol.STANDARD,
                listOf("8.8.8.8"), connectionRequestId = "family-${System.nanoTime()}")
            val intent = Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_CONNECT)
                .putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            withTimeout(8000) { DnsVpnServiceEvents.events.first { it is DnsVpnServiceEvent.Established && it.config == config } }
            await(8000) { vpnPresent() }
        }
        val repository = NetworkCapabilitiesRepositoryImpl(context)
        val started = SystemClock.elapsedRealtime()
        assertEquals("Owned image must start with dual-stack Wi-Fi", NetworkIpStack.DUAL_STACK, repository.getActiveNetworkIpStack())
        Log.i("FamilyTransitionTest", "Initial Wi-Fi: DUAL_STACK, vpn=$withVpn")
        shell("svc data enable")
        shell("svc wifi disable")
        await(30000) { onlyPhysical(NetworkCapabilities.TRANSPORT_CELLULAR) }
        val elapsed = SystemClock.elapsedRealtime() - started
        assertTrue("Transition must stay inside the former 60-second cache window: $elapsed", elapsed < 60000)
        assertEquals(NetworkIpStack.IPv4_ONLY, repository.getActiveNetworkIpStack())
        Log.i("FamilyTransitionTest", "Mobile: IPv4_ONLY after ${elapsed}ms, vpn=$withVpn")
        if (withVpn) {
            shell("svc data disable")
            await(15000) { physical().isEmpty() }
            assertTrue("The VPN must remain installed during this classification check", vpnPresent())
            assertEquals("No physical network is unknown, not the TUN's IPv4", NetworkIpStack.DUAL_STACK,
                repository.getActiveNetworkIpStack())
            Log.i("FamilyTransitionTest", "Offline with retained VPN: unknown/DUAL_STACK")
        }
        shell("svc wifi enable")
        await(30000) { physical().any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } }
        shell("svc data disable")
        await(15000) { onlyPhysical(NetworkCapabilities.TRANSPORT_WIFI) }
        assertEquals(NetworkIpStack.DUAL_STACK, repository.getActiveNetworkIpStack())
        Log.i("FamilyTransitionTest", "Returned Wi-Fi: DUAL_STACK, vpn=$withVpn")
    }

    @Suppress("DEPRECATION")
    private fun physical() = manager.allNetworks.filter {
        val caps = manager.getNetworkCapabilities(it)
        caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private fun onlyPhysical(transport: Int): Boolean {
        val networks = physical()
        return networks.isNotEmpty() && networks.all { manager.getNetworkCapabilities(it)?.hasTransport(transport) == true }
    }
    @Suppress("DEPRECATION")
    private fun vpnPresent() = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    private suspend fun await(timeout: Long, condition: () -> Boolean) = withTimeout(timeout) { while (!condition()) delay(100) }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
