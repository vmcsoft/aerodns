package com.vmcsoft.aerodns

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.SpeedTestResult
import com.vmcsoft.aerodns.presentation.MainActivity
import com.vmcsoft.aerodns.presentation.components.CustomDnsDialog
import com.vmcsoft.aerodns.presentation.components.DnsSelectorBottomSheet
import com.vmcsoft.aerodns.presentation.components.SpeedTestDialog
import com.vmcsoft.aerodns.presentation.components.SpeedTestState
import com.vmcsoft.aerodns.presentation.theme.AeroDNSTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run on isolated emulators with real display/font settings and VPN consent prepared. */
@RunWith(AndroidJUnit4::class)
class LayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val server = DnsServer("layout-fixture", "Layout test resolver", "1.1.1.1", "1.0.0.1",
        ipv6Primary = "2606:4700:4700::1111", description = "Resolver for layout validation", isCustom = true)
    private val results = (1..20).map { SpeedTestResult(server.copy(id = "layout-$it", name = "Layout resolver $it"), it * 10L, true, sampleCount = 10) }

    @Test fun dashboardAndSelector() {
        compose.onNodeWithText("Change DNS").assertIsDisplayed().performClick()
        compose.onNodeWithText("Select DNS Server").assertIsDisplayed()
        capture("selector")
    }

    @Test fun dashboardSurvivesRotation() {
        val original = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText("Change DNS").assertIsDisplayed()
            capture("dashboard-landscape")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithText("Change DNS").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = original }
        }
    }

    @Test fun selectorWithManyProviders() {
        content { DnsSelectorBottomSheet(results.map { it.server }, {}, {}) }
        compose.onNodeWithText("Select DNS Server").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            .performSemanticsAction(SemanticsActions.Expand)
        compose.waitForIdle()
        capture("selector-many")
        compose.onNodeWithText("Add Custom DNS").assertIsDisplayed()
    }

    @Test fun standardEditorWithKeyboard() {
        editor(server)
        val field = compose.onNode(hasSetTextAction() and hasText("Primary DNS *"))
        field.performScrollTo().performClick().performTextReplacement("2001:4860:4860::8888")
        awaitKeyboard()
        capture("editor-standard-keyboard")
        field.assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.onNodeWithText("Save & Connect").assertIsDisplayed()
        capture("editor-actions")
    }

    @Test fun dohEditorWithKeyboard() {
        editor(server.copy(primary = "", secondary = null, dohUrl = "https://dns.example/dns-query",
            supportedProtocols = listOf(DnsProtocol.DOH)))
        val field = compose.onNode(hasSetTextAction() and hasText("DoH URL *"))
        field.performScrollTo().performClick().performTextReplacement("https://dns.example/" + "segment/".repeat(32) + "dns-query")
        awaitKeyboard()
        capture("editor-doh-keyboard")
        field.assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.onNodeWithText("Save & Connect").assertIsDisplayed()
        capture("editor-actions")
    }

    @Test fun editorWithLongStoredName() {
        val name = "Resolver".repeat(2048)
        editor(server.copy(name = name))
        compose.onNodeWithText("Edit Custom DNS").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Name (Optional)")).assertTextContains(name)
    }

    @Test fun editorWithLongStoredDohUrl() {
        val url = "https://dns.example/" + "segment/".repeat(2048)
        editor(server.copy(primary = "", dohUrl = url,
            supportedProtocols = listOf(DnsProtocol.DOH)))
        compose.onNodeWithText("Edit Custom DNS").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("DoH URL *")).assertTextContains(url)
    }

    @Test fun speedTestRunning() = speed(SpeedTestState.Running(10, 20, results.take(10)), "speed-running")
    @Test fun speedTestCompleted() = speed(SpeedTestState.Completed(results), "speed-completed")
    @Test fun speedTestError() = speed(SpeedTestState.Error("DNS request timed out. Please check the connection and retry."), "speed-error")
    @Test fun speedTestIdle() = speed(SpeedTestState.Idle, "speed-idle")

    @Test fun speedTestProtocolSamplesAndActivation() {
        val full = results.first().copy(testedProtocol = DnsProtocol.DOH)
        val partial = full.copy(server = server.copy(id = "partial", name = "Partial resolver"), sampleCount = 2, timedOut = true)
        val failed = full.copy(server = server.copy(id = "failed", name = "Unavailable resolver"),
            sampleCount = 0, isReachable = false, failureReason = "Protocol unavailable")
        var selected: SpeedTestResult? = null
        content { SpeedTestDialog(SpeedTestState.Completed(listOf(full, partial, failed)), {}, { selected = it }) }
        compose.onNodeWithText("Activate Selected").assertIsNotEnabled()
        compose.onNodeWithText("10/10 replies").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("2/10 replies · Time limit reached"))
        compose.onNodeWithText("2/10 replies · Time limit reached").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Partial resolver"))
        compose.onNodeWithText("Partial resolver").performClick()
        compose.onNodeWithText("Activate Selected").assertIsEnabled().performClick()
        assertEquals(DnsProtocol.DOH, selected?.testedProtocol)
        assertEquals(2, selected?.sampleCount)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Protocol unavailable"))
        // Exercise real scrolling too: lazy-list item scrolling can leave a tall
        // card's last line below the viewport at 200% font scale.
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        compose.onNodeWithText("Protocol unavailable").assertIsDisplayed()
        capture("speed-protocol-samples")
    }

    private fun speed(state: SpeedTestState, name: String) {
        content { SpeedTestDialog(state, {}, {}) }
        compose.onNodeWithText("DNS Speed Test").assertIsDisplayed()
        compose.onNodeWithText(if (state is SpeedTestState.Running) "Cancel" else "Close").assertIsDisplayed()
        capture(name)
    }

    private fun editor(value: DnsServer) = content {
        CustomDnsDialog({}, { _, _, _, _, _, _, _ -> }, { _, _, _, _, _, _, _ -> }, value)
    }

    private fun content(body: @Composable () -> Unit) {
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.setContent { AeroDNSTheme { body() } } }
        compose.waitForIdle()
    }

    private fun awaitKeyboard() {
        compose.waitUntil(5000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = InstrumentationRegistry.getArguments().getString("layoutLabel") ?: "default"
        val file = File(instrumentation.targetContext.cacheDir, "layout-$label-$name.png")
        instrumentation.waitForIdleSync()
        Thread.sleep(300) // Let the emulator compositor present the settled Compose frame.
        // UiAutomation can return null after an old emulator's display resize.
        // Capture the complete display through the same authorized shell transport.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: run {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("screencap -p")
            ).use { BitmapFactory.decodeStream(it) }
        }
        checkNotNull(bitmap) { "Emulator screenshot capture failed for $name" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
