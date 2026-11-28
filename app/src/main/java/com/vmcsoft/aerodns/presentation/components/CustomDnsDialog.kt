package com.vmcsoft.aerodns.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.selectableProtocols
import kotlinx.coroutines.delay

typealias SaveCustomResolver = (
    name: String,
    primary: String,
    secondary: String?,
    dohUrl: String?,
    protocol: DnsProtocol,
    customBootstrapIp: String?,
    allowUntrustedCertificates: Boolean
) -> Unit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomDnsDialog(
    onDismiss: () -> Unit,
    onSave: SaveCustomResolver,
    onSaveAndConnect: SaveCustomResolver,
    serverToEdit: DnsServer? = null
) {
    val isEditMode = serverToEdit != null

    // Initial values for comparison - computed once per serverToEdit
    val initialValues = remember(serverToEdit) {
        CustomDnsInitialValues(
            serverToEdit?.name ?: "",
            serverToEdit?.primary?.takeIf { it.isNotBlank() }
                ?: serverToEdit?.ipv6Primary ?: "",
            if (serverToEdit?.primary.isNullOrBlank()) {
                serverToEdit?.ipv6Secondary.orEmpty()
            } else {
                serverToEdit?.secondary.orEmpty()
            },
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
    var pendingProtocol by remember(serverToEdit) { mutableStateOf<DnsProtocol?>(null) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var showUntrustedCertificateWarning by remember { mutableStateOf(false) }
    var primaryHasFocus by remember { mutableStateOf(false) }
    var secondaryHasFocus by remember { mutableStateOf(false) }
    var dohUrlHasFocus by remember { mutableStateOf(false) }
    var bootstrapIpHasFocus by remember { mutableStateOf(false) }

    val standardChanged = primary != initialValues.primary || secondary != initialValues.secondary
    val dohChanged = dohUrl != initialValues.dohUrl ||
        customBootstrapIp != initialValues.customBootstrapIp ||
        allowUntrustedCertificates != initialValues.allowUntrustedCertificates
    val hasUnsavedChanges = name != initialValues.name || standardChanged || dohChanged ||
        selectedProtocol != initialValues.protocol

    fun switchProtocol(protocol: DnsProtocol) {
        selectedProtocol = protocol
        primaryError = null
        secondaryError = null
        dohUrlError = null
        bootstrapIpError = null
        if (!isEditMode && protocol != DnsProtocol.DOH) {
            allowUntrustedCertificates = false
        }
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

    val isDoh = selectedProtocol == DnsProtocol.DOH
    val canSubmit = if (isDoh) dohUrl.isNotBlank() else primary.isNotBlank()
    val colors = MaterialTheme.colorScheme
    val fieldShape = RoundedCornerShape(12.dp)

    fun submit(save: SaveCustomResolver) {
        if (validateForm()) {
            save(
                name,
                primary.takeUnless { isDoh }.orEmpty(),
                secondary.takeIf { !isDoh && it.isNotBlank() },
                dohUrl.takeIf { isDoh && it.isNotBlank() },
                selectedProtocol,
                customBootstrapIp.takeIf { isDoh && it.isNotBlank() },
                isDoh && allowUntrustedCertificates
            )
        }
    }

    val keyboardVisible = WindowInsets.isImeVisible
    BasicAlertDialog(
        onDismissRequest = ::handleDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = colors.background,
            border = BorderStroke(1.dp, colors.outline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (isEditMode) "Edit Custom DNS" else "Add Custom DNS",
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = ::handleDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }

                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProtocolSegmentedControl(
                        selectedProtocol = selectedProtocol,
                        onProtocolSelected = { protocol ->
                            val activeTabChanged = if (isDoh) dohChanged else standardChanged
                            if (protocol != selectedProtocol) {
                                if (isEditMode && activeTabChanged) pendingProtocol = protocol
                                else switchProtocol(protocol)
                            }
                        }
                    )

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        shape = fieldShape,
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
                            modifier = Modifier.fillMaxWidth().onFocusChanged { dohUrlHasFocus = it.hasFocus },
                            shape = fieldShape,
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
                            modifier = Modifier.fillMaxWidth().onFocusChanged { bootstrapIpHasFocus = it.hasFocus },
                            shape = fieldShape,
                            label = { Text("Custom Bootstrap IP") },
                            isError = bootstrapIpError != null,
                            supportingText = {
                                Text(bootstrapIpError ?: "Optional server IP. Without one, your network's DNS looks up the DoH hostname.")
                            },
                            singleLine = true,
                            placeholder = { Text("203.0.113.10 or 2001:db8::10") }
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.outline)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("Allow untrusted certificates", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Only enable this for a resolver you control or trust.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = allowUntrustedCertificates,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Allow untrusted certificates"
                                    },
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            showUntrustedCertificateWarning = true
                                        } else {
                                            allowUntrustedCertificates = false
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = primary,
                            onValueChange = {
                                primary = it
                                primaryError = null
                            },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { primaryHasFocus = it.hasFocus },
                            shape = fieldShape,
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
                            modifier = Modifier.fillMaxWidth().onFocusChanged { secondaryHasFocus = it.hasFocus },
                            shape = fieldShape,
                            label = { Text("Secondary DNS (Optional)") },
                            isError = secondaryError != null,
                            supportingText = secondaryError?.let { { Text(it) } },
                            singleLine = true,
                            placeholder = { Text("8.8.4.4 or 2001:4860:4860::8844") }
                        )
                    }
                }

                if (!keyboardVisible) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedButton(
                            onClick = { submit(onSave) },
                            enabled = canSubmit,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = fieldShape
                        ) {
                            Text("Save")
                        }
                        Button(
                            onClick = { submit(onSaveAndConnect) },
                            enabled = canSubmit,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = fieldShape
                        ) {
                            Text("Save & Connect")
                        }
                    }
                }
            }
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

    pendingProtocol?.let { protocol ->
        val tab = if (isDoh) "DoH" else "Standard DNS"
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surface,
            onDismissRequest = { pendingProtocol = null },
            title = { Text("Unsaved $tab changes") },
            text = { Text("Save your $tab changes before switching tabs, or discard them and switch.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isDoh) {
                            dohUrl = initialValues.dohUrl
                            customBootstrapIp = initialValues.customBootstrapIp
                            allowUntrustedCertificates = initialValues.allowUntrustedCertificates
                        } else {
                            primary = initialValues.primary
                            secondary = initialValues.secondary
                        }
                        pendingProtocol = null
                        switchProtocol(protocol)
                    }
                ) {
                    Text("Discard and switch")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProtocol = null }) {
                    Text("Keep editing")
                }
            }
        )
    }

    // Discard confirmation dialog
    if (showDiscardConfirmation) {
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surface,
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
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surface,
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
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(modifier = Modifier.selectableGroup().padding(4.dp)) {
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
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surface,
        border = if (selected) BorderStroke(1.dp, colors.primary.copy(alpha = 0.65f)) else null
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.primary else colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
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
