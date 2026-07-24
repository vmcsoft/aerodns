package com.vmcsoft.aerodns.presentation.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.NetworkIpStack
import com.vmcsoft.aerodns.domain.model.SpeedTestResult
import com.vmcsoft.aerodns.domain.model.buildDnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.resolveSelectedProtocol
import com.vmcsoft.aerodns.domain.model.selectableProtocols
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import com.vmcsoft.aerodns.domain.repository.VpnRepository
import com.vmcsoft.aerodns.domain.usecase.DeleteCustomDnsUseCase
import com.vmcsoft.aerodns.domain.usecase.GetDnsListUseCase
import com.vmcsoft.aerodns.data.vpn.NetworkPinger
import com.vmcsoft.aerodns.data.vpn.PingResult
import com.vmcsoft.aerodns.data.dns.DnsSecurityMessages
import com.vmcsoft.aerodns.domain.usecase.PingDnsServerUseCase
import com.vmcsoft.aerodns.domain.usecase.RunSpeedTestUseCase
import com.vmcsoft.aerodns.domain.usecase.SaveCustomDnsUseCase
import com.vmcsoft.aerodns.domain.usecase.UpdateCustomDnsUseCase
import com.vmcsoft.aerodns.presentation.components.SpeedTestState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    private val pingDnsServerUseCase: PingDnsServerUseCase,
    private val networkPinger: NetworkPinger,
    private val networkCapabilitiesRepository: NetworkCapabilitiesRepository,
    private val preferencesDataStore: com.vmcsoft.aerodns.data.local.PreferencesDataStore
) : ViewModel() {

    companion object {
        private const val TAG = "DashboardViewModel"
        private const val PING_INTERVAL_MS = 10_000L
        private const val CUSTOM_DNS_VALIDATION_TIMEOUT_MS = 3_000
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

    private val _currentPingMs = MutableStateFlow<Long?>(null)
    val currentPingMs: StateFlow<Long?> = _currentPingMs.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isValidatingDns = MutableStateFlow(false)
    val isValidatingDns: StateFlow<Boolean> = _isValidatingDns.asStateFlow()

    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()

    private val _experimentalPacketLoopEnabled = MutableStateFlow(false)
    val experimentalPacketLoopEnabled: StateFlow<Boolean> = _experimentalPacketLoopEnabled.asStateFlow()

    private var pingJob: Job? = null
    private var speedTestJob: Job? = null

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
                        startPingMonitoring(state.server)
                        _errorMessage.value = null
                        _validationError.value = null
                    }
                    is ConnectionState.Error -> {
                        stopPingMonitoring()
                        _errorMessage.value = state.message
                    }
                    else -> {
                        stopPingMonitoring()
                    }
                }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearValidationError() {
        _validationError.value = null
    }

    private fun loadExperimentalPacketLoopSetting() {
        viewModelScope.launch {
            _experimentalPacketLoopEnabled.value = preferencesDataStore.isExperimentalPacketLoopEnabled()
        }
    }

    fun onToggleExperimentalPacketLoop() {
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

    /**
     * Validates that a custom DNS server answers DNS queries before connecting.
     * Returns true if the resolver is usable, false otherwise.
     * Sets error message if validation fails.
     */
    private suspend fun validateCustomDnsConnectivity(server: DnsServer): Boolean {
        if (!server.isCustom) {
            // Skip validation for predefined servers (they're trusted)
            return true
        }

        val stack = networkCapabilitiesRepository.getActiveNetworkIpStack()
        val protocol = server.resolveSelectedProtocol(_selectedProtocol.value)
        return if (protocol == DnsProtocol.DOH) {
            validateCustomDohConnectivity(server, stack)
        } else {
            validateCustomStandardDnsConnectivity(server, stack)
        }
    }

    private suspend fun validateCustomStandardDnsConnectivity(
        server: DnsServer,
        stack: NetworkIpStack
    ): Boolean {
        val dnsAddress = getCustomValidationAddresses(server, stack).firstOrNull()
        if (dnsAddress.isNullOrBlank()) {
            _validationError.value = "Invalid DNS server address"
            return false
        }

        Log.d(TAG, "Validating custom DNS connectivity: ${server.name} ($dnsAddress)")
        _isValidatingDns.value = true
        _validationError.value = null

        return try {
            when (
                val result = networkPinger.measureDnsQueryLatency(
                    ipAddress = dnsAddress,
                    timeoutMs = CUSTOM_DNS_VALIDATION_TIMEOUT_MS
                )
            ) {
                is PingResult.Success -> {
                    Log.d(TAG, "DNS server answered query: ${result.latencyMs}ms")
                    _validationError.value = null
                    true
                }
                is PingResult.Timeout -> {
                    Log.w(TAG, "DNS query timeout: $dnsAddress")
                    _validationError.value = "DNS server \"${server.name}\" did not answer DNS queries. Please check that DNS is running on port 53."
                    false
                }
                is PingResult.Error -> {
                    Log.w(TAG, "DNS query error: ${result.message}")
                    _validationError.value = "Cannot connect to DNS server \"${server.name}\": ${result.message}"
                    false
                }
            }
        } finally {
            _isValidatingDns.value = false
        }
    }

    private suspend fun validateCustomDohConnectivity(
        server: DnsServer,
        stack: NetworkIpStack
    ): Boolean {
        val dnsConfig = buildDnsConnectionConfig(server, stack, DnsProtocol.DOH).getOrElse { error ->
            _validationError.value = error.message ?: "Invalid DNS-over-HTTPS configuration"
            return false
        }
        val dohUrl = dnsConfig.dohUrl
        if (dohUrl.isNullOrBlank()) {
            _validationError.value = "Invalid DNS-over-HTTPS URL"
            return false
        }

        Log.d(TAG, "Validating custom DoH connectivity: ${server.name} ($dohUrl)")
        _isValidatingDns.value = true
        _validationError.value = null

        return try {
            when (
                val result = networkPinger.measureDohQueryLatency(
                    dohUrl = dohUrl,
                    upstreamAddresses = dnsConfig.upstreamAddresses,
                    timeoutMs = CUSTOM_DNS_VALIDATION_TIMEOUT_MS,
                    customBootstrapIp = dnsConfig.customBootstrapIp,
                    allowUntrustedCertificates = dnsConfig.allowUntrustedCertificates
                )
            ) {
                is PingResult.Success -> {
                    Log.d(TAG, "DoH server answered query: ${result.latencyMs}ms")
                    _validationError.value = null
                    true
                }
                is PingResult.Timeout -> {
                    Log.w(TAG, "DoH query timeout: $dohUrl")
                    _validationError.value = "DNS-over-HTTPS server \"${server.name}\" did not answer DNS queries."
                    false
                }
                is PingResult.Error -> {
                    Log.w(TAG, "DoH query error: ${result.message}")
                    _validationError.value = if (result.message == DnsSecurityMessages.UNTRUSTED_CERTIFICATE) {
                        DnsSecurityMessages.UNTRUSTED_CERTIFICATE
                    } else {
                        "Cannot connect to DNS-over-HTTPS server \"${server.name}\": ${result.message}"
                    }
                    false
                }
            }
        } finally {
            _isValidatingDns.value = false
        }
    }

    private fun getCustomValidationAddresses(server: DnsServer, stack: NetworkIpStack): List<String> {
        val ipv4Addresses = listOfNotNull(
            server.primary.takeIf { it.isNotBlank() },
            server.secondary
        )
        val ipv6Addresses = listOfNotNull(
            server.ipv6Primary,
            server.ipv6Secondary
        )

        return when (stack) {
            NetworkIpStack.IPv6_ONLY -> ipv6Addresses.ifEmpty { ipv4Addresses }
            NetworkIpStack.IPv4_ONLY,
            NetworkIpStack.DUAL_STACK -> ipv4Addresses.ifEmpty { ipv6Addresses }
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
        viewModelScope.launch {
            when (connectionState.value) {
                is ConnectionState.Connected, is ConnectionState.Connecting -> {
                    vpnRepository.disconnect()
                }
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    _selectedServer.value?.let { server ->
                        _validationError.value = null // Clear any previous validation errors
                        // Validate custom DNS before connecting
                        if (validateCustomDnsConnectivity(server)) {
                            vpnRepository.connect(server)
                        }
                    }
                }
                is ConnectionState.Disconnecting -> {
                    // Do nothing while disconnecting
                }
            }
        }
    }

    fun onDnsServerSelected(server: DnsServer) {
        _selectedServer.value = server
        val resolvedProtocol = resolveProtocolForServer(server)
        _selectedProtocol.value = resolvedProtocol
        _showDnsSelector.value = false
        _validationError.value = null // Clear any previous validation errors

        viewModelScope.launch {
            preferencesDataStore.setSelectedDnsId(server.id)
            preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)

            // If already connected, reconnect with new server
            if (connectionState.value is ConnectionState.Connected) {
                // Validate custom DNS before connecting
                if (validateCustomDnsConnectivity(server)) {
                    vpnRepository.disconnect()
                    delay(500)
                    vpnRepository.connect(server)
                }
            }
        }
    }

    fun onSelectAndConnectDns(server: DnsServer) {
        _selectedServer.value = server
        val resolvedProtocol = resolveProtocolForServer(server)
        _selectedProtocol.value = resolvedProtocol
        _validationError.value = null // Clear any previous validation errors

        viewModelScope.launch {
            preferencesDataStore.setSelectedDnsId(server.id)
            preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)

            // Always connect to the selected server
            // Validate custom DNS before connecting
            if (validateCustomDnsConnectivity(server)) {
                // If already connected, disconnect first
                if (connectionState.value is ConnectionState.Connected) {
                    vpnRepository.disconnect()
                    delay(500)
                }
                vpnRepository.connect(server)
            }
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

        _selectedProtocol.value = resolvedProtocol
        viewModelScope.launch {
            preferencesDataStore.setSelectedDnsProtocol(resolvedProtocol)

            if (connectionState.value is ConnectionState.Connected) {
                if (validateCustomDnsConnectivity(server)) {
                    vpnRepository.disconnect()
                    delay(500)
                    vpnRepository.connect(server)
                }
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
        speedTestJob = null
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
                _validationError.value = null // Clear any previous validation errors

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

                    // Validate and connect to the saved server
                    if (validateCustomDnsConnectivity(savedServer)) {
                        // If already connected, disconnect first
                        if (connectionState.value is ConnectionState.Connected) {
                            vpnRepository.disconnect()
                            delay(500)
                        }
                        vpnRepository.connect(savedServer)
                    }
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
        speedTestJob?.cancel()
        speedTestJob = viewModelScope.launch {
            var wasConnected = false
            var previousServer: DnsServer? = null

            try {
                // Save current VPN state
                val currentState = connectionState.value
                wasConnected = currentState is ConnectionState.Connected
                if (wasConnected) {
                    previousServer = (currentState as ConnectionState.Connected).server
                    Log.d(TAG, "Disconnecting VPN for speed test...")
                    // Disconnect VPN for accurate speed test
                    vpnRepository.disconnect()
                    delay(500) // Wait for disconnection to complete
                }

                val completedResults = mutableListOf<SpeedTestResult>()

                val results = runSpeedTestUseCase { completed, total, result ->
                    completedResults.add(result)
                    _speedTestState.value = SpeedTestState.Running(
                        completed = completed,
                        total = total,
                        results = completedResults.sortedBy { it.averageLatencyMs }
                    )
                }

                _speedTestState.value = SpeedTestState.Completed(results)
            } catch (e: CancellationException) {
                Log.d(TAG, "Speed test cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Speed test failed", e)
                _speedTestState.value = SpeedTestState.Error(e.message ?: "Unknown error")
            } finally {
                // Restore VPN connection if it was previously connected
                if (wasConnected && previousServer != null) {
                    withContext(NonCancellable) {
                        try {
                            Log.d(TAG, "Restoring VPN connection to ${previousServer.name}...")
                            delay(500)
                            vpnRepository.connect(previousServer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restore VPN connection", e)
                            _errorMessage.value = "Failed to restore VPN connection: ${e.message}"
                        }
                    }
                }
                speedTestJob = null
            }
        }
    }

    private fun startPingMonitoring(server: DnsServer) {
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            while (isActive) {
                val latency = pingDnsServerUseCase(server)
                _currentPingMs.value = latency
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun stopPingMonitoring() {
        pingJob?.cancel()
        pingJob = null
        _currentPingMs.value = null
    }

    fun isVpnPrepared(): Boolean {
        return vpnRepository.isVpnPrepared()
    }

    fun getPreDefinedServersList() = _dnsServers.value

    private fun resolveProtocolForServer(server: DnsServer): DnsProtocol {
        return server.resolveSelectedProtocol(_selectedProtocol.value)
    }

    override fun onCleared() {
        super.onCleared()
        stopPingMonitoring()
    }
}
