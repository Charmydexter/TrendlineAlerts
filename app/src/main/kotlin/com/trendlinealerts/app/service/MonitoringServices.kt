package com.trendlinealerts.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trendlinealerts.app.R
import com.trendlinealerts.app.TrendlineAlertsApp
import com.trendlinealerts.app.data.remote.DataSourceType
import com.trendlinealerts.app.data.repository.SignalDotColor
import com.trendlinealerts.app.data.repository.SymbolMonitorRow
import com.trendlinealerts.app.ui.MainActivity
import com.trendlinealerts.core.detector.EventDetector
import com.trendlinealerts.core.dispatcher.AlertDispatcher
import com.trendlinealerts.core.dispatcher.SystemClock
import com.trendlinealerts.core.engine.SymbolEngineState
import com.trendlinealerts.core.engine.TrendlineEngine
import com.trendlinealerts.core.model.Candle
import com.trendlinealerts.core.model.DetectedEvent
import com.trendlinealerts.core.model.EventType
import com.trendlinealerts.core.model.SymbolEvaluationResult
import com.trendlinealerts.core.model.Timeframe
import com.trendlinealerts.core.model.TrendlineSlotId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR_ID,
            context.getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_monitor_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS_ID,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 250L, 120L, 250L)
            setSound(soundUri, audioAttrs)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannel(monitorChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    fun buildMonitorNotification(symbolCount: Int, remainingMs: Long): Notification {
        val totalSeconds = (remainingMs.coerceAtLeast(0L) / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val countdown = String.format(Locale.US, "%02d:%02d", minutes, seconds)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQ_OPEN_MONITOR,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITOR_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Trendline Alerts Active")
            .setContentText("Monitoring $symbolCount perps | next check in $countdown")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun updateMonitorNotification(symbolCount: Int, remainingMs: Long) {
        if (!hasPostNotificationPermission()) return
        val notification = buildMonitorNotification(symbolCount, remainingMs)
        NotificationManagerCompat.from(context).notify(MONITOR_NOTIFICATION_ID, notification)
    }

    fun showAlertNotification(event: DetectedEvent) {
        if (!hasPostNotificationPermission()) return

        val openAlertLogIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_ALERT_LOG)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQ_OPEN_ALERT_LOG,
            openAlertLogIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priceStr = formatPrice(event.price)
        val lineStr = formatPrice(event.lineValue)
        val gapStr = String.format(Locale.US, "%.2f%%", event.gapPct)
        val body = "${event.symbol} | ${event.slot.name} | ${event.eventType.name} | " +
            "price $priceStr vs line $lineStr ($gapStr)"

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${event.symbol} ${event.slot.name} ${event.eventType.name}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val id = (ALERT_NOTIFICATION_BASE_ID + (event.detectedAtMs % 10_000L).toInt())
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun showTestNotification() {
        val sample = DetectedEvent(
            symbol = "BTCUSDT",
            slot = TrendlineSlotId.R1,
            eventType = EventType.KISS,
            candleOpenTime = System.currentTimeMillis(),
            price = 65_010.0,
            lineValue = 65_060.0,
            gapPct = 0.08,
            detectedAtMs = System.currentTimeMillis()
        )
        showAlertNotification(sample)
    }

    fun hasPostNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_MONITOR_ID = "trendline_monitor_status"
        const val CHANNEL_ALERTS_ID = "trendline_price_alerts"
        const val MONITOR_NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_BASE_ID = 2000
        private const val REQ_OPEN_MONITOR = 101
        private const val REQ_OPEN_ALERT_LOG = 102

        fun formatPrice(value: Double): String {
            val absVal = abs(value)
            val symbols = DecimalFormatSymbols(Locale.US)
            val pattern = when {
                absVal >= 1000.0 -> "#,##0.##"
                absVal >= 10.0 -> "#,##0.###"
                absVal >= 1.0 -> "0.####"
                else -> "0.######"
            }
            return DecimalFormat(pattern, symbols).format(value)
        }
    }
}

class MonitoringForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var countdownJob: Job? = null

    private val trendlineEngine = TrendlineEngine()
    private val eventDetector = EventDetector()
    private val symbolStates = ConcurrentHashMap<String, SymbolEngineState>()

    private lateinit var alertDispatcher: AlertDispatcher

    private var lastAppliedTimeframe: Timeframe? = null
    private var lastAppliedSource: DataSourceType? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as TrendlineAlertsApp).container
        alertDispatcher = AlertDispatcher(clock = SystemClock)
        container.notificationHelper.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopMonitoringInternal()
            return START_NOT_STICKY
        }

        val container = (application as TrendlineAlertsApp).container
        val initialNotification = container.notificationHelper.buildMonitorNotification(
            symbolCount = 20,
            remainingMs = 60_000L
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.MONITOR_NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.MONITOR_NOTIFICATION_ID, initialNotification)
        }

        container.monitorStateRepository.setMonitoring(true)
        if (loopJob?.isActive != true) {
            startMonitoringLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val container = (application as TrendlineAlertsApp).container
        container.monitorStateRepository.setMonitoring(false)
        loopJob?.cancel()
        countdownJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopMonitoringInternal() {
        val container = (application as TrendlineAlertsApp).container
        serviceScope.launch {
            container.settingsDataStore.setMonitoringEnabled(false)
        }
        container.monitorStateRepository.setMonitoring(false)
        loopJob?.cancel()
        countdownJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startMonitoringLoop() {
        val container = (application as TrendlineAlertsApp).container

        loopJob = serviceScope.launch {
            val initialPrefs = container.settingsDataStore.getSnapshot()
            alertDispatcher.restoreState(initialPrefs.dispatcherState)
            container.adaptiveMarketDataSource.setManualPreference(initialPrefs.dataSourceType)
            container.settingsDataStore.setMonitoringEnabled(true)

            startNotificationCountdownJob()

            val firstNextTick = nextMinutePlusTwoSecondsMs(System.currentTimeMillis())
            container.monitorStateRepository.setNextCheckAtMs(firstNextTick)
            runSingleCycle()

            while (isActive) {
                val now = System.currentTimeMillis()
                val nextTickMs = nextMinutePlusTwoSecondsMs(now)
                container.monitorStateRepository.setNextCheckAtMs(nextTickMs)

                val waitMs = (nextTickMs - now).coerceAtLeast(1_000L)
                delay(waitMs)

                runSingleCycle()
            }
        }
    }

    private fun startNotificationCountdownJob() {
        val container = (application as TrendlineAlertsApp).container
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            while (isActive) {
                val nextCheck = container.monitorStateRepository.nextCheckAtMs.value
                val count = container.monitorStateRepository.symbolRows.value.size.takeIf { it > 0 } ?: 20
                val remaining = if (nextCheck > 0L) {
                    (nextCheck - System.currentTimeMillis()).coerceAtLeast(0L)
                } else {
                    0L
                }
                container.notificationHelper.updateMonitorNotification(count, remaining)
                delay(1_000L)
            }
        }
    }

    private suspend fun runSingleCycle() {
        val container = (application as TrendlineAlertsApp).container
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrendlineAlerts::PollWakeLock"
        ).apply { setReferenceCounted(false) }

        wakeLock.acquire(90_000L)
        try {
            val prefs = container.settingsDataStore.getSnapshot()
            val settings = prefs.alertSettings
            val symbols = container.watchlistRepository.getSymbols()

            val activeSource = container.adaptiveMarketDataSource.currentSourceType()
            if (lastAppliedTimeframe != settings.timeframe || lastAppliedSource != activeSource) {
                symbolStates.clear()
                lastAppliedTimeframe = settings.timeframe
                lastAppliedSource = activeSource
            }

            symbolStates.keys.retainAll(symbols.toSet())

            val nowMs = System.currentTimeMillis()

            // 1. Fetch 1m candles first so live prices appear quickly even on slow connections
            val minuteResults = container.adaptiveMarketDataSource.fetchRecent1mCandlesBatch(
                symbols = symbols,
                limit = 3
            )

            // 2. Ensure HTF cache is current
            val htfResults = container.htfCandleCache.ensureCurrentBatch(
                symbols = symbols,
                timeframe = settings.timeframe,
                nowMs = nowMs
            )

            var firstError: Throwable? = null
            var successCount = 0

            for (symbol in symbols) {
                val state = symbolStates.getOrPut(symbol) { SymbolEngineState(symbol) }
                val htfOutcome = htfResults[symbol]
                val htfRes = htfOutcome?.getOrNull()
                if (htfOutcome != null && htfOutcome.isFailure && firstError == null) {
                    firstError = htfOutcome.exceptionOrNull()
                }
                if (htfRes != null && (htfRes.didRefreshFromNetwork || state.currentTrendlines == null)) {
                    val computed = trendlineEngine.compute(symbol, htfRes.closedCandles)
                    state.applyTrendlines(computed)
                }
            }

            val evalNowMs = System.currentTimeMillis()
            val candidates = ArrayList<DetectedEvent>()
            val evalResultsBySymbol = HashMap<String, SymbolEvaluationResult>()

            for (symbol in symbols) {
                val state = symbolStates[symbol] ?: continue
                val minOutcome = minuteResults[symbol]
                val candles1m = minOutcome?.getOrNull()
                if (minOutcome != null && minOutcome.isFailure && firstError == null) {
                    firstError = minOutcome.exceptionOrNull()
                }
                if (candles1m == null) continue
                successCount++

                val lastClosed1m: Candle? = candles1m
                    .asSequence()
                    .filter { it.isClosed(evalNowMs) }
                    .maxByOrNull { it.openTime }

                val forming1m: Candle? = candles1m
                    .asSequence()
                    .filter { !it.isClosed(evalNowMs) }
                    .maxByOrNull { it.openTime }

                val eval = eventDetector.evaluate(
                    symbolState = state,
                    lastClosed1m = lastClosed1m,
                    forming1m = forming1m,
                    nowMs = evalNowMs,
                    settings = settings
                )
                evalResultsBySymbol[symbol] = eval
                eval.candidateEvent?.let { candidates.add(it) }

                state.currentTrendlines?.let { trendlines ->
                    val sides = TrendlineSlotId.ALL.associateWith { slotId ->
                        state.slotState(slotId).rememberedSide
                    }
                    container.monitorStateRepository.updateDebugSnapshot(
                        symbol = symbol,
                        trendlines = trendlines,
                        rememberedSides = sides,
                        nowMs = evalNowMs
                    )
                }
            }

            val prevDispatcherState = alertDispatcher.currentState()
            val dispatchOutcome = alertDispatcher.processCycle(candidates)
            if (dispatchOutcome.updatedState != prevDispatcherState) {
                container.settingsDataStore.saveDispatcherState(dispatchOutcome.updatedState)
            }

            val dispatched = dispatchOutcome.dispatchedAlert
            if (dispatched != null) {
                container.alertLogRepository.recordAlert(dispatched)
                container.notificationHelper.showAlertNotification(dispatched)
            }

            val rows = symbols.map { symbol ->
                val eval = evalResultsBySymbol[symbol]
                val dotColor = when {
                    dispatched?.symbol == symbol -> SignalDotColor.RED
                    eval?.anyWithinThreeXProximity == true -> SignalDotColor.YELLOW
                    else -> SignalDotColor.GREY
                }
                SymbolMonitorRow(
                    symbol = symbol,
                    lastPrice = eval?.lastPrice,
                    nearestLine = eval?.nearestLine,
                    dotColor = dotColor
                )
            }

            container.monitorStateRepository.updateSymbolRows(rows)
            container.monitorStateRepository.updateDispatcherStatus(
                lastAlertSymbol = dispatchOutcome.updatedState.lastAlertSymbol,
                nextAlertAllowedAtMs = dispatchOutcome.nextAlertAllowedAtMs
            )

            if (successCount == 0 && firstError != null) {
                container.monitorStateRepository.setLastCycleError(
                    "${firstError::class.simpleName}: ${firstError.message ?: "Network error"}"
                )
            } else if (firstError != null && successCount < symbols.size) {
                container.monitorStateRepository.setLastCycleError(
                    "Partial data ($successCount/${symbols.size}): ${firstError.message}"
                )
            } else {
                container.monitorStateRepository.setLastCycleError(null)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            container.monitorStateRepository.setLastCycleError(
                "${t::class.simpleName}: ${t.message ?: "Error during monitoring cycle"}"
            )
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    companion object {
        private const val ACTION_START = "com.trendlinealerts.app.action.START_MONITORING"
        private const val ACTION_STOP = "com.trendlinealerts.app.action.STOP_MONITORING"

        fun start(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun nextMinutePlusTwoSecondsMs(nowMs: Long): Long {
            val nextMinuteStart = ((nowMs / 60_000L) + 1L) * 60_000L
            return nextMinuteStart + 2_000L
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as TrendlineAlertsApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = app.container.settingsDataStore.getSnapshot()
                if (prefs.monitoringEnabled) {
                    MonitoringForegroundService.start(context.applicationContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}