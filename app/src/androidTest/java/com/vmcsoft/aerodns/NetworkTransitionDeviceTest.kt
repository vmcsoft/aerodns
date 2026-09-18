package com.vmcsoft.aerodns

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vmcsoft.aerodns.data.dns.DohEndpointResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.*
import com.vmcsoft.aerodns.domain.model.*
import com.vmcsoft.aerodns.validation.ValidationRepositories
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.Before
import org.junit.Test

/** Destructive network toggles are confined to an explicitly opted-in, owned emulator. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class NetworkTransitionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private val repository get() = EntryPointAccessors.fromApplication(context, ValidationRepositories::class.java).vpnRepository()
    private val args get() = InstrumentationRegistry.getArguments()
    private var prepared = false

    @Before fun prepare() = runBlocking {
        assumeTrue("Only run on an explicitly opted-in disposable emulator", args.getString("ownedNetworkEmulator") == "true")
        check(shell("getprop ro.kernel.qemu").trim() == "1")
        assertNull(VpnService.prepare(context))
        assertFalse(hasVpn())
        prepared = true
        shell("svc data disable")
        shell("svc wifi enable")
        await(30000) { physical().any { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true } }
        repository // Start the real network observer before connecting.
        delay(1500)
    }

    @After fun cleanup(): Unit = runBlocking {
        if (prepared) {
            try {
                repository.disconnect()
                await(8000) { !hasVpn() }
            } finally {
                shell("svc wifi enable")
                shell("svc data enable")
            }
        }
    }

    @Test fun underlyingNetworkControl() {
        if (args.getString("fixtureBootstrap") == "true") {
            assertLookup(42)
        } else {
            assertTrue("Normal underlay must not resolve the fixture name", lookup().contains("unknown host"))
        }
    }

    @Test fun standardSystemLookupUsesSelectedResolver() = runBlocking {
        val config = standard()
        connect(config)
        healthy(config.serverId)
        assertLookup(42)
    }

    @Test fun standardHealthIsReadyForImmediateSystemLookupAcrossReplacements() = runBlocking {
        repeat(6) {
            val config = standard()
            connect(config)
            healthy(config.serverId)
            assertLookup(42)
        }
    }

    @Test fun dohSystemLookupTracksReplacementAndFailsWithoutFallback() = runBlocking {
        connect(doh("42")); healthy("controlled-42"); assertLookup(42)
        connect(doh("43")); healthy("controlled-43"); assertLookup(43)
        val failing = doh("fail")
        connect(failing)
        withTimeout(12000) { repository.connectionState.first { it is ConnectionState.Connected && it.server.id == failing.serverId && it.dnsHealth is DnsHealth.Unhealthy } }
        val result = lookup()
        assertTrue("Failed resolver must fail the OS lookup: $result", result.contains("unknown host"))
        assertTrue(hasVpn())
        assertEquals(failing.serverId, (repository.connectionState.value as ConnectionState.Connected).server.id)
    }

    @Test fun certificateOptInDoesNotCarryIntoTheNextProfile() = runBlocking {
        connect(doh("42")); healthy("controlled-42"); assertLookup(42)
        val strict = doh("43").copy(allowUntrustedCertificates = false)
        connect(strict)
        withTimeout(12000) { repository.connectionState.first { it is ConnectionState.Connected && it.server.id == strict.serverId && it.dnsHealth is DnsHealth.Unhealthy } }
        assertTrue(lookup().contains("unknown host"))
        assertEquals(false, (repository.connectionState.value as ConnectionState.Connected).activeConfig?.allowUntrustedCertificates)
    }

    @Test fun dohWifiLossAndRecoveryPreserveResolverAndExplicitStop() = runBlocking {
        lossAndRecovery(doh("42"))
    }

    @Test fun standardWifiLossAndRecoveryPreserveResolverAndExplicitStop() = runBlocking {
        lossAndRecovery(standard())
    }

    private suspend fun lossAndRecovery(config: DnsConnectionConfig) = coroutineScope {
        connect(config); healthy(config.serverId); assertLookup(42)
        val replacements = linkedSetOf<String>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            DnsVpnServiceEvents.events.collect { event ->
                if (event is DnsVpnServiceEvent.Established && event.config.serverId == config.serverId &&
                    event.config.connectionRequestId != config.connectionRequestId) {
                    replacements += event.config.connectionRequestId
                }
            }
        }
        try {
            val before = physical().toSet()
            val endpointBefore = resolveEndpoint()
            assertTrue(endpointBefore.network in before)
            shell("svc wifi disable")
            await(15000) { physical().isEmpty() }
            withTimeout(42000) { repository.connectionState.first { it is ConnectionState.Connected && it.dnsHealth is DnsHealth.Unhealthy } }
            assertTrue(hasVpn())
            shell("svc wifi enable")
            await(30000) { physical().any { it !in before && connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true } }
            val recovered = healthy(config.serverId, 45000)
            assertEquals(config.copy(connectionRequestId = ""), recovered.activeConfig!!.copy(connectionRequestId = ""))
            val endpointAfter = resolveEndpoint()
            assertNotEquals(endpointBefore.network, endpointAfter.network)
            assertTrue(endpointAfter.network in physical())
            assertEquals(endpointBefore.addresses, endpointAfter.addresses)
            // A fresh unbound lookup must work immediately after recovered health, and
            // capability callbacks must not replace that recovered interface again.
            assertLookup(42)
            delay(4000)
            assertEquals("One network return must establish exactly one replacement", 1, replacements.size)
            assertEquals(recovered.activeConfig!!.connectionRequestId,
                (repository.connectionState.value as ConnectionState.Connected).activeConfig!!.connectionRequestId)
            assertLookup(42)
            repository.disconnect(); await(8000) { !hasVpn() }
            shell("svc wifi disable"); await(15000) { physical().isEmpty() }
            shell("svc wifi enable"); await(30000) { physical().isNotEmpty() }
            delay(4000)
            assertFalse("Network return must not undo explicit disconnect", hasVpn())
            assertTrue(repository.connectionState.value is ConnectionState.Disconnected)
        } finally { observer.cancel() }
    }

    private fun standard() = DnsConnectionConfig("controlled-standard", "Controlled Standard", DnsProtocol.STANDARD,
        listOf("10.0.2.2"), connectionRequestId = "standard-${System.nanoTime()}")

    private val endpointHost get() = if (args.getString("fixtureBootstrap") == "true") "bootstrap.aerodns.test" else "10.0.2.2"
    private fun resolveEndpoint() = DohEndpointResolver(context).resolve(endpointHost, emptyList())

    private fun doh(path: String): DnsConnectionConfig {
        return DnsConnectionConfig("controlled-$path", "Controlled $path", DnsProtocol.DOH,
            emptyList(), "https://$endpointHost:18445/$path", null, true,
            connectionRequestId = "network-${System.nanoTime()}", enableExperimentalPacketLoop = true)
    }

    private suspend fun connect(config: DnsConnectionConfig) {
        context.startForegroundService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_CONNECT
            putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
        })
        withTimeout(8000) { DnsVpnServiceEvents.events.first { it is DnsVpnServiceEvent.Established && it.config == config } }
        withTimeout(8000) { repository.connectionState.first { it is ConnectionState.Connected && it.activeConfig == config } }
    }
    private suspend fun healthy(id: String, timeout: Long = 12000): ConnectionState.Connected =
        withTimeout(timeout) { repository.connectionState.first { it is ConnectionState.Connected && it.server.id == id && it.dnsHealth is DnsHealth.Healthy } } as ConnectionState.Connected
    private fun physical() = connectivity.allNetworks.filter {
        val caps = connectivity.getNetworkCapabilities(it)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun hasVpn() = connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    // A separate shell UID performs a fresh OS resolver lookup, without naming a DNS
    // server or binding to the VPN. ICMP success is irrelevant; inspect its resolved IP.
    private fun lookup(): String {
        val name = "probe-${System.nanoTime()}.aerodns.test"
        val active = connectivity.activeNetwork
        Log.i("ResolverAttributionTest", "Before $name: active=$active vpn=${connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} dns=${connectivity.getLinkProperties(active)?.dnsServers}")
        val pipes = instrumentation.uiAutomation.executeShellCommandRwe("ping -c 1 -W 1 $name")
        // UiAutomation executes argv directly, without shell quoting/redirection.
        pipes[1].close()
        val result = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() } +
            ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).bufferedReader().use { it.readText() }
        Log.i("ResolverAttributionTest", "$name: ${result.trim()}")
        return result
    }
    private fun assertLookup(last: Int) { val result = lookup(); assertTrue("Unexpected system DNS attribution: $result", result.contains("192.0.2.$last)")) }
    private suspend fun await(timeout: Long, predicate: () -> Boolean) = withTimeout(timeout) { while (!predicate()) delay(100) }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
