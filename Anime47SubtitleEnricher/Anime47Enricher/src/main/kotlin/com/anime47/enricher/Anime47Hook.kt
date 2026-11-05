package com.anime47.enricher

import android.util.Log
import com.anime47.enricher.models.SubtitleTrack
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dynamic Provider Hook for CloudStream.
 * Transparently wraps target providers (Torrentio, AnimePahe, HiAnime, etc.)
 * to scrape and inject Vietnamese softsubs from Anime47 directly into the video player.
 */
object Anime47Hook {

    private const val TAG = "Anime47Hook"
    private val hookedProviderNames = ConcurrentHashMap.newKeySet<String>()

    data class ShowInfo(
        val title: String,
        val altTitles: List<String> = emptyList(),
        val episodeNum: Int? = null,
        val seasonNum: Int? = null
    )

    // Maps episode data string -> ShowInfo
    val episodeDataCache = ConcurrentHashMap<String, ShowInfo>()
    @Volatile var latestLoadedShow: ShowInfo? = null

    // Target providers to enrich
    var targetProviders: Set<String> = setOf(
        "Torrentio", "TorraStream", "StremioX", "StremioAddon", "Stremio",
        "AnimePahe", "HiAnime", "AllAnime", "YTS", "Anime"
    )
    var enrichAllProviders: Boolean = true

    /**
     * Initializes and attaches hooks to all registered CloudStream providers.
     * Continuously observes for any providers registered dynamically later.
     */
    fun attachHooks(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        try {
            scanAndHookProviders()

            // Keep observing continuously so any provider loaded at any point gets hooked
            scope.launch {
                while (isActive) {
                    delay(3000)
                    scanAndHookProviders()
                }
            }
        } catch (e: Throwable) {
            logError(e)
        }
    }

