package com.habitminer.ui.trends

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habitminer.analytics.AppDetail
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.UsageSummaries
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.repository.ContextRepository
import com.habitminer.repository.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

data class AppDetailState(
    val loading: Boolean = true,
    val detail: AppDetail? = null,
    /** Routines (app sequences) this app is part of. */
    val routines: List<String> = emptyList(),
)

/** One app's page: loaded once when opened, from the last five weeks. */
@HiltViewModel
class AppDetailViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val contextRepository: ContextRepository,
        private val habitRepository: HabitRepository,
    ) : ViewModel() {
        val packageName: String = savedState.get<String>(ARG_PACKAGE).orEmpty()

        val state: StateFlow<AppDetailState> =
            flow {
                val zone = ZoneId.systemDefault()
                val today = java.time.LocalDate.now(zone)
                val since = TimeUtil.startOfDay(today.minusDays(34), zone)
                val sessions = contextRepository.getUsageForPackageSince(packageName, since).map(AnalyticsMappers::session)
                val notes = contextRepository.notificationTimesForPackageSince(packageName, since)
                val detail = UsageSummaries.detail(sessions, notes, today, zone)
                val name = detail?.appName
                val routines =
                    if (name == null) {
                        emptyList()
                    } else {
                        habitRepository.getAllHabits().first()
                            .filter { it.patternDescription.contains(name) }
                            .sortedByDescending { it.confidence }
                            .map { it.patternDescription }
                            .distinct()
                            .take(4)
                    }
                emit(AppDetailState(loading = false, detail = detail, routines = routines))
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppDetailState())

        companion object {
            const val ARG_PACKAGE = "pkg"
        }
    }
