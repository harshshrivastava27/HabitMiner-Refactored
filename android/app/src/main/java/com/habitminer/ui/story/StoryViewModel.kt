package com.habitminer.ui.story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.WeeklyStory
import com.habitminer.analytics.WeeklyStoryBuilder
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.engine.AnalysisRepository
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.goals.GoalsStore
import com.habitminer.repository.ContextRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

data class StoryState(
    val loading: Boolean = true,
    val story: WeeklyStory? = null,
)

/** The Weekly Story, built once when opened from the last five weeks. */
@HiltViewModel
class StoryViewModel
    @Inject
    constructor(
        private val contextRepository: ContextRepository,
        private val analysisRepository: AnalysisRepository,
        private val appIdentityResolver: AppIdentityResolver,
        private val goalsStore: GoalsStore,
    ) : ViewModel() {
        val state: StateFlow<StoryState> =
            flow {
                val zone = ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val today = TimeUtil.dateOf(now, zone)
                val since = TimeUtil.startOfDay(today.minusDays(42), zone)
                val sessions =
                    contextRepository.getUsageSince(since)
                        .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                        .map(AnalyticsMappers::session)
                val unlocks = contextRepository.unlockTimesSince(since)
                val bundle = analysisRepository.get(maxAgeMs = 30 * TimeUtil.MINUTE)
                val goals = goalsStore.goals.value
                val story =
                    WeeklyStoryBuilder.build(
                        sessions = sessions,
                        unlocks = unlocks,
                        nights = bundle?.sleepNights.orEmpty(),
                        naps = bundle?.confirmedNaps.orEmpty(),
                        patterns = bundle?.patternGroups.orEmpty(),
                        dailyTargetMinutes = goals.dailyTargetMinutes,
                        useLess = goals.useLess.toSet(),
                        today = today,
                        now = now,
                        zone = zone,
                    )
                emit(StoryState(loading = false, story = story))
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryState())
    }
