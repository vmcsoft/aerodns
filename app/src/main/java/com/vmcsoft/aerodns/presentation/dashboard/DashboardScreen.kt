package com.vmcsoft.aerodns.presentation.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator as Material2CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vmcsoft.aerodns.data.dns.DnsSecurityMessages
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.presentation.components.CustomDnsDialog
import com.vmcsoft.aerodns.presentation.components.DnsSelectorBottomSheet
import com.vmcsoft.aerodns.presentation.components.SpeedTestDialog
import com.vmcsoft.aerodns.presentation.theme.ActiveButtonCyan
import com.vmcsoft.aerodns.presentation.theme.AeroCyan
import com.vmcsoft.aerodns.presentation.theme.AeroGradient
import com.vmcsoft.aerodns.presentation.theme.DeepSkyBlack
import com.vmcsoft.aerodns.presentation.theme.ElectricBlue
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
    val currentPingMs by viewModel.currentPingMs.collectAsState()
    val dnsServers by viewModel.dnsServers.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isValidatingDns by viewModel.isValidatingDns.collectAsState()
    val validationError by viewModel.validationError.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()

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
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0B1118),
                            DeepSkyBlack,
                            Color(0xFF05070A)
                        )
                    )
                )
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 28.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    ConnectionPanel(
                        connectionState = connectionState,
                        selectedServer = selectedServer,
                        currentPingMs = currentPingMs,
                        onConnectToggle = viewModel::onConnectToggle
                    )

                    ValidationMessage(
                        isValidatingDns = isValidatingDns,
                        validationError = validationError,
                        onClearValidationError = viewModel::clearValidationError
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

                    Spacer(modifier = Modifier.height(16.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                ActionButtons(
                    onShowSpeedTest = viewModel::onShowSpeedTest,
                    onShowDnsSelector = viewModel::onShowDnsSelector
                )
            }

            if (showDnsSelector) {
                DnsSelectorBottomSheet(
                    servers = dnsServers,
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
                        viewModel.onSelectAndConnectDns(result.server)
                        viewModel.onDismissSpeedTest()
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

            if (validationError == DnsSecurityMessages.UNTRUSTED_CERTIFICATE) {
                AlertDialog(
                    onDismissRequest = { viewModel.clearValidationError() },
                    title = { Text("Certificate blocked") },
                    text = { Text(DnsSecurityMessages.UNTRUSTED_CERTIFICATE) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearValidationError() }) {
                            Text("OK")
                        }
                    }
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
    selectedServer: DnsServer?,
    currentPingMs: Long?,
    onConnectToggle: () -> Unit
) {
    val isConnectingOrDisconnecting = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Disconnecting
    val isConnected = connectionState is ConnectionState.Connected

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedContent(
            targetState = connectionState,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) + slideInVertically { it / 2 } togetherWith
                    fadeOut(animationSpec = tween(220)) + slideOutVertically { -it / 2 }
            },
            label = "status"
        ) { state ->
            Text(
                text = getStatusText(state),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 30.sp,
                    lineHeight = 36.sp
                ),
                color = getStatusColor(state),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        ConnectButton(
            isConnected = isConnected,
            isBusy = isConnectingOrDisconnecting,
            onClick = onConnectToggle
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniMetric(
                label = "Protocol",
                value = if (selectedServer?.dohUrl != null) "DoH ready" else "Standard"
            )
            MiniMetric(
                label = "Latency",
                value = currentPingMs?.let { "${it}ms" } ?: "--"
            )
        }
    }
}

@Composable
private fun MiniMetric(
    label: String,
    value: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = TextGray,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ConnectButton(
    isConnected: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "connect_button")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.24f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.62f),
        label = "button_scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(196.dp)
            .scale(scale)
    ) {
        Box(
            modifier = Modifier
                .size(if (isConnected) 196.dp else 178.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            (if (isConnected) ActiveButtonCyan else ElectricBlue).copy(alpha = pulseAlpha * 0.46f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(CircleShape)
                .background(if (isConnected) connectedButtonBrush() else AeroGradient)
                .pointerInput(isBusy) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = {
                            if (!isBusy) onClick()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (isBusy) {
                Material2CircularProgressIndicator(
                    modifier = Modifier.size(54.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = if (isConnected) Icons.Default.Power else Icons.Default.PowerOff,
                    contentDescription = "Connect/Disconnect",
                    modifier = Modifier.size(70.dp),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ValidationMessage(
    isValidatingDns: Boolean,
    validationError: String?,
    onClearValidationError: () -> Unit
) {
    if (!isValidatingDns && validationError == null) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (validationError == null) {
            AeroCyan.copy(alpha = 0.12f)
        } else {
            StatusDisconnected.copy(alpha = 0.12f)
        },
        border = BorderStroke(
            1.dp,
            if (validationError == null) AeroCyan.copy(alpha = 0.36f) else StatusDisconnected.copy(alpha = 0.36f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isValidatingDns) {
                Material2CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AeroCyan
                )
                Text(
                    text = "Testing DNS connectivity...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AeroCyan
                )
            }
            validationError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures { onClearValidationError() }
                    }
                )
            }
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
        shape = RoundedCornerShape(8.dp),
        color = MidnightSurface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, StrokeGray.copy(alpha = 0.72f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "DNS Server",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextGray,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

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
                    if (server.primary.isNotBlank() || server.secondary != null) {
                        AddressRow(
                            title = if (server.isCustom) "Primary" else "IPv4",
                            primary = server.primary.takeIf { it.isNotBlank() } ?: "N/A",
                            secondary = server.secondary
                        )
                    }
                    if (!server.isCustom && (server.ipv6Primary != null || server.ipv6Secondary != null)) {
                        AddressRow(
                            title = "IPv6",
                            primary = server.ipv6Primary ?: "N/A",
                            secondary = server.ipv6Secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    title: String,
    primary: String,
    secondary: String?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DeepSkyBlack.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, StrokeGray.copy(alpha = 0.55f))
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
                text = primary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            secondary?.let {
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
        color = DeepSkyBlack.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, StrokeGray.copy(alpha = 0.55f))
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
    onShowSpeedTest: () -> Unit,
    onShowDnsSelector: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ElevatedButton(
            onClick = onShowSpeedTest,
            modifier = Modifier
                .weight(1f)
                .height(54.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = MidnightSurface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Speed Test", maxLines = 1)
        }

        Button(
            onClick = onShowDnsSelector,
            modifier = Modifier
                .weight(1f)
                .height(54.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Change DNS", maxLines = 1)
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
                    .height(42.dp),
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

private fun connectedButtonBrush(): Brush = Brush.radialGradient(
    colors = listOf(
        ActiveButtonCyan,
        Color(0xFF009E96)
    )
)

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
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Connecting -> "Connecting..."
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Disconnecting -> "Disconnecting..."
        is ConnectionState.Error -> "Error: ${state.message}"
    }
}

@Composable
private fun getStatusColor(state: ConnectionState): Color {
    return when (state) {
        is ConnectionState.Connected -> ActiveButtonCyan
        is ConnectionState.Connecting -> MaterialTheme.colorScheme.primary
        is ConnectionState.Disconnected -> TextGray
        is ConnectionState.Disconnecting -> MaterialTheme.colorScheme.primary
        is ConnectionState.Error -> StatusDisconnected
    }
}
