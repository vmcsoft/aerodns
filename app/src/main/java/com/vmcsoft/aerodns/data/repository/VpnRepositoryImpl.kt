package com.vmcsoft.aerodns.data.repository

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.vmcsoft.aerodns.data.local.DnsProviderData
import com.vmcsoft.aerodns.data.vpn.VpnRecoveryStore
import java.util.concurrent.atomic.AtomicLong
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.data.vpn.NetworkMonitor
import com.vmcsoft.aerodns.data.vpn.NetworkState
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsHealth
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.buildDnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.requiresPacketLoop
import com.vmcsoft.aerodns.domain.model.resolveSelectedProtocol
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import com.vmcsoft.aerodns.domain.repository.SettingsRepository
import com.vmcsoft.aerodns.domain.repository.VpnRepository
import com.vmcsoft.aerodns.domain.repository.SpeedTestPause
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnRepositoryImpl internal constructor(
    @ApplicationContext private val context: Context,
    private val networkMonitor: NetworkMonitor,
    private val networkCapabilitiesRepository: NetworkCapabilitiesRepository,
    private val settingsRepository: SettingsRepository,
    private val repositoryScope: CoroutineScope
) : VpnRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        networkMonitor: NetworkMonitor,
        networkCapabilitiesRepository: NetworkCapabilitiesRepository,
        settingsRepository: SettingsRepository
    ) : this(context, networkMonitor, networkCapabilitiesRepository, settingsRepository,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val operationMutex = Mutex()
    private var lastConnectedServer: DnsServer? = null
    private var lastConnectedConfig: DnsConnectionConfig? = null
    private var isReconnecting = false
    private val userOperation = AtomicLong()
    private val restorationRevision = AtomicLong()
    private data class PausedConnection(
        val token: SpeedTestPause, val operation: Long, val revision: Long, val server: DnsServer,
        val config: DnsConnectionConfig, val stopped: DnsVpnServiceEvent.Stopped
    )
    private var pausedConnection: PausedConnection? = null

    companion object {
        private const val TAG = "VpnRepositoryImpl"
        private const val RECONNECT_DELAY_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val DISCONNECT_TIMEOUT_MS = 2_000L
    }

    init {
        // Monitor network changes and auto-reconnect
        repositoryScope.launch {
            networkMonitor.networkState.collect { networkState ->
                handleNetworkChange(networkState)
            }
        }

        repositoryScope.launch {
            DnsVpnServiceEvents.events.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: DnsVpnServiceEvent) {
        pausedConnection?.let { pause ->
            // A later service action (including a system/notification stop) supersedes
            // the pause. The pause's own delayed stop delivery is harmless.
            if (event !== pause.stopped) {
                invalidateSpeedTestRestoration()
                pausedConnection = null
            }
        }
        when (event) {
            is DnsVpnServiceEvent.Established -> {
                // The command waiter and collector may both receive this event. Do not
                // revive it after the service has already emitted a newer terminal state.
                if (DnsVpnServiceEvents.events.replayCache.lastOrNull() !== event) return
                if (_connectionState.value is ConnectionState.Disconnecting) return
                val config = event.config
                val pending = lastConnectedConfig
                if (_connectionState.value is ConnectionState.Connecting && pending != null &&
                    pending.connectionRequestId != config.connectionRequestId) return
                val current = _connectionState.value as? ConnectionState.Connected
                // A completed probe belongs only to its established interface. A fresh
                // repository may adopt the latest service snapshot after restoration.
                if (current != null && current.activeConfig != config && event.health != DnsHealth.Checking) return
                if (current?.activeConfig == config && current.dnsHealth == event.health && current.controlPolicy == event.controlPolicy) return
                val server = lastConnectedServer?.takeIf { it.id == config.serverId }
                    ?: DnsProviderData.providers.firstOrNull { it.id == config.serverId }
                    ?: config.toRestoredServer()
                lastConnectedServer = server
                lastConnectedConfig = config
                _connectionState.value = ConnectionState.Connected(
                    server,
                    current?.takeIf { it.activeConfig == config }?.connectedAtMillis ?: System.currentTimeMillis(),
                    currentPingMs = (event.health as? DnsHealth.Healthy)?.latencyMs,
                    activeConfig = config, dnsHealth = event.health, controlPolicy = event.controlPolicy
                )
            }
            is DnsVpnServiceEvent.Failed -> {
                if (event.config?.connectionRequestId == lastConnectedConfig?.connectionRequestId ||
                    (event.config == null && _connectionState.value is ConnectionState.Connecting)) {
                    lastConnectedServer = null
                    lastConnectedConfig = null
                    _connectionState.value = ConnectionState.Error(event.message)
                }
            }
            is DnsVpnServiceEvent.Stopped -> {
                if (_connectionState.value is ConnectionState.Error) {
                    return
                }
                val currentConfig = lastConnectedConfig
                if (
                    currentConfig == null ||
                    event.config?.connectionRequestId == currentConfig.connectionRequestId
                ) {
                    if (!isReconnecting) {
                        lastConnectedServer = null
                    }
                    lastConnectedConfig = null
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    private suspend fun handleNetworkChange(networkState: NetworkState) {
        when (networkState) {
            is NetworkState.Available, is NetworkState.Changed -> {
                // Network is available or changed, attempt reconnect if we were connected
                val currentState = _connectionState.value
                if (currentState is ConnectionState.Connected && !isReconnecting) {
                    Log.d(TAG, "Network changed, reconnecting VPN...")
                    reconnectVpn()
                }
            }
            is NetworkState.Lost -> {
                Log.d(TAG, "Network lost, VPN will reconnect when network is restored")
                // Don't disconnect, let VPN handle it gracefully
            }
        }
    }

    private suspend fun reconnectVpn() {
        val operation = userOperation.get()
        operationMutex.withLock {
            val server = lastConnectedServer ?: return
            val activeConfig = lastConnectedConfig ?: return
            if (_connectionState.value !is ConnectionState.Connected || operation != userOperation.get()) return
            if (isReconnecting) {
                Log.d(TAG, "Already reconnecting, skipping")
                return
            }

            isReconnecting = true

            try {
                // Brief delay to allow network to stabilize
                delay(RECONNECT_DELAY_MS)

                // A newer user action or external service stop supersedes this reconnect.
                if (operation != userOperation.get() || lastConnectedConfig != activeConfig) return
                // Check if network is actually available
                if (!networkMonitor.isNetworkAvailable()) {
                    Log.d(TAG, "Network not available, skipping reconnect")
                    return
                }

                // Disconnect and reconnect
                Log.d(TAG, "Reconnecting to ${server.name}...")
                if ((_connectionState.value as? ConnectionState.Connected)?.controlPolicy?.alwaysOn != true) {
                    if (!disconnectInternal(clearLastServer = false)) return
                    delay(RECONNECT_DELAY_MS)
                }
                if (operation != userOperation.get() ||
                    VpnRecoveryStore(context).load()?.connectionRequestId != activeConfig.connectionRequestId) return
                connectInternal(server, activeConfig)

            } catch (e: Exception) {
                Log.e(TAG, "Error during reconnect", e)
                _connectionState.value = ConnectionState.Error("Reconnection failed: ${e.message}")
            } finally {
                isReconnecting = false
            }
        }
    }

    override suspend fun connect(server: DnsServer) {
        val operation = userOperation.incrementAndGet()
        // Serialize state mutations with service events, while keeping the caller's cancellation.
        withContext(repositoryScope.coroutineContext.minusKey(Job)) {
            operationMutex.withLock {
                if (operation == userOperation.get()) connectInternal(server, isCurrent = { operation == userOperation.get() })
            }
        }
    }

    private suspend fun connectInternal(
        server: DnsServer, restoredConfig: DnsConnectionConfig? = null,
        restoreExactConfig: Boolean = false, isCurrent: () -> Boolean = { true }
    ) {
        if ((_connectionState.value as? ConnectionState.Connected)?.controlPolicy?.alwaysOn == false) {
            if (!disconnectInternal(clearLastServer = true) &&
                (_connectionState.value as? ConnectionState.Connected)?.controlPolicy?.alwaysOn != true) return
        }

        if (!isCurrent()) return
        val retainedConnection = _connectionState.value as? ConnectionState.Connected
        _connectionState.value = ConnectionState.Connecting

        try {
            val dnsConfig = if (restoredConfig != null && restoreExactConfig) {
                restoredConfig.copy(connectionRequestId = buildConnectionRequestId(server))
            } else {
                val stack = networkCapabilitiesRepository.getActiveNetworkIpStack()
                val selectedProtocol = restoredConfig?.protocol
                    ?: server.resolveSelectedProtocol(settingsRepository.getSelectedDnsProtocol())
                val enablePacketLoop = restoredConfig?.enableExperimentalPacketLoop
                    ?: (settingsRepository.isExperimentalPacketLoopEnabled() || selectedProtocol.requiresPacketLoop())
                buildDnsConnectionConfig(server, stack, selectedProtocol).getOrElse { error ->
                    Log.e(TAG, "Invalid DNS config for ${server.name} on network $stack", error)
                    _connectionState.value = retainedConnection ?: ConnectionState.Error(
                        error.message ?: "No compatible DNS addresses for current network"
                    )
                    return
                }.copy(
                    connectionRequestId = buildConnectionRequestId(server),
                    enableExperimentalPacketLoop = enablePacketLoop
                )
            }
            // Preferences/network reads can suspend while a newer choice is made.
            if (!isCurrent()) return
            lastConnectedConfig = dnsConfig
            lastConnectedServer = server

            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_CONNECT
                putExtra(DnsVpnService.EXTRA_DNS_CONFIG, dnsConfig)
            }
            val previousEvent = DnsVpnServiceEvents.events.replayCache.lastOrNull()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            when (val event = awaitConnectResult(dnsConfig, previousEvent)) {
                is DnsVpnServiceEvent.Established -> {
                    handleServiceEvent(event)

                    Log.d(TAG, "Connected to ${server.name}")
                }
                is DnsVpnServiceEvent.Failed -> {
                    lastConnectedConfig = null
                    _connectionState.value = ConnectionState.Error(event.message)
                }
                else -> {
                    lastConnectedConfig = null
                    // Cancel only this timed-out attempt, never a newer service connection.
                    context.startService(Intent(context, DnsVpnService::class.java).apply {
                        action = DnsVpnService.ACTION_DISCONNECT
                        putExtra(DnsVpnService.EXTRA_DISCONNECT_REQUEST_ID, dnsConfig.connectionRequestId)
                    })
                    _connectionState.value = ConnectionState.Error("VPN connection timed out")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            lastConnectedConfig = null
            _connectionState.value = ConnectionState.Error(e.message ?: "Failed to connect")
        }
    }

    override suspend fun disconnect() {
        if ((_connectionState.value as? ConnectionState.Connected)?.controlPolicy?.alwaysOn == true) return
        val operation = userOperation.incrementAndGet()
        // Serialize state mutations with service events, while keeping the caller's cancellation.
        withContext(repositoryScope.coroutineContext.minusKey(Job)) {
            operationMutex.withLock {
                if (operation == userOperation.get()) disconnectInternal(clearLastServer = true)
            }
        }
    }

    private suspend fun disconnectInternal(clearLastServer: Boolean): Boolean {
        val previousState = _connectionState.value
        val configToStop = lastConnectedConfig
        _connectionState.value = ConnectionState.Disconnecting

        try {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_DISCONNECT
                putExtra(DnsVpnService.EXTRA_PRESERVE_RECOVERY, !clearLastServer)
            }
            val previousEvent = DnsVpnServiceEvents.events.replayCache.lastOrNull()
            context.startService(intent)

            if (awaitDisconnectResult(configToStop, previousEvent) == null) {
                _connectionState.value = previousState
                DnsVpnServiceEvents.events.replayCache.lastOrNull()?.let(::handleServiceEvent)
                return false
            }
            if (clearLastServer) {
                lastConnectedServer = null
            }
            lastConnectedConfig = null
            _connectionState.value = ConnectionState.Disconnected

            Log.d(TAG, "Disconnected")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Failed to disconnect")
            return false
        }
    }

    override fun invalidateSpeedTestRestoration() {
        restorationRevision.incrementAndGet()
    }

    override suspend fun pauseForSpeedTest(): SpeedTestPause? {
        val operation = userOperation.incrementAndGet()
        val revision = restorationRevision.get()
        return withContext(repositoryScope.coroutineContext.minusKey(Job)) {
            operationMutex.withLock {
                if (operation != userOperation.get() || revision != restorationRevision.get()) return@withLock null
                val connected = _connectionState.value as? ConnectionState.Connected ?: return@withLock null
                check(!connected.controlPolicy.alwaysOn) { "Turn off Always-on VPN in Android VPN settings before running a speed test." }
                val config = connected.activeConfig ?: return@withLock null
                check(disconnectInternal(clearLastServer = true)) { "VPN did not stop for the speed test" }
                val stopped = DnsVpnServiceEvents.events.replayCache.lastOrNull() as? DnsVpnServiceEvent.Stopped
                if (operation != userOperation.get() || revision != restorationRevision.get()) return@withLock null
                check(_connectionState.value == ConnectionState.Disconnected && stopped?.config == config) {
                    "VPN did not stop for the speed test"
                }
                SpeedTestPause().also { token ->
                    pausedConnection = PausedConnection(token, operation, revision, connected.server, config, requireNotNull(stopped))
                }
            }
        }
    }

    override suspend fun restoreAfterSpeedTest(pause: SpeedTestPause) {
        withContext(repositoryScope.coroutineContext.minusKey(Job)) {
            operationMutex.withLock {
                val saved = pausedConnection?.takeIf { it.token === pause } ?: return@withLock
                pausedConnection = null // Consume once, including invalidated/failed attempts.
                if (saved.operation != userOperation.get() || saved.revision != restorationRevision.get() ||
                    _connectionState.value != ConnectionState.Disconnected ||
                    DnsVpnServiceEvents.events.replayCache.lastOrNull() !== saved.stopped) return@withLock
                connectInternal(saved.server, saved.config, restoreExactConfig = true,
                    isCurrent = { saved.operation == userOperation.get() && saved.revision == restorationRevision.get() })
            }
        }
    }

    override fun isVpnPrepared(): Boolean {
        return VpnService.prepare(context) == null
    }

    private suspend fun awaitConnectResult(
        config: DnsConnectionConfig,
        previousEvent: DnsVpnServiceEvent?
    ): DnsVpnServiceEvent? {
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            DnsVpnServiceEvents.events.first { event ->
                if (event === previousEvent) return@first false
                when (event) {
                    is DnsVpnServiceEvent.Established ->
                        event.config.connectionRequestId == config.connectionRequestId
                    is DnsVpnServiceEvent.Failed ->
                        event.config == null || event.config.connectionRequestId == config.connectionRequestId
                    is DnsVpnServiceEvent.Stopped -> false
                }
            }
        }
    }

    private suspend fun awaitDisconnectResult(config: DnsConnectionConfig?, previous: DnsVpnServiceEvent?): DnsVpnServiceEvent? {
        return withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
            DnsVpnServiceEvents.events.first { event ->
                event !== previous && event is DnsVpnServiceEvent.Stopped &&
                    (config == null || event.config?.connectionRequestId == config.connectionRequestId)
            }
        }
    }

    private fun buildConnectionRequestId(server: DnsServer): String {
        return "${server.id}-${System.currentTimeMillis()}-${System.nanoTime()}"
    }
}

private fun DnsConnectionConfig.toRestoredServer(): DnsServer {
    val ipv4 = upstreamAddresses.filterNot { ':' in it }
    val ipv6 = upstreamAddresses.filter { ':' in it }
    return DnsServer(
        id = serverId, name = displayName, primary = ipv4.firstOrNull().orEmpty(),
        secondary = ipv4.getOrNull(1), ipv6Primary = ipv6.firstOrNull(), ipv6Secondary = ipv6.getOrNull(1),
        dohUrl = dohUrl, customBootstrapIp = customBootstrapIp,
        allowUntrustedCertificates = allowUntrustedCertificates, dotHostname = dotHostname,
        supportedProtocols = listOf(protocol), isCustom = true
    )
}
