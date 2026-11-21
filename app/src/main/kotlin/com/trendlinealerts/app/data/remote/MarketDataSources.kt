package com.trendlinealerts.app.data.remote

import com.trendlinealerts.core.model.Candle
import com.trendlinealerts.core.model.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

enum class DataSourceType(val displayName: String) {
    BINANCE("Binance USDT-M Futures"),
    BYBIT("Bybit Linear Perps")
}

interface MarketDataSource {
    val sourceType: DataSourceType

    suspend fun fetchHtfCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 1500
    ): List<Candle>

    suspend fun fetchRecent1mCandles(
        symbol: String,
        limit: Int = 3
    ): List<Candle>

    suspend fun fetchValidPerpetualSymbols(): Set<String>
}

@Serializable
data class BinanceExchangeInfoResponse(
    val symbols: List<BinanceSymbolInfo> = emptyList()
)

@Serializable
data class BinanceSymbolInfo(
    val symbol: String,
    val status: String = "",
    val contractType: String = "",
    val quoteAsset: String = ""
)

interface BinanceApi {
    @GET("fapi/v1/klines")
    suspend fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int
    ): List<JsonArray>

    @GET("fapi/v1/exchangeInfo")
    suspend fun getExchangeInfo(): BinanceExchangeInfoResponse
}

class BinanceMarketDataSource(
    private val api: BinanceApi
) : MarketDataSource {

    override val sourceType: DataSourceType = DataSourceType.BINANCE

    override suspend fun fetchHtfCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int
    ): List<Candle> {
        val raw = api.getKlines(
            symbol = symbol.uppercase(),
            interval = timeframe.binanceInterval,
            limit = limit.coerceIn(1, 1500)
        )
        return raw.map(::parseKline).sortedBy { it.openTime }
    }

    override suspend fun fetchRecent1mCandles(
        symbol: String,
        limit: Int
    ): List<Candle> {
        val raw = api.getKlines(
            symbol = symbol.uppercase(),
            interval = "1m",
            limit = limit.coerceIn(1, 100)
        )
        return raw.map(::parseKline).sortedBy { it.openTime }
    }

    override suspend fun fetchValidPerpetualSymbols(): Set<String> {
        val info = api.getExchangeInfo()
        return info.symbols
            .asSequence()
            .filter {
                it.status.equals("TRADING", ignoreCase = true) &&
                    it.contractType.equals("PERPETUAL", ignoreCase = true) &&
                    it.quoteAsset.equals("USDT", ignoreCase = true)
            }
            .map { it.symbol.uppercase() }
            .toSet()
    }

    private fun parseKline(row: JsonArray): Candle {
        return Candle(
            openTime = row[0].jsonPrimitive.long,
            open = row[1].jsonPrimitive.double,
            high = row[2].jsonPrimitive.double,
            low = row[3].jsonPrimitive.double,
            close = row[4].jsonPrimitive.double,
            closeTime = row[6].jsonPrimitive.long
        )
    }
}

@Serializable
data class BybitKlineResponse(
    val retCode: Int = 0,
    val retMsg: String = "",
    val result: BybitKlineResult = BybitKlineResult()
)

@Serializable
data class BybitKlineResult(
    val symbol: String = "",
    val category: String = "",
    val list: List<JsonArray> = emptyList()
)

@Serializable
data class BybitInstrumentsResponse(
    val retCode: Int = 0,
    val retMsg: String = "",
    val result: BybitInstrumentsResult = BybitInstrumentsResult()
)

@Serializable
data class BybitInstrumentsResult(
    val category: String = "",
    val list: List<BybitInstrumentInfo> = emptyList(),
    val nextPageCursor: String = ""
)

@Serializable
data class BybitInstrumentInfo(
    val symbol: String,
    val contractType: String = "",
    val status: String = "",
    val quoteCoin: String = ""
)

interface BybitApi {
    @GET("v5/market/kline")
    suspend fun getKline(
        @Query("category") category: String = "linear",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int,
        @Query("end") end: Long? = null
    ): BybitKlineResponse

    @GET("v5/market/instruments-info")
    suspend fun getInstrumentsInfo(
        @Query("category") category: String = "linear",
        @Query("limit") limit: Int = 1000,
        @Query("cursor") cursor: String? = null
    ): BybitInstrumentsResponse
}

