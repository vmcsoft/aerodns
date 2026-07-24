package com.vmcsoft.aerodns.data.repository

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvent
import com.vmcsoft.aerodns.data.vpn.DnsVpnServiceEvents
import com.vmcsoft.aerodns.data.vpn.NetworkMonitor
import com.vmcsoft.aerodns.data.vpn.NetworkState
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.buildDnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.requiresPacketLoop
import com.vmcsoft.aerodns.domain.model.resolveSelectedProtocol
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import com.vmcsoft.aerodns.domain.repository.SettingsRepository
import com.vmcsoft.aerodns.domain.repository.VpnRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class VpnRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkMonitor: NetworkMonitor,
    private val networkCapabilitiesRepository: NetworkCapabilitiesRepository,
    private val settingsRepository: SettingsRepository
) : VpnRepository {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private var lastConnectedServer: DnsServer? = null
    private var lastConnectedConfig: DnsConnectionConfig? = null
    private var isReconnecting = false

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
        when (event) {
            is DnsVpnServiceEvent.Established -> {
                // connect() owns the successful transition because it still has the DnsServer model.
            }
            is DnsVpnServiceEvent.Failed -> {
                if (event.config?.connectionRequestId == lastConnectedConfig?.connectionRequestId) {
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
        val server = lastConnectedServer ?: return

        operationMutex.withLock {
            if (isReconnecting) {
                Log.d(TAG, "Already reconnecting, skipping")
                return
            }

            isReconnecting = true

            try {
                // Brief delay to allow network to stabilize
                delay(RECONNECT_DELAY_MS)

                // Check if network is actually available
                if (!networkMonitor.isNetworkAvailable()) {
                    Log.d(TAG, "Network not available, skipping reconnect")
                    return
                }

                // Disconnect and reconnect
                Log.d(TAG, "Reconnecting to ${server.name}...")
                disconnectInternal(clearLastServer = false)
                delay(RECONNECT_DELAY_MS)
                connectInternal(server)

            } catch (e: Exception) {
                Log.e(TAG, "Error during reconnect", e)
                _connectionState.value = ConnectionState.Error("Reconnection failed: ${e.message}")
            } finally {
                isReconnecting = false
            }
        }
    }

    override suspend fun connect(server: DnsServer) = operationMutex.withLock {
        connectInternal(server)
    }

    private suspend fun connectInternal(server: DnsServer) {
        if (_connectionState.value is ConnectionState.Connected) {
            disconnectInternal(clearLastServer = true)
        }

        _connectionState.value = ConnectionState.Connecting

        try {
            val stack = networkCapabilitiesRepository.getActiveNetworkIpStack()
            val selectedProtocol = server.resolveSelectedProtocol(settingsRepository.getSelectedDnsProtocol())
            val enablePacketLoop = settingsRepository.isExperimentalPacketLoopEnabled() ||
                selectedProtocol.requiresPacketLoop()
            val dnsConfig = buildDnsConnectionConfig(server, stack, selectedProtocol).getOrElse { error ->
                Log.e(TAG, "Invalid DNS config for ${server.name} on network $stack", error)
                _connectionState.value = ConnectionState.Error(
                    error.message ?: "No compatible DNS addresses for current network"
                )
                return
            }.copy(
                connectionRequestId = buildConnectionRequestId(server),
                enableExperimentalPacketLoop = enablePacketLoop
            )
            lastConnectedConfig = dnsConfig

            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_CONNECT
                putExtra(DnsVpnService.EXTRA_DNS_CONFIG, dnsConfig)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            when (val event = awaitConnectResult(dnsConfig)) {
                is DnsVpnServiceEvent.Established -> {
                    lastConnectedServer = server
                    lastConnectedConfig = dnsConfig
                    _connectionState.value = ConnectionState.Connected(
                        server = server,
                        connectedAtMillis = System.currentTimeMillis()
                    )

                    Log.d(TAG, "Connected to ${server.name}")
                }
                is DnsVpnServiceEvent.Failed -> {
                    lastConnectedConfig = null
                    _connectionState.value = ConnectionState.Error(event.message)
                }
                else -> {
                    lastConnectedConfig = null
                    _connectionState.value = ConnectionState.Error("VPN connection timed out")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            lastConnectedConfig = null
            _connectionState.value = ConnectionState.Error(e.message ?: "Failed to connect")
        }
    }

    override suspend fun disconnect() = operationMutex.withLock {
        disconnectInternal(clearLastServer = true)
    }

    private suspend fun disconnectInternal(clearLastServer: Boolean) {
        val configToStop = lastConnectedConfig
        _connectionState.value = ConnectionState.Disconnecting

        try {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)

            awaitDisconnectResult(configToStop)
            if (clearLastServer) {
                lastConnectedServer = null
            }
            lastConnectedConfig = null
            _connectionState.value = ConnectionState.Disconnected

            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Failed to disconnect")
        }
    }

    override fun isVpnPrepared(): Boolean {
        return VpnService.prepare(context) == null
    }

    private suspend fun awaitConnectResult(config: DnsConnectionConfig): DnsVpnServiceEvent? {
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            DnsVpnServiceEvents.events.first { event ->
                when (event) {
                    is DnsVpnServiceEvent.Established ->
                        event.config.connectionRequestId == config.connectionRequestId
                    is DnsVpnServiceEvent.Failed ->
                        event.config?.connectionRequestId == config.connectionRequestId
                    is DnsVpnServiceEvent.Stopped -> false
                }
            }
        }
    }

    private suspend fun awaitDisconnectResult(config: DnsConnectionConfig?) {
        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
            DnsVpnServiceEvents.events.first { event ->
                event is DnsVpnServiceEvent.Stopped &&
                    (config == null || event.config?.connectionRequestId == config.connectionRequestId)
            }
        }
    }

    private fun buildConnectionRequestId(server: DnsServer): String {
        return "${server.id}-${System.currentTimeMillis()}-${System.nanoTime()}"
    }
}
