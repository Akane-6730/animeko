/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import androidx.collection.MutableIntObjectMap
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.schedule.OnAirAnimeInfo
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.LightSubjectAndEpisodes
import me.him188.ani.datasources.api.toLocalDateOrNull
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

object AnimeScheduleHelper {

    data class EpisodeNextAiringTime(
        val subjectId: Int,
        val episode: LightEpisodeInfo,
        val airingTime: Instant,
    )

    /**
     * Builds an airing schedule for [targetDate], in the given [localTimeZone], with an
     * [allowedDeviation] (default = 24h). The algorithm:
     *
     * - For each subject & episode (in ascending sort order):
     *   - Determine an "intended" LocalDate for that episode (either from [episode.airDate]
     *     if valid, or guess from the previous episode).
     *   - Convert that LocalDate into an approximate "actual" Instant by snapping to the nearest
     *     multiple of [recurrence.interval] from [recurrence.startTime]—but only if the difference
     *     is within [allowedDeviation].
     *   - If it’s out of [allowedDeviation], fall back to “previous episode’s actual airtime + interval”
     *     or “startTime + (episodeIndex * interval)”.
     *   - Finally, if that actual airtime’s LocalDate == [targetDate], we include it in the result.
     */
    fun buildAiringScheduleForDate(
        subjects: List<LightSubjectAndEpisodes>,
        airInfos: List<OnAirAnimeInfo>,
        targetDate: LocalDate,
        localTimeZone: TimeZone,
        allowedDeviation: Duration = 24.hours,
    ): List<EpisodeNextAiringTime> {
        return buildAiringScheduleForDateRange(
            subjects = subjects,
            airInfos = airInfos,
            dates = listOf(targetDate),
            localTimeZone = localTimeZone,
            allowedDeviation = allowedDeviation,
        )[targetDate].orEmpty()
    }

    fun buildAiringScheduleForDateRange(
        subjects: List<LightSubjectAndEpisodes>,
        airInfos: List<OnAirAnimeInfo>,
        dates: List<LocalDate>,
        localTimeZone: TimeZone,
        allowedDeviation: Duration = 24.hours,
    ): Map<LocalDate, List<EpisodeNextAiringTime>> {
        if (dates.isEmpty()) return emptyMap()

        // Pre-map OnAirAnimeInfo by bangumiId (subjectId)
        val subjectIdToAirInfo = MutableIntObjectMap<OnAirAnimeInfo>(subjects.size).apply {
            airInfos.forEach { put(it.bangumiId, it) }
        }

        val targetDates = dates.toHashSet()
        val results = linkedMapOf<LocalDate, MutableList<EpisodeNextAiringTime>>()
        dates.forEach { results.getOrPut(it) { mutableListOf() } }

        subjects.forEach { subject ->
            val airInfo = subjectIdToAirInfo[subject.subjectId] ?: return@forEach

            val startTime = airInfo.begin ?: return@forEach
            val recurrence = airInfo.recurrence ?: return@forEach

            val episodes = subject.episodes.sortedBy { it.sort }

            var lastEpisodeInstant: Instant? = null
            val matchedEpisodesByDate = linkedMapOf<LocalDate, EpisodeNextAiringTime>()

            episodes.forEachIndexed { index, ep ->
                val episodeNumber = index + 1

                val epLocalDate: LocalDate? = ep.airDate.toLocalDateOrNull()
                val epLocalMidnight: Instant? = epLocalDate
                    ?.atStartOfDayIn(ep.timezone)

                val snappedAirtime: Instant? = epLocalMidnight?.let { localMidnight ->
                    val diff = localMidnight - recurrence.startTime
                    val n = (diff.inWholeMilliseconds.toDouble() / recurrence.interval.inWholeMilliseconds).roundToInt()
                    val candidateAirtime = recurrence.startTime + (recurrence.interval * n)
                    val offBy = (candidateAirtime - localMidnight).absoluteValue

                    if (offBy <= allowedDeviation) {
                        candidateAirtime
                    } else {
                        null
                    }
                }

                val actualEpTime: Instant = when {
                    snappedAirtime != null -> {
                        snappedAirtime
                    }

                    lastEpisodeInstant != null -> {
                        lastEpisodeInstant!!.plus(recurrence.interval)
                    }

                    else -> {
                        startTime + recurrence.interval * (episodeNumber - 1)
                    }
                }

                lastEpisodeInstant = actualEpTime

                val actualEpLocalDate = actualEpTime.toLocalDateTime(localTimeZone).date
                if (actualEpLocalDate in targetDates) {
                    matchedEpisodesByDate[actualEpLocalDate] =
                        EpisodeNextAiringTime(
                            subjectId = subject.subjectId,
                            episode = ep,
                            airingTime = actualEpTime,
                        )
                }
            }

            matchedEpisodesByDate.forEach { (date, episode) ->
                results.getOrPut(date) { mutableListOf() }.add(episode)
            }
        }

        return results
    }
}
