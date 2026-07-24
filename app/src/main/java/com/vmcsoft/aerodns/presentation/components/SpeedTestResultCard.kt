package com.vmcsoft.aerodns.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vmcsoft.aerodns.domain.model.SpeedTestResult
import com.vmcsoft.aerodns.presentation.theme.AeroCyan
import com.vmcsoft.aerodns.presentation.theme.AeroOrange
import com.vmcsoft.aerodns.presentation.theme.StatusConnected
import com.vmcsoft.aerodns.presentation.theme.StatusDisconnected

@Composable
fun SpeedTestResultCard(
    result: SpeedTestResult,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = result.isReachable,
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AeroCyan.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected) BorderStroke(2.dp, AeroCyan) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = result.server.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = if (result.isReachable) "${result.averageLatencyMs}ms" else "N/A",
                style = MaterialTheme.typography.titleLarge,
                color = when {
                    !result.isReachable -> StatusDisconnected
                    result.averageLatencyMs < 50 -> StatusConnected
                    result.averageLatencyMs < 100 -> AeroOrange
                    else -> StatusDisconnected
                }
            )
        }
    }
}
