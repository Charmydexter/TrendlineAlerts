package com.trendlinealerts.app.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trendlinealerts.app.data.local.SettingsDataStore
import com.trendlinealerts.app.data.remote.AdaptiveMarketDataSource
import com.trendlinealerts.app.data.remote.DataSourceType
import com.trendlinealerts.app.data.repository.MonitorStateRepository
import com.trendlinealerts.app.data.repository.SignalDotColor
import com.trendlinealerts.app.data.repository.SymbolMonitorRow
import com.trendlinealerts.app.data.repository.WatchlistRepository
import com.trendlinealerts.app.service.NotificationHelper
import com.trendlinealerts.app.ui.theme.OutlineDark
import com.trendlinealerts.app.ui.theme.SignalGreen
import com.trendlinealerts.app.ui.theme.SignalGrey
import com.trendlinealerts.app.ui.theme.SignalRed
import com.trendlinealerts.app.ui.theme.SignalYellow
import com.trendlinealerts.app.ui.theme.TextSecondary
import com.trendlinealerts.core.model.Timeframe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

data class MonitorUiState(
    val isMonitoring: Boolean = false,
    val lastAlertSymbol: String? = null,
    val nextAlertAllowedInText: String = "Ready",
    val nextCheckInText: String = "--:--",
    val activeDataSource: DataSourceType = DataSourceType.BINANCE,
    val timeframe: Timeframe = Timeframe.DEFAULT,
    val failoverBanner: String? = null,
    val cycleError: String? = null,
    val rows: List<SymbolMonitorRow> = emptyList()
)

class MonitorViewModel(
    monitorStateRepository: MonitorStateRepository,
    watchlistRepository: WatchlistRepository,
    settingsDataStore: SettingsDataStore,
    adaptiveMarketDataSource: AdaptiveMarketDataSource
) : ViewModel() {

    private val tickerFlow: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }

    private val baseStateFlow = combine(
        monitorStateRepository.isMonitoring,
        monitorStateRepository.lastAlertSymbol,
        monitorStateRepository.nextAlertAllowedAtMs,
        monitorStateRepository.nextCheckAtMs,
        monitorStateRepository.lastCycleError
    ) { isMonitoring, lastSym, nextAlertAt, nextCheckAt, cycleError ->
        BaseState(isMonitoring, lastSym, nextAlertAt, nextCheckAt, cycleError)
    }

    private val configAndRowsFlow = combine(
        settingsDataStore.preferencesFlow,
        adaptiveMarketDataSource.status,
        monitorStateRepository.symbolRows,
        watchlistRepository.symbolsFlow
    ) { prefs, dsStatus, liveRows, watchlist ->
        val rowsBySymbol = liveRows.associateBy { it.symbol }
        val mergedRows = watchlist.map { sym ->
            rowsBySymbol[sym] ?: SymbolMonitorRow(
                symbol = sym,
                lastPrice = null,
                nearestLine = null,
                dotColor = SignalDotColor.GREY
            )
        }
        ConfigAndRows(
            timeframe = prefs.alertSettings.timeframe,
            activeSource = dsStatus.activeSource,
            banner = dsStatus.bannerMessage,
            rows = mergedRows
        )
    }

    val uiState: StateFlow<MonitorUiState> = combine(
        baseStateFlow,
        configAndRowsFlow,
        tickerFlow
    ) { base, cfg, nowMs ->
        val alertRemainMs = (base.nextAlertAtMs - nowMs).coerceAtLeast(0L)
        val alertText = if (base.nextAlertAtMs <= 0L || alertRemainMs == 0L) {
            "00:00 (Ready)"
        } else {
            formatMmSs(alertRemainMs)
        }

        val checkRemainMs = (base.nextCheckAtMs - nowMs).coerceAtLeast(0L)
        val checkText = if (!base.isMonitoring || base.nextCheckAtMs <= 0L) {
            "--:--"
        } else {
            formatMmSs(checkRemainMs)
        }

        MonitorUiState(
            isMonitoring = base.isMonitoring,
            lastAlertSymbol = base.lastAlertSymbol,
            nextAlertAllowedInText = alertText,
            nextCheckInText = checkText,
            activeDataSource = cfg.activeSource,
            timeframe = cfg.timeframe,
            failoverBanner = cfg.banner,
            cycleError = base.cycleError,
            rows = cfg.rows
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MonitorUiState()
    )

    private data class BaseState(
        val isMonitoring: Boolean,
        val lastAlertSymbol: String?,
        val nextAlertAtMs: Long,
        val nextCheckAtMs: Long,
        val cycleError: String?
    )

    private data class ConfigAndRows(
        val timeframe: Timeframe,
        val activeSource: DataSourceType,
        val banner: String?,
        val rows: List<SymbolMonitorRow>
    )

    private fun formatMmSs(ms: Long): String {
        val totalSec = ms / 1000L
        val m = totalSec / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    class Factory(
        private val monitorStateRepository: MonitorStateRepository,
        private val watchlistRepository: WatchlistRepository,
        private val settingsDataStore: SettingsDataStore,
        private val adaptiveMarketDataSource: AdaptiveMarketDataSource
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MonitorViewModel(
                monitorStateRepository,
                watchlistRepository,
                settingsDataStore,
                adaptiveMarketDataSource
            ) as T
        }
    }
}

@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel,
    onToggleMonitoring: (start: Boolean) -> Unit,
    onOpenSymbolDebug: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Button(
            onClick = { onToggleMonitoring(!state.isMonitoring) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isMonitoring) SignalRed else SignalGreen
            )
        ) {
            Icon(
                imageVector = if (state.isMonitoring) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.isMonitoring) "STOP MONITORING" else "START MONITORING",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (state.failoverBanner != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = SignalYellow.copy(alpha = 0.16f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = SignalYellow
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.failoverBanner!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SignalYellow
                    )
                }
            }
        }

        if (state.cycleError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Last poll error: ${state.cycleError}",
                style = MaterialTheme.typography.bodySmall,
                color = SignalRed
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val lastText = state.lastAlertSymbol?.let {
                    "$it (locked until another asset alerts)"
                } ?: "None"
                StatusLine(label = "Last alert", value = lastText)
                StatusLine(label = "Next alert allowed in", value = state.nextAlertAllowedInText)
                StatusLine(label = "Next check in", value = state.nextCheckInText)
                StatusLine(label = "Data source", value = state.activeDataSource.displayName)
                StatusLine(label = "HTF", value = state.timeframe.label)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("SYMBOL", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text("PRICE / NEAREST LINE", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
        HorizontalDivider(color = OutlineDark)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.rows, key = { it.symbol }) { row ->
                SymbolRowItem(
                    row = row,
                    onClick = { onOpenSymbolDebug(row.symbol) }
                )
                HorizontalDivider(color = OutlineDark.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SymbolRowItem(
    row: SymbolMonitorRow,
    onClick: () -> Unit
) {
    val dotColor = when (row.dotColor) {
        SignalDotColor.GREY -> SignalGrey
        SignalDotColor.YELLOW -> SignalYellow
        SignalDotColor.RED -> SignalRed
    }

    val priceText = row.lastPrice?.let { NotificationHelper.formatPrice(it) } ?: "--"
    val nearestText = row.nearestLine?.let { info ->
        val sign = if (info.signedDistancePct >= 0.0) "+" else ""
        String.format(Locale.US, "%s %s%.2f%%", info.slot.name, sign, info.signedDistancePct)
    } ?: "--"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = row.symbol,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = priceText,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = nearestText,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}