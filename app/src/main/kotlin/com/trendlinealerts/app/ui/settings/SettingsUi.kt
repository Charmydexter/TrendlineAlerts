package com.trendlinealerts.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trendlinealerts.app.data.cache.HtfCandleCache
import com.trendlinealerts.app.data.local.AppPreferences
import com.trendlinealerts.app.data.local.SettingsDataStore
import com.trendlinealerts.app.data.remote.AdaptiveMarketDataSource
import com.trendlinealerts.app.data.remote.DataSourceType
import com.trendlinealerts.app.service.NotificationHelper
import com.trendlinealerts.app.ui.theme.SignalGreen
import com.trendlinealerts.app.ui.theme.SignalYellow
import com.trendlinealerts.app.ui.theme.TextSecondary
import com.trendlinealerts.core.model.EventType
import com.trendlinealerts.core.model.KissMode
import com.trendlinealerts.core.model.Timeframe
import com.trendlinealerts.core.model.TrendlineSlotId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class SettingsViewModel(
    private val settingsDataStore: SettingsDataStore,
    private val adaptiveMarketDataSource: AdaptiveMarketDataSource,
    private val htfCandleCache: HtfCandleCache,
    private val notificationHelper: NotificationHelper
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = settingsDataStore.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AppPreferences()
        )

    fun setTimeframe(timeframe: Timeframe) {
        viewModelScope.launch {
            settingsDataStore.setTimeframe(timeframe)
        }
    }

    fun setProximityPct(value: Double) {
        viewModelScope.launch {
            settingsDataStore.setProximityPct(value.coerceIn(0.01, 1.0))
        }
    }

    fun setKissMode(mode: KissMode) {
        viewModelScope.launch {
            settingsDataStore.setKissMode(mode)
        }
    }

    fun setEventEnabled(eventType: EventType, enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setEventEnabled(eventType, enabled)
        }
    }

    fun setSlotEnabled(slotId: TrendlineSlotId, enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSlotEnabled(slotId, enabled)
        }
    }

    fun setDataSource(source: DataSourceType) {
        viewModelScope.launch {
            adaptiveMarketDataSource.setManualPreference(source)
            htfCandleCache.clearMemory()
            settingsDataStore.setDataSource(source)
        }
    }

    fun sendTestNotification() {
        notificationHelper.showTestNotification()
    }

    class Factory(
        private val settingsDataStore: SettingsDataStore,
        private val adaptiveMarketDataSource: AdaptiveMarketDataSource,
        private val htfCandleCache: HtfCandleCache,
        private val notificationHelper: NotificationHelper
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                settingsDataStore,
                adaptiveMarketDataSource,
                htfCandleCache,
                notificationHelper
            ) as T
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onRequestTestNotification: () -> Unit,
    onOpenBatteryHelper: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsState()
    val settings = prefs.alertSettings
    var tfExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        SettingsSection("Higher Timeframe (HTF)") {
            Box {
                OutlinedButton(
                    onClick = { tfExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Timeframe: ${settings.timeframe.label}")
                }
                DropdownMenu(
                    expanded = tfExpanded,
                    onDismissRequest = { tfExpanded = false }
                ) {
                    Timeframe.entries.forEach { tf ->
                        DropdownMenuItem(
                            text = { Text(tf.label) },
                            onClick = {
                                viewModel.setTimeframe(tf)
                                tfExpanded = false
                            }
                        )
                    }
                }
            }
        }

        SettingsSection("KISS Proximity (%)") {
            Text(
                text = String.format(Locale.US, "Proximity: %.2f%% (0.01%% - 1.00%%)", settings.proximityPct),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.proximityPct.toFloat(),
                onValueChange = { raw ->
                    val stepped = (raw * 100.0).roundToInt() / 100.0
                    viewModel.setProximityPct(stepped.coerceIn(0.01, 1.0))
                },
                valueRange = 0.01f..1.00f
            )
        }

        SettingsSection("KISS Mode") {
            KissMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.kissMode == mode,
                        onClick = { viewModel.setKissMode(mode) }
                    )
                    Column {
                        Text(
                            text = if (mode == KissMode.ENTER_ZONE) "ENTER_ZONE (immediate)" else "REVERSAL (30% flip)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (mode == KissMode.ENTER_ZONE) {
                                "Alert as soon as candle enters proximity zone"
                            } else {
                                "Alert only after entering zone, not touching, and reversing 30% away"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        SettingsSection("Event Toggles") {
            EventType.entries.forEach { eventType ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(eventType.name, style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = eventType in settings.enabledEvents,
                        onCheckedChange = { viewModel.setEventEnabled(eventType, it) }
                    )
                }
            }
        }

        SettingsSection("Trendline Slot Toggles") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TrendlineSlotId.ALL.forEach { slot ->
                    val enabled = slot in settings.enabledSlots
                    FilterChip(
                        selected = enabled,
                        onClick = { viewModel.setSlotEnabled(slot, !enabled) },
                        label = { Text(slot.name) }
                    )
                }
            }
        }

        SettingsSection("Market Data Source") {
            DataSourceType.entries.forEach { source ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = prefs.dataSourceType == source,
                        onClick = { viewModel.setDataSource(source) }
                    )
                    Text(source.displayName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Button(
            onClick = {
                onRequestTestNotification()
                viewModel.sendTestNotification()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Notifications, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Send test notification")
        }

        OutlinedButton(
            onClick = onOpenBatteryHelper,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.BatteryChargingFull, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Battery Optimization & OEM Autostart")
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun BatteryOptimizationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var isExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

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
            Text("Background & Battery Setup", style = MaterialTheme.typography.titleLarge)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isExempt) {
                        "Battery optimization: EXEMPTED (Unrestricted)"
                    } else {
                        "Battery optimization: RESTRICTED (Recommended to disable)"
                    },
                    color = if (isExempt) SignalGreen else SignalYellow,
                    style = MaterialTheme.typography.titleMedium
                )
                Button(
                    onClick = {
                        openBatteryExemptionDialog(context)
                        isExempt = isIgnoringBatteryOptimizations(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disable Battery Optimization")
                }
            }
        }

        Text("OEM Autostart Instructions", style = MaterialTheme.typography.titleMedium)

        OemCard(
            oem = "Samsung (One UI)",
            steps = "Settings -> Apps -> Trendline Alerts -> Battery -> Unrestricted. " +
                "Also add the app to Device Care -> Battery -> Background usage limits -> Never auto sleeping apps."
        )
        OemCard(
            oem = "Xiaomi / Redmi / POCO (MIUI / HyperOS)",
            steps = "Settings -> Apps -> Manage apps -> Trendline Alerts -> Enable Autostart, " +
                "and set Battery saver to No restrictions."
        )
        OemCard(
            oem = "Tecno (HiOS)",
            steps = "Phone Master -> Toolbox / App Management -> Auto-start management -> Enable Trendline Alerts. " +
                "Settings -> Battery -> Battery optimization -> Don't optimize."
        )
        OemCard(
            oem = "Infinix (XOS)",
            steps = "Phone Master -> App Management -> Auto-start -> Enable Trendline Alerts, " +
                "and lock the app in the Recent Apps switcher."
        )
        OemCard(
            oem = "Oppo / Realme (ColorOS)",
            steps = "Settings -> Apps -> App management -> Trendline Alerts -> Battery usage -> " +
                "Enable Allow background activity and Allow auto-launch."
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun OemCard(oem: String, steps: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(oem, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(steps, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatteryExemptionDialog(context: Context) {
    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(requestIntent)
    } catch (_: Throwable) {
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallback)
    }
}