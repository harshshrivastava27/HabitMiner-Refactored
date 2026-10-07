package com.habitminer.engine

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.habitminer.collection.DataCollectionWorker
import com.habitminer.collection.HabitNotificationListener
import com.habitminer.collection.UsageDataCollector
import com.habitminer.data.AppUsageEntity
import com.habitminer.data.ContextSnapshotEntity
import com.habitminer.data.DiscoveredHabitEntity
import com.habitminer.data.PrefsKeys
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.repository.ContextRepository
import com.habitminer.repository.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

/** On/off switches for the v1.2 features, mirrored from preferences. */
@Immutable
data class FeatureSettings(
    val checkIns: Boolean = true,
    val nudges: Boolean = true,
    val digest: Boolean = true,
    val deviationAlerts: Boolean = true,
    val places: Boolean = false,
    val placesPermission: Boolean = false,
)

@Immutable
data class HabitUiState(
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val retentionDays: Int = 90,
    val daysOfData: Int = 0,
    val todayScreenTimeMs: Long = 0L,
    val todayUnlocks: Int = 0,
    val todayTopApp: String = "",
    val todayUsageByApp: ImmutableMap<String, Long> = persistentMapOf(),
    val todayAppUsage: ImmutableList<AppUsageEntity> = persistentListOf(),
    val todaySnapshots: ImmutableList<ContextSnapshotEntity> = persistentListOf(),
    val discoveredHabits: ImmutableList<DiscoveredHabitEntity> = persistentListOf(),
    /** Likely next apps after [predictionsAfter], from the next-app model. */
    val predictions: ImmutableList<com.habitminer.analytics.AppGuess> = persistentListOf(),
    val predictionsAfter: String? = null,
    val predictabilityScore: Float = 0f,
    val baselineStatus: String = "Building baseline...",
    val hasEnoughData: Boolean = false,
    val latestContext: ContextSnapshotEntity? = null,
    val hasUsagePermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasRuntimePermissions: Boolean = false,
    val expectedScreenTimeMs: Long = 0L,
    val typicalUsage: TypicalUsageCalculator.TypicalUsage? = null,
    val exportMessage: String? = null,
    val usageRecordCount: Int = 0,
    val liveUsageRecordCount: Int = 0,
    val historicalUsageRecordCount: Int = 0,
    val contextRecordCount: Int = 0,
    val lastUsageUpdate: Long? = null,
    val isMonitoringServiceActive: Boolean = false,
    val selectedHistoryDate: Long = 0L,
    val historicalAppUsage: ImmutableList<AppUsageEntity> = persistentListOf(),
    val historicalSnapshots: ImmutableList<ContextSnapshotEntity> = persistentListOf(),
    // ---- v1.2 ----
    val insights: InsightsBundle? = null,
    /** Latest snapshot that has real sensor readings (sensors pause while the screen is off). */
    val latestSensorContext: ContextSnapshotEntity? = null,
    /** Deviation key → EXPECTED / UNUSUAL. */
    val deviationFeedback: ImmutableMap<String, String> = persistentMapOf(),
    val checkInCount: Int = 0,
    val labelCount: Int = 0,
    /** Non-null while the check-in sheet should be shown; value is when it was prompted. */
    val pendingCheckInPromptedAt: Long? = null,
    val features: FeatureSettings = FeatureSettings(),
    val places: ImmutableList<com.habitminer.data.PlaceEntity> = persistentListOf(),
    val sensingModeName: String? = null,
    val sensingMsToday: Long = 0L,
    /** Steps counted today by the hardware step counter, or -1 before the first reading. */
    val stepsToday: Long = -1L,
    val stepSensorAvailable: Boolean = true,
    val stepPermission: Boolean = true,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HabitViewModel
    @Inject
    constructor(
        application: Application,
        private val contextRepository: ContextRepository,
        private val habitRepository: HabitRepository,
        private val habitEngine: HabitEngine,
        private val baselineBuilder: BaselineBuilder,
        private val usageDataCollector: UsageDataCollector,
        private val appIdentityResolver: AppIdentityResolver,
        private val exportManager: com.habitminer.data.ExportManager,
        private val habitServiceManager: com.habitminer.collection.HabitServiceManager,
        private val feedbackRepository: com.habitminer.repository.FeedbackRepository,
        private val insightsComputer: InsightsComputer,
        private val wifiPlaceProvider: com.habitminer.collection.WifiPlaceProvider,
        private val labelContextCapture: com.habitminer.proactive.LabelContextCapture,
        private val importManager: com.habitminer.data.ImportManager,
        private val stepCounterMonitor: com.habitminer.collection.StepCounterMonitor,
        private val usageIngestor: com.habitminer.collection.UsageIngestor,
        private val analysisRepository: AnalysisRepository,
    ) : AndroidViewModel(application), HabitActions {
        private val _uiState = MutableStateFlow(HabitUiState(selectedHistoryDate = getStartOfDay()))
        val uiState: StateFlow<HabitUiState> = _uiState.asStateFlow()
        // One-shot event: emits the file path for the Share Sheet. replay=0 means no re-play
        // after rotation, so the Share Sheet fires exactly once per export.
        private val _shareExportEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
        val shareExportEvent: SharedFlow<String> = _shareExportEvent.asSharedFlow()
        private var initialCollectionStarted = false
        private val syncMutex = Mutex()
        private val insightsRefresh = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

        private data class InsightsInputs(
            val usage: List<AppUsageEntity>,
            val snapshots: List<ContextSnapshotEntity>,
            val habits: List<DiscoveredHabitEntity>,
            val places: List<com.habitminer.data.PlaceEntity>,
            val labels: List<com.habitminer.data.UserLabelEntity>,
        )

        /** Periods you labelled (exams…), kept out of the "usual by now" comparison. */
        private val labelledPeriods = MutableStateFlow<List<com.habitminer.analytics.LabelledPeriod>>(emptyList())

        /** Kept apart from [_uiState] so observing it doesn't count as a screen watching. */
        private val selectedHistoryDate = MutableStateFlow(getStartOfDay())

        init {
            // The observers (tickers, database queries, analysis refreshes) run only while a
            // screen is collecting uiState, plus 5 seconds so rotation doesn't restart them.
            // Before, they kept running with the app in the background for as long as the
            // monitoring service kept the process alive.
            viewModelScope.launch {
                _uiState.subscriptionCount
                    .map { it > 0 }
                    .distinctUntilChanged()
                    .transformLatest { active ->
                        if (!active) delay(5_000L)
                        emit(active)
                    }
                    .distinctUntilChanged()
                    .collectLatest { active -> if (active) observeData() }
            }
        }

        override fun checkPermissions() {
            val application = getApplication<Application>()
            val appOps = application.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        application.packageName,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        application.packageName,
                    )
                }
            val hasUsage = mode == AppOpsManager.MODE_ALLOWED
            val hasNotif = HabitNotificationListener.isEnabled(application)
            val retentionDays =
                application.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt(PrefsKeys.RETENTION_DAYS, 90).coerceIn(30, 180)
            val collectionEnabled =
                application.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PrefsKeys.COLLECTION_ENABLED, true)

            val hasRuntime =
                buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(android.Manifest.permission.ACTIVITY_RECOGNITION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(android.Manifest.permission.POST_NOTIFICATIONS)
                }.all {
                    androidx.core.content.ContextCompat.checkSelfPermission(application, it) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }

            // Permission may have just been granted on the permission screen.
            stepCounterMonitor.start()
            _uiState.update {
                it.copy(
                    hasUsagePermission = hasUsage,
                    hasNotificationPermission = hasNotif,
                    hasRuntimePermissions = hasRuntime,
                    retentionDays = retentionDays,
                    features = readFeatureSettings(),
                    stepSensorAvailable = stepCounterMonitor.hasSensor(),
                    stepPermission = stepCounterMonitor.hasPermission(),
                )
            }

            if (hasUsage && collectionEnabled && !initialCollectionStarted) {
                initialCollectionStarted = true
                synchronizeUsageAndModel()
            }
        }

        override fun loadHistoricalData() {
            if (!_uiState.value.hasUsagePermission) return
            val application = getApplication<Application>()
            application.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PrefsKeys.COLLECTION_ENABLED, true).apply()
            synchronizeUsageAndModel()
        }

        private fun synchronizeUsageAndModel() {
            viewModelScope.launch(Dispatchers.IO) {
                if (!syncMutex.tryLock()) return@launch
                _uiState.update { it.copy(isLoading = true, isSyncing = true) }
                try {
                    val application = getApplication<Application>()
                    usageIngestor.ingest()

                    habitServiceManager.startServices()

                    val preferences = application.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    var labelsChanged = false
                    if (!preferences.getBoolean("stored_labels_resolved", false)) {
                        contextRepository.getPackagesWithFallbackNames().forEach { packageName ->
                            val appName = appIdentityResolver.getAppName(packageName)
                            if (appName != packageName) {
                                contextRepository.updateFallbackAppName(packageName, appName)
                                labelsChanged = true
                            }
                        }
                        preferences.edit().putBoolean("stored_labels_resolved", true).apply()
                    }

                    val revision = contextRepository.getModelRevision(getStartOfDay())
                    insightsRefresh.tryEmit(Unit)
                    if (labelsChanged || preferences.getString("source_revision", null) != revision) {
                        refreshHabits()
                        preferences.edit()
                            .putString("source_revision", revision)
                            .putInt(PrefsKeys.DAYS_OF_DATA, _uiState.value.daysOfData)
                            .putFloat("predictability_score", _uiState.value.predictabilityScore)
                            .apply()
                    } else {
                        val days = preferences.getInt(PrefsKeys.DAYS_OF_DATA, 0)
                        val existingBaselines = habitRepository.getAllBaselines().first()
                        val hasEnough = days >= 5 || existingBaselines.isNotEmpty()
                        _uiState.update {
                            it.copy(
                                daysOfData = days,
                                hasEnoughData = hasEnough,
                                baselineStatus = if (hasEnough) "Model up to date · $days days" else "Building baseline: $days/5 days",
                                predictabilityScore = preferences.getFloat("predictability_score", 0f),
                            )
                        }
                    }

                    // Trigger immediate collection for fresh sensor context
                    DataCollectionWorker.runOnce(application)
                } catch (error: Exception) {
                    android.util.Log.e("HabitMiner", "Could not sync usage or update the model", error)
                } finally {
                    syncMutex.unlock()
                    _uiState.update { it.copy(isLoading = false, isSyncing = false) }
                }
            }
        }

        fun setRetentionDays(days: Int) {
            val normalizedDays = days.coerceIn(30, 180)
            val application = getApplication<Application>()
            application.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(PrefsKeys.RETENTION_DAYS, normalizedDays).apply()
            _uiState.update { it.copy(retentionDays = normalizedDays) }
            DataCollectionWorker.runOnce(application)
        }

        fun clearCollectedData() {
            viewModelScope.launch(Dispatchers.IO) {
                contextRepository.clearCollectedData()
                habitRepository.clearModelData()
                feedbackRepository.clearAll()
                usageIngestor.reset()
                analysisRepository.invalidate()
                val application = getApplication<Application>()
                application.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove("source_revision")
                    .remove(PrefsKeys.DAYS_OF_DATA)
                    .remove("predictability_score")
                    .remove("stored_labels_resolved")
                    .putBoolean(PrefsKeys.COLLECTION_ENABLED, false)
                    .apply()
                _uiState.update {
                    HabitUiState(
                        hasUsagePermission = true,
                        hasRuntimePermissions = true,
                        retentionDays = it.retentionDays,
                        baselineStatus = "Data cleared. Tap Sync Usage to resume collection.",
                    )
                }
                // Re-check actual permission state instead of hardcoding true (MINOR-10)
                checkPermissions()
            }
        }

        fun exportDataToCsv() {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(exportMessage = "Exporting data...") }
                val path = exportManager.exportDataToCsv()
                if (path != null) {
                    _uiState.update { it.copy(exportMessage = "Export complete. Choose an app to share.") }
                    _shareExportEvent.emit(path)
                } else {
                    _uiState.update { it.copy(exportMessage = "Export failed. Please try again.") }
                }
            }
        }

        /**
         * Restores a ZIP from Export (e.g. after reinstalling). Afterwards the usual sync runs,
         * which rebuilds routines and baselines from the restored history.
         */
        fun importData(uri: android.net.Uri) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(exportMessage = "Importing your data…") }
                val message =
                    try {
                        val r = importManager.importZip(uri)
                        if (r.isEmpty) {
                            "That file doesn't look like a HabitMiner export. Pick the .zip made by Export Data."
                        } else {
                            "Imported ${r.usage} app sessions, ${r.snapshots} surroundings readings, " +
                                "${r.labels} labels and ${r.places} places. Rebuilding your routines…"
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("HabitMiner", "Import failed", e)
                        "Import failed: ${e.message ?: "unreadable file"}"
                    }
                _uiState.update { it.copy(exportMessage = message) }
                loadHistoricalData()
                insightsRefresh.tryEmit(Unit)
            }
        }

        override fun selectHistoryDate(timeInMillis: Long) {
            val startOfDay = Calendar.getInstance().apply {
                this.timeInMillis = timeInMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            selectedHistoryDate.value = startOfDay
            _uiState.update { it.copy(selectedHistoryDate = startOfDay) }
        }

        /**
         * Records whether a deviation was expected or unusual. Stored by key (date, kind and
         * subject) because deviations are recomputed whenever the data changes.
         */
        override fun giveDeviationFeedback(
            key: String,
            value: String,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                feedbackRepository.saveDeviationFeedback(key, value, labelContextCapture.captureJson())
                analysisRepository.invalidate()
                insightsRefresh.tryEmit(Unit)
            }
        }

        override fun answerNap(
            key: String,
            asleep: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                feedbackRepository.saveNapAnswer(key, asleep, labelContextCapture.captureJson())
                analysisRepository.invalidate()
                insightsRefresh.tryEmit(Unit)
                com.habitminer.proactive.Notifier.cancel(getApplication(), com.habitminer.proactive.Notifier.ID_NAP)
            }
        }

        override fun labelPeriod(
            key: String,
            from: java.time.LocalDate,
            value: String,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                feedbackRepository.savePeriodLabel(key, value, from, java.time.LocalDate.now())
                analysisRepository.invalidate()
                insightsRefresh.tryEmit(Unit)
                com.habitminer.proactive.Notifier.cancel(getApplication(), com.habitminer.proactive.Notifier.ID_PERIOD)
            }
        }

        // ---- Check-ins -------------------------------------------------------------------

        fun openCheckIn(promptedAt: Long?) {
            _uiState.update { it.copy(pendingCheckInPromptedAt = promptedAt ?: System.currentTimeMillis()) }
        }

        override fun dismissCheckIn() {
            _uiState.update { it.copy(pendingCheckInPromptedAt = null) }
        }

        override fun answerCheckIn(option: com.habitminer.analytics.CheckInOption) {
            val promptedAt = _uiState.value.pendingCheckInPromptedAt
            _uiState.update { it.copy(pendingCheckInPromptedAt = null) }
            viewModelScope.launch(Dispatchers.IO) {
                feedbackRepository.saveCheckIn(option.key, promptedAt, labelContextCapture.captureJson())
                com.habitminer.proactive.Notifier.cancelCheckIn(getApplication())
            }
        }

        // ---- Feature switches ------------------------------------------------------------

        private fun readFeatureSettings(): FeatureSettings {
            val prefs = getApplication<Application>().getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            return FeatureSettings(
                checkIns = prefs.getBoolean(PrefsKeys.CHECKINS_ENABLED, true),
                nudges = prefs.getBoolean(PrefsKeys.NUDGES_ENABLED, true),
                digest = prefs.getBoolean(PrefsKeys.DIGEST_ENABLED, true),
                deviationAlerts = prefs.getBoolean(PrefsKeys.DEVIATION_ALERTS_ENABLED, true),
                places = wifiPlaceProvider.isEnabled() && wifiPlaceProvider.hasPermission(),
                placesPermission = wifiPlaceProvider.hasPermission(),
            )
        }

        private fun setFlag(
            key: String,
            value: Boolean,
        ) {
            getApplication<Application>().getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply()
            _uiState.update { it.copy(features = readFeatureSettings()) }
        }

        fun setCheckInsEnabled(enabled: Boolean) = setFlag(PrefsKeys.CHECKINS_ENABLED, enabled)

        fun setNudgesEnabled(enabled: Boolean) = setFlag(PrefsKeys.NUDGES_ENABLED, enabled)

        fun setDigestEnabled(enabled: Boolean) = setFlag(PrefsKeys.DIGEST_ENABLED, enabled)

        fun setDeviationAlertsEnabled(enabled: Boolean) = setFlag(PrefsKeys.DEVIATION_ALERTS_ENABLED, enabled)

        /** Call after the location permission result. Restarts the service so it gains the location type. */
        fun setPlacesEnabled(enabled: Boolean) {
            wifiPlaceProvider.setEnabled(enabled && wifiPlaceProvider.hasPermission())
            _uiState.update { it.copy(features = readFeatureSettings()) }
            if (_uiState.value.hasUsagePermission) habitServiceManager.startServices()
        }

        fun renamePlace(
            placeHash: String,
            label: String?,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                feedbackRepository.renamePlace(placeHash, label)
            }
        }

        /** Recomputes the insights bundle now (e.g. pull-to-refresh or after returning to the app). */
        fun refreshInsights() {
            insightsRefresh.tryEmit(Unit)
        }

        fun clearExportMessage() {
            _uiState.update { it.copy(exportMessage = null) }
        }

        private suspend fun refreshHabits() = withContext(Dispatchers.Default) {
            val startOfDay = getStartOfDay()
            val windowStart = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            val allUsage = contextRepository.getUsageSince(windowStart)
            val historicalUsage = allUsage.filter { it.startTime < startOfDay }

            val snapshots = contextRepository.getSnapshotsSince(windowStart)
            val newBaselines = baselineBuilder.buildBaseline(historicalUsage, snapshots.filter { it.timestamp < startOfDay })
            val habits = habitEngine.discoverHabits(historicalUsage)
            // One transaction: screens watching these tables refresh once, not per row.
            habitRepository.replaceModel(newBaselines, habits)

            // Predictability
            val predictability = habitEngine.computePredictabilityScore(historicalUsage)

            val daysOfData = baselineBuilder.getDaysOfData(allUsage)
            val existingBaselines = habitRepository.getAllBaselines().first()
            val hasEnoughData = daysOfData >= 5 || existingBaselines.isNotEmpty()
            val baselineStatus = if (hasEnoughData) "Baseline built from $daysOfData days" else "Building baseline: $daysOfData/5 days"

            _uiState.update { current ->
                current.copy(
                    daysOfData = daysOfData,
                    hasEnoughData = hasEnoughData,
                    baselineStatus = baselineStatus,
                    predictabilityScore = predictability,
                )
            }
        }

        /**
         * Everything the screens show, kept current while a screen is visible. Suspends until
         * cancelled (when no screen has watched for 5 seconds).
         */
        private suspend fun observeData() =
            kotlinx.coroutines.supervisorScope {
                val zone = java.time.ZoneId.systemDefault()
                val startOfDayFlow =
                    flow {
                        while (currentCoroutineContext().isActive) {
                            emit(getStartOfDay())
                            delay(60_000L)
                        }
                    }.distinctUntilChanged()

                // Copy whatever happened since the last reading, so the numbers are current.
                launch(Dispatchers.IO) { runCatching { usageIngestor.ingest(minIntervalMs = 30_000L) } }

                // Five weeks of sessions, reloaded (ranged query) when today's usage changes.
                val window = MutableStateFlow<List<AppUsageEntity>>(emptyList())

                launch {
                    startOfDayFlow.collectLatest { startOfDay ->
                        contextRepository.getTodayUsage(startOfDay).collect { usage ->
                            val (totalTime, categories) =
                                withContext(Dispatchers.Default) {
                                    val valid = usage.filterNot { appIdentityResolver.isLauncher(it.packageName) }
                                    val byApp =
                                        valid.groupBy { appIdentityResolver.getAppName(it.packageName) }
                                            .mapValues { entry -> entry.value.sumOf { item -> item.durationMs } }
                                    valid.sumOf { it.durationMs } to byApp
                                }
                            _uiState.update {
                                it.copy(
                                    todayScreenTimeMs = totalTime,
                                    todayUsageByApp = categories.toImmutableMap(),
                                    todayTopApp = categories.maxByOrNull { e -> e.value }?.key.orEmpty(),
                                    todayAppUsage = usage.toImmutableList(),
                                )
                            }
                            val since = com.habitminer.analytics.TimeUtil.startOfDay(
                                java.time.LocalDate.now(zone).minusDays(InsightsComputer.LOOKBACK_DAYS),
                                zone,
                            )
                            window.value = withContext(Dispatchers.IO) { contextRepository.getUsageSince(since) }
                        }
                    }
                }

                // Likely next apps, days of data and last update: only when the sessions change.
                launch {
                    window.collectLatest { usage ->
                        if (usage.isEmpty()) return@collectLatest
                        val (after, guesses) =
                            withContext(Dispatchers.Default) {
                                val sessions =
                                    usage.filterNot { appIdentityResolver.isLauncher(it.packageName) }
                                        .map(AnalyticsMappers::session)
                                com.habitminer.analytics.NextAppModel.currentApp(sessions) to
                                    com.habitminer.analytics.NextAppModel.predict(sessions, System.currentTimeMillis(), zone)
                            }
                        val days =
                            withContext(Dispatchers.IO) {
                                contextRepository.countDaysWithUsageSince(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
                            }
                        _uiState.update {
                            it.copy(
                                predictions = guesses.toImmutableList(),
                                predictionsAfter = after,
                                daysOfData = days,
                                lastUsageUpdate = usage.maxOfOrNull { u -> u.endTime },
                            )
                        }
                    }
                }

                launch {
                    startOfDayFlow.collectLatest { startOfDay ->
                        contextRepository.getTodaySnapshots(startOfDay).collect { snapshots ->
                            _uiState.update { it.copy(todaySnapshots = snapshots.toImmutableList()) }
                        }
                    }
                }

                launch {
                    habitRepository.getAllHabits().collect { habits ->
                        _uiState.update { it.copy(discoveredHabits = habits.toImmutableList()) }
                    }
                }

                launch {
                    habitRepository.getAllBaselines().collect { baselines ->
                        val cal = Calendar.getInstance()
                        val day = cal.get(Calendar.DAY_OF_WEEK)
                        val dayType = if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) "WEEKEND" else "WEEKDAY"
                        val expected = baselines.filter { it.timeBin.startsWith(dayType) }.sumOf { it.avgScreenTimeMs }
                        _uiState.update { it.copy(expectedScreenTimeMs = expected) }
                    }
                }

                launch {
                    contextRepository.getLatestSnapshot().collect { snapshot ->
                        _uiState.update { it.copy(latestContext = snapshot) }
                    }
                }

                // "Usual by now" and today's unlocks: every 5 minutes and when the data changes.
                // (This ran every minute over every stored session.)
                val fiveMinutes =
                    flow {
                        while (currentCoroutineContext().isActive) {
                            emit(System.currentTimeMillis())
                            delay(5 * 60_000L)
                        }
                    }
                launch {
                    combine(window, fiveMinutes, labelledPeriods) { usage, now, periods -> Triple(usage, now, periods) }
                        .collectLatest { (usage, _, periods) ->
                            val now = System.currentTimeMillis()
                            val typical =
                                withContext(Dispatchers.Default) {
                                    val excluded =
                                        periods.flatMap { p ->
                                            generateSequence(p.from) { it.plusDays(1) }.takeWhile { !it.isAfter(p.to) }
                                                .map { com.habitminer.analytics.TimeUtil.startOfDay(it, zone) }.toList()
                                        }.toSet()
                                    TypicalUsageCalculator.compute(
                                        usage
                                            .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                                            .map { TypicalUsageCalculator.Interval(it.startTime, it.endTime, it.durationMs) },
                                        now,
                                        excludedDayStarts = excluded,
                                    )
                                }
                            val unlocks = withContext(Dispatchers.IO) { contextRepository.countUnlocksSince(getStartOfDay()) }
                            _uiState.update { it.copy(typicalUsage = typical, todayUnlocks = unlocks) }
                        }
                }

                launch {
                    stepCounterMonitor.stepsToday.collect { steps ->
                        _uiState.update { it.copy(stepsToday = steps) }
                    }
                }

                launch {
                    // Resets "steps today" after midnight even if no new steps arrive.
                    startOfDayFlow.collect { stepCounterMonitor.refreshDay() }
                }

                launch {
                    contextRepository.getLatestSnapshotWithSensors().collect { snapshot ->
                        _uiState.update { it.copy(latestSensorContext = snapshot) }
                    }
                }

                launch {
                    feedbackRepository.getAllLabels().collect { labels ->
                        labelledPeriods.value = LabelMappers.periods(labels)
                        val feedback =
                            labels.filter { it.kind == com.habitminer.data.UserLabelEntity.KIND_DEVIATION_FEEDBACK && it.refKey != null }
                                .associate { it.refKey!! to it.value }
                        _uiState.update {
                            it.copy(
                                deviationFeedback = feedback.toImmutableMap(),
                                checkInCount = labels.count { l -> l.kind == com.habitminer.data.UserLabelEntity.KIND_CHECK_IN },
                                labelCount = labels.size,
                            )
                        }
                    }
                }

                launch {
                    feedbackRepository.getPlaces().collect { places ->
                        _uiState.update { it.copy(places = places.toImmutableList()) }
                    }
                }

                launch {
                    startOfDayFlow.collectLatest { startOfDay ->
                        contextRepository.getSensingMsSince(startOfDay).collect { ms ->
                            val mode =
                                getApplication<Application>().getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString(PrefsKeys.SENSING_MODE, null)
                            _uiState.update { it.copy(sensingMsToday = ms, sensingModeName = mode) }
                        }
                    }
                }

                // The shared analysis: shown as soon as anyone (screen or background) computes it,
                // refreshed when sessions change, every 5 minutes, or on request.
                launch {
                    analysisRepository.bundle.collect { bundle ->
                        if (bundle != null) _uiState.update { it.copy(insights = bundle) }
                    }
                }
                launch {
                    merge(window.map { }, fiveMinutes.map { }, insightsRefresh)
                        .debounce(1_000L)
                        .collectLatest { analysisRepository.get() }
                }

                launch {
                    selectedHistoryDate.collectLatest { date ->
                        val endOfDay = date + 24 * 60 * 60 * 1000L - 1L
                        val usage = contextRepository.getUsageForDateRange(date, endOfDay)
                        val snaps = contextRepository.getSnapshotsForDateRange(date, endOfDay)
                        _uiState.update {
                            it.copy(historicalAppUsage = usage.toImmutableList(), historicalSnapshots = snaps.toImmutableList())
                        }
                    }
                }

                launch {
                    contextRepository.getUsageCountFlow().collect { count ->
                        _uiState.update { it.copy(usageRecordCount = count) }
                    }
                }

                launch {
                    contextRepository.getHistoricalUsageCountFlow().collect { count ->
                        _uiState.update { it.copy(historicalUsageRecordCount = count) }
                    }
                }

                launch {
                    contextRepository.getLiveUsageCountFlow().collect { count ->
                        _uiState.update { it.copy(liveUsageRecordCount = count) }
                    }
                }

                launch {
                    contextRepository.getSnapshotCountFlow().collect { count ->
                        _uiState.update { it.copy(contextRecordCount = count) }
                    }
                }

                launch {
                    com.habitminer.collection.MonitoringService.isServiceRunning.collectLatest { isRunning ->
                        _uiState.update { it.copy(isMonitoringServiceActive = isRunning) }
                    }
                }
            }

        private fun getStartOfDay(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun clearDatabase() {
            viewModelScope.launch(Dispatchers.IO) {
                val app = getApplication<Application>()

                // Cancel all background work
                habitServiceManager.stopServices()

                // Clear all Room tables
                contextRepository.clearCollectedData()
                habitRepository.clearModelData()
                feedbackRepository.clearAll()
                usageIngestor.reset()
                analysisRepository.invalidate()

                // Clear all SharedPreferences caches
                app.getSharedPreferences("sensor_prefs", Context.MODE_PRIVATE).edit().clear().commit()
                app.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

                // Reset state flow UI flags if necessary
                initialCollectionStarted = false
                _uiState.update { HabitUiState() }
                checkPermissions()
            }
        }
    }
