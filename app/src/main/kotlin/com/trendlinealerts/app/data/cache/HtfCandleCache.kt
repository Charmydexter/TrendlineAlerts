package com.trendlinealerts.app.data.cache

import com.trendlinealerts.app.data.local.HtfCandleDao
import com.trendlinealerts.app.data.local.HtfCandleEntity
import com.trendlinealerts.app.data.remote.AdaptiveMarketDataSource
import com.trendlinealerts.app.data.remote.DataSourceType
import com.trendlinealerts.core.model.Candle
import com.trendlinealerts.core.model.Timeframe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class HtfCacheKey(
    val symbol: String,
    val timeframe: Timeframe,
    val source: DataSourceType
)

data class HtfSymbolUpdate(
    val symbol: String,
    val closedCandles: List<Candle>,
    val didRefreshFromNetwork: Boolean
)

class HtfCandleCache(
    private val dao: HtfCandleDao,
    private val marketData: AdaptiveMarketDataSource
) {
    private data class CacheEntry(
        val closedCandles: List<Candle>,
        val lastClosedOpenTime: Long
    )

    private val memory = ConcurrentHashMap<HtfCacheKey, CacheEntry>()
    private val mutexByKey = ConcurrentHashMap<HtfCacheKey, Mutex>()

    suspend fun ensureCurrentBatch(
        symbols: List<String>,
        timeframe: Timeframe,
        nowMs: Long
    ): Map<String, Result<HtfSymbolUpdate>> = coroutineScope {
        val activeSource = marketData.currentSourceType()
        symbols.map { symbol ->
            async {
                val res = runCatching {
                    ensureCurrent(symbol, timeframe, activeSource, nowMs)
                }
                symbol to res
            }
        }.awaitAll().toMap()
    }

    suspend fun ensureCurrent(
        symbol: String,
        timeframe: Timeframe,
        source: DataSourceType,
        nowMs: Long
    ): HtfSymbolUpdate {
        val key = HtfCacheKey(symbol.uppercase(), timeframe, source)
        val mutex = mutexByKey.getOrPut(key) { Mutex() }

        return mutex.withLock {
            var existing = memory[key]
            if (existing == null) {
                val stored = dao.getCandles(key.symbol, timeframe.label, source.name)
                    .map { it.toCandle() }
                    .filter { it.isClosed(nowMs) }
                    .sortedBy { it.openTime }
                if (stored.isNotEmpty()) {
                    existing = CacheEntry(
                        closedCandles = stored,
                        lastClosedOpenTime = stored.last().openTime
                    )
                    memory[key] = existing
                }
            }

            val needsFetch = existing == null ||
                nowMs >= existing.lastClosedOpenTime + 2L * timeframe.intervalMs

            if (!needsFetch && existing != null) {
                return@withLock HtfSymbolUpdate(
                    symbol = key.symbol,
                    closedCandles = existing.closedCandles,
                    didRefreshFromNetwork = false
                )
            }

            val raw = marketData.fetchHtfCandles(
                symbol = key.symbol,
                timeframe = timeframe,
                limit = 1500
            )
            val closed = raw
                .asSequence()
                .filter { it.isClosed(nowMs) }
                .sortedBy { it.openTime }
                .toList()

            if (closed.isEmpty()) {
                return@withLock HtfSymbolUpdate(
                    symbol = key.symbol,
                    closedCandles = existing?.closedCandles ?: emptyList(),
                    didRefreshFromNetwork = false
                )
            }

            val newLastOpenTime = closed.last().openTime
            val changed = existing == null ||
                newLastOpenTime != existing.lastClosedOpenTime ||
                closed.size != existing.closedCandles.size

            val updatedEntry = CacheEntry(
                closedCandles = closed,
                lastClosedOpenTime = newLastOpenTime
            )
            memory[key] = updatedEntry

            dao.replaceForKey(
                symbol = key.symbol,
                timeframeLabel = timeframe.label,
                sourceName = source.name,
                candles = closed.map {
                    HtfCandleEntity.fromCandle(key.symbol, timeframe.label, source.name, it)
                }
            )

            HtfSymbolUpdate(
                symbol = key.symbol,
                closedCandles = closed,
                didRefreshFromNetwork = changed
            )
        }
    }

    fun clearMemory() {
        memory.clear()
    }
}