/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource

import io.ktor.http.Url
import me.him188.ani.app.domain.mediasource.web.SelectorSearchConfig
import me.him188.ani.app.domain.mediasource.web.SelectorSourceAccessConfig
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSourceEngine
import me.him188.ani.app.domain.mediasource.web.createBootstrapConfig
import me.him188.ani.utils.xml.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectorSourceAccessTest {
    @Test
    fun `selector access defaults stay unblocked`() {
        val config = SelectorSearchConfig()
        val bootstrap = config.createBootstrapConfig()

        assertFalse(bootstrap.auth.requiresAuth)
        assertFalse(bootstrap.auth.hasChallenge)
        assertTrue(bootstrap.auth.playerSupport.isEmpty())
    }

    @Test
    fun `selector access uses explicit supported players`() {
        val engine = object : SelectorMediaSourceEngine() {
            override suspend fun searchImpl(finalUrl: Url): SearchSubjectResult {
                error("unused")
            }

            override suspend fun doHttpGet(uri: String): Document {
                error("unused")
            }
        }
        val evaluation = engine.evaluateSourceAccess(
            SelectorSearchConfig(
                access = SelectorSourceAccessConfig(
                    supportedPlayers = listOf("vlc"),
                ),
            ),
            currentPlayerName = "exoplayer",
        )

        assertTrue(evaluation.access.isBlocked)
        assertEquals(listOf("vlc"), evaluation.bootstrap.auth.playerSupport)
    }
}
