package com.anime47.enricher

import android.util.Log
import com.anime47.enricher.models.EpisodeItem
import com.anime47.enricher.models.EpisodeWatchResponse
import com.anime47.enricher.models.EpisodesResponse
import com.anime47.enricher.models.SearchResponse
import com.anime47.enricher.models.SearchResult
import com.anime47.enricher.models.SubtitleTrack
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.NiceResponse
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap

internal fun NiceResponse.safeText(): String? {
    return try {
        this.text
    } catch (_: LinkageError) {
        try {
            this.okhttpResponse.body?.string()
        } catch (_: Throwable) {
            null
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Core scraping engine for Anime47 (backend API: https://animevui.com/api).
 * Interacts with Search, Episode List, and Watch/Subtitle endpoints.
 */
object Anime47Scraper {

    private const val TAG = "Anime47Scraper"
    private const val API_BASE_URL = Anime47Auth.API_BASE_URL

    private class LruCache<K, V>(private val maxEntries: Int) :
        LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    val subtitleCache = Collections.synchronizedMap(LruCache<String, List<SubtitleTrack>>(128))
    val searchCache = Collections.synchronizedMap(LruCache<String, List<SearchResult>>(64))
    val episodesCache = Collections.synchronizedMap(LruCache<Int, List<EpisodeItem>>(64))

    fun clearCaches() {
        subtitleCache.clear()
        searchCache.clear()
        episodesCache.clear()
        Log.i(TAG, "Cleared Anime47 in-memory caches.")
    }

    /**
     * Searches Anime47 by keyword.
     * Endpoint: GET /search/full/?keyword={keyword}&page=1
     */
    suspend fun searchAnime(keyword: String, page: Int = 1): List<SearchResult> {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return emptyList()
        val cacheKey = "${trimmed.lowercase()}_p$page"
        searchCache[cacheKey]?.let { return it }

        return try {
            val encodedKeyword = URLEncoder.encode(trimmed, "UTF-8")
            val url = "$API_BASE_URL/search/full/?keyword=$encodedKeyword&page=$page"
            
            val response = app.get(
                url = url,
                headers = Anime47Auth.defaultHeaders(),
                timeout = 10
            )

            if (response.isSuccessful) {
                val text = response.safeText() ?: ""
                val searchResult = parseJson<SearchResponse>(text)
                val results = searchResult.results ?: emptyList()
                if (results.isNotEmpty()) {
                    searchCache[cacheKey] = results
                }
                results
            } else {
                emptyList()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Retrieves the list of episodes for a given anime ID.
     * Endpoint: GET /anime/{animeId}/episodes
     * Requires Authorization: Bearer {accessToken}
     */
    suspend fun getEpisodes(animeId: Int): List<EpisodeItem> {
        episodesCache[animeId]?.let { return it }

        val token = Anime47Auth.getValidToken() ?: return emptyList()

        return try {
            val url = "$API_BASE_URL/anime/$animeId/episodes"
            var response = app.get(
                url = url,
                headers = Anime47Auth.defaultHeaders(token),
                timeout = 10
            )

            // If private mode or token expired, force refresh token and retry once
            val initialText = response.safeText() ?: ""
            if (!response.isSuccessful || initialText.contains("PRIVATE_MODE")) {
                val freshToken = Anime47Auth.getValidToken(forceRefresh = true)
                if (freshToken != null) {
                    response = app.get(
                        url = url,
                        headers = Anime47Auth.defaultHeaders(freshToken),
                        timeout = 10
                    )
                }
            }

            if (response.isSuccessful) {
                val text = response.safeText() ?: ""
                val episodesData = parseJson<EpisodesResponse>(text)
                val allEpisodes = mutableListOf<EpisodeItem>()

                episodesData.teams?.forEach { team ->
                    team.groups?.forEach { group ->
                        group.episodes?.let { allEpisodes.addAll(it) }
                    }
                }
                if (allEpisodes.isNotEmpty()) {
                    episodesCache[animeId] = allEpisodes
                }
                allEpisodes
            } else {
                emptyList()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Retrieves stream and subtitle tracks for a specific episode ID.
     * Endpoint: GET /anime/watch/episode/{episodeId}
     * Requires Authorization: Bearer {accessToken}
     */
    suspend fun getEpisodeSubtitles(episodeId: Int): List<SubtitleTrack> {
        val token = Anime47Auth.getValidToken() ?: return emptyList()

        return try {
            val url = "$API_BASE_URL/anime/watch/episode/$episodeId"
            var response = app.get(
                url = url,
                headers = Anime47Auth.defaultHeaders(token),
                timeout = 10
            )

            val initialText = response.safeText() ?: ""
            if (!response.isSuccessful || initialText.contains("PRIVATE_MODE")) {
                val freshToken = Anime47Auth.getValidToken(forceRefresh = true)
                if (freshToken != null) {
                    response = app.get(
                        url = url,
                        headers = Anime47Auth.defaultHeaders(freshToken),
                        timeout = 10
                    )
                }
            }

            if (response.isSuccessful) {
                val text = response.safeText() ?: ""
                val watchData = parseJson<EpisodeWatchResponse>(text)
                val subtitleTracks = mutableListOf<SubtitleTrack>()

                watchData.streams?.forEach { stream ->
                    stream.subtitles?.forEach { sub ->
                        if (sub.file.isNotBlank()) {
                            val rawLabel = sub.label ?: "Tiếng Việt"
                            val formattedLabel = if (rawLabel.contains("Anime47", ignoreCase = true)) {
                                rawLabel
                            } else {
                                "$rawLabel (Anime47)"
                            }

                            subtitleTracks.add(
                                SubtitleTrack(
                                    label = formattedLabel,
                                    url = sub.file,
                                    isDefault = sub.isDefault,
                                    server = stream.serverName
                                )
                            )
                        }
                    }
                }
                // Deduplicate by URL
                subtitleTracks.distinctBy { it.url }
            } else {
                emptyList()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * End-to-end enrichment helper:
     * Takes raw torrent/media title and optional episode number,
     * cleans the title, finds the anime on Anime47, maps the episode,
     * and extracts all available softsub subtitle tracks.
     */
    suspend fun fetchSubtitlesForMedia(
        rawTitle: String,
        targetEpisodeNum: Int? = null,
        alternativeTitles: List<String> = emptyList()
    ): List<SubtitleTrack> {
        val parsed = Anime47Matcher.parseMediaInfo(rawTitle)
        val episodeNum = targetEpisodeNum ?: parsed.episodeNumber ?: 1
        var searchQuery = parsed.cleanTitle

        if (searchQuery.isBlank()) return emptyList()

        val cacheKey = "${searchQuery.lowercase()}_ep_${episodeNum}"
        subtitleCache[cacheKey]?.let { cachedTracks ->
            if (cachedTracks.isNotEmpty()) {
                Log.i(TAG, "Cache HIT for Anime47 Vietsub: '$cacheKey' (${cachedTracks.size} tracks)")
                return cachedTracks
            }
        }

        // 1. Search anime with primary title
        var searchResults = searchAnime(searchQuery)

        // 2. If no results and alternative titles available, try each alternative
        if (searchResults.isEmpty() && alternativeTitles.isNotEmpty()) {
            for (alt in alternativeTitles) {
                val cleanAlt = Anime47Matcher.parseMediaInfo(alt).cleanTitle
                if (cleanAlt.isNotBlank() && !cleanAlt.equals(searchQuery, ignoreCase = true)) {
                    val altResults = searchAnime(cleanAlt)
                    if (altResults.isNotEmpty()) {
                        searchResults = altResults
                        searchQuery = cleanAlt
                        break
                    }
                }
            }
        }

        if (searchResults.isEmpty()) {
            return emptyList()
        }

        // 3. Find best match
        val matchedAnime = Anime47Matcher.findBestAnimeMatch(searchQuery, searchResults)
            ?: return emptyList()

        // 4. Get anime episodes
        val episodes = getEpisodes(matchedAnime.id)
        if (episodes.isEmpty()) return emptyList()

        // 5. Find matching episode
        val matchedEpisode = Anime47Matcher.findMatchingEpisode(episodeNum, episodes)
            ?: return emptyList()

        // 6. Fetch subtitle tracks
        val tracks = getEpisodeSubtitles(matchedEpisode.id)
        if (tracks.isNotEmpty()) {
            subtitleCache[cacheKey] = tracks
        }
        return tracks
    }
}
