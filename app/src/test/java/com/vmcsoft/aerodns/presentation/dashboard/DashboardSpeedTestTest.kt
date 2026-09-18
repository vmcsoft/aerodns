package com.vmcsoft.aerodns.presentation.dashboard

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.vmcsoft.aerodns.data.local.PreferencesDataStore
import com.vmcsoft.aerodns.domain.model.*
import com.vmcsoft.aerodns.domain.repository.*
import com.vmcsoft.aerodns.domain.usecase.*
import com.vmcsoft.aerodns.presentation.components.SpeedTestState
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardSpeedTestTest {
    private val dispatcher = StandardTestDispatcher()
    private val original = DnsServer("old", "Old", "1.1.1.1")
    private val fastest = DnsServer("fast", "Fast", "9.9.9.9")
    private val result = SpeedTestResult(fastest, 10, true)
    private val repository = TestVpn()
    private val preferences = mockk<PreferencesDataStore>(relaxed = true)
    private val speed = mockk<RunSpeedTestUseCase>()
    private lateinit var vm: DashboardViewModel

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        Dispatchers.setMain(dispatcher)
        coEvery { preferences.getSelectedDnsId() } returns original.id
        coEvery { preferences.getSelectedDnsProtocol() } returns DnsProtocol.STANDARD
        val list = mockk<GetDnsListUseCase>()
        coEvery { list() } returns listOf(original, fastest)
        vm = DashboardViewModel(repository, list, speed, mockk(), mockk(), mockk(), preferences)
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); Dispatchers.resetMain(); unmockkStatic(Log::class) }

    @Test fun `always-on blocks dashboard stop and measurement without pausing`() = runTest {
        runCurrent()
        val before = (repository.connectionState.value as ConnectionState.Connected).copy(controlPolicy = VpnControlPolicy(alwaysOn = true))
        repository.connectionState.value = before
        runCurrent()
        vm.onConnectToggle(); vm.onShowSpeedTest(); runCurrent()
        assertTrue(repository.calls.isEmpty())
        assertFalse(vm.showSpeedTestDialog.value)
        assertSame(before, repository.connectionState.value)
        assertTrue(vm.errorMessage.value.orEmpty().contains("Always-on"))
    }

    @Test fun `unknown policy keeps stop and measurement behind Android settings`() = runTest {
        runCurrent()
        val before = (repository.connectionState.value as ConnectionState.Connected)
            .copy(controlPolicy = VpnControlPolicy(isKnown = false))
        repository.connectionState.value = before
        runCurrent()
        vm.onConnectToggle(); vm.onShowSpeedTest(); runCurrent()
        assertTrue(repository.calls.isEmpty())
        assertFalse(vm.showSpeedTestDialog.value)
        assertSame(before, repository.connectionState.value)
        assertTrue(vm.errorMessage.value.orEmpty().contains("VPN settings"))
    }

    @Test fun `always-on resolver selection delegates one connect without explicit stop`() = runTest {
        runCurrent()
        repository.connectionState.value = (repository.connectionState.value as ConnectionState.Connected)
            .copy(controlPolicy = VpnControlPolicy(alwaysOn = true))
        vm.onDnsServerSelected(fastest); advanceUntilIdle()
        assertEquals(listOf("connect:fast"), repository.calls)
    }

    @Test fun `completion restores once and closing completed dialog does not restore again`() = runTest {
        coEvery { speed(any(), any()) } returns listOf(result)
        runCurrent(); vm.onShowSpeedTest(); runCurrent()
        assertEquals(listOf("pause", "restore"), repository.calls)
        assertEquals(SpeedTestState.Completed(listOf(result)), vm.speedTestState.value)
        vm.onDismissSpeedTest(); runCurrent()
        assertEquals(1, repository.calls.count { it == "restore" })
    }

    @Test fun `dismiss cancels measurement and restores previous connection once`() = runTest {
        coEvery { speed(any(), any()) } coAnswers { awaitCancellation() }
        runCurrent(); vm.onShowSpeedTest(); runCurrent(); vm.onDismissSpeedTest(); runCurrent()
        assertEquals(listOf("pause", "restore"), repository.calls)
        assertEquals(SpeedTestState.Idle, vm.speedTestState.value)
    }

    @Test fun `fast selection invalidates cleanup before preference persistence suspends`() = runTest {
        val saved = CompletableDeferred<Unit>()
        coEvery { preferences.setSelectedDnsId(fastest.id) } coAnswers { saved.await() }
        coEvery { speed(any(), any()) } coAnswers { awaitCancellation() }
        runCurrent(); vm.onShowSpeedTest(); runCurrent()
        vm.onSelectAndConnectDns(fastest); vm.onDismissSpeedTest(); runCurrent()
        assertFalse(repository.calls.contains("restore"))
        saved.complete(Unit); advanceUntilIdle()
        assertEquals(fastest, (repository.connectionState.value as ConnectionState.Connected).server)
        assertEquals(listOf("pause", "connect:fast"), repository.calls)
    }

    @Test fun `retest inherits pause and ignores retired progress while awaiting cancellation`() = runTest {
        val finishOld = CompletableDeferred<Unit>()
        var runs = 0
        coEvery { speed(any(), any()) } coAnswers {
            if (++runs == 1) {
                val progress = secondArg<(Int, Int, SpeedTestResult) -> Unit>()
                withContext(NonCancellable) { finishOld.await(); progress(1, 1, result) }
                emptyList()
            } else listOf(result)
        }
        runCurrent(); vm.onShowSpeedTest(); runCurrent(); vm.onShowSpeedTest(); runCurrent()
        assertEquals(1, runs)
        finishOld.complete(Unit); runCurrent()
        assertEquals(2, runs)
        assertEquals(listOf("pause", "restore"), repository.calls)
        assertEquals(SpeedTestState.Completed(listOf(result)), vm.speedTestState.value)
    }

    @Test fun `immediate dismiss of a waiting retest still cleans up the inherited pause`() = runTest {
        val finishOld = CompletableDeferred<Unit>()
        coEvery { speed(any(), any()) } coAnswers { withContext(NonCancellable) { finishOld.await() }; emptyList() }
        runCurrent(); vm.onShowSpeedTest(); runCurrent()
        vm.onShowSpeedTest(); vm.onDismissSpeedTest(); runCurrent()
        finishOld.complete(Unit); runCurrent()
        assertEquals(listOf("pause", "restore"), repository.calls)
        assertEquals(SpeedTestState.Idle, vm.speedTestState.value)
        coVerify(exactly = 1) { speed(any(), any()) }
    }

    @Test fun `clearing view model during pause acknowledgement still restores`() = runTest {
        val paused = CompletableDeferred<Unit>()
        repository.pauseGate = paused
        coEvery { speed(any(), any()) } returns emptyList()
        runCurrent(); vm.onShowSpeedTest(); runCurrent(); vm.viewModelScope.cancel()
        paused.complete(Unit); runCurrent()
        assertEquals(listOf("pause", "restore"), repository.calls)
        coVerify(exactly = 0) { speed(any(), any()) }
    }

    @Test fun `measurement failure restores and retains visible error`() = runTest {
        coEvery { speed(any(), any()) } throws IllegalStateException("fixture failed")
        runCurrent(); vm.onShowSpeedTest(); runCurrent()
        assertEquals(listOf("pause", "restore"), repository.calls)
        assertEquals(SpeedTestState.Error("fixture failed"), vm.speedTestState.value)
    }

    @Test fun `initially disconnected measurement never creates a connection`() = runTest {
        repository.connectionState.value = ConnectionState.Disconnected
        coEvery { speed(any(), any()) } returns emptyList()
        runCurrent(); vm.onShowSpeedTest(); runCurrent()
        assertEquals(emptyList<String>(), repository.calls)
        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
    }

    @Test fun `external disconnect supersedes measurement restoration`() = runTest {
        val measured = CompletableDeferred<Unit>()
        coEvery { speed(any(), any()) } coAnswers { measured.await(); emptyList() }
        runCurrent(); vm.onShowSpeedTest(); runCurrent(); repository.disconnect()
        measured.complete(Unit); runCurrent()
        assertFalse(repository.calls.contains("restore"))
        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
    }

    @Test fun `measurement snapshots selected protocol before waiting for pause`() = runTest {
        val dual = original.copy(dohUrl = "https://dns.example/dns-query", supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH))
        val paused = CompletableDeferred<Unit>()
        repository.pauseGate = paused
        coEvery { speed(any(), any()) } returns emptyList()
        runCurrent(); vm.onDnsServerSelected(dual); vm.onDnsProtocolSelected(DnsProtocol.DOH); runCurrent()
        vm.onShowSpeedTest(); runCurrent()
        vm.onDnsProtocolSelected(DnsProtocol.STANDARD); runCurrent()
        paused.complete(Unit); runCurrent()
        coVerify(exactly = 1) { speed(DnsProtocol.DOH, any()) }
    }

    @Test fun `activating a result persists its tested protocol before connecting`() = runTest {
        val dual = fastest.copy(dohUrl = "https://dns.example/dns-query", supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH))
        runCurrent()
        vm.onSpeedTestResultSelected(result.copy(server = dual, testedProtocol = DnsProtocol.DOH))
        advanceUntilIdle()
        assertEquals(DnsProtocol.DOH, vm.selectedProtocol.value)
        coVerifyOrder {
            preferences.setSelectedDnsId(dual.id)
            preferences.setSelectedDnsProtocol(DnsProtocol.DOH)
        }
        assertEquals(dual, (repository.connectionState.value as ConnectionState.Connected).server)
    }

    @Test fun `unreachable or unsupported speed results cannot activate a connection`() = runTest {
        runCurrent()
        vm.onSpeedTestResultSelected(result.copy(isReachable = false))
        vm.onSpeedTestResultSelected(result.copy(testedProtocol = DnsProtocol.DOH))
        runCurrent()
        assertTrue(repository.calls.isEmpty())
    }

    private inner class TestVpn : VpnRepository {
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected(original, 1))
        val calls = mutableListOf<String>()
        var pauseGate: CompletableDeferred<Unit>? = null
        private var revision = 0
        private var pausedRevision = 0
        private var token: SpeedTestPause? = null
        override fun invalidateSpeedTestRestoration() { revision++ }
        override fun isVpnPrepared() = true
        override suspend fun connect(server: DnsServer) {
            invalidateSpeedTestRestoration(); calls += "connect:${server.id}"
            connectionState.value = ConnectionState.Connected(server, 1)
        }
        override suspend fun disconnect() {
            invalidateSpeedTestRestoration(); calls += "disconnect"
            connectionState.value = ConnectionState.Disconnected
        }
        override suspend fun pauseForSpeedTest(): SpeedTestPause? {
            if (connectionState.value !is ConnectionState.Connected) return null
            calls += "pause"; pausedRevision = revision
            connectionState.value = ConnectionState.Disconnected
            pauseGate?.await()
            return SpeedTestPause().also { token = it }
        }
        override suspend fun restoreAfterSpeedTest(pause: SpeedTestPause) {
            if (pause !== token) return
            token = null
            if (revision == pausedRevision) { calls += "restore"; connectionState.value = ConnectionState.Connected(original, 1) }
        }
    }
}
