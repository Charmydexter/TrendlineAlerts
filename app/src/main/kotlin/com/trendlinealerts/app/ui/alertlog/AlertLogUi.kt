package com.trendlinealerts.app.ui.alertlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trendlinealerts.app.data.local.AlertLogEntity
import com.trendlinealerts.app.data.repository.AlertLogRepository
import com.trendlinealerts.app.service.NotificationHelper
import com.trendlinealerts.app.ui.theme.AccentBlue
import com.trendlinealerts.app.ui.theme.SignalGreen
import com.trendlinealerts.app.ui.theme.SignalYellow
import com.trendlinealerts.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AlertLogViewModel(
    private val alertLogRepository: AlertLogRepository
) : ViewModel() {

    val logs: StateFlow<List<AlertLogEntity>> = alertLogRepository.observeLogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    fun clearLogs() {
        viewModelScope.launch {
            alertLogRepository.clearAll()
        }
    }

    class Factory(
        private val alertLogRepository: AlertLogRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AlertLogViewModel(alertLogRepository) as T
        }
    }
}

private val LOG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

@Composable
fun AlertLogScreen(
    viewModel: AlertLogViewModel
) {
    val logs by viewModel.logs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Alert Log",
                style = MaterialTheme.typography.titleLarge
            )
            if (logs.isNotEmpty()) {
                IconButton(onClick = viewModel::clearLogs) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear alert log")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No alerts triggered yet.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.id }) { entry ->
                    AlertLogCard(entry)
                }
            }
        }
    }
}

@Composable
private fun AlertLogCard(entry: AlertLogEntity) {
    val timeText = Instant.ofEpochMilli(entry.timestampMs)
        .atZone(ZoneId.systemDefault())
        .format(LOG_TIME_FORMATTER)

    val eventColor = when (entry.eventType) {
        "CROSS" -> SignalGreen
        "TOUCH" -> AccentBlue
        else -> SignalYellow
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${entry.symbol}  •  ${entry.slot}  •  ${entry.eventType}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = eventColor
                )
                Text(
                    text = String.format(Locale.US, "%.2f%%", entry.gapPct),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Price: ${NotificationHelper.formatPrice(entry.price)}   |   " +
                    "Line: ${NotificationHelper.formatPrice(entry.lineValue)}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}