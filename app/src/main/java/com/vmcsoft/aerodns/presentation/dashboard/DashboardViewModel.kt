package com.vmcsoft.aerodns.presentation.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.SpeedTestResult
import com.vmcsoft.aerodns.domain.model.resolveSelectedProtocol
import com.vmcsoft.aerodns.domain.model.selectableProtocols
import com.vmcsoft.aerodns.domain.repository.VpnRepository
import com.vmcsoft.aerodns.domain.usecase.DeleteCustomDnsUseCase
import com.vmcsoft.aerodns.domain.usecase.GetDnsListUseCase
import com.vmcsoft.aerodns.domain.usecase.RunSpeedTestUseCase
import com.vmcsoft.aerodns.domain.usecase.SaveCustomDnsUseCase
import com.vmcsoft.aerodns.domain.usecase.UpdateCustomDnsUseCase
import com.vmcsoft.aerodns.presentation.components.SpeedTestState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val getDnsListUseCase: GetDnsListUseCase,
    private val runSpeedTestUseCase: RunSpeedTestUseCase,
    private val saveCustomDnsUseCase: SaveCustomDnsUseCase,
    private val updateCustomDnsUseCase: UpdateCustomDnsUseCase,
    private val deleteCustomDnsUseCase: DeleteCustomDnsUseCase,
    private val preferencesDataStore: com.vmcsoft.aerodns.data.local.PreferencesDataStore
) : ViewModel() {

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    val connectionState: StateFlow<ConnectionState> = vpnRepository.connectionState

    private val _selectedServer = MutableStateFlow<DnsServer?>(null)
    val selectedServer: StateFlow<DnsServer?> = _selectedServer.asStateFlow()

    private val _selectedProtocol = MutableStateFlow(DnsProtocol.STANDARD)
    val selectedProtocol: StateFlow<DnsProtocol> = _selectedProtocol.asStateFlow()

    private val _showDnsSelector = MutableStateFlow(false)
    val showDnsSelector: StateFlow<Boolean> = _showDnsSelector.asStateFlow()

    private val _dnsServers = MutableStateFlow<List<DnsServer>>(emptyList())
    val dnsServers: StateFlow<List<DnsServer>> = _dnsServers.asStateFlow()

    private val _speedTestState = MutableStateFlow<SpeedTestState>(SpeedTestState.Idle)
    val speedTestState: StateFlow<SpeedTestState> = _speedTestState.asStateFlow()

    private val _showSpeedTestDialog = MutableStateFlow(false)
    val showSpeedTestDialog: StateFlow<Boolean> = _showSpeedTestDialog.asStateFlow()

    private val _showCustomDnsDialog = MutableStateFlow(false)
    val showCustomDnsDialog: StateFlow<Boolean> = _showCustomDnsDialog.asStateFlow()

    private val _serverToEdit = MutableStateFlow<DnsServer?>(null)
    val serverToEdit: StateFlow<DnsServer?> = _serverToEdit.asStateFlow()

    private val _serverToDelete = MutableStateFlow<DnsServer?>(null)
    val serverToDelete: StateFlow<DnsServer?> = _serverToDelete.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _experimentalPacketLoopEnabled = MutableStateFlow(false)
    val experimentalPacketLoopEnabled: StateFlow<Boolean> = _experimentalPacketLoopEnabled.asStateFlow()

    private var speedTestJob: Job? = null
    private var speedTestGeneration = 0L
    private var speedTestPause: com.vmcsoft.aerodns.domain.repository.SpeedTestPause? = null
    private var speedTestPaused = false

    init {
        loadDnsServers()
        loadExperimentalPacketLoopSetting()
        observeConnectionState()
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        _errorMessage.value = null
                    }
                    is ConnectionState.Error -> {
                        _errorMessage.value = state.message
                    }
                    else -> Unit
                }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun loadExperimentalPacketLoopSetting() {
        viewModelScope.launch {
            _experimentalPacketLoopEnabled.value = preferencesDataStore.isExperimentalPacketLoopEnabled()
        }
    }

    fun onToggleExperimentalPacketLoop() {
        vpnRepository.invalidateSpeedTestRestoration()
        viewModelScope.launch {
            val enabled = !_experimentalPacketLoopEnabled.value
            preferencesDataStore.setExperimentalPacketLoopEnabled(enabled)
            _experimentalPacketLoopEnabled.value = enabled
            _errorMessage.value = if (enabled) {
                "Experimental packet loop enabled. Reconnect VPN to apply."
            } else {
                "Experimental packet loop disabled. Reconnect VPN to apply."
            }
        }
    }

    private fun loadDnsServers() {
        viewModelScope.launch {
            val servers = getDnsListUseCase()
            _dnsServers.value = servers

            // Load previously selected DNS from DataStore
            val savedDnsId = preferencesDataStore.getSelectedDnsId()
            val savedServer = savedDnsId?.let { id ->
                servers.find { it.id == id }
            }

            // Set saved server or default to first server
            _selectedServer.value = savedServer ?: servers.firstOrNull()
            _selectedServer.value?.let { server ->
                val savedProtocol = preferencesDataStore.getSelectedDnsProtocol()
                val resolvedProtocol = server.resolveSelectedProtocol(savedProtocol)
                _selectedProtocol.value = resolvedProtocol
                if (resolvedProtocol != savedProtocol) {
                    preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)
                }
            }

            // Save the selected server ID if it wasn't saved before
            _selectedServer.value?.let { server ->
                if (savedDnsId == null) {
                    preferencesDataStore.setSelectedDnsId(server.id)
                }
            }
        }
    }

    fun onConnectToggle() {
        vpnRepository.invalidateSpeedTestRestoration()
        viewModelScope.launch {
            when (connectionState.value) {
                is ConnectionState.Connected, is ConnectionState.Connecting -> {
                    vpnRepository.disconnect()
                }
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    _selectedServer.value?.let { server ->
                        vpnRepository.connect(server)
                    }
                }
                is ConnectionState.Disconnecting -> {
                    // Do nothing while disconnecting
                }
            }
        }
    }

    fun onDnsServerSelected(server: DnsServer) {
        vpnRepository.invalidateSpeedTestRestoration()
        _selectedServer.value = server
        val resolvedProtocol = resolveProtocolForServer(server)
        _selectedProtocol.value = resolvedProtocol
        _showDnsSelector.value = false

        viewModelScope.launch {
            preferencesDataStore.setSelectedDnsId(server.id)
            preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)

            // If already connected, reconnect with new server
            if (connectionState.value is ConnectionState.Connected) {
                vpnRepository.disconnect()
                delay(500)
                vpnRepository.connect(server)
            }
        }
    }

    fun onSelectAndConnectDns(server: DnsServer) {
        vpnRepository.invalidateSpeedTestRestoration()
        _selectedServer.value = server
        val resolvedProtocol = resolveProtocolForServer(server)
        _selectedProtocol.value = resolvedProtocol

        viewModelScope.launch {
            preferencesDataStore.setSelectedDnsId(server.id)
            preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)

            // Always connect to the selected server
            // If already connected, disconnect first
            if (connectionState.value is ConnectionState.Connected) {
                vpnRepository.disconnect()
                delay(500)
            }
            vpnRepository.connect(server)
        }
    }

    fun onShowDnsSelector() {
        _showDnsSelector.value = true
    }

    fun onDismissDnsSelector() {
        _showDnsSelector.value = false
    }

    fun getSelectableProtocols(server: DnsServer): List<DnsProtocol> {
        return server.selectableProtocols()
    }

    fun onDnsProtocolSelected(protocol: DnsProtocol) {
        val server = _selectedServer.value ?: return
        val resolvedProtocol = server.resolveSelectedProtocol(protocol)
        if (resolvedProtocol != protocol) {
            return
        }

        vpnRepository.invalidateSpeedTestRestoration()
        _selectedProtocol.value = resolvedProtocol
        viewModelScope.launch {
            preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)

            if (connectionState.value is ConnectionState.Connected) {
                vpnRepository.disconnect()
                delay(500)
                vpnRepository.connect(server)
            }
        }
    }

    fun onShowSpeedTest() {
        _showSpeedTestDialog.value = true
        _speedTestState.value = SpeedTestState.Running(
            completed = 0,
            total = _dnsServers.value.size
        )
        runSpeedTest()
    }

    fun onDismissSpeedTest() {
        _showSpeedTestDialog.value = false
        speedTestJob?.cancel()
        _speedTestState.value = SpeedTestState.Idle
    }

    fun onShowCustomDnsDialog(server: DnsServer? = null) {
        _serverToEdit.value = server
        _showCustomDnsDialog.value = true
    }

    fun onDismissCustomDnsDialog() {
        _showCustomDnsDialog.value = false
        _serverToEdit.value = null
    }

    fun onSaveCustomDns(
        name: String,
        primary: String,
        secondary: String?,
        dohUrl: String?,
        protocol: DnsProtocol,
        customBootstrapIp: String?,
        allowUntrustedCertificates: Boolean
    ) {
        vpnRepository.invalidateSpeedTestRestoration()
        viewModelScope.launch {
            val serverToEdit = _serverToEdit.value
            val result = if (serverToEdit != null) {
                // Update existing custom DNS
                updateCustomDnsUseCase(
                    serverToEdit.id,
                    name,
                    primary,
                    secondary,
                    dohUrl,
                    protocol,
                    customBootstrapIp,
                    allowUntrustedCertificates
                )
            } else {
                // Save new custom DNS
                saveCustomDnsUseCase(
                    name,
                    primary,
                    secondary,
                    dohUrl,
                    protocol,
                    customBootstrapIp,
                    allowUntrustedCertificates
                )
            }

            if (result.isSuccess) {
                Log.d(TAG, "Custom DNS ${if (serverToEdit != null) "updated" else "saved"}: ${result.getOrNull()?.name}")
                loadDnsServers()
                _showCustomDnsDialog.value = false
                _serverToEdit.value = null
                _errorMessage.value = null

                // If the edited server was selected, update selection
                result.getOrNull()?.let { updatedServer ->
                    if (_selectedServer.value?.id == serverToEdit?.id) {
                        _selectedServer.value = updatedServer
                        val resolvedProtocol = resolveProtocolForServer(updatedServer)
                        _selectedProtocol.value = resolvedProtocol
                        preferencesDataStore.setSelectedDnsId(updatedServer.id)
                        preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)
                    }
                }
            } else {
                Log.e(TAG, "Failed to ${if (serverToEdit != null) "update" else "save"} custom DNS", result.exceptionOrNull())
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Invalid DNS address"
            }
        }
    }

    fun onSaveAndConnectCustomDns(
        name: String,
        primary: String,
        secondary: String?,
        dohUrl: String?,
        protocol: DnsProtocol,
        customBootstrapIp: String?,
        allowUntrustedCertificates: Boolean
    ) {
        vpnRepository.invalidateSpeedTestRestoration()
        viewModelScope.launch {
            val serverToEdit = _serverToEdit.value
            val result = if (serverToEdit != null) {
                // Update existing custom DNS
                updateCustomDnsUseCase(
                    serverToEdit.id,
                    name,
                    primary,
                    secondary,
                    dohUrl,
                    protocol,
                    customBootstrapIp,
                    allowUntrustedCertificates
                )
            } else {
                // Save new custom DNS
                saveCustomDnsUseCase(
                    name,
                    primary,
                    secondary,
                    dohUrl,
                    protocol,
                    customBootstrapIp,
                    allowUntrustedCertificates
                )
            }

            if (result.isSuccess) {
                Log.d(TAG, "Custom DNS ${if (serverToEdit != null) "updated" else "saved"}: ${result.getOrNull()?.name}")
                loadDnsServers()
                _showCustomDnsDialog.value = false
                _serverToEdit.value = null
                _errorMessage.value = null

                // Get the saved/updated server
                result.getOrNull()?.let { savedServer ->
                    // Update selection to the saved server
                    _selectedServer.value = savedServer
                    val resolvedProtocol = if (!savedServer.dohUrl.isNullOrBlank()) {
                        DnsProtocol.DOH
                    } else {
                        resolveProtocolForServer(savedServer)
                    }
                    _selectedProtocol.value = resolvedProtocol
                    preferencesDataStore.setSelectedDnsId(savedServer.id)
                    preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)

                    // Connect through the same service health checks as every other entry point.
                    // If already connected, disconnect first
                    if (connectionState.value is ConnectionState.Connected) {
                        vpnRepository.disconnect()
                        delay(500)
                    }
                    vpnRepository.connect(savedServer)
                }
            } else {
                Log.e(TAG, "Failed to ${if (serverToEdit != null) "update" else "save"} custom DNS", result.exceptionOrNull())
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Invalid DNS address"
            }
        }
    }

    fun onShowDeleteConfirmation(server: DnsServer) {
        _serverToDelete.value = server
    }

    fun onDismissDeleteConfirmation() {
        _serverToDelete.value = null
    }

    fun onConfirmDeleteCustomDns() {
        vpnRepository.invalidateSpeedTestRestoration()
        val server = _serverToDelete.value ?: return
        viewModelScope.launch {
            val result = deleteCustomDnsUseCase(server.id)
            if (result.isSuccess) {
                Log.d(TAG, "Custom DNS deleted: ${server.name}")

                // If deleted server was selected, select a different one
                if (_selectedServer.value?.id == server.id) {
                    val servers = getDnsListUseCase()
                    _selectedServer.value = servers.firstOrNull()
                    _selectedServer.value?.let { newServer ->
                        val resolvedProtocol = resolveProtocolForServer(newServer)
                        _selectedProtocol.value = resolvedProtocol
                        preferencesDataStore.setSelectedDnsId(newServer.id)
                        preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)
                    }

                    // Disconnect if connected to deleted server
                    if (connectionState.value is ConnectionState.Connected) {
                        vpnRepository.disconnect()
                    }
                }

                loadDnsServers()
                _errorMessage.value = null
                _serverToDelete.value = null
            } else {
                Log.e(TAG, "Failed to delete custom DNS", result.exceptionOrNull())
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to delete DNS server"
            }
        }
    }

    private fun runSpeedTest() {
        val generation = ++speedTestGeneration
        val previous = speedTestJob
        previous?.cancel()
        // Enter try/finally even if dismissed immediately, before the dispatcher runs.
        speedTestJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext()[Job]!!
            try {
                previous?.join()
                currentCoroutineContext().ensureActive()
                if (!speedTestPaused) {
                    // Pair a completed pause with cleanup even if the screen disappears
                    // while the service is acknowledging its stop.
                    withContext(NonCancellable) {
                        speedTestPause = vpnRepository.pauseForSpeedTest()
                        speedTestPaused = true
                    }
                }
                currentCoroutineContext().ensureActive()
                val completedResults = mutableListOf<SpeedTestResult>()
                val results = runSpeedTestUseCase { completed, total, result ->
                    if (owner.isActive && generation == speedTestGeneration && _showSpeedTestDialog.value) {
                        completedResults.add(result)
                        _speedTestState.value = SpeedTestState.Running(completed, total,
                            completedResults.sortedBy { it.averageLatencyMs })
                    }
                }
                if (owner.isActive && generation == speedTestGeneration && _showSpeedTestDialog.value) {
                    _speedTestState.value = SpeedTestState.Completed(results)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (owner.isActive && generation == speedTestGeneration && _showSpeedTestDialog.value) {
                    _speedTestState.value = SpeedTestState.Error(e.message ?: "Unknown error")
                }
            } finally {
                withContext(NonCancellable) {
                    previous?.join()
                    // A retest inherits the pause; only the latest run may restore it.
                    if (generation == speedTestGeneration) {
                        val pause = speedTestPause
                        speedTestPause = null
                        speedTestPaused = false
                        try {
                            if (pause != null) vpnRepository.restoreAfterSpeedTest(pause)
                        } catch (e: Exception) {
                            if (generation == speedTestGeneration) {
                                _errorMessage.value = "Failed to restore VPN connection: ${e.message}"
                            }
                        }
                    }
                }
            }
        }
    }

    fun isVpnPrepared(): Boolean {
        return vpnRepository.isVpnPrepared()
    }

    fun getPreDefinedServersList() = _dnsServers.value

    private fun resolveProtocolForServer(server: DnsServer): DnsProtocol {
        return server.resolveSelectedProtocol(_selectedProtocol.value)
    }

}
