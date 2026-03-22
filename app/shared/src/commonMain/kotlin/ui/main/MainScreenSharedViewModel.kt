/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.domain.episode.GetAnimeScheduleFlowUseCase
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.user.SelfInfoStateProducer
import kotlin.time.Clock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainScreenSharedViewModel : AbstractViewModel(), KoinComponent {
    private val getAnimeScheduleFlowUseCase: GetAnimeScheduleFlowUseCase by inject()
    val selfInfo = SelfInfoStateProducer(koin = getKoin()).flow

    override fun init() {
        val timeZone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(timeZone).date
        getAnimeScheduleFlowUseCase.prewarm(today, timeZone)
    }
}
