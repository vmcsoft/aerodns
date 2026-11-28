package com.vmcsoft.aerodns.presentation.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.Intent
import android.app.Activity
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsHealth
import com.vmcsoft.aerodns.domain.model.statusText
import com.vmcsoft.aerodns.domain.model.label
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.presentation.components.CustomDnsDialog
import com.vmcsoft.aerodns.presentation.components.DnsSelectorBottomSheet
import com.vmcsoft.aerodns.presentation.components.SpeedTestDialog
import com.vmcsoft.aerodns.presentation.theme.ActiveButtonCyan
import com.vmcsoft.aerodns.presentation.theme.DeepSkyBlack
import com.vmcsoft.aerodns.presentation.theme.MidnightSurface
import com.vmcsoft.aerodns.presentation.theme.StatusDisconnected
import com.vmcsoft.aerodns.presentation.theme.StrokeGray
import com.vmcsoft.aerodns.presentation.theme.TextGray

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val showDnsSelector by viewModel.showDnsSelector.collectAsState()
    val showSpeedTestDialog by viewModel.showSpeedTestDialog.collectAsState()
    val showCustomDnsDialog by viewModel.showCustomDnsDialog.collectAsState()
    val serverToEdit by viewModel.serverToEdit.collectAsState()
    val serverToDelete by viewModel.serverToDelete.collectAsState()
    val speedTestState by viewModel.speedTestState.collectAsState()
    val dnsServers by viewModel.dnsServers.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()

    val context = LocalContext.current
    // Keep this action only for the current screen instance. A recreated screen may
    // retain consent, but must not replay an old connection choice automatically.
    var pendingVpnAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingVpnAction
        pendingVpnAction = null
        if (result.resultCode == Activity.RESULT_OK && VpnService.prepare(context) == null) action?.invoke()
    }
    fun withVpnPermission(action: () -> Unit) {
        val intent = VpnService.prepare(context)
        if (intent == null) action() else {
            pendingVpnAction = action
            vpnPermissionLauncher.launch(intent)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = DeepSkyBlack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 520.dp)
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ConnectionPanel(
                        connectionState = connectionState,
                        selectedProtocol = selectedProtocol,
                        onConnectToggle = {
                            if (connectionState is ConnectionState.Disconnected || connectionState is ConnectionState.Error) {
                                withVpnPermission(viewModel::onConnectToggle)
                            } else viewModel.onConnectToggle()
                        }
                    )

                    selectedServer?.let { server ->
                        DnsConfigPanel(
                            server = server,
                            selectedProtocol = selectedProtocol,
                            protocolOptions = viewModel.getSelectableProtocols(server),
                            protocolEnabled = connectionState !is ConnectionState.Connecting &&
                                connectionState !is ConnectionState.Disconnecting,
                            onProtocolSelected = viewModel::onDnsProtocolSelected
                        )
                    }

                }

                Spacer(modifier = Modifier.size(12.dp))

                ActionButtons(
                    speedTestEnabled = (connectionState as? ConnectionState.Connected)?.controlPolicy?.systemManaged != true,
                    onShowSpeedTest = viewModel::onShowSpeedTest,
                    onShowDnsSelector = viewModel::onShowDnsSelector
                )
            }

            if (showDnsSelector) {
                DnsSelectorBottomSheet(
                    servers = dnsServers,
                    selectedServerId = selectedServer?.id,
                    onServerSelected = { viewModel.onDnsServerSelected(it) },
                    onDismiss = { viewModel.onDismissDnsSelector() },
                    onAddCustomDns = { viewModel.onShowCustomDnsDialog() },
                    onEditCustomDns = { server ->
                        viewModel.onDismissDnsSelector()
                        viewModel.onShowCustomDnsDialog(server)
                    },
                    onDeleteCustomDns = { server ->
                        viewModel.onShowDeleteConfirmation(server)
                    }
                )
            }

            if (showSpeedTestDialog) {
                SpeedTestDialog(
                    state = speedTestState,
                    onDismiss = { viewModel.onDismissSpeedTest() },
                    onSelectDns = { result ->
                        withVpnPermission {
                            viewModel.onSpeedTestResultSelected(result)
                            viewModel.onDismissSpeedTest()
                        }
                    },
                    onRetest = {
                        viewModel.onShowSpeedTest()
                    }
                )
            }

            if (showCustomDnsDialog) {
                CustomDnsDialog(
                    onDismiss = { viewModel.onDismissCustomDnsDialog() },
                    onSave = {
                        name,
                        primary,
                        secondary,
                        dohUrl,
                        protocol,
                        customBootstrapIp,
                        allowUntrustedCertificates ->
                        viewModel.onSaveCustomDns(
                            name,
                            primary,
                            secondary,
                            dohUrl,
                            protocol,
                            customBootstrapIp,
                            allowUntrustedCertificates
                        )
                    },
                    onSaveAndConnect = {
                        name,
                        primary,
                        secondary,
                        dohUrl,
                        protocol,
                        customBootstrapIp,
                        allowUntrustedCertificates ->
                        viewModel.onSaveAndConnectCustomDns(
                            name,
                            primary,
                            secondary,
                            dohUrl,
                            protocol,
                            customBootstrapIp,
                            allowUntrustedCertificates
                        )
                    },
                    serverToEdit = serverToEdit
                )
            }

            serverToDelete?.let { server ->
                AlertDialog(
                    onDismissRequest = { viewModel.onDismissDeleteConfirmation() },
                    title = { Text("Delete Custom DNS") },
                    text = {
                        Text(
                            "Are you sure you want to delete \"${server.name}\"? This action cannot be undone."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { viewModel.onConfirmDeleteCustomDns() }
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { viewModel.onDismissDeleteConfirmation() }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    connectionState: ConnectionState,
    selectedProtocol: DnsProtocol,
    onConnectToggle: () -> Unit
) {
    val connected = connectionState as? ConnectionState.Connected
    val isBusy = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Disconnecting
    val systemManaged = connected?.controlPolicy?.systemManaged == true
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MidnightSurface,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "DNS connection",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.labelLarge,
                    color = TextGray
                )
                Text(
                    text = getStatusText(connectionState),
                    style = MaterialTheme.typography.headlineMedium,
                    color = getStatusColor(connectionState),
                    fontWeight = FontWeight.SemiBold
                )
            }

            connected?.activeConfig?.let { config ->
                DashboardMetric(label = "Active resolver", value = config.displayName)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DashboardMetric(
                    label = if (connected == null) "Selected protocol" else "Protocol",
                    value = (connected?.activeConfig?.protocol ?: selectedProtocol).label,
                    modifier = Modifier.weight(1f)
                )
                DashboardMetric(
                    label = "DNS latency",
                    value = (connected?.dnsHealth as? DnsHealth.Healthy)?.latencyMs
                        ?.let { "$it ms" } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }

            if (systemManaged) {
                Text(
                    text = when {
                        connected?.controlPolicy?.isKnown == false ->
                            "Android VPN settings could not be read. Open VPN settings to manage this connection."
                        connected?.controlPolicy?.lockdown == true ->
                            "AeroDNS changes DNS only. Turn off ‘Block connections without VPN’ in Android VPN settings to let other apps use the internet."
                        else ->
                            "Always-on VPN is controlled by Android. Turn it off in VPN settings to disconnect or run a speed test."
                    },
                    color = TextGray,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) {
                    Text("VPN settings")
                }
            }
            if (connected?.dnsHealth is DnsHealth.Unhealthy) {
                Text(
                    text = if (systemManaged)
                        "VPN is on. DNS checks will retry automatically. You can choose another resolver."
                    else
                        "VPN is on. DNS checks will retry automatically. You can disconnect or choose another resolver.",
                    color = TextGray,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            ConnectButton(connected != null, isBusy, !systemManaged, onConnectToggle)
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextGray, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ConnectButton(
    isConnected: Boolean,
    isBusy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isBusy,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .semantics { contentDescription = "Connect/Disconnect" },
        shape = RoundedCornerShape(14.dp),
        colors = if (isConnected) ButtonDefaults.buttonColors(
            containerColor = DeepSkyBlack,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) else ButtonDefaults.buttonColors()
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = if (isConnected) Icons.Default.PowerOff else Icons.Default.Power,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(if (isConnected) "Disconnect" else "Connect")
        }
    }
}

@Composable
private fun DnsConfigPanel(
    server: DnsServer,
    selectedProtocol: DnsProtocol,
    protocolOptions: List<DnsProtocol>,
    protocolEnabled: Boolean,
    onProtocolSelected: (DnsProtocol) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MidnightSurface
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DashboardMetric(label = "Selected DNS server", value = server.name)

            if (protocolOptions.size > 1) {
                DnsProtocolSelector(
                    protocols = protocolOptions,
                    selectedProtocol = selectedProtocol,
                    enabled = protocolEnabled,
                    onProtocolSelected = onProtocolSelected
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectedProtocol == DnsProtocol.DOH && !server.dohUrl.isNullOrBlank()) {
                    InfoRow(
                        title = "DoH URL",
                        primary = server.dohUrl,
                        secondary = "HTTPS endpoint used for encrypted DNS queries"
                    )
                } else {
                    AddressRow(primary = server.primary, secondary = server.secondary)
                    AddressRow(primary = server.ipv6Primary, secondary = server.ipv6Secondary)
                }
            }
        }
    }
}

@Composable
private fun AddressRow(primary: String?, secondary: String?) {
    val addresses = listOfNotNull(primary, secondary).filter(String::isNotBlank)
    if (addresses.isEmpty()) return
    val title = when (addresses.map { ':' in it }.distinct().singleOrNull()) {
        true -> "IPv6"
        false -> "IPv4"
        null -> "DNS addresses"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DeepSkyBlack.copy(alpha = 0.46f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextGray
            )
            Text(
                text = addresses.first(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            addresses.getOrNull(1)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    title: String,
    primary: String,
    secondary: String?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DeepSkyBlack.copy(alpha = 0.46f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextGray
            )
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                overflow = TextOverflow.Ellipsis
            )
            secondary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGray
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    speedTestEnabled: Boolean,
    onShowSpeedTest: () -> Unit,
    onShowDnsSelector: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onShowSpeedTest,
            enabled = speedTestEnabled,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Speed Test")
        }

        Button(
            onClick = onShowDnsSelector,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.primary
            ),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Change DNS")
        }
    }
}

