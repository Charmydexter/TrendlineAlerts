package com.trendlinealerts.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.trendlinealerts.app.data.remote.DataSourceType
import com.trendlinealerts.core.dispatcher.DispatcherPersistedState
import com.trendlinealerts.core.model.AlertSettings
import com.trendlinealerts.core.model.Candle
import com.trendlinealerts.core.model.EventType
import com.trendlinealerts.core.model.KissMode
import com.trendlinealerts.core.model.Timeframe
import com.trendlinealerts.core.model.TrendlineSlotId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey
    val symbol: String,
    val sortOrder: Int,
    val addedAtMs: Long
)

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY sortOrder ASC, addedAtMs ASC")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist ORDER BY sortOrder ASC, addedAtMs ASC")
    suspend fun getAll(): List<WatchlistEntity>

    @Query("SELECT COUNT(*) FROM watchlist")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WatchlistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)
}

@Entity(tableName = "alert_logs")
data class AlertLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMs: Long,
    val symbol: String,
    val slot: String,
    val eventType: String,
    val price: Double,
    val lineValue: Double,
    val gapPct: Double
)

@Dao
interface AlertLogDao {
    @Query("SELECT * FROM alert_logs ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int = 500): Flow<List<AlertLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AlertLogEntity): Long

    @Query("DELETE FROM alert_logs")
    suspend fun clearAll()
}

@Entity(
    tableName = "htf_candles",
    primaryKeys = ["symbol", "timeframeLabel", "sourceName", "openTime"]
)
data class HtfCandleEntity(
    val symbol: String,
    val timeframeLabel: String,
    val sourceName: String,
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val closeTime: Long
) {
    fun toCandle(): Candle = Candle(
        openTime = openTime,
        open = open,
        high = high,
        low = low,
        close = close,
        closeTime = closeTime
    )

    companion object {
        fun fromCandle(
            symbol: String,
            timeframeLabel: String,
            sourceName: String,
            candle: Candle
        ): HtfCandleEntity = HtfCandleEntity(
            symbol = symbol,
            timeframeLabel = timeframeLabel,
            sourceName = sourceName,
            openTime = candle.openTime,
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            closeTime = candle.closeTime
        )
    }
}

@Dao
interface HtfCandleDao {
    @Query(
        "SELECT * FROM htf_candles " +
            "WHERE symbol = :symbol AND timeframeLabel = :timeframeLabel AND sourceName = :sourceName " +
            "ORDER BY openTime ASC"
    )
    suspend fun getCandles(
        symbol: String,
        timeframeLabel: String,
        sourceName: String
    ): List<HtfCandleEntity>

    @Query(
        "DELETE FROM htf_candles " +
            "WHERE symbol = :symbol AND timeframeLabel = :timeframeLabel AND sourceName = :sourceName"
    )
    suspend fun deleteForKey(
        symbol: String,
        timeframeLabel: String,
        sourceName: String
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(candles: List<HtfCandleEntity>)

    @Transaction
    suspend fun replaceForKey(
        symbol: String,
        timeframeLabel: String,
        sourceName: String,
        candles: List<HtfCandleEntity>
    ) {
        deleteForKey(symbol, timeframeLabel, sourceName)
        insertAll(candles)
    }
}

@Database(
    entities = [
        WatchlistEntity::class,
        AlertLogEntity::class,
        HtfCandleEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun alertLogDao(): AlertLogDao
    abstract fun htfCandleDao(): HtfCandleDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "trendline_alerts.db"
            ).build()
    }
}

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trendline_alerts_settings"
)

data class AppPreferences(
    val alertSettings: AlertSettings = AlertSettings(),
    val dataSourceType: DataSourceType = DataSourceType.BINANCE,
    val monitoringEnabled: Boolean = false,
    val dispatcherState: DispatcherPersistedState = DispatcherPersistedState()
)

class SettingsDataStore(context: Context) {
    private val dataStore = context.appSettingsDataStore

