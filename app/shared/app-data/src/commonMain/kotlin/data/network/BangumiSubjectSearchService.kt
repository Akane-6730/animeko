/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import androidx.collection.IntList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.subject.RatingCounts
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.repository.RepositoryRateLimitedException
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.search.SubjectType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.bangumi.BangumiRateLimitedException
import me.him188.ani.datasources.bangumi.BangumiSearchSubjectNewApi
import me.him188.ani.datasources.bangumi.Rating
import me.him188.ani.datasources.bangumi.client.BangumiSearchApi
import me.him188.ani.datasources.bangumi.models.BangumiCollection
import me.him188.ani.datasources.bangumi.models.BangumiSubjectType
import me.him188.ani.datasources.bangumi.models.search.BangumiSort
import me.him188.ani.datasources.bangumi.models.subjects.BangumiLegacySubject
import me.him188.ani.datasources.bangumi.models.subjects.BangumiSubjectImageSize
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.platform.collections.mapToIntList
import kotlin.coroutines.CoroutineContext

data class BangumiSearchFilters(
    val tags: List<String>? = null, // "童年", "原创"
    val airDates: List<String>? = null, // YYYY-MM-DD
    val ratings: List<String>? = null, // ">=6", "<8"
    val ranks: List<String>? = null,
    val nsfw: Boolean? = null,
)

class BangumiSubjectSearchService(
    private val searchApi: ApiInvoker<BangumiSearchApi>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    suspend fun searchSubjectIds(
        keyword: String,
        useNewApi: Boolean,
        offset: Int? = null,
        limit: Int? = null,

        sort: BangumiSort? = null,
        filters: BangumiSearchFilters? = null,
    ): IntList = withContext(ioDispatcher) {
        searchImpl(sanitizeKeyword(keyword), useNewApi, offset, limit, sort, filters).fold(
            left = { list ->
                list.orEmpty().mapToIntList {
                    it.id
                }
            },
            right = { list ->
                list.mapToIntList {
                    it.id
                }
            },
        )
    }

    suspend fun searchSubjects(
        keyword: String,
        useNewApi: Boolean,
        offset: Int? = null,
        limit: Int? = null,
        sort: BangumiSort? = null,
        filters: BangumiSearchFilters? = null,
    ): List<BatchSubjectDetails> = withContext(ioDispatcher) {
        searchImpl(sanitizeKeyword(keyword), useNewApi, offset, limit, sort, filters).fold(
            left = { list ->
                list.orEmpty().map { it.toBatchSubjectDetails() }
            },
            right = { list ->
                list.map { it.toBatchSubjectDetails() }
            },
        )
    }

    suspend fun searchSubjectNames(
        keyword: String,
        useNewApi: Boolean,
        includeNsfw: Boolean,
//        offset: Int? = null, // 无法支持 offset, 因为过滤掉 NSFW 后可能会导致返回的结果数量与 offset 不匹配
        limit: Int? = null,
    ): List<String> = withContext(ioDispatcher) {
        searchImpl(keyword, useNewApi, 0, limit).fold(
            left = { list ->
                list.orEmpty()
                    .filter { includeNsfw || !it.nsfw }
                    .map { subject ->
                        subject.nameCn.takeIf { it.isNotEmpty() } ?: subject.name
                    }
            },
            right = { list ->
                list
                    // 不支持 nsfw 过滤
                    .map { subject ->
                        subject.chineseName.takeIf { it.isNotEmpty() } ?: subject.originalName
                    }
            },
        )
    }

    private suspend fun searchImpl(
        keyword: String,
        useNewApi: Boolean,
        offset: Int? = null,
        limit: Int? = null,

        sort: BangumiSort? = null,
        filters: BangumiSearchFilters? = null,
    ): Either<List<BangumiSearchSubjectNewApi>?, List<BangumiLegacySubject>> = searchApi {
        if (useNewApi) {
            Either.Left(
                searchSubjectByKeywords(
                    keyword,
                    offset = offset,
                    limit = limit,
                    types = listOf(BangumiSubjectType.Anime),
                    sort = sort,
                    tags = filters?.tags,
                    airDates = filters?.airDates,
                    ratings = filters?.ratings,
                    ranks = filters?.ranks,
                    nsfw = filters?.nsfw,
                ),
            )
        } else {
            try {
                Either.Right(
                    searchSubjectsByKeywordsWithOldApi(
                        keyword,
                        type = BangumiSubjectType.Anime,
                        responseGroup = BangumiSubjectImageSize.SMALL,
                        start = offset,
                        maxResults = limit,
                    ).page,
                )
            } catch (e: BangumiRateLimitedException) {
                throw RepositoryRateLimitedException(cause = e)
            }
        }
    }

    companion object {
        fun sanitizeKeyword(keyword: String): String {
            return buildString(keyword.length) {
                for (c in keyword) {
                    if (MediaListFilters.charsToDeleteForSearch.contains(c.code)) {
                        append(' ')
                    } else {
                        append(c)
                    }
                }
            }
        }
    }
}

