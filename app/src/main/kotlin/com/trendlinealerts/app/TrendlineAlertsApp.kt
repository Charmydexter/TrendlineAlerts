package com.trendlinealerts.app

import android.app.Application
import com.trendlinealerts.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrendlineAlertsApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notificationHelper.createChannels()

        appScope.launch {
            container.watchlistRepository.ensureDefaultSeeded()
            val prefs = container.settingsDataStore.getSnapshot()
            container.adaptiveMarketDataSource.setManualPreference(prefs.dataSourceType)
            container.monitorStateRepository.updateDispatcherStatus(
                lastAlertSymbol = prefs.dispatcherState.lastAlertSymbol,
                nextAlertAllowedAtMs = (prefs.dispatcherState.lastAlertTimeMs ?: 0L)
                    .let { if (it > 0L) it + 60_000L else 0L }
            )
        }
    }
}