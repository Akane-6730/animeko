/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import me.him188.ani.datasources.api.matcher.WebViewAuthConfig
import me.him188.ani.datasources.api.matcher.WebViewBootstrapConfig

@Immutable
@Serializable
data class SelectorSourceAccessConfig(
    val requiresAuth: Boolean = false,
    val hasChallenge: Boolean = false,
    val supportedPlayers: List<String> = emptyList(),
    val challengeUrlPatterns: List<String> = emptyList(),
)

internal enum class SelectorSourceAccessBlockReason {
    UnsupportedPlayer,
    RequiresAuthentication,
    ChallengeRequired,
}

internal data class SelectorSourceAccessState(
    val blockedReason: SelectorSourceAccessBlockReason? = null,
) {
    val isBlocked: Boolean get() = blockedReason != null

    fun toMessage(sourceDisplayName: String): String {
        return when (blockedReason) {
            SelectorSourceAccessBlockReason.UnsupportedPlayer ->
                "$sourceDisplayName is not supported by the current player"

            SelectorSourceAccessBlockReason.RequiresAuthentication ->
                "$sourceDisplayName requires website authentication before playback"

            SelectorSourceAccessBlockReason.ChallengeRequired ->
                "$sourceDisplayName requires completing a website challenge before playback"

            null -> "$sourceDisplayName is accessible"
        }
    }
}

internal data class SelectorSourceAccessEvaluation(
    val access: SelectorSourceAccessState,
    val bootstrap: WebViewBootstrapConfig,
)

internal data class SelectorMediaCandidate(
    val media: me.him188.ani.datasources.api.DefaultMedia,
    val access: SelectorSourceAccessState,
)

internal fun SelectorSearchConfig.createBootstrapConfig(): WebViewBootstrapConfig {
    return WebViewBootstrapConfig(
        userAgent = matchVideo.bootstrap.userAgent.takeIf { it.isNotBlank() },
        headers = buildMap {
            putAll(matchVideo.bootstrap.headers)
            matchVideo.bootstrap.referer.takeIf { it.isNotBlank() }?.let {
                put("Referer", it)
            }
        },
        auth = WebViewAuthConfig(
            requiresAuth = access.requiresAuth,
            hasChallenge = access.hasChallenge,
            challengeUrlPatterns = access.challengeUrlPatterns,
            playerSupport = access.supportedPlayers.ifEmpty { onlySupportsPlayers },
        ),
    )
}
