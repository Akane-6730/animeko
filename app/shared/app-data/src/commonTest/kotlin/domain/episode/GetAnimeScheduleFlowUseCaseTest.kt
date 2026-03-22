/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import me.him188.ani.app.data.models.schedule.AnimeRecurrence
import me.him188.ani.app.data.models.schedule.OnAirAnimeInfo
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.LightSubjectAndEpisodes
import me.him188.ani.app.data.models.subject.LightSubjectInfo
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.UTC9
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GetAnimeScheduleFlowUseCaseTest {
    @Test
    fun `buildAiringScheduleForDateRange matches per-day results`() {
        val subjects = listOf(
            LightSubjectAndEpisodes(
                subject = LightSubjectInfo(1, "A", "A", "https://example.com/a.jpg"),
                episodes = listOf(
                    LightEpisodeInfo(1, "ep1", "ep1", PackedDate(2025, 1, 1), UTC9, EpisodeSort("1"), null),
                    LightEpisodeInfo(2, "ep2", "ep2", PackedDate(2025, 1, 8), UTC9, EpisodeSort("2"), null),
                ),
            ),
        )
        val airInfos = listOf(
            OnAirAnimeInfo(
                bangumiId = 1,
                name = "A",
                aliases = emptyList(),
                begin = Instant.parse("2025-01-01T00:00:00Z"),
                recurrence = AnimeRecurrence(
                    startTime = Instant.parse("2025-01-01T00:00:00Z"),
                    interval = 7.days,
                ),
                end = null,
                mikanId = null,
            ),
        )
        val dates = listOf(LocalDate(2025, 1, 1), LocalDate(2025, 1, 8), LocalDate(2025, 1, 15))

        val range = AnimeScheduleHelper.buildAiringScheduleForDateRange(
            subjects = subjects,
            airInfos = airInfos,
            dates = dates,
            localTimeZone = TimeZone.UTC,
        )

        dates.forEach { date ->
            assertEquals(
                AnimeScheduleHelper.buildAiringScheduleForDate(subjects, airInfos, date, TimeZone.UTC),
                range[date].orEmpty(),
            )
        }
    }

    @Test
    fun `refreshable warm flow replays latest and refreshes`() = runTest {
        var buildCount = 0
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val warmFlow = RefreshableWarmFlow(
            parentScope = scope,
        ) {
            flow {
                buildCount += 1
                emit(buildCount)
            }
        }

        advanceUntilIdle()

        assertEquals(1, warmFlow.peek())

        warmFlow.refresh()
        advanceUntilIdle()

        assertEquals(2, warmFlow.peek())

        scope.cancel()
    }
}