class BybitMarketDataSource(
    private val api: BybitApi
) : MarketDataSource {

    override val sourceType: DataSourceType = DataSourceType.BYBIT

    override suspend fun fetchHtfCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int
    ): List<Candle> {
        val target = limit.coerceIn(1, 1500)
        val collected = LinkedHashMap<Long, Candle>(target)
        var endCursor: Long? = null

        while (collected.size < target) {
            val batchSize = minOf(1000, target - collected.size)
            val response = api.getKline(
                category = "linear",
                symbol = symbol.uppercase(),
                interval = timeframe.bybitInterval,
                limit = batchSize,
                end = endCursor
            )
            if (response.retCode != 0) {
                throw IllegalStateException("Bybit kline error ${response.retCode}: ${response.retMsg}")
            }
            val rows = response.result.list
            if (rows.isEmpty()) break

            var oldestOpenTime = Long.MAX_VALUE
            for (row in rows) {
                val candle = parseBybitKline(row, timeframe.intervalMs)
                collected[candle.openTime] = candle
                if (candle.openTime < oldestOpenTime) {
                    oldestOpenTime = candle.openTime
                }
            }
            if (rows.size < batchSize || oldestOpenTime == Long.MAX_VALUE) break
            endCursor = oldestOpenTime - 1L
        }

        return collected.values.sortedBy { it.openTime }
    }

    override suspend fun fetchRecent1mCandles(
        symbol: String,
        limit: Int
    ): List<Candle> {
        val response = api.getKline(
            category = "linear",
            symbol = symbol.uppercase(),
            interval = "1",
            limit = limit.coerceIn(1, 100)
        )
        if (response.retCode != 0) {
            throw IllegalStateException("Bybit 1m kline error ${response.retCode}: ${response.retMsg}")
        }
        return response.result.list
            .map { parseBybitKline(it, 60_000L) }
            .sortedBy { it.openTime }
    }

    override suspend fun fetchValidPerpetualSymbols(): Set<String> {
        val valid = HashSet<String>()
        var cursor: String? = null
        do {
            val response = api.getInstrumentsInfo(
                category = "linear",
                limit = 1000,
                cursor = cursor
            )
            if (response.retCode != 0) {
                throw IllegalStateException("Bybit instruments error ${response.retCode}: ${response.retMsg}")
            }
            for (item in response.result.list) {
                if (item.status.equals("Trading", ignoreCase = true) &&
                    item.contractType.equals("LinearPerpetual", ignoreCase = true) &&
                    item.quoteCoin.equals("USDT", ignoreCase = true)
                ) {
                    valid.add(item.symbol.uppercase())
                }
            }
            val next = response.result.nextPageCursor
            cursor = next.takeIf { it.isNotBlank() }
        } while (cursor != null)
        return valid
    }

    private fun parseBybitKline(row: JsonArray, intervalMs: Long): Candle {
        val openTime = row[0].jsonPrimitive.long
        return Candle(
            openTime = openTime,
            open = row[1].jsonPrimitive.double,
            high = row[2].jsonPrimitive.double,
            low = row[3].jsonPrimitive.double,
            close = row[4].jsonPrimitive.double,
            closeTime = openTime + intervalMs - 1L
        )
    }
}

class RateLimitCoordinator {
    private val blockedUntilMs = AtomicLong(0L)

