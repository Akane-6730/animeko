/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import androidx.compose.ui.util.packInts
import androidx.collection.MutableIntObjectMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.LightSubjectInfo
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.usecase.UseCase
import me.him188.ani.utils.coroutines.flows.catching
import me.him188.ani.utils.platform.collections.mapToIntList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class AiringScheduleForDate(
    val date: LocalDate,
    val list: List<EpisodeWithAiringTime>,
)

data class EpisodeWithAiringTime(
    val subject: LightSubjectInfo,
    val episode: LightEpisodeInfo,
    val airingTime: Instant,
) {
    val combinedId = packInts(subject.subjectId, episode.episodeId)
}

interface GetAnimeScheduleFlowUseCase : UseCase {
    operator fun invoke(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>>

    fun peek(today: LocalDate, timeZone: TimeZone): List<AiringScheduleForDate>?

    fun prewarm(today: LocalDate, timeZone: TimeZone)

    fun refresh(today: LocalDate, timeZone: TimeZone)

    companion object {
        val OFFSET_DAYS_RANGE = (-7..7)
    }
}

class GetAnimeScheduleFlowUseCaseImpl(
    private val animeScheduleRepository: AnimeScheduleRepository,
    private val subjectCollectionRepository: SubjectCollectionRepository,
    private val appScope: CoroutineScope,
    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : GetAnimeScheduleFlowUseCase {
    private data class WarmKey(
        val today: LocalDate,
        val timeZoneId: String,
    )

    private data class WarmEntry(
        val key: WarmKey,
        val shared: RefreshableWarmFlow<List<AiringScheduleForDate>>,
    )

    private var warmEntry: WarmEntry? = null

    override fun invoke(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>> =
        getOrCreateEntry(today, timeZone).shared.flow

    override fun peek(today: LocalDate, timeZone: TimeZone): List<AiringScheduleForDate>? {
        return getExistingEntry(today, timeZone)?.shared?.peek()
    }

    override fun prewarm(today: LocalDate, timeZone: TimeZone) {
        getOrCreateEntry(today, timeZone)
    }

    override fun refresh(today: LocalDate, timeZone: TimeZone) {
        getOrCreateEntry(today, timeZone).shared.refresh()
    }

    private fun getExistingEntry(today: LocalDate, timeZone: TimeZone): WarmEntry? {
        val key = WarmKey(today, timeZone.id)
        return warmEntry?.takeIf { it.key == key }
    }

    private fun getOrCreateEntry(today: LocalDate, timeZone: TimeZone): WarmEntry {
        getExistingEntry(today, timeZone)?.let { return it }

        val key = WarmKey(today, timeZone.id)
        warmEntry?.shared?.close()
        val shared = RefreshableWarmFlow(appScope) { buildScheduleFlow(today, timeZone) }
        return WarmEntry(key, shared).also { warmEntry = it }
    }

    private fun buildScheduleFlow(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>> =
        animeScheduleRepository.recentSchedulesFlow()
            .take(1)
            .flatMapLatest { schedule ->
                val onAirAnimeInfos = schedule.flatMap { it.list }
                    .filter {
                        val end = it.end
                        it.begin != null && it.recurrence != null && (end == null || end > Clock.System.now())
                    }

                subjectCollectionRepository.batchLightSubjectAndEpisodesFlow(onAirAnimeInfos.mapToIntList { it.bangumiId })
                    .mapLatest { subjects ->
                        val subjectsById = MutableIntObjectMap<LightSubjectInfo>(subjects.size).apply {
                            subjects.forEach { put(it.subjectId, it.subject) }
                        }
                        val airingScheduleByDate = AnimeScheduleHelper.buildAiringScheduleForDateRange(
                            subjects = subjects,
                            airInfos = onAirAnimeInfos,
                            dates = GetAnimeScheduleFlowUseCase.OFFSET_DAYS_RANGE.map { offsetDays ->
                                today.plus(DatePeriod(days = offsetDays))
                            },
                            localTimeZone = timeZone,
                            allowedDeviation = 1.minutes,
                        )

                        GetAnimeScheduleFlowUseCase.OFFSET_DAYS_RANGE.map { offsetDays ->
                            val date = today.plus(DatePeriod(days = offsetDays))
                            AiringScheduleForDate(
                                date,
                                airingScheduleByDate[date].orEmpty().mapNotNull { episodeSchedule ->
                                    val subject = subjectsById[episodeSchedule.subjectId] ?: return@mapNotNull null
                                    EpisodeWithAiringTime(
                                        subject = subject,
                                        episode = episodeSchedule.episode,
                                        airingTime = episodeSchedule.airingTime,
                                    )
                                }.distinctBy { it.combinedId },
                            )
                        }
                    }
            }.flowOn(defaultDispatcher)
}

internal class RefreshableWarmFlow<T>(
    parentScope: CoroutineScope,
    private val builder: () -> Flow<T>,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]))
    private val state = MutableStateFlow<Result<T>?>(null)
    private var refreshJob: Job? = null

    val flow: Flow<T> = state.filterNotNull().map { it.getOrThrow() }

    init {
        refresh()
    }

    fun peek(): T? = state.value?.getOrNull()

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            builder().catching().collect { result ->
                state.value = result
            }
        }
    }

    fun close() {
        refreshJob?.cancel()
        scope.cancel()
    }
}
