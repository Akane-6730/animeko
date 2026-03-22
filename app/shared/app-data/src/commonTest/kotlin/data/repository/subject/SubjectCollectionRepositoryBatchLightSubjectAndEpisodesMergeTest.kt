/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlin.test.Test
import kotlin.test.assertEquals

class SubjectCollectionRepositoryBatchLightSubjectAndEpisodesMergeTest {
    @Test
    fun `fresh results win over expired cache and preserve request order`() {
        val subjectIds = listOf(3, 1, 2)
        val existingResults = mapOf(
            1 to "fresh-cache",
            2 to "stale-cache",
        )
        val missingResults = mapOf(
            2 to "refetched",
            3 to "missing-fetched",
        )

        val merged = buildList {
            subjectIds.forEach { subjectId ->
                val result = missingResults[subjectId] ?: existingResults[subjectId]
                if (result != null) {
                    add(subjectId to result)
                }
            }
        }

        assertEquals(
            listOf(
                3 to "missing-fetched",
                1 to "fresh-cache",
                2 to "refetched",
            ),
            merged,
        )
    }
}
