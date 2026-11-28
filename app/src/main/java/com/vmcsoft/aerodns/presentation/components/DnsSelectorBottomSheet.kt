package com.vmcsoft.aerodns.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.label
import com.vmcsoft.aerodns.domain.model.selectableProtocols

private fun DnsServer.addressLine(): String? = when {
    primary.isNotBlank() -> listOfNotNull(primary, secondary).joinToString(" / ")
    !ipv6Primary.isNullOrBlank() -> listOfNotNull(ipv6Primary, ipv6Secondary).joinToString(" / ")
    else -> dohUrl?.takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSelectorBottomSheet(
    servers: List<DnsServer>,
    onServerSelected: (DnsServer) -> Unit,
    onDismiss: () -> Unit,
    onAddCustomDns: () -> Unit = {},
    onEditCustomDns: ((DnsServer) -> Unit)? = null,
    onDeleteCustomDns: ((DnsServer) -> Unit)? = null,
    selectedServerId: String? = null
) {
    val custom = remember(servers) { servers.filter { it.isCustom } }
    val builtIn = remember(servers) { servers.filterNot { it.isCustom }.groupBy { it.category } }
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Select DNS Server",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() }
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                if (custom.isNotEmpty()) {
                    item("custom-title") { ResolverSectionHeading("Custom profiles") }
                    items(custom, key = { it.id }) { server ->
                        ResolverCard(
                            server = server,
                            selected = server.id == selectedServerId,
                            onClick = { onServerSelected(server) },
                            onEdit = onEditCustomDns,
                            onDelete = onDeleteCustomDns
                        )
                    }
                }

                if (builtIn.isNotEmpty()) {
                    item("built-in-title") { ResolverSectionHeading("Built-in resolvers") }
                }
                builtIn.forEach { (category, group) ->
                    if (category != null && category != "cloudflare" && group.size > 1) {
                        val expanded = expandedCategories[category]
                            ?: group.any { it.id == selectedServerId }

                        item("category-$category") {
                            ResolverCategory(
                                name = group.first().name.substringBefore(" ("),
                                expanded = expanded,
                                onToggle = { expandedCategories[category] = !expanded }
                            )
                        }
                        if (expanded) {
                            items(group, key = { it.id }) { server ->
                                ResolverCard(
                                    server = server,
                                    selected = server.id == selectedServerId,
                                    onClick = { onServerSelected(server) },
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    } else {
                        items(group, key = { it.id }) { server ->
                            ResolverCard(server, server.id == selectedServerId, { onServerSelected(server) })
                        }
                    }
                }
            }

            Button(
                onClick = {
                    onDismiss()
                    onAddCustomDns()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Custom DNS")
            }
        }
    }
}

@Composable
private fun ResolverSectionHeading(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge
    )
}

@Composable
private fun ResolverCategory(name: String, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ResolverCard(
    server: DnsServer,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: ((DnsServer) -> Unit)? = null,
    onDelete: ((DnsServer) -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primary.copy(alpha = 0.08f) else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = 0.65f) else colors.outline)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        server.selectableProtocols().joinToString(" · ") { it.label },
                        color = if (selected) colors.primary else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium
                    )
                    server.addressLine()?.let {
                        Text(
                            it,
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (server.description.isNotBlank()) {
                        Text(server.description, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                }
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = if (selected) colors.primary else colors.onSurfaceVariant
                )
            }
            if (onEdit != null || onDelete != null) {
                ResolverOptions(server, onEdit, onDelete)
            }
        }
    }
}

@Composable
private fun ResolverOptions(
    server: DnsServer,
    onEdit: ((DnsServer) -> Unit)?,
    onDelete: ((DnsServer) -> Unit)?
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options for ${server.name}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            onEdit?.let { edit ->
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        expanded = false
                        edit(server)
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                )
            }
            onDelete?.let { delete ->
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        expanded = false
                        delete(server)
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                )
            }
        }
    }
}
