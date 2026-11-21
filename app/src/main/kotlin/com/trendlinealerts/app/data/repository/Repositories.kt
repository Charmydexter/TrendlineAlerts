package com.trendlinealerts.app.data.repository

import com.trendlinealerts.app.data.local.AlertLogDao
import com.trendlinealerts.app.data.local.AlertLogEntity
import com.trendlinealerts.app.data.local.WatchlistDao
import com.trendlinealerts.app.data.local.WatchlistEntity
import com.trendlinealerts.app.data.remote.AdaptiveMarketDataSource
import com.trendlinealerts.core.model.DetectedEvent
import com.trendlinealerts.core.model.PivotStackSnapshot
import com.trendlinealerts.core.model.PriceSide
import com.trendlinealerts.core.model.SlotNearestInfo
import com.trendlinealerts.core.model.SymbolTrendlines
import com.trendlinealerts.core.model.TrendlineSlotId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

sealed interface AddSymbolResult {
    data class Success(val symbol: String) : AddSymbolResult
    data class Error(val message: String) : AddSymbolResult
}

class WatchlistRepository(
    private val watchlistDao: WatchlistDao,
    private val marketData: AdaptiveMarketDataSource
) {
    @Volatile
    private var cachedValidSymbols: Set<String> = emptySet()

    val symbolsFlow: Flow<List<String>> = watchlistDao.observeAll()
        .map { list -> list.map { it.symbol } }

    suspend fun ensureDefaultSeeded() {
        if (watchlistDao.count() == 0) {
            val now = System.currentTimeMillis()
            val entities = DEFAULT_SYMBOLS.mapIndexed { idx, sym ->
                WatchlistEntity(symbol = sym, sortOrder = idx, addedAtMs = now)
            }
            watchlistDao.insertAll(entities)
        }
    }

    suspend fun getSymbols(): List<String> {
        ensureDefaultSeeded()
        return watchlistDao.getAll().map { it.symbol }
    }

    suspend fun addSymbol(rawInput: String): AddSymbolResult {
        val symbol = rawInput.trim().uppercase()
        if (symbol.isBlank()) {
            return AddSymbolResult.Error("Enter a symbol such as BTCUSDT.")
        }
        if (!symbol.endsWith("USDT")) {
            return AddSymbolResult.Error("Only USDT perpetual symbols are supported.")
        }

        val current = watchlistDao.getAll()
        if (current.any { it.symbol == symbol }) {
            return AddSymbolResult.Error("$symbol is already on the watchlist.")
        }
        if (current.size >= MAX_SYMBOLS) {
            return AddSymbolResult.Error("Maximum $MAX_SYMBOLS symbols allowed.")
        }

        val validSet = try {
            if (cachedValidSymbols.isEmpty()) {
                cachedValidSymbols = marketData.fetchValidPerpetualSymbols()
            }
            if (!cachedValidSymbols.contains(symbol)) {
                cachedValidSymbols = marketData.fetchValidPerpetualSymbols()
            }
            cachedValidSymbols
        } catch (t: Throwable) {
            return AddSymbolResult.Error("Failed to validate against exchangeInfo: ${t.message ?: "network error"}")
        }

        if (!validSet.contains(symbol)) {
            return AddSymbolResult.Error("$symbol is not an active USDT perpetual contract.")
        }

        val nextOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1
        watchlistDao.upsert(
            WatchlistEntity(
                symbol = symbol,
                sortOrder = nextOrder,
                addedAtMs = System.currentTimeMillis()
            )
        )
        return AddSymbolResult.Success(symbol)
    }

    suspend fun removeSymbol(symbol: String) {
        watchlistDao.deleteBySymbol(symbol.uppercase())
    }

    companion object {
        const val MAX_SYMBOLS = 20

        val DEFAULT_SYMBOLS: List<String> = listOf(
            "BTCUSDT",
            "ETHUSDT",
            "SOLUSDT",
            "BNBUSDT",
            "XRPUSDT",
            "DOGEUSDT",
            "ADAUSDT",
            "AVAXUSDT",
            "LINKUSDT",
            "TRXUSDT",
            "DOTUSDT",
            "SUIUSDT",
            "NEARUSDT",
            "LTCUSDT",
            "BCHUSDT",
            "APTUSDT",
            "ARBUSDT",
            "OPUSDT",
            "INJUSDT",
            "AAVEUSDT"
        )
    }
}

