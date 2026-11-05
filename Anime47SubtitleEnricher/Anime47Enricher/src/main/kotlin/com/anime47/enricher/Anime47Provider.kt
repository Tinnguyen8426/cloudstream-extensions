package com.anime47.enricher

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper

class Anime47Provider : MainAPI() {

    override var name = "Anime47 (Vietsub Softsub)"
    override var mainUrl = "https://anime47.best"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val searchResults = Anime47Scraper.searchAnime("a", page = page)
        val animeList = searchResults.map { item ->
            newAnimeSearchResponse(item.title, "${item.id}") {
                this.posterUrl = item.image
            }
        }
        return newHomePageResponse(listOf(com.lagradost.cloudstream3.HomePageList("Anime47 Mới Cập Nhật", animeList)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = Anime47Scraper.searchAnime(query)
        return results.map { item ->
            newAnimeSearchResponse(item.title, "${item.id}") {
                this.posterUrl = item.image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.toIntOrNull() ?: 0
        val episodes = Anime47Scraper.getEpisodes(animeId)

        val episodeList = episodes.mapNotNull { ep ->
            val epNum = ep.parsedNumber ?: return@mapNotNull null
            newEpisode("${ep.id}") {
                this.name = ep.title ?: "Tập $epNum"
                this.episode = epNum
            }
        }

        return newAnimeLoadResponse("Anime47 #$animeId", url, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, episodeList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.toIntOrNull() ?: return false
        val token = Anime47Auth.getValidToken() ?: return false

        try {
            val url = "${Anime47Auth.API_BASE_URL}/anime/watch/episode/$episodeId"
            val response = app.get(url, headers = Anime47Auth.defaultHeaders(token))

            if (response.isSuccessful) {
                val watchData = parseJson<com.anime47.enricher.models.EpisodeWatchResponse>(response.text)

                // 1. Emit subtitles
                watchData.streams?.forEach { stream ->
                    stream.subtitles?.forEach { sub ->
                        if (sub.file.isNotBlank()) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = sub.label ?: "Tiếng Việt (Anime47)",
                                    url = sub.file
                                )
                            )
                        }
                    }

                    // 2. Emit HLS video stream if present
                    val streamUrl = stream.url
                    if (!streamUrl.isNullOrBlank() && streamUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            source = this.name,
                            name = this.name,
                            streamUrl = streamUrl,
                            referer = "${Anime47Auth.SITE_ORIGIN}/"
                        ).forEach(callback)
                    }
                }
                return true
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return false
    }
}
