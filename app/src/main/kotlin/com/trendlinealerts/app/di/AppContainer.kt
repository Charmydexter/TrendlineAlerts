package com.trendlinealerts.app.di

import android.content.Context
import com.trendlinealerts.app.data.cache.HtfCandleCache
import com.trendlinealerts.app.data.local.AppDatabase
import com.trendlinealerts.app.data.local.SettingsDataStore
import com.trendlinealerts.app.data.remote.AdaptiveMarketDataSource
import com.trendlinealerts.app.data.remote.BinanceApi
import com.trendlinealerts.app.data.remote.BinanceMarketDataSource
import com.trendlinealerts.app.data.remote.BybitApi
import com.trendlinealerts.app.data.remote.BybitMarketDataSource
import com.trendlinealerts.app.data.remote.OkxApi
import com.trendlinealerts.app.data.remote.OkxMarketDataSource
import com.trendlinealerts.app.data.remote.RateLimitCoordinator
import com.trendlinealerts.app.data.repository.AlertLogRepository
import com.trendlinealerts.app.data.repository.MonitorStateRepository
import com.trendlinealerts.app.data.repository.WatchlistRepository
import com.trendlinealerts.app.service.NotificationHelper
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private val binanceApi: BinanceApi = Retrofit.Builder()
        .baseUrl("https://fapi.binance.com/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory(jsonMediaType))
        .build()
        .create(BinanceApi::class.java)

    private val bybitApi: BybitApi = Retrofit.Builder()
        .baseUrl("https://api.bybit.com/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory(jsonMediaType))
        .build()
        .create(BybitApi::class.java)

    private val okxApi: OkxApi = Retrofit.Builder()
        .baseUrl("https://www.okx.com/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory(jsonMediaType))
        .build()
        .create(OkxApi::class.java)

    val rateLimitCoordinator = RateLimitCoordinator()

    val adaptiveMarketDataSource = AdaptiveMarketDataSource(
        binance = BinanceMarketDataSource(binanceApi),
        bybit = BybitMarketDataSource(bybitApi),
        okx = OkxMarketDataSource(okxApi),
        rateLimiter = rateLimitCoordinator,
        maxConcurrentRequests = 3
    )

    val database: AppDatabase = AppDatabase.build(appContext)
    val settingsDataStore = SettingsDataStore(appContext)

    val htfCandleCache = HtfCandleCache(
        dao = database.htfCandleDao(),
        marketData = adaptiveMarketDataSource
    )

    val watchlistRepository = WatchlistRepository(
        watchlistDao = database.watchlistDao(),
        marketData = adaptiveMarketDataSource
    )

    val alertLogRepository = AlertLogRepository(
        dao = database.alertLogDao()
    )

    val monitorStateRepository = MonitorStateRepository()
    val notificationHelper = NotificationHelper(appContext)
}