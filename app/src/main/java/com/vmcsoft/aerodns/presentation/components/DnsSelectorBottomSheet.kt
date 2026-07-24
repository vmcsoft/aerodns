package com.vmcsoft.aerodns.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.presentation.theme.AeroCyan
import com.vmcsoft.aerodns.presentation.theme.DeepSkyBlack
import com.vmcsoft.aerodns.presentation.theme.MidnightSurface
import com.vmcsoft.aerodns.presentation.theme.StrokeGray
import com.vmcsoft.aerodns.presentation.theme.TextSecondary

private fun DnsServer.addressLine(): String = when {
    primary.isNotBlank() -> "$primary / ${secondary ?: "N/A"}"
    ipv6Primary != null -> "$ipv6Primary / ${ipv6Secondary ?: "N/A"}"
    !dohUrl.isNullOrBlank() -> dohUrl
    else -> "N/A"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSelectorBottomSheet(
    servers: List<DnsServer>,
    onServerSelected: (DnsServer) -> Unit,
    onDismiss: () -> Unit,
    onAddCustomDns: () -> Unit = {},
    onEditCustomDns: ((DnsServer) -> Unit)? = null,
    onDeleteCustomDns: ((DnsServer) -> Unit)? = null
) {
    // Group servers by category
    val grouped = remember(servers) {
        servers.groupBy { it.category }
    }

    // Track expansion state for each category
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DeepSkyBlack,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(width = 44.dp, height = 4.dp)
                    .background(StrokeGray, RoundedCornerShape(8.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF101720),
                            DeepSkyBlack
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Select DNS Server",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "${servers.size} providers available",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxHeight(0.7f)
            ) {
                grouped.forEach { (category, serversInCategory) ->
                    val shouldGroupCategory = category != null &&
                        category != "cloudflare" &&
                        serversInCategory.size > 1

                    if (shouldGroupCategory) {
                        val categoryKey = category ?: return@forEach

                        // Expandable category with multiple variants
                        item(key = "header_$categoryKey") {
                            DnsCategoryHeader(
                                displayName = serversInCategory.first().name.split(" (").first(),
                                isExpanded = expandedCategories[categoryKey] ?: false,
                                onToggle = {
                                    expandedCategories[categoryKey] = !(expandedCategories[categoryKey] ?: false)
                                }
                            )
                        }

                        // Variants (shown when expanded)
                        if (expandedCategories[categoryKey] == true) {
                            items(
                                items = serversInCategory,
                                key = { "variant_${it.id}" }
                            ) { server ->
                                DnsVariantCard(
                                    server = server,
                                    onClick = { onServerSelected(server) },
                                    onEdit = if (server.isCustom) onEditCustomDns else null,
                                    onDelete = if (server.isCustom) onDeleteCustomDns else null
                                )
                            }
                        }
                    } else {
                        // Single server (no category or single variant)
                        items(
                            items = serversInCategory,
                            key = { "server_${it.id}" }
                        ) { server ->
                            DnsServerCard(
                                server = server,
                                onClick = { onServerSelected(server) },
                                onEdit = if (server.isCustom) onEditCustomDns else null,
                                onDelete = if (server.isCustom) onDeleteCustomDns else null
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    onDismiss()
                    onAddCustomDns()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AeroCyan.copy(alpha = 0.55f))
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = AeroCyan
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Custom DNS", color = AeroCyan)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun DnsCategoryHeader(
    displayName: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MidnightSurface
        ),
        border = BorderStroke(1.dp, StrokeGray.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isExpanded) "Variants shown" else "Multiple profiles",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun DnsVariantCard(
    server: DnsServer,
    onClick: () -> Unit,
    onEdit: ((DnsServer) -> Unit)? = null,
    onDelete: ((DnsServer) -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp), // Indent variants
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = DeepSkyBlack.copy(alpha = 0.76f)
        ),
        border = BorderStroke(1.dp, StrokeGray.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = server.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = server.addressLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AeroCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onEdit != null || onDelete != null) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        onEdit?.let {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    showMenu = false
                                    it(server)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                        onDelete?.let {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
                                    it(server)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DnsServerCard(
    server: DnsServer,
    onClick: () -> Unit,
    onEdit: ((DnsServer) -> Unit)? = null,
    onDelete: ((DnsServer) -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MidnightSurface
        ),
        border = BorderStroke(1.dp, StrokeGray.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = server.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = server.addressLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AeroCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onEdit != null || onDelete != null) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        onEdit?.let {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    showMenu = false
                                    it(server)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                        onDelete?.let {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
                                    it(server)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