    /**
     * Scans APIHolder.allProviders and APIHolder.apis, replacing matching providers with EnrichedMainAPI.
     */
    @Synchronized
    fun scanAndHookProviders() {
        try {
            // 1. Hook APIHolder.allProviders
            val allProviders = APIHolder.allProviders
            allProviders.withLock {
                for (i in 0 until allProviders.size) {
                    val provider = allProviders.getOrNull(i) ?: continue
                    if (provider is EnrichedMainAPI || provider.name.contains("Anime47")) {
                        continue
                    }

                    if (shouldHook(provider)) {
                        val enriched = EnrichedMainAPI(provider)
                        allProviders[i] = enriched
                        hookedProviderNames.add(provider.name)
                        Log.i(TAG, "Successfully attached Anime47 Subtitle Enricher to provider: ${provider.name}")

                        try {
                            APIHolder.addPluginMapping(enriched)
                        } catch (e: Throwable) {
                            // Non-fatal if mapping already exists or method not present
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            logError(e)
        }
    }

    private fun shouldHook(provider: MainAPI): Boolean {
        if (hookedProviderNames.contains(provider.name)) return false
        if (enrichAllProviders) return true
        return targetProviders.any { provider.name.contains(it, ignoreCase = true) }
    }

    /**
     * EnrichedMainAPI wraps a MainAPI instance, delegating all operations to it,
     * while intercepting load to cache Anime titles, and loadLinks to inject Vietsub from Anime47.
     */
    class EnrichedMainAPI(val original: MainAPI) : MainAPI() {

        init {
            this.name = original.name
            this.mainUrl = original.mainUrl
            this.lang = original.lang
        }

        override val hasMainPage: Boolean get() = original.hasMainPage
        override val hasQuickSearch: Boolean get() = original.hasQuickSearch
        override val supportedTypes: Set<TvType> get() = original.supportedTypes

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            return original.getMainPage(page, request)
        }

        override suspend fun search(query: String): List<SearchResponse>? {
            return original.search(query)
        }

        override suspend fun quickSearch(query: String): List<SearchResponse>? {
            return original.quickSearch(query)
        }

        override suspend fun load(url: String): LoadResponse? {
            val response = original.load(url) ?: return null
            try {
                val showTitle = response.name
                val altTitles = mutableListOf<String>()

                when (response) {
                    is AnimeLoadResponse -> {
                        response.engName?.let { altTitles.add(it) }
                        response.japName?.let { altTitles.add(it) }
                        response.synonyms?.let { altTitles.addAll(it) }

                        response.episodes?.values?.forEach { epList ->
                            epList.forEach { ep ->
                                val info = ShowInfo(
                                    title = showTitle,
                                    altTitles = altTitles,
                                    episodeNum = ep.episode,
                                    seasonNum = ep.season
                                )
                                episodeDataCache[ep.data] = info
                            }
                        }
                    }
                    is TvSeriesLoadResponse -> {
                        response.episodes?.forEach { ep ->
                            val info = ShowInfo(
                                title = showTitle,
                                altTitles = altTitles,
                                episodeNum = ep.episode,
                                seasonNum = ep.season
                            )
                            episodeDataCache[ep.data] = info
                        }
                    }
                    is MovieLoadResponse -> {
                        val info = ShowInfo(
                            title = showTitle,
                            altTitles = altTitles,
                            episodeNum = 1,
                            seasonNum = 1
                        )
                        response.dataUrl?.let { episodeDataCache[it] = info }
                    }
                }

                latestLoadedShow = ShowInfo(
                    title = showTitle,
                    altTitles = altTitles,
                    episodeNum = 1,
                    seasonNum = 1
                )
                Log.d(TAG, "Cached anime details for: '$showTitle' (altTitles: ${altTitles.size})")
            } catch (e: Throwable) {
                logError(e)
            }
            return response
        }

        override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
            return original.getVideoInterceptor(extractorLink)
        }

        override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
            return original.getLoadUrl(name, id)
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean = withContext(Dispatchers.IO) {
            val subtitleScraped = AtomicBoolean(false)
            val activeScrapeJobs = ConcurrentHashMap.newKeySet<Job>()

            // Helper to scrape subtitles safely and emit them to the player
            suspend fun scrapeAndEmitSubtitles(searchTitle: String, episodeNumber: Int?, altTitles: List<String> = emptyList()) {
                if (subtitleScraped.get()) return
                if (searchTitle.isBlank() || searchTitle.length < 2) return

                try {
                    Log.d(TAG, "Scraping Anime47 Vietsub for: '$searchTitle', episode: $episodeNumber")
                    val tracks = Anime47Scraper.fetchSubtitlesForMedia(
                        rawTitle = searchTitle,
                        targetEpisodeNum = episodeNumber,
                        alternativeTitles = altTitles
                    )
                    if (tracks.isNotEmpty()) {
                        subtitleScraped.set(true)
                        for (track in tracks) {
                            Log.i(TAG, "Injecting Anime47 Vietsub: ${track.label} -> ${track.url}")
                            subtitleCallback(
                                SubtitleFile(
                                    lang = track.label,
                                    url = track.url
                                )
                            )
                        }
                    }
                } catch (e: Throwable) {
                    logError(e)
                }
            }

            // Strategy 1: Check cached ShowInfo from load()
            val cached = episodeDataCache[data] ?: latestLoadedShow
            if (cached != null && cached.title.isNotBlank()) {
                var epNum = cached.episodeNum
                if (epNum == null) {
                    val epMatch = Regex("""(?::|/|ep|episode|\b)(\d{1,4})\b""", RegexOption.IGNORE_CASE).findAll(data).lastOrNull()
                    epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                }

                val job = launch {
                    scrapeAndEmitSubtitles(cached.title, epNum, cached.altTitles)
                }
                activeScrapeJobs.add(job)
            } else {
                // Strategy 2: Check if 'data' directly contains parseable anime title & episode
                val parsedData = Anime47Matcher.parseMediaInfo(data)
                if (parsedData.cleanTitle.isNotBlank() && parsedData.cleanTitle.length >= 3) {
                    val job = launch {
                        scrapeAndEmitSubtitles(parsedData.cleanTitle, parsedData.episodeNumber)
                    }
                    activeScrapeJobs.add(job)
                }
            }

            // Strategy 3: Wrap the stream callback to extract filenames from ExtractorLinks (Torrentio format)
            val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                // Immediately forward video stream to the player (zero latency)
                callback(link)

                if (!subtitleScraped.get()) {
                    var candidateTitle: String? = null
                    var candidateEp: Int? = null

                    // 3a. Check magnet dn parameter (display name)
                    val url = link.url
                    if (url.contains("dn=")) {
                        try {
                            val dnMatch = Regex("""[?&]dn=([^&]+)""").find(url)
                            if (dnMatch != null) {
                                val decoded = URLDecoder.decode(dnMatch.groupValues[1], "UTF-8")
                                val p = Anime47Matcher.parseMediaInfo(decoded)
                                if (p.cleanTitle.isNotBlank() && p.cleanTitle.length >= 3) {
                                    candidateTitle = p.cleanTitle
                                    candidateEp = p.episodeNumber
                                }
                            }
                        } catch (e: Throwable) {}
                    }

                    // 3b. Check filename in link url (.mkv / .mp4)
                    if (candidateTitle == null && (url.endsWith(".mkv", true) || url.endsWith(".mp4", true))) {
                        try {
                            val filename = url.substringAfterLast("/").substringBefore("?")
                            val decoded = URLDecoder.decode(filename, "UTF-8")
                            val p = Anime47Matcher.parseMediaInfo(decoded)
                            if (p.cleanTitle.isNotBlank() && p.cleanTitle.length >= 3) {
                                candidateTitle = p.cleanTitle
                                candidateEp = p.episodeNumber
                            }
                        } catch (e: Throwable) {}
                    }

                    // 3c. Check link name
                    if (candidateTitle == null && link.name.isNotBlank()) {
                        val p = Anime47Matcher.parseMediaInfo(link.name)
                        if (p.cleanTitle.isNotBlank() && p.cleanTitle.length >= 3 && !p.cleanTitle.equals("Torrentio", true)) {
                            candidateTitle = p.cleanTitle
                            candidateEp = p.episodeNumber
                        }
                    }

                    if (candidateTitle != null) {
                        val job = launch {
                            scrapeAndEmitSubtitles(candidateTitle, candidateEp)
                        }
                        activeScrapeJobs.add(job)
                    }
                }
            }

            // Execute original provider loadLinks
            val result = try {
                original.loadLinks(data, isCasting, subtitleCallback, wrappedCallback)
            } catch (e: Throwable) {
                logError(e)
                false
            }

            // Wait for subtitle scrape job to finish (up to 4.5s) so Vietsub arrives before player closes generator
            if (!subtitleScraped.get() && activeScrapeJobs.isNotEmpty()) {
                withTimeoutOrNull(4500L) {
                    for (job in activeScrapeJobs) {
                        if (job.isActive) job.join()
                    }
                }
            }

            result
        }
    }
}
