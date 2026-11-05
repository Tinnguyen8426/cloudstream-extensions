package com.anime47.enricher

import com.anime47.enricher.models.EpisodeItem
import com.anime47.enricher.models.EpisodeWatchResponse
import com.anime47.enricher.models.EpisodesResponse
import com.anime47.enricher.models.SearchResponse
import com.anime47.enricher.models.SearchResult
import com.anime47.enricher.models.SubtitleTrack
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

/**
 * Core scraping engine for Anime47 (backend API: https://animevui.com/api).
 * Interacts with Search, Episode List, and Watch/Subtitle endpoints.
 */
object Anime47Scraper {

    private const val API_BASE_URL = Anime47Auth.API_BASE_URL

    /**
     * Searches Anime47 by keyword.
     * Endpoint: GET /search/full/?keyword={keyword}&page=1
     */
    suspend fun searchAnime(keyword: String, page: Int = 1): List<SearchResult> {
        return try {
            val encodedKeyword = URLEncoder.encode(keyword.trim(), "UTF-8")
            val url = "$API_BASE_URL/search/full/?keyword=$encodedKeyword&page=$page"
            
            val response = app.get(
                url = url,
                headers = Anime47Auth.defaultHeaders(),
                timeout = 10
            )

            if (response.isSuccessful) {
                val searchResult = parseJson<SearchResponse>(response.text)
                searchResult.results ?: emptyList()
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
        val token = Anime47Auth.getValidToken() ?: return emptyList()

        return try {
            val url = "$API_BASE_URL/anime/$animeId/episodes"
            var response = app.get(
                url = url,
                headers = Anime47Auth.defaultHeaders(token),
                timeout = 10
            )

            // If private mode or token expired, force refresh token and retry once
            if (!response.isSuccessful || response.text.contains("PRIVATE_MODE")) {
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
                val episodesData = parseJson<EpisodesResponse>(response.text)
                val allEpisodes = mutableListOf<EpisodeItem>()

                episodesData.teams?.forEach { team ->
                    team.groups?.forEach { group ->
                        group.episodes?.let { allEpisodes.addAll(it) }
                    }
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

            if (!response.isSuccessful || response.text.contains("PRIVATE_MODE")) {
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
                val watchData = parseJson<EpisodeWatchResponse>(response.text)
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
        return getEpisodeSubtitles(matchedEpisode.id)
    }
}
