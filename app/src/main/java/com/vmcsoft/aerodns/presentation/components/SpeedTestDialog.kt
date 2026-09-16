package com.vmcsoft.aerodns.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vmcsoft.aerodns.domain.model.SpeedTestResult

sealed class SpeedTestState {
    object Idle : SpeedTestState()
    data class Running(
        val completed: Int,
        val total: Int,
        val results: List<SpeedTestResult> = emptyList()
    ) : SpeedTestState()
    data class Completed(val results: List<SpeedTestResult>) : SpeedTestState()
    data class Error(val message: String) : SpeedTestState()
}

@Composable
fun SpeedTestDialog(
    state: SpeedTestState,
    onDismiss: () -> Unit,
    onSelectDns: (SpeedTestResult) -> Unit,
    onRetest: () -> Unit = {}
) {
    val results = when (state) {
        is SpeedTestState.Running -> state.results
        is SpeedTestState.Completed -> state.results
        else -> emptyList()
    }
    var selectedServerId by remember { mutableStateOf<String?>(null) }
    val selectedResult = results.firstOrNull {
        it.server.id == selectedServerId && it.isReachable
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DNS Speed Test") },
        text = {
            when (state) {
                is SpeedTestState.Running -> {
                    val progress = if (state.total > 0) {
                        state.completed.toFloat() / state.total.toFloat()
                    } else {
                        0f
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "VPN temporarily disabled for accurate results",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Testing DNS providers...")
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${state.completed} / ${state.total}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.results.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 360.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(state.results) { _, result ->
                                    SpeedTestResultCard(
                                        result = result,
                                        isSelected = result.server.id == selectedServerId,
                                        onClick = { selectedServerId = result.server.id }
                                    )
                                }
                            }
                        }
                    }
                }

                is SpeedTestState.Completed -> {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text("More successful replies rank first. Latency uses successful queries.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        itemsIndexed(state.results) { _, result ->
                            SpeedTestResultCard(
                                result = result,
                                isSelected = result.server.id == selectedServerId,
                                onClick = { selectedServerId = result.server.id }
                            )
                        }
                    }
                }

                is SpeedTestState.Error -> {
                    Text(
                        text = "Error: ${state.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                SpeedTestState.Idle -> {
                    Text("Ready to test DNS servers")
                }
            }
        },
        confirmButton = {
            if (state is SpeedTestState.Completed && state.results.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onRetest) {
                        Text("Retest")
                    }
                    TextButton(
                        enabled = selectedResult != null,
                        onClick = {
                            selectedResult?.let { onSelectDns(it) }
                        }
                    ) {
                        Text("Activate Selected")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (state is SpeedTestState.Running) "Cancel" else "Close")
            }
        }
    )
}
