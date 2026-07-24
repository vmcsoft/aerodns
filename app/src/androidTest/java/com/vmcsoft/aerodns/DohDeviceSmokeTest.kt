package com.vmcsoft.aerodns

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.local.DnsProviderData
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DohDeviceSmokeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun builtInDohProvidersResolveAndKeepDirectIpv4Reachable() {
        if (VpnService.prepare(context) != null) {
            fail("VPN permission is not prepared. Open AeroDNS, connect once, and accept the Android VPN prompt before running this test.")
        }

        val failures = mutableListOf<String>()

        DnsProviderData.providers.forEach { provider ->
            try {
                val requestId = connectDoh(
                    provider.id,
                    provider.name,
                    provider.primary,
                    provider.secondary,
                    provider.dohUrl
                )
                awaitVpnEstablished(provider.name, requestId)
                assertShellSuccess(provider.name, "ping -c 1 -W 5 google.com")
                assertShellSuccess(provider.name, "ping -c 1 -W 5 1.1.1.1")
            } catch (e: Throwable) {
                failures += "${provider.name}: ${e.message}"
            } finally {
                disconnect()
                Thread.sleep(500)
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }

    private fun connectDoh(
        serverId: String,
        displayName: String,
        primary: String,
        secondary: String?,
        dohUrl: String?
    ): String {
        require(!dohUrl.isNullOrBlank()) { "Missing DoH URL" }

        val upstreamAddresses = listOfNotNull(
            primary.takeIf { it.isNotBlank() },
            secondary?.takeIf { it.isNotBlank() }
        )
        require(upstreamAddresses.isNotEmpty()) { "Missing bootstrap addresses" }

        val requestId = "instrumented-$serverId-doh-${System.nanoTime()}"
        val config = DnsConnectionConfig(
            serverId = serverId,
            displayName = displayName,
            protocol = DnsProtocol.DOH,
            upstreamAddresses = upstreamAddresses,
            dohUrl = dohUrl,
            connectionRequestId = requestId,
            enableExperimentalPacketLoop = true
        )

        val intent = Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_CONNECT
            putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        return requestId
    }

    private fun disconnect() {
        context.startService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_DISCONNECT
        })
    }

    private fun awaitVpnEstablished(providerName: String, requestId: String) = runBlocking {
        when (val event = withTimeout(5_000) {
            DnsVpnServiceEvents.events.first { event ->
                when (event) {
                    is DnsVpnServiceEvent.Established ->
                        event.config.connectionRequestId == requestId
                    is DnsVpnServiceEvent.Failed ->
                        event.config?.connectionRequestId == requestId
                    is DnsVpnServiceEvent.Stopped -> false
                }
            }
        }) {
            is DnsVpnServiceEvent.Established -> Unit
            is DnsVpnServiceEvent.Failed ->
                throw AssertionError("$providerName VPN failed to establish: ${event.message}")
            is DnsVpnServiceEvent.Stopped ->
                throw AssertionError("$providerName VPN stopped before establishment")
        }
    }

    private fun assertShellSuccess(providerName: String, command: String) {
        val exitCode = runShell(command)
        if (exitCode != 0) {
            throw AssertionError("$command failed for $providerName with exit code $exitCode")
        }
    }

    private fun runShell(command: String): Int {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        return process.waitFor()
    }
}
