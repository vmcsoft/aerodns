package com.vmcsoft.aerodns.data.repository

import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.data.vpn.NetworkMonitor
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnRepositoryRecoveryTest {
    private val events = MutableSharedFlow<DnsVpnServiceEvent>(replay = 1, extraBufferCapacity = 8)
    private val networkMonitor = mockk<NetworkMonitor> { every { networkState } returns emptyFlow() }
    private val config = DnsConnectionConfig("saved-custom", "Saved custom", DnsProtocol.DOH,
        emptyList(), "https://resolver.example/dns-query", "2001:db8::1", true,
        connectionRequestId = "restored-1", enableExperimentalPacketLoop = true)

    @Before fun before() {
        mockkObject(DnsVpnServiceEvents)
        every { DnsVpnServiceEvents.events } returns events
    }
    @After fun after() = unmockkObject(DnsVpnServiceEvents)

    @Test fun `repository created after service restoration reads actual saved settings`() = runTest {
        events.emit(DnsVpnServiceEvent.Established(config))
        val repository = VpnRepositoryImpl(mockk(), networkMonitor, mockk(), mockk(), backgroundScope)
        runCurrent()
        val state = repository.connectionState.value as ConnectionState.Connected
        assertEquals(config, state.activeConfig)
        assertEquals("Saved custom", state.server.name)
        assertEquals(config.dohUrl, state.server.dohUrl)
        assertEquals(config.customBootstrapIp, state.server.customBootstrapIp)
        assertTrue(state.server.allowUntrustedCertificates)
        assertEquals(listOf(DnsProtocol.DOH), state.server.supportedProtocols)
    }

    @Test fun `old stopped and failed events cannot clear a replacement connection`() = runTest {
        val repository = VpnRepositoryImpl(mockk(), networkMonitor, mockk(), mockk(), backgroundScope)
        events.emit(DnsVpnServiceEvent.Established(config))
        runCurrent()
        val replacement = config.copy(connectionRequestId = "restored-2")
        events.emit(DnsVpnServiceEvent.Established(replacement))
        runCurrent()
        events.emit(DnsVpnServiceEvent.Stopped(config))
        events.emit(DnsVpnServiceEvent.Failed(config, "old loop failed"))
        runCurrent()
        assertEquals(replacement, (repository.connectionState.value as ConnectionState.Connected).activeConfig)
    }

    @Test fun `current forwarding failure clears connected state and remains a failure`() = runTest {
        val repository = VpnRepositoryImpl(mockk(), networkMonitor, mockk(), mockk(), backgroundScope)
        events.emit(DnsVpnServiceEvent.Established(config))
        runCurrent()
        events.emit(DnsVpnServiceEvent.Failed(config, "forwarding stopped"))
        runCurrent()
        assertEquals(ConnectionState.Error("forwarding stopped"), repository.connectionState.value)
        events.emit(DnsVpnServiceEvent.Stopped(config))
        runCurrent()
        assertEquals(ConnectionState.Error("forwarding stopped"), repository.connectionState.value)
    }

    @Test fun `actual service stop clears restored connection`() = runTest {
        val repository = VpnRepositoryImpl(mockk(), networkMonitor, mockk(), mockk(), backgroundScope)
        events.emit(DnsVpnServiceEvent.Established(config))
        runCurrent()
        events.emit(DnsVpnServiceEvent.Stopped(config))
        runCurrent()
        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
    }
}