    val preferencesFlow: Flow<AppPreferences> = dataStore.data.map { prefs ->
        val timeframe = prefs[KEY_TIMEFRAME]?.let { Timeframe.fromLabel(it) } ?: Timeframe.DEFAULT
        val proximityPct = (prefs[KEY_PROXIMITY_PCT] ?: 0.08).coerceIn(0.01, 1.0)
        val kissMode = prefs[KEY_KISS_MODE]?.let {
            runCatching { KissMode.valueOf(it) }.getOrNull()
        } ?: KissMode.ENTER_ZONE

        val enabledEvents = buildSet {
            if (prefs[KEY_ENABLE_CROSS] ?: true) add(EventType.CROSS)
            if (prefs[KEY_ENABLE_TOUCH] ?: true) add(EventType.TOUCH)
            if (prefs[KEY_ENABLE_KISS] ?: true) add(EventType.KISS)
        }

        val enabledSlots = buildSet {
            if (prefs[KEY_ENABLE_R1] ?: true) add(TrendlineSlotId.R1)
            if (prefs[KEY_ENABLE_R2] ?: true) add(TrendlineSlotId.R2)
            if (prefs[KEY_ENABLE_R3] ?: true) add(TrendlineSlotId.R3)
            if (prefs[KEY_ENABLE_S1] ?: true) add(TrendlineSlotId.S1)
            if (prefs[KEY_ENABLE_S2] ?: true) add(TrendlineSlotId.S2)
            if (prefs[KEY_ENABLE_S3] ?: true) add(TrendlineSlotId.S3)
        }

        val dataSource = prefs[KEY_DATA_SOURCE]?.let {
            runCatching { DataSourceType.valueOf(it) }.getOrNull()
        } ?: DataSourceType.BINANCE

        AppPreferences(
            alertSettings = AlertSettings(
                timeframe = timeframe,
                proximityPct = proximityPct,
                kissMode = kissMode,
                enabledEvents = enabledEvents,
                enabledSlots = enabledSlots
            ),
            dataSourceType = dataSource,
            monitoringEnabled = prefs[KEY_MONITORING_ENABLED] ?: false,
            dispatcherState = DispatcherPersistedState(
                lastAlertSymbol = prefs[KEY_LAST_ALERT_SYMBOL],
                lastAlertTimeMs = prefs[KEY_LAST_ALERT_TIME_MS]
            )
        )
    }

    suspend fun getSnapshot(): AppPreferences = preferencesFlow.first()

    suspend fun setTimeframe(timeframe: Timeframe) {
        dataStore.edit { it[KEY_TIMEFRAME] = timeframe.label }
    }

    suspend fun setProximityPct(proximityPct: Double) {
        dataStore.edit { it[KEY_PROXIMITY_PCT] = proximityPct.coerceIn(0.01, 1.0) }
    }

    suspend fun setKissMode(kissMode: KissMode) {
        dataStore.edit { it[KEY_KISS_MODE] = kissMode.name }
    }

    suspend fun setEventEnabled(eventType: EventType, enabled: Boolean) {
        val key = when (eventType) {
            EventType.CROSS -> KEY_ENABLE_CROSS
            EventType.TOUCH -> KEY_ENABLE_TOUCH
            EventType.KISS -> KEY_ENABLE_KISS
        }
        dataStore.edit { it[key] = enabled }
    }

    suspend fun setSlotEnabled(slotId: TrendlineSlotId, enabled: Boolean) {
        val key = when (slotId) {
            TrendlineSlotId.R1 -> KEY_ENABLE_R1
            TrendlineSlotId.R2 -> KEY_ENABLE_R2
            TrendlineSlotId.R3 -> KEY_ENABLE_R3
            TrendlineSlotId.S1 -> KEY_ENABLE_S1
            TrendlineSlotId.S2 -> KEY_ENABLE_S2
            TrendlineSlotId.S3 -> KEY_ENABLE_S3
        }
        dataStore.edit { it[key] = enabled }
    }

    suspend fun setDataSource(dataSource: DataSourceType) {
        dataStore.edit { it[KEY_DATA_SOURCE] = dataSource.name }
    }

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_MONITORING_ENABLED] = enabled }
    }

    suspend fun saveDispatcherState(state: DispatcherPersistedState) {
        val symbol = state.lastAlertSymbol
        val timeMs = state.lastAlertTimeMs
        dataStore.edit { prefs ->
            if (symbol != null) {
                prefs[KEY_LAST_ALERT_SYMBOL] = symbol
            } else {
                prefs.remove(KEY_LAST_ALERT_SYMBOL)
            }
            if (timeMs != null) {
                prefs[KEY_LAST_ALERT_TIME_MS] = timeMs
            } else {
                prefs.remove(KEY_LAST_ALERT_TIME_MS)
            }
        }
    }

    companion object {
        private val KEY_TIMEFRAME = stringPreferencesKey("timeframe")
        private val KEY_PROXIMITY_PCT = doublePreferencesKey("proximity_pct")
        private val KEY_KISS_MODE = stringPreferencesKey("kiss_mode")
        private val KEY_ENABLE_CROSS = booleanPreferencesKey("enable_cross")
        private val KEY_ENABLE_TOUCH = booleanPreferencesKey("enable_touch")
        private val KEY_ENABLE_KISS = booleanPreferencesKey("enable_kiss")
        private val KEY_ENABLE_R1 = booleanPreferencesKey("enable_r1")
        private val KEY_ENABLE_R2 = booleanPreferencesKey("enable_r2")
        private val KEY_ENABLE_R3 = booleanPreferencesKey("enable_r3")
        private val KEY_ENABLE_S1 = booleanPreferencesKey("enable_s1")
        private val KEY_ENABLE_S2 = booleanPreferencesKey("enable_s2")
        private val KEY_ENABLE_S3 = booleanPreferencesKey("enable_s3")
        private val KEY_DATA_SOURCE = stringPreferencesKey("data_source")
        private val KEY_MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        private val KEY_LAST_ALERT_SYMBOL = stringPreferencesKey("last_alert_symbol")
        private val KEY_LAST_ALERT_TIME_MS = longPreferencesKey("last_alert_time_ms")
    }
}