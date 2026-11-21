package com.trendlinealerts.app.ui.watchlist

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trendlinealerts.app.data.repository.AddSymbolResult
import com.trendlinealerts.app.data.repository.WatchlistRepository
import com.trendlinealerts.app.ui.theme.OutlineDark
import com.trendlinealerts.app.ui.theme.SignalGreen
import com.trendlinealerts.app.ui.theme.SignalRed
import com.trendlinealerts.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WatchlistUiState(
    val symbols: List<String> = emptyList(),
    val inputText: String = "",
    val isValidating: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class WatchlistViewModel(
    private val watchlistRepository: WatchlistRepository
) : ViewModel() {

    private val inputText = MutableStateFlow("")
    private val isValidating = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<Pair<String, Boolean>?>(null)

    val uiState: StateFlow<WatchlistUiState> = combine(
        watchlistRepository.symbolsFlow,
        inputText,
        isValidating,
        statusMessage
    ) { symbols, input, validating, msg ->
        WatchlistUiState(
            symbols = symbols,
            inputText = input,
            isValidating = validating,
            statusMessage = msg?.first,
            isError = msg?.second ?: false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = WatchlistUiState()
    )

    fun onInputChange(value: String) {
        inputText.value = value.uppercase()
        if (statusMessage.value != null) {
            statusMessage.value = null
        }
    }

    fun addSymbol() {
        if (isValidating.value) return
        val raw = inputText.value
        viewModelScope.launch {
            isValidating.value = true
            when (val result = watchlistRepository.addSymbol(raw)) {
                is AddSymbolResult.Success -> {
                    inputText.value = ""
                    statusMessage.value = "${result.symbol} added." to false
                }
                is AddSymbolResult.Error -> {
                    statusMessage.value = result.message to true
                }
            }
            isValidating.value = false
        }
    }

    fun removeSymbol(symbol: String) {
        viewModelScope.launch {
            watchlistRepository.removeSymbol(symbol)
        }
    }

    class Factory(
        private val watchlistRepository: WatchlistRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return WatchlistViewModel(watchlistRepository) as T
        }
    }
}

@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Watchlist (${state.symbols.size} / ${WatchlistRepository.MAX_SYMBOLS})",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = "Validated against active USDT perpetual futures",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.inputText,
                onValueChange = viewModel::onInputChange,
                label = { Text("Add symbol (e.g. PEPEUSDT)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = viewModel::addSymbol,
                enabled = !state.isValidating && state.symbols.size < WatchlistRepository.MAX_SYMBOLS,
                modifier = Modifier.height(54.dp)
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
            }
        }

        if (state.statusMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.statusMessage!!,
                color = if (state.isError) SignalRed else SignalGreen,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = OutlineDark)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.symbols, key = { it }) { symbol ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = { viewModel.removeSymbol(symbol) }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Remove $symbol",
                            tint = SignalRed
                        )
                    }
                }
                HorizontalDivider(color = OutlineDark.copy(alpha = 0.5f))
            }
        }
    }
}