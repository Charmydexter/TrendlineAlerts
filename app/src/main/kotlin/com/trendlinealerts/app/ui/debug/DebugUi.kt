package com.trendlinealerts.app.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trendlinealerts.app.data.repository.MonitorStateRepository
import com.trendlinealerts.app.data.repository.SymbolDebugSnapshot
import com.trendlinealerts.app.data.repository.WatchlistRepository
import com.trendlinealerts.app.service.NotificationHelper
import com.trendlinealerts.app.ui.theme.TextSecondary
import com.trendlinealerts.core.model.Pivot
import com.trendlinealerts.core.model.PivotStackSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DebugUiState(
    val symbols: List<String> = emptyList(),
    val selectedSymbol: String = "BTCUSDT",
    val snapshot: SymbolDebugSnapshot? = null
)

class DebugViewModel(
    initialSymbol: String,
    monitorStateRepository: MonitorStateRepository,
    watchlistRepository: WatchlistRepository
) : ViewModel() {

    private val selectedSymbol = MutableStateFlow(initialSymbol.uppercase())

    val uiState: StateFlow<DebugUiState> = combine(
        watchlistRepository.symbolsFlow,
        selectedSymbol,
        monitorStateRepository.debugSnapshots
    ) { symbols, selected, snapshots ->
        val effective = when {
            symbols.isEmpty() -> selected
            selected in symbols -> selected
            else -> symbols.first()
        }
        DebugUiState(
            symbols = symbols,
            selectedSymbol = effective,
            snapshot = snapshots[effective]
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DebugUiState(selectedSymbol = initialSymbol.uppercase())
    )

    fun selectSymbol(symbol: String) {
        selectedSymbol.value = symbol.uppercase()
    }

    class Factory(
        private val initialSymbol: String,
        private val monitorStateRepository: MonitorStateRepository,
        private val watchlistRepository: WatchlistRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DebugViewModel(initialSymbol, monitorStateRepository, watchlistRepository) as T
        }
    }
}

private val UTC_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)

@Composable
fun DebugScreen(
    viewModel: DebugViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var dropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("TradingView Debug Inspector", style = MaterialTheme.typography.titleLarge)
        }

        Box {
            OutlinedButton(
                onClick = { dropdownExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Symbol: ${state.selectedSymbol}")
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                state.symbols.forEach { sym ->
                    DropdownMenuItem(
                        text = { Text(sym) },
                        onClick = {
                            viewModel.selectSymbol(sym)
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        val snap = state.snapshot
        if (snap == null) {
            Text(
                text = "No engine snapshot yet. Start monitoring and wait for the first poll cycle.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }

        Text(
            text = "Last closed HTF candle: ${snap.lastClosedCandleOpenTime?.let(::formatUtc) ?: "--"}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        PivotStackCard(title = "Resistance Stack (P1-P4 + Pending)", stack = snap.resistanceStack)
        PivotStackCard(title = "Support Stack (P1-P4 + Pending)", stack = snap.supportStack)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("6 Trendline Slots", style = MaterialTheme.typography.titleMedium)
                snap.slots.forEach { slot ->
                    val valText = slot.lineValueNow?.let { NotificationHelper.formatPrice(it) } ?: "Invalid"
                    Text(
                        text = "${slot.slotId.name}  |  Line: $valText  |  Side: ${slot.rememberedSide.name}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (slot.isValid) {
                        Text(
                            text = "   E: ${formatPivot(Pivot(slot.newerPivotValue!!, slot.newerPivotTimeMs!!))}\n" +
                                "   O: ${formatPivot(Pivot(slot.olderPivotValue!!, slot.olderPivotTimeMs!!))}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun PivotStackCard(
    title: String,
    stack: PivotStackSnapshot
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("$title (size=${stack.stackSize})", style = MaterialTheme.typography.titleMedium)
            Text("P1: ${formatPivot(stack.p1)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("P2: ${formatPivot(stack.p2)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("P3: ${formatPivot(stack.p3)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("P4: ${formatPivot(stack.p4)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "Pending: ${formatPivot(stack.pending)}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

private fun formatPivot(pivot: Pivot?): String {
    if (pivot == null) return "--"
    return "${NotificationHelper.formatPrice(pivot.value)} @ ${formatUtc(pivot.timeMs)}"
}

private fun formatUtc(timeMs: Long): String =
    Instant.ofEpochMilli(timeMs).atZone(ZoneOffset.UTC).format(UTC_FORMATTER)