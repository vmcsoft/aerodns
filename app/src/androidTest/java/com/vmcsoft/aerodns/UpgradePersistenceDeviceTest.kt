package com.vmcsoft.aerodns

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.local.PreferencesDataStore
import com.vmcsoft.aerodns.data.repository.DnsRepositoryImpl
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.presentation.MainActivity
import com.vmcsoft.aerodns.presentation.dashboard.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host installs the old APK for seed, then replaces it without clearing app data.
 * Seed/storage verification use APIs shared with v1.5.2; UI phases run on the candidate.
 */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    @Test fun persistAcrossPackageReplacement(): Unit = runBlocking {
        assumeTrue("Owned emulator opt-in required", args.getString("ownedUpgradeEmulator") == "true")
        check(context.packageName == "com.vmcsoft.aerodns.validation")
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        ).bufferedReader().use { it.readText().trim() }
        check(qemu == "1")
        val manager = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        assertFalse(manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        })
        val phase = requireNotNull(args.getString("upgradePhase"))
        require(phase in listOf("seed", "verify", "edit", "verifyEdited"))
        val scenario = requireNotNull(args.getString("upgradeScenario"))
        require(scenario in listOf("standard", "doh", "legacy"))
        val store = PreferencesDataStore(context)
        val repository = DnsRepositoryImpl(store)
        val selected = if (scenario == "doh") "upgrade-unsafe" else "upgrade-dual"
        val protocol = if (scenario == "doh") DnsProtocol.DOH else DnsProtocol.STANDARD
        val expected = profiles().map {
            if (scenario == "legacy" && it.id == "upgrade-unsafe") it.copy(allowUntrustedCertificates = false, customBootstrapIp = null)
            else it
        }
        if (phase == "seed") {
            assertNull("Seed requires fresh test-package data", store.getCustomDnsJson())
            expected.forEach { repository.saveCustomServer(it) }
            if (scenario == "legacy") {
                // Older JSON records predate these optional custom DoH fields.
                val json = JSONArray(store.getCustomDnsJson())
                repeat(json.length()) { i ->
                    json.getJSONObject(i).remove("customBootstrapIp")
                    json.getJSONObject(i).remove("allowUntrustedCertificates")
                }
                store.setCustomDnsJson(json.toString())
            }
            store.setSelectedDnsId(selected)
            store.setSelectedDnsProtocol(protocol)
            store.setLastConnectedDnsId("upgrade-url-only")
            store.setExperimentalPacketLoopEnabled(scenario == "doh")
        }
        val edited = expected.map {
            if (it.id == "upgrade-url-only") it.copy(name = "Edited after upgrade — DNS", allowUntrustedCertificates = true)
            else it
        }
        val wanted = if (phase == "verifyEdited") edited else expected
        assertEquals(wanted, repository.getCustomServers())
        assertEquals(selected, store.getSelectedDnsId())
        assertEquals(protocol, store.getSelectedDnsProtocol())
        assertEquals("upgrade-url-only", store.getLastConnectedDnsId())
        assertEquals(scenario == "doh", store.isExperimentalPacketLoopEnabled())

        if (phase == "verify" || phase == "verifyEdited") {
            val originalJson = store.getCustomDnsJson()
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                withTimeout(10000) {
                    var loaded = false
                    while (!loaded) {
                        activity.onActivity {
                            val viewModel = ViewModelProvider(it)[DashboardViewModel::class.java]
                            loaded = viewModel.selectedServer.value?.id == selected &&
                                viewModel.selectedProtocol.value == protocol &&
                                viewModel.dnsServers.value.count { server -> server.isCustom } == wanted.size
                        }
                        if (!loaded) delay(100)
                    }
                }
            }
            assertEquals("Opening dashboard must not rewrite custom profiles", originalJson, store.getCustomDnsJson())
            assertEquals(wanted, repository.getCustomServers())
            assertEquals(selected, store.getSelectedDnsId())
            assertEquals(protocol, store.getSelectedDnsProtocol())
        } else if (phase == "edit") {
            repository.updateCustomServer(edited.single { it.id == "upgrade-url-only" })
            assertEquals(edited, repository.getCustomServers())
        }
        context.filesDir.resolve("upgrade-$scenario-$phase.json").writeText(JSONObject()
            .put("scenario", scenario).put("phase", phase).put("passed", true)
            .put("profile_count", wanted.size).put("selected_id", selected)
            .put("protocol", protocol.name).put("package_uid", context.applicationInfo.uid)
            .toString(2))
    }

    private fun profiles() = listOf(
        DnsServer("upgrade-dual", "Upgrade DNS — Tiếng Việt 日本語", "192.0.2.53", "198.51.100.53",
            "2001:db8::53", "2001:db8:1::53", "https://resolver.example/dns-query?mode=family&x=%2F",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH), isCustom = true),
        DnsServer("upgrade-url-only", "Strict URL only " + "長".repeat(256), "",
            dohUrl = "https://dns.example/dns-query?policy=a%26b",
            supportedProtocols = listOf(DnsProtocol.DOH), isCustom = true),
        DnsServer("upgrade-unsafe", "Upgrade HTTPS", "",
            dohUrl = "https://localhost:18443/health-ok-upgrade", customBootstrapIp = "127.0.0.1",
            allowUntrustedCertificates = true, supportedProtocols = listOf(DnsProtocol.DOH), isCustom = true),
        DnsServer("upgrade-standard", "IPv4 only", "203.0.113.53", isCustom = true)
    )
}