class AlertLogRepository(
    private val dao: AlertLogDao
) {
    fun observeLogs(limit: Int = 500): Flow<List<AlertLogEntity>> = dao.observeLatest(limit)

    suspend fun recordAlert(event: DetectedEvent) {
        dao.insert(
            AlertLogEntity(
                timestampMs = event.detectedAtMs,
                symbol = event.symbol,
                slot = event.slot.name,
                eventType = event.eventType.name,
                price = event.price,
                lineValue = event.lineValue,
                gapPct = event.gapPct
            )
        )
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}

enum class SignalDotColor {
    GREY,
    YELLOW,
    RED
}

data class SymbolMonitorRow(
    val symbol: String,
    val lastPrice: Double?,
    val nearestLine: SlotNearestInfo?,
    val dotColor: SignalDotColor
)

data class DebugSlotInfo(
    val slotId: TrendlineSlotId,
    val isValid: Boolean,
    val lineValueNow: Double?,
    val slopePerMs: Double?,
    val newerPivotValue: Double?,
    val newerPivotTimeMs: Long?,
    val olderPivotValue: Double?,
    val olderPivotTimeMs: Long?,
    val rememberedSide: PriceSide
)

data class SymbolDebugSnapshot(
    val symbol: String,
    val resistanceStack: PivotStackSnapshot,
    val supportStack: PivotStackSnapshot,
    val slots: List<DebugSlotInfo>,
    val computedAtMs: Long,
    val lastClosedCandleOpenTime: Long?
)

class MonitorStateRepository {
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _nextCheckAtMs = MutableStateFlow(0L)
    val nextCheckAtMs: StateFlow<Long> = _nextCheckAtMs.asStateFlow()

    private val _nextAlertAllowedAtMs = MutableStateFlow(0L)
    val nextAlertAllowedAtMs: StateFlow<Long> = _nextAlertAllowedAtMs.asStateFlow()

    private val _lastAlertSymbol = MutableStateFlow<String?>(null)
    val lastAlertSymbol: StateFlow<String?> = _lastAlertSymbol.asStateFlow()

    private val _lastCycleError = MutableStateFlow<String?>(null)
    val lastCycleError: StateFlow<String?> = _lastCycleError.asStateFlow()

    private val _symbolRows = MutableStateFlow<List<SymbolMonitorRow>>(emptyList())
    val symbolRows: StateFlow<List<SymbolMonitorRow>> = _symbolRows.asStateFlow()

    private val _debugSnapshots = MutableStateFlow<Map<String, SymbolDebugSnapshot>>(emptyMap())
    val debugSnapshots: StateFlow<Map<String, SymbolDebugSnapshot>> = _debugSnapshots.asStateFlow()

    fun setMonitoring(running: Boolean) {
        _isMonitoring.value = running
        if (!running) {
            _nextCheckAtMs.value = 0L
        }
    }

    fun setNextCheckAtMs(timestampMs: Long) {
        _nextCheckAtMs.value = timestampMs
    }

    fun updateDispatcherStatus(lastAlertSymbol: String?, nextAlertAllowedAtMs: Long) {
        _lastAlertSymbol.value = lastAlertSymbol
        _nextAlertAllowedAtMs.value = nextAlertAllowedAtMs
    }

    fun setLastCycleError(message: String?) {
        _lastCycleError.value = message
    }

    fun updateSymbolRows(rows: List<SymbolMonitorRow>) {
        _symbolRows.value = rows
    }

    fun updateDebugSnapshot(
        symbol: String,
        trendlines: SymbolTrendlines,
        rememberedSides: Map<TrendlineSlotId, PriceSide>,
        nowMs: Long
    ) {
        val slotInfos = TrendlineSlotId.ALL.map { id ->
            val slot = trendlines.slot(id)
            DebugSlotInfo(
                slotId = id,
                isValid = slot.isValid,
                lineValueNow = slot.lineValue(nowMs),
                slopePerMs = slot.slopePerMs,
                newerPivotValue = slot.newerPivot?.value,
                newerPivotTimeMs = slot.newerPivot?.timeMs,
                olderPivotValue = slot.olderPivot?.value,
                olderPivotTimeMs = slot.olderPivot?.timeMs,
                rememberedSide = rememberedSides[id] ?: PriceSide.UNKNOWN
            )
        }

        val snap = SymbolDebugSnapshot(
            symbol = symbol,
            resistanceStack = trendlines.resistanceStack,
            supportStack = trendlines.supportStack,
            slots = slotInfos,
            computedAtMs = nowMs,
            lastClosedCandleOpenTime = trendlines.lastClosedCandleOpenTime
        )
        val updated = _debugSnapshots.value.toMutableMap()
        updated[symbol] = snap
        _debugSnapshots.value = updated
    }
}