    suspend fun <T> executeWithBackoff(
        maxAttempts: Int = 4,
        block: suspend () -> T
    ): T {
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            awaitRateLimitCooldown()
            try {
                return block()
            } catch (http: HttpException) {
                val code = http.code()
                if (code == 451 || code == 403) {
                    throw http
                }
                if (code == 429 || code == 418) {
                    val retryAfterMs = parseRetryAfterMs(http) ?: backoffMs
                    val targetUntil = System.currentTimeMillis() + retryAfterMs
                    updateBlockedUntil(targetUntil)
                    attempt++
                    if (attempt >= maxAttempts) throw http
                    delay(retryAfterMs)
                    backoffMs = min(backoffMs * 2L, MAX_BACKOFF_MS)
                } else if (code in 500..599 && attempt + 1 < maxAttempts) {
                    attempt++
                    val jitter = Random.nextLong(100L, 400L)
                    delay(backoffMs + jitter)
                    backoffMs = min(backoffMs * 2L, MAX_BACKOFF_MS)
                } else {
                    throw http
                }
            } catch (io: IOException) {
                attempt++
                if (attempt >= maxAttempts) throw io
                val jitter = Random.nextLong(100L, 350L)
                delay(backoffMs + jitter)
                backoffMs = min(backoffMs * 2L, MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun awaitRateLimitCooldown() {
        val waitMs = blockedUntilMs.get() - System.currentTimeMillis()
        if (waitMs > 0L) {
            delay(waitMs)
        }
    }

    private fun updateBlockedUntil(candidateMs: Long) {
        while (true) {
            val prev = blockedUntilMs.get()
            if (candidateMs <= prev) return
            if (blockedUntilMs.compareAndSet(prev, candidateMs)) return
        }
    }

    private fun parseRetryAfterMs(http: HttpException): Long? {
        val raw = http.response()?.headers()?.get("Retry-After")?.trim() ?: return null
        val seconds = raw.toLongOrNull() ?: return null
        return (seconds * 1000L).coerceIn(1000L, 120_000L)
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}

data class MarketDataStatus(
    val activeSource: DataSourceType = DataSourceType.BINANCE,
    val autoSwitchedToBybit: Boolean = false,
    val bannerMessage: String? = null
)

class AdaptiveMarketDataSource(
    private val binance: MarketDataSource,
    private val bybit: MarketDataSource,
    private val rateLimiter: RateLimitCoordinator = RateLimitCoordinator(),
    maxConcurrentRequests: Int = 5
) {
    private val semaphore = Semaphore(permits = maxConcurrentRequests)
    private val consecutiveBinanceFailures = AtomicInteger(0)

    private val _status = MutableStateFlow(MarketDataStatus())
    val status: StateFlow<MarketDataStatus> = _status.asStateFlow()

    @Volatile
    private var manualPreference: DataSourceType = DataSourceType.BINANCE

    fun setManualPreference(preference: DataSourceType) {
        manualPreference = preference
        consecutiveBinanceFailures.set(0)
        _status.value = MarketDataStatus(
            activeSource = preference,
            autoSwitchedToBybit = false,
            bannerMessage = null
        )
    }

    fun currentSourceType(): DataSourceType = _status.value.activeSource

    suspend fun fetchHtfCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 1500
    ): List<Candle> = executeWithFailover { source ->
        source.fetchHtfCandles(symbol, timeframe, limit)
    }

    suspend fun fetchRecent1mCandlesBatch(
        symbols: List<String>,
        limit: Int = 3
    ): Map<String, Result<List<Candle>>> = coroutineScope {
        symbols.map { symbol ->
            async {
                val result = runCatching {
                    executeWithFailover { source ->
                        source.fetchRecent1mCandles(symbol, limit)
                    }
                }
                symbol to result
            }
        }.awaitAll().toMap()
    }

    suspend fun fetchValidPerpetualSymbols(): Set<String> =
        executeWithFailover { source ->
            source.fetchValidPerpetualSymbols()
        }

    private suspend fun <T> executeWithFailover(
        block: suspend (MarketDataSource) -> T
    ): T {
        val active = _status.value.activeSource
        val source = sourceFor(active)

        return try {
            val result = semaphore.withPermit {
                rateLimiter.executeWithBackoff { block(source) }
            }
            if (active == DataSourceType.BINANCE) {
                consecutiveBinanceFailures.set(0)
            }
            result
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (active == DataSourceType.BINANCE && manualPreference == DataSourceType.BINANCE) {
                val isHardBlock = (t as? HttpException)?.code() in setOf(451, 403)
                val failures = consecutiveBinanceFailures.incrementAndGet()
                if (isHardBlock || failures >= AUTO_FAILOVER_FAILURE_THRESHOLD) {
                    val reason = if (isHardBlock) {
                        val code = (t as HttpException).code()
                        "Binance returned HTTP $code — switched automatically to Bybit."
                    } else {
                        "Binance failed repeatedly — switched automatically to Bybit."
                    }
                    _status.value = MarketDataStatus(
                        activeSource = DataSourceType.BYBIT,
                        autoSwitchedToBybit = true,
                        bannerMessage = reason
                    )
                    return semaphore.withPermit {
                        rateLimiter.executeWithBackoff { block(bybit) }
                    }
                }
            }
            throw t
        }
    }

    private fun sourceFor(type: DataSourceType): MarketDataSource = when (type) {
        DataSourceType.BINANCE -> binance
        DataSourceType.BYBIT -> bybit
    }

    companion object {
        private const val AUTO_FAILOVER_FAILURE_THRESHOLD = 3
    }
}