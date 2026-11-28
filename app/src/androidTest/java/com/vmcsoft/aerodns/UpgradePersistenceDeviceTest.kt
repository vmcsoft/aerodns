package com.vmcsoft.aerodns

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host installs the old APK for seed, then replaces it without clearing app data.
 * Seed/storage verification use APIs shared with v1.5.2; UI phases run on the candidate.
 */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

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
        val renamed = expected.map { it.copy(name = "Edited ${it.id} — DNS") }
        val edited = renamed.map {
            if (it.id == "upgrade-url-only") it.copy(allowUntrustedCertificates = true)
            else it
        }
        val ipv6Doh = edited.first().copy(
            id = "upgrade-ipv6-doh",
            name = "IPv6 and DoH",
            primary = "",
            secondary = null
        )
        val wanted = if (phase == "verifyEdited") edited + ipv6Doh else expected
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
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                compose.waitUntil(5000) {
                    var loaded = false
                    activity.onActivity {
                        val viewModel = ViewModelProvider(it)[DashboardViewModel::class.java]
                        loaded = viewModel.dnsServers.value.count { server -> server.isCustom } == expected.size &&
                            viewModel.selectedServer.value?.id == selected
                    }
                    loaded
                }
                for ((profile, renamedProfile) in expected.zip(edited)) {
                    val options = "Options for ${profile.name}"
                    openChooser()
                    compose.onNode(hasScrollToIndexAction())
                        .performScrollToNode(hasContentDescription(options))
                    compose.onNodeWithContentDescription(options).performClick()
                    compose.onNodeWithText("Edit").performClick()
                    val dohUrl = profile.dohUrl
                    if (dohUrl != null) {
                        compose.onNodeWithText("Standard DNS").performScrollTo().performClick()
                        compose.onNodeWithText("DoH (HTTPS)").performClick()
                        compose.onNode(hasSetTextAction() and hasText("DoH URL *"))
                            .assertTextContains(dohUrl)
                        val certificates = compose.onNodeWithContentDescription("Allow untrusted certificates")
                        certificates.performScrollTo()
                        if (profile.allowUntrustedCertificates) certificates.assertIsOn()
                        else certificates.assertIsOff()
                        if (renamedProfile.allowUntrustedCertificates && !profile.allowUntrustedCertificates) {
                            certificates.performClick()
                            compose.onNodeWithText("Security warning").assertIsDisplayed()
                            compose.onNodeWithText("I understand, enable").performClick()
                            certificates.assertIsOn()
                        }
                    }
                    compose.onNode(hasSetTextAction() and hasText("Name (Optional)"))
                        .performScrollTo().performTextReplacement(renamedProfile.name)
                    val save = compose.onNodeWithText("Save")
                    if (!save.isDisplayed()) {
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        compose.waitUntil(5000) { save.isDisplayed() }
                    }
                    save.performClick()
                    withTimeout(5000) {
                        while (repository.getCustomServers().none {
                                it.id == profile.id && it.name == renamedProfile.name
                            }) {
                            delay(50)
                        }
                    }
                    assertEquals("Editing ${profile.id} must preserve its untouched fields",
                        renamedProfile, repository.getCustomServers().single { it.id == profile.id })
                }
                val original = edited.single { it.id == selected }
                openChooser()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(original.name))
                compose.onNode(hasText(original.name) and hasClickAction()).assertIsSelected()
                val alternative = edited.single { it.id == "upgrade-standard" }
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(alternative.name))
                compose.onNode(hasText(alternative.name) and hasClickAction()).performClick()
                awaitSelection(store, alternative.id, DnsProtocol.STANDARD)
                assertMixedAddressPair(alternative.primary, requireNotNull(alternative.secondary))
                openChooser()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(original.name))
                compose.onNode(hasText(original.name) and hasClickAction()).performClick()
                awaitSelection(store, selected, protocol)
                if (protocol == DnsProtocol.STANDARD) {
                    assertMixedAddressPair(requireNotNull(original.ipv6Primary), requireNotNull(original.ipv6Secondary))
                }
                assertEquals("Selecting saved profiles must not change them", edited, repository.getCustomServers())
            }
            repository.saveCustomServer(ipv6Doh)
            assertEquals(edited + ipv6Doh, repository.getCustomServers())
        }
        context.filesDir.resolve("upgrade-$scenario-$phase.json").writeText(JSONObject()
            .put("scenario", scenario).put("phase", phase).put("passed", true)
            .put("profile_count", repository.getCustomServers().size).put("selected_id", selected)
            .put("protocol", protocol.name).put("package_uid", context.applicationInfo.uid)
            .toString(2))
    }

    private fun openChooser() {
        compose.onNodeWithText("Change DNS").performScrollTo().performClick()
        compose.onNodeWithText("Select DNS Server").assertIsDisplayed()
    }

    private fun assertMixedAddressPair(primary: String, secondary: String) {
        val mixedLabel = hasAnySibling(hasText("DNS addresses"))
        compose.onNode(hasText(primary) and mixedLabel).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(secondary) and mixedLabel).performScrollTo().assertIsDisplayed()
    }

    private suspend fun awaitSelection(store: PreferencesDataStore, id: String, protocol: DnsProtocol) {
        withTimeout(5000) {
            while (store.getSelectedDnsId() != id || store.getSelectedDnsProtocol() != protocol) {
                delay(50)
            }
        }
    }

    private fun profiles() = listOf(
        DnsServer("upgrade-dual", "Upgrade DNS — Tiếng Việt 日本語", "192.0.2.53", "198.51.100.53",
            "2001:db8::53", "192.0.2.54", "https://resolver.example/dns-query?mode=family&x=%2F",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH), isCustom = true),
        DnsServer("upgrade-url-only", "Strict URL only " + "長".repeat(256), "",
            dohUrl = "https://dns.example/dns-query?policy=a%26b",
            supportedProtocols = listOf(DnsProtocol.DOH), isCustom = true),
        DnsServer("upgrade-unsafe", "Upgrade HTTPS", "",
            dohUrl = "https://localhost:18443/health-ok-upgrade", customBootstrapIp = "127.0.0.1",
            allowUntrustedCertificates = true, supportedProtocols = listOf(DnsProtocol.DOH), isCustom = true),
        DnsServer("upgrade-standard", "Standard resolver", "203.0.113.53", "2001:db8::54", isCustom = true)
    )
}
