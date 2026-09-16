package com.vmcsoft.aerodns.data.repository

import android.util.Log
import android.content.Context
import android.content.Intent
import com.vmcsoft.aerodns.data.vpn.*
import com.vmcsoft.aerodns.domain.model.*
import com.vmcsoft.aerodns.domain.repository.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.Serializable

@OptIn(ExperimentalCoroutinesApi::class)
class VpnSpeedTestPauseTest {
    private val events = MutableSharedFlow<DnsVpnServiceEvent>(replay = 1, extraBufferCapacity = 32)
    private val networks = MutableSharedFlow<NetworkState>(extraBufferCapacity = 8)
    private val monitor = mockk<NetworkMonitor> { every { networkState } returns networks }
    private val context = mockk<Context>()
    private val capabilities = mockk<NetworkCapabilitiesRepository>()
    private val settings = mockk<SettingsRepository>()
    private val original = DnsConnectionConfig("old", "Original", DnsProtocol.DOH, emptyList(),
        "https://custom.example/dns-query", "192.0.2.1", true,
        connectionRequestId = "original-request", enableExperimentalPacketLoop = true)
    private val newer = DnsServer("new", "New", "9.9.9.9")
    private var active: DnsConnectionConfig? = original
    private var pending: DnsConnectionConfig? = null
    private var action: String? = null
    private var acknowledgeStop = true
    private val commands = mutableListOf<String?>()

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        mockkObject(DnsVpnServiceEvents)
        every { DnsVpnServiceEvents.events } returns events
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { action = firstArg(); self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Serializable>()) } answers {
            if (firstArg<String>() == DnsVpnService.EXTRA_DNS_CONFIG) pending = secondArg()
            self as Intent
        }
        every { context.startService(any()) } answers { deliver(); mockk() }
        every { context.startForegroundService(any()) } answers { deliver(); mockk() }
        coEvery { capabilities.getActiveNetworkIpStack() } returns NetworkIpStack.IPv4_ONLY
        coEvery { settings.getSelectedDnsProtocol() } returns DnsProtocol.STANDARD
        coEvery { settings.isExperimentalPacketLoopEnabled() } returns false
    }
    @After fun cleanup() { unmockkConstructor(Intent::class); unmockkObject(DnsVpnServiceEvents); unmockkStatic(Log::class) }

    private fun deliver() {
        commands += action
        if (action == DnsVpnService.ACTION_CONNECT) {
            active = requireNotNull(pending)
            events.tryEmit(DnsVpnServiceEvent.Established(active!!))
        } else if (action == DnsVpnService.ACTION_DISCONNECT && acknowledgeStop) {
            events.tryEmit(DnsVpnServiceEvent.Stopped(active)); active = null
        }
    }
    private suspend fun TestScope.repository(): VpnRepositoryImpl {
        events.emit(DnsVpnServiceEvent.Established(original))
        val repository = VpnRepositoryImpl(context, monitor, capabilities, settings, backgroundScope)
        runCurrent()
        return repository
    }

    @Test fun `restore consumes token once and preserves actual protocol endpoint and TLS policy`() = runTest {
        val repository = repository()
        val token = requireNotNull(repository.pauseForSpeedTest())
        runCurrent() // Delayed delivery of our own stop must not invalidate the token.
        repository.restoreAfterSpeedTest(token)
        repository.restoreAfterSpeedTest(token)
        assertEquals(listOf(DnsVpnService.ACTION_DISCONNECT, DnsVpnService.ACTION_CONNECT), commands)
        assertEquals(original.copy(connectionRequestId = ""), active!!.copy(connectionRequestId = ""))
        assertNotEquals(original.connectionRequestId, active!!.connectionRequestId)
        coVerify(exactly = 0) { settings.getSelectedDnsProtocol() }
    }

    @Test fun `new connect wins over cleanup`() = runTest {
        val repository = repository()
        val token = requireNotNull(repository.pauseForSpeedTest())
        repository.connect(newer); repository.restoreAfterSpeedTest(token)
        assertEquals(newer.id, active!!.serverId)
        assertEquals(1, commands.count { it == DnsVpnService.ACTION_CONNECT })
    }

    @Test fun `explicit disconnect while already paused prevents restoration`() = runTest {
        val repository = repository()
        val token = requireNotNull(repository.pauseForSpeedTest())
        repository.disconnect(); repository.restoreAfterSpeedTest(token)
        assertNull(active)
        assertFalse(commands.contains(DnsVpnService.ACTION_CONNECT))
    }

    @Test fun `selection invalidation prevents restoration without a connection state change`() = runTest {
        val repository = repository()
        val token = requireNotNull(repository.pauseForSpeedTest())
        repository.invalidateSpeedTestRestoration(); repository.restoreAfterSpeedTest(token)
        assertFalse(commands.contains(DnsVpnService.ACTION_CONNECT))
    }

    @Test fun `external service connect supersedes pause`() = runTest {
        val repository = repository()
        val token = requireNotNull(repository.pauseForSpeedTest())
        val external = original.copy(connectionRequestId = "external", displayName = "External")
        events.emit(DnsVpnServiceEvent.Established(external)); runCurrent()
        repository.restoreAfterSpeedTest(token)
        assertEquals(external, (repository.connectionState.value as ConnectionState.Connected).activeConfig)
        assertFalse(commands.contains(DnsVpnService.ACTION_CONNECT))
    }

    @Test fun `later system stop supersedes pause even when already disconnected`() = runTest {
        val repository = repository()
        val token = requireNotNull(repository.pauseForSpeedTest())
        events.emit(DnsVpnServiceEvent.Stopped(null)); runCurrent()
        repository.restoreAfterSpeedTest(token)
        assertFalse(commands.contains(DnsVpnService.ACTION_CONNECT))
    }

    @Test fun `choice made while pause awaits acknowledgement prevents token creation`() = runTest {
        val repository = repository()
        acknowledgeStop = false
        val pausing = async { repository.pauseForSpeedTest() }
        runCurrent(); repository.invalidateSpeedTestRestoration()
        events.emit(DnsVpnServiceEvent.Stopped(original)); runCurrent()
        assertNull(pausing.await())
    }

    @Test fun `network changes during measurement do not start a competing reconnect`() = runTest {
        val repository = repository()
        val token = requireNotNull(repository.pauseForSpeedTest())
        networks.emit(NetworkState.Lost); runCurrent()
        networks.emit(NetworkState.Available); runCurrent()
        networks.emit(NetworkState.Changed(mockk())); runCurrent()
        assertEquals(listOf(DnsVpnService.ACTION_DISCONNECT), commands)
        repository.restoreAfterSpeedTest(token)
        assertEquals(original.copy(connectionRequestId = ""), active!!.copy(connectionRequestId = ""))
        assertEquals(1, commands.count { it == DnsVpnService.ACTION_CONNECT })
    }

    @Test fun `missing stop acknowledgement fails instead of measuring through an active VPN`() = runTest {
        val repository = repository()
        acknowledgeStop = false
        val failure = runCatching { repository.pauseForSpeedTest() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("VPN did not stop for the speed test", failure!!.message)
        assertFalse(commands.contains(DnsVpnService.ACTION_CONNECT))
    }

    @Test fun `selection invalidation does not cancel a newer connection awaiting preferences`() = runTest {
        val repository = repository()
        val token = requireNotNull(repository.pauseForSpeedTest())
        val saved = CompletableDeferred<Unit>()
        coEvery { settings.getSelectedDnsProtocol() } coAnswers { saved.await(); DnsProtocol.STANDARD }
        val connecting = async { repository.connect(newer) }
        runCurrent()
        val restoring = async { repository.restoreAfterSpeedTest(token) }
        runCurrent(); repository.invalidateSpeedTestRestoration()
        saved.complete(Unit); connecting.await(); restoring.await()
        assertEquals(newer.id, active!!.serverId)
        assertEquals(1, commands.count { it == DnsVpnService.ACTION_CONNECT })
    }

    @Test fun `retired token cannot consume a newer pause`() = runTest {
        val repository = repository()
        val old = requireNotNull(repository.pauseForSpeedTest())
        repository.connect(newer)
        val current = requireNotNull(repository.pauseForSpeedTest())
        repository.restoreAfterSpeedTest(old)
        assertNull(active)
        repository.restoreAfterSpeedTest(current)
        assertEquals(newer.id, active!!.serverId)
        assertEquals(2, commands.count { it == DnsVpnService.ACTION_CONNECT })
    }
}