@Composable
private fun DnsProtocolSelector(
    protocols: List<DnsProtocol>,
    selectedProtocol: DnsProtocol,
    enabled: Boolean,
    onProtocolSelected: (DnsProtocol) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        protocols.forEachIndexed { index, protocol ->
            val selected = protocol == selectedProtocol
            OutlinedButton(
                enabled = enabled,
                onClick = { onProtocolSelected(protocol) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = protocolButtonShape(index, protocols.size),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        DeepSkyBlack.copy(alpha = 0.42f)
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                ),
                border = BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else StrokeGray
                ),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    text = protocol.displayName(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

private fun protocolButtonShape(index: Int, count: Int): RoundedCornerShape {
    val corner = 8.dp
    return when (index) {
        0 -> RoundedCornerShape(topStart = corner, bottomStart = corner)
        count - 1 -> RoundedCornerShape(topEnd = corner, bottomEnd = corner)
        else -> RoundedCornerShape(0.dp)
    }
}

private fun DnsProtocol.displayName(): String {
    return when (this) {
        DnsProtocol.STANDARD -> "Standard"
        DnsProtocol.DOH -> "DoH"
        DnsProtocol.DOT -> "DoT"
    }
}

@Composable
private fun getStatusText(state: ConnectionState): String {
    return when (state) {
        is ConnectionState.Connected -> state.controlPolicy.statusText(state.dnsHealth)
        is ConnectionState.Connecting -> "Connecting..."
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Disconnecting -> "Disconnecting..."
        is ConnectionState.Error -> "Error: ${state.message}"
    }
}

@Composable
private fun getStatusColor(state: ConnectionState): Color {
    return when (state) {
        is ConnectionState.Connected -> if (state.controlPolicy.lockdown || !state.controlPolicy.isKnown) StatusDisconnected else when (state.dnsHealth) {
            is DnsHealth.Healthy -> ActiveButtonCyan
            DnsHealth.Checking -> MaterialTheme.colorScheme.primary
            is DnsHealth.Unhealthy -> StatusDisconnected
        }
        is ConnectionState.Connecting -> MaterialTheme.colorScheme.primary
        is ConnectionState.Disconnected -> TextGray
        is ConnectionState.Disconnecting -> MaterialTheme.colorScheme.primary
        is ConnectionState.Error -> StatusDisconnected
    }
}
