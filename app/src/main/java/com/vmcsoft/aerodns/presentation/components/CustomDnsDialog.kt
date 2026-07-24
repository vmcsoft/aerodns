package com.vmcsoft.aerodns.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.selectableProtocols
import com.vmcsoft.aerodns.presentation.theme.AeroCyan
import com.vmcsoft.aerodns.presentation.theme.MidnightSurface
import com.vmcsoft.aerodns.presentation.theme.StrokeGray
import com.vmcsoft.aerodns.presentation.theme.TextGray
import kotlinx.coroutines.delay

@Composable
fun CustomDnsDialog(
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        primary: String,
        secondary: String?,
        dohUrl: String?,
        protocol: DnsProtocol,
        customBootstrapIp: String?,
        allowUntrustedCertificates: Boolean
    ) -> Unit,
    onSaveAndConnect: (
        name: String,
        primary: String,
        secondary: String?,
        dohUrl: String?,
        protocol: DnsProtocol,
        customBootstrapIp: String?,
        allowUntrustedCertificates: Boolean
    ) -> Unit,
    serverToEdit: DnsServer? = null
) {
    val isEditMode = serverToEdit != null

    // Initial values for comparison - computed once per serverToEdit
    val initialValues = remember(serverToEdit) {
        CustomDnsInitialValues(
            serverToEdit?.name ?: "",
            serverToEdit?.primary?.takeIf { it.isNotBlank() }
                ?: serverToEdit?.ipv6Primary ?: "",
            serverToEdit?.secondary?.takeIf { it.isNotBlank() }
                ?: serverToEdit?.ipv6Secondary ?: "",
            serverToEdit?.dohUrl ?: "",
            serverToEdit?.customBootstrapIp ?: "",
            serverToEdit?.allowUntrustedCertificates ?: false,
            if (serverToEdit?.dohUrl?.isNotBlank() == true ||
                serverToEdit?.selectableProtocols() == listOf(DnsProtocol.DOH)
            ) {
                DnsProtocol.DOH
            } else {
                DnsProtocol.STANDARD
            }
        )
    }

    var name by remember(serverToEdit) {
        mutableStateOf(initialValues.name)
    }
    var primary by remember(serverToEdit) {
        mutableStateOf(initialValues.primary)
    }
    var secondary by remember(serverToEdit) {
        mutableStateOf(initialValues.secondary)
    }
    var dohUrl by remember(serverToEdit) {
        mutableStateOf(initialValues.dohUrl)
    }
    var customBootstrapIp by remember(serverToEdit) {
        mutableStateOf(initialValues.customBootstrapIp)
    }
    var allowUntrustedCertificates by remember(serverToEdit) {
        mutableStateOf(initialValues.allowUntrustedCertificates)
    }
    var selectedProtocol by remember(serverToEdit) {
        mutableStateOf(initialValues.protocol)
    }
    var primaryError by remember { mutableStateOf<String?>(null) }
    var secondaryError by remember { mutableStateOf<String?>(null) }
    var dohUrlError by remember { mutableStateOf<String?>(null) }
    var bootstrapIpError by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var showUntrustedCertificateWarning by remember { mutableStateOf(false) }
    var primaryHasFocus by remember { mutableStateOf(false) }
    var secondaryHasFocus by remember { mutableStateOf(false) }
    var dohUrlHasFocus by remember { mutableStateOf(false) }
    var bootstrapIpHasFocus by remember { mutableStateOf(false) }

    // Check if there are unsaved changes
    val hasUnsavedChanges = remember(
        name,
        primary,
        secondary,
        dohUrl,
        customBootstrapIp,
        allowUntrustedCertificates,
        selectedProtocol,
        initialValues
    ) {
        name != initialValues.name ||
            primary != initialValues.primary ||
            secondary != initialValues.secondary ||
            dohUrl != initialValues.dohUrl ||
            customBootstrapIp != initialValues.customBootstrapIp ||
            allowUntrustedCertificates != initialValues.allowUntrustedCertificates ||
            selectedProtocol != initialValues.protocol
    }

    fun handleDismiss() {
        if (hasUnsavedChanges) {
            showDiscardConfirmation = true
        } else {
            onDismiss()
        }
    }

    val ipv4Regex = remember {
        Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
    }

    val ipv6Regex = remember {
        Regex(
            "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,7}:|" +
            "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|" +
            "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|" +
            "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|" +
            "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|" +
            ":((:[0-9a-fA-F]{1,4}){1,7}|:))$"
        )
    }

    fun validateIp(ip: String): Boolean {
        return ipv4Regex.matches(ip) || ipv6Regex.matches(ip)
    }

    fun validateDohUrl(url: String): Boolean {
        return url.startsWith("https://") && url.length > "https://".length
    }

    fun validateForm(): Boolean {
        primaryError = when {
            selectedProtocol == DnsProtocol.STANDARD && primary.isBlank() -> "Primary DNS is required"
            selectedProtocol == DnsProtocol.STANDARD && primary.isNotBlank() && !validateIp(primary) -> "Invalid IP address"
            else -> null
        }
        secondaryError = if (
            selectedProtocol == DnsProtocol.STANDARD &&
            secondary.isNotBlank() &&
            !validateIp(secondary)
        ) {
            "Invalid IP address"
        } else {
            null
        }
        dohUrlError = when {
            selectedProtocol == DnsProtocol.DOH && dohUrl.isBlank() -> "DoH URL is required"
            selectedProtocol == DnsProtocol.DOH && dohUrl.isNotBlank() && !validateDohUrl(dohUrl) -> {
                "DoH URL must start with https://"
            }
            else -> null
        }
        bootstrapIpError = when {
            selectedProtocol == DnsProtocol.DOH &&
                customBootstrapIp.isNotBlank() &&
                !validateIp(customBootstrapIp) -> "Invalid custom bootstrap IP"
            else -> null
        }
        return (selectedProtocol == DnsProtocol.DOH || primary.isNotBlank()) &&
            (selectedProtocol == DnsProtocol.STANDARD || dohUrl.isNotBlank()) &&
            primaryError == null &&
            secondaryError == null &&
            dohUrlError == null &&
            bootstrapIpError == null
    }

    AlertDialog(
        onDismissRequest = ::handleDismiss,
        title = { Text(if (isEditMode) "Edit Custom DNS" else "Add Custom DNS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProtocolSegmentedControl(
                    selectedProtocol = selectedProtocol,
                    onProtocolSelected = {
                        selectedProtocol = it
                        primaryError = null
                        secondaryError = null
                        dohUrlError = null
                        bootstrapIpError = null
                        if (it != DnsProtocol.DOH) {
                            allowUntrustedCertificates = false
                        }
                    }
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (Optional)") },
                    singleLine = true
                )

                if (selectedProtocol == DnsProtocol.DOH) {
                    OutlinedTextField(
                        value = dohUrl,
                        onValueChange = {
                            dohUrl = it
                            dohUrlError = null
                        },
                        modifier = Modifier.onFocusChanged { dohUrlHasFocus = it.hasFocus },
                        label = { Text("DoH URL *") },
                        isError = dohUrlError != null,
                        supportingText = dohUrlError?.let { { Text(it) } },
                        singleLine = true,
                        placeholder = { Text("https://dns.example/dns-query") }
                    )

                    OutlinedTextField(
                        value = customBootstrapIp,
                        onValueChange = {
                            customBootstrapIp = it
                            bootstrapIpError = null
                        },
                        modifier = Modifier.onFocusChanged { bootstrapIpHasFocus = it.hasFocus },
                        label = { Text("Custom Bootstrap IP") },
                        isError = bootstrapIpError != null,
                        supportingText = bootstrapIpError?.let { { Text(it) } },
                        singleLine = true,
                        placeholder = { Text("203.0.113.10 or 2001:db8::10") }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Allow untrusted certificates",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Off by default. Only use this for a Custom DoH server you fully control or trust.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextGray
                            )
                        }
                        Switch(
                            checked = allowUntrustedCertificates,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    showUntrustedCertificateWarning = true
                                } else {
                                    allowUntrustedCertificates = false
                                }
                            }
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = primary,
                        onValueChange = {
                            primary = it
                            primaryError = null
                        },
                        modifier = Modifier.onFocusChanged { primaryHasFocus = it.hasFocus },
                        label = { Text("Primary DNS *") },
                        isError = primaryError != null,
                        supportingText = primaryError?.let { { Text(it) } },
                        singleLine = true,
                        placeholder = { Text("8.8.8.8 or 2001:4860:4860::8888") }
                    )

                    OutlinedTextField(
                        value = secondary,
                        onValueChange = {
                            secondary = it
                            secondaryError = null
                        },
                        modifier = Modifier.onFocusChanged { secondaryHasFocus = it.hasFocus },
                        label = { Text("Secondary DNS (Optional)") },
                        isError = secondaryError != null,
                        supportingText = secondaryError?.let { { Text(it) } },
                        singleLine = true,
                        placeholder = { Text("8.8.4.4 or 2001:4860:4860::8844") }
                    )
                }
            }
            // Delay validation on focus loss so the paste/selection menu doesn't trigger
            // immediate layout change (error text) which dismisses the menu ("blink").
            // Primary is affected more because it's often focused when the menu is shown.
            LaunchedEffect(primaryHasFocus) {
                if (!primaryHasFocus) {
                    delay(250)
                    primaryError = when {
                        selectedProtocol == DnsProtocol.STANDARD && primary.isBlank() -> "Primary DNS is required"
                        selectedProtocol == DnsProtocol.STANDARD && primary.isNotBlank() && !validateIp(primary) -> "Invalid IP address"
                        else -> null
                    }
                }
            }
            LaunchedEffect(secondaryHasFocus) {
                if (!secondaryHasFocus) {
                    delay(250)
                    secondaryError = if (
                        selectedProtocol == DnsProtocol.STANDARD &&
                        secondary.isNotBlank() &&
                        !validateIp(secondary)
                    ) {
                        "Invalid IP address"
                    } else {
                        null
                    }
                }
            }
            LaunchedEffect(dohUrlHasFocus) {
                if (!dohUrlHasFocus) {
                    delay(250)
                    dohUrlError = when {
                        selectedProtocol == DnsProtocol.DOH && dohUrl.isBlank() -> "DoH URL is required"
                        selectedProtocol == DnsProtocol.DOH && dohUrl.isNotBlank() && !validateDohUrl(dohUrl) -> {
                            "DoH URL must start with https://"
                        }
                        else -> null
                    }
                }
            }
            LaunchedEffect(bootstrapIpHasFocus) {
                if (!bootstrapIpHasFocus) {
                    delay(250)
                    bootstrapIpError = when {
                        selectedProtocol == DnsProtocol.DOH &&
                            customBootstrapIp.isNotBlank() &&
                            !validateIp(customBootstrapIp) -> "Invalid custom bootstrap IP"
                        else -> null
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (validateForm()) {
                            onSave(
                                name,
                                primary.takeIf { selectedProtocol == DnsProtocol.STANDARD }.orEmpty(),
                                secondary.takeIf { selectedProtocol == DnsProtocol.STANDARD && it.isNotBlank() },
                                dohUrl.takeIf { selectedProtocol == DnsProtocol.DOH && it.isNotBlank() },
                                selectedProtocol,
                                customBootstrapIp.takeIf {
                                    selectedProtocol == DnsProtocol.DOH && it.isNotBlank()
                                },
                                selectedProtocol == DnsProtocol.DOH && allowUntrustedCertificates
                            )
                        }
                    },
                    enabled = (selectedProtocol == DnsProtocol.STANDARD && primary.isNotBlank()) ||
                        (selectedProtocol == DnsProtocol.DOH && dohUrl.isNotBlank())
                ) {
                    Text("Save")
                }
                TextButton(
                    onClick = {
                        if (validateForm()) {
                            onSaveAndConnect(
                                name,
                                primary.takeIf { selectedProtocol == DnsProtocol.STANDARD }.orEmpty(),
                                secondary.takeIf { selectedProtocol == DnsProtocol.STANDARD && it.isNotBlank() },
                                dohUrl.takeIf { selectedProtocol == DnsProtocol.DOH && it.isNotBlank() },
                                selectedProtocol,
                                customBootstrapIp.takeIf {
                                    selectedProtocol == DnsProtocol.DOH && it.isNotBlank()
                                },
                                selectedProtocol == DnsProtocol.DOH && allowUntrustedCertificates
                            )
                        }
                    },
                    enabled = (selectedProtocol == DnsProtocol.STANDARD && primary.isNotBlank()) ||
                        (selectedProtocol == DnsProtocol.DOH && dohUrl.isNotBlank())
                ) {
                    Text("Save & Connect")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ::handleDismiss) {
                Text("Cancel")
            }
        }
    )

    // Discard confirmation dialog
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. Are you sure you want to discard them?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDismiss()
                    }
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showUntrustedCertificateWarning) {
        AlertDialog(
            onDismissRequest = { showUntrustedCertificateWarning = false },
            title = { Text("Security warning") },
            text = {
                Text(
                    "Allowing untrusted certificates disables normal TLS certificate and hostname verification for this Custom DoH server only. This can expose your DNS queries to Man-in-the-Middle attacks. Enable it only if you understand the risk and trust this resolver."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        allowUntrustedCertificates = true
                        showUntrustedCertificateWarning = false
                    }
                ) {
                    Text("I understand, enable")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        allowUntrustedCertificates = false
                        showUntrustedCertificateWarning = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProtocolSegmentedControl(
    selectedProtocol: DnsProtocol,
    onProtocolSelected: (DnsProtocol) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, StrokeGray)
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            ProtocolSegment(
                label = "Standard DNS",
                selected = selectedProtocol == DnsProtocol.STANDARD,
                modifier = Modifier.weight(1f),
                onClick = { onProtocolSelected(DnsProtocol.STANDARD) }
            )
            ProtocolSegment(
                label = "DoH (HTTPS)",
                selected = selectedProtocol == DnsProtocol.DOH,
                modifier = Modifier.weight(1f),
                onClick = { onProtocolSelected(DnsProtocol.DOH) }
            )
        }
    }
}

@Composable
private fun ProtocolSegment(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) AeroCyan.copy(alpha = 0.16f) else MidnightSurface,
        border = if (selected) BorderStroke(1.dp, AeroCyan) else null
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AeroCyan else TextGray,
            textAlign = TextAlign.Center
        )
    }
}

private data class CustomDnsInitialValues(
    val name: String,
    val primary: String,
    val secondary: String,
    val dohUrl: String,
    val customBootstrapIp: String,
    val allowUntrustedCertificates: Boolean,
    val protocol: DnsProtocol
)