private fun BangumiSearchSubjectNewApi.toBatchSubjectDetails(): BatchSubjectDetails {
    val subjectInfo = SubjectInfo(
        subjectId = id,
        subjectType = SubjectType.ANIME,
        name = name,
        nameCn = nameCn,
        summary = summary,
        nsfw = nsfw,
        imageLarge = buildSubjectImageUrl(id, BangumiSubjectImageSize.LARGE),
        totalEpisodes = 0,
        airDate = date?.let { PackedDate.parseFromDate(it) } ?: PackedDate.Invalid,
        tags = tags.map { Tag(it.name, it.count) },
        aliases = emptyList(),
        ratingInfo = RatingInfo.Empty,
        collectionStats = SubjectCollectionStats.Zero,
        completeDate = PackedDate.Invalid,
    )
    return BatchSubjectDetails(
        subjectInfo = subjectInfo,
        mainEpisodeCount = 0,
        lightSubjectRelations = LightSubjectRelations(emptyList(), emptyList()),
    )
}

private fun BangumiLegacySubject.toBatchSubjectDetails(): BatchSubjectDetails {
    val subjectInfo = SubjectInfo(
        subjectId = id,
        subjectType = SubjectType.ANIME,
        name = originalName,
        nameCn = chineseName,
        summary = summary,
        nsfw = false,
        imageLarge = buildSubjectImageUrl(id, BangumiSubjectImageSize.LARGE),
        totalEpisodes = epsCount.takeIf { it > 0 } ?: eps,
        airDate = PackedDate.parseFromDate(airDate),
        tags = emptyList(),
        aliases = emptyList(),
        ratingInfo = rating?.toRatingInfo() ?: RatingInfo.Empty,
        collectionStats = collection?.toSubjectCollectionStats() ?: SubjectCollectionStats.Zero,
        completeDate = PackedDate.Invalid,
    )
    return BatchSubjectDetails(
        subjectInfo = subjectInfo,
        mainEpisodeCount = subjectInfo.totalEpisodes,
        lightSubjectRelations = LightSubjectRelations(emptyList(), emptyList()),
    )
}

private fun me.him188.ani.datasources.bangumi.BangumiRating.toRatingInfo(): RatingInfo {
    val counts = RatingCounts(
        s1 = count[Rating.ONE] ?: 0,
        s2 = count[Rating.TWO] ?: 0,
        s3 = count[Rating.THREE] ?: 0,
        s4 = count[Rating.FOUR] ?: 0,
        s5 = count[Rating.FIVE] ?: 0,
        s6 = count[Rating.SIX] ?: 0,
        s7 = count[Rating.SEVEN] ?: 0,
        s8 = count[Rating.EIGHT] ?: 0,
        s9 = count[Rating.NINE] ?: 0,
        s10 = count[Rating.TEN] ?: 0,
    )
    return RatingInfo(
        rank = rank,
        total = total,
        count = counts,
        score = score.toString(),
    )
}

private fun BangumiCollection.toSubjectCollectionStats(): SubjectCollectionStats {
    return SubjectCollectionStats(
        wish = wish,
        doing = doing,
        done = collect,
        onHold = onHold,
        dropped = dropped,
    )
}

private fun buildSubjectImageUrl(id: Int, size: BangumiSubjectImageSize): String {
    return "https://api.bgm.tv/v0/subjects/$id/image?type=${size.id.lowercase()}"
}

private sealed class Either<out A, out B> {
    data class Left<A>(val value: A) : Either<A, Nothing>()
    data class Right<B>(val value: B) : Either<Nothing, B>()
}

private inline fun <A, B, C> Either<A, B>.fold(
    left: (A) -> C,
    right: (B) -> C,
): C = when (this) {
    is Either.Left -> left(value)
    is Either.Right -> right(value)
}
