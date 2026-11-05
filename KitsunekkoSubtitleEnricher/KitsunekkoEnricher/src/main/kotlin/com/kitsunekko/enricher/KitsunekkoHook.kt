package com.kitsunekko.enricher

import android.content.Context
import android.util.Log
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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dynamic Provider Hook for CloudStream.
 * Intercepts external providers (Torrentio, AnimePahe, etc.) to scrape and inject
 * original Japanese subtitles (.ass, .srt, .ssa, .zip) from kitsunekko.net.
 * Adheres to 0ms video passthrough, 4.5s non-blocking coroutine timeout,
 * and multi-enricher hook chaining coexistence.
 */
object KitsunekkoHook {

    private const val TAG = "KitsunekkoHook"
    private val hookedProviderNames = ConcurrentHashMap.newKeySet<String>()
    private var observerJob: Job? = null
    var appContext: Context? = null

    data class ShowInfo(
        val title: String,
        val altTitles: List<String> = emptyList(),
        val episodeNum: Int? = null,
        val seasonNum: Int? = null,
        val year: Int? = null
    )

    val episodeDataCache = ConcurrentHashMap<String, ShowInfo>()
    @Volatile var latestLoadedShow: ShowInfo? = null

    var targetProviders: Set<String> = setOf(
        "Torrentio", "TorraStream", "StremioX", "StremioAddon", "Stremio",
        "AnimePahe", "HiAnime", "AllAnime", "YTS", "Anime"
    )
    var enrichAllProviders: Boolean = true

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun attachHooks(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        try {
            scanAndHookProviders()
            observerJob?.cancel()
            observerJob = scope.launch {
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
     * Resolves the next inner MainAPI in the decorator chain.
     * Uses direct property access for this enricher's wrapper,
     * and reflective inspection for peer enrichers (Stremio, Anime47, etc.).
     */
    fun getInnerDelegate(provider: MainAPI): MainAPI? {
        if (provider is KitsunekkoEnrichedMainAPI) return provider.baseApi

        val clazz = provider.javaClass
        // 1. Check common getter methods
        for (getter in listOf("getBaseApi", "getOriginal", "getDelegate", "getBase", "getInner")) {
            try {
                val method = clazz.getMethod(getter)
                val result = method.invoke(provider)
                if (result is MainAPI) return result
            } catch (_: NoSuchMethodException) {
            } catch (_: Throwable) {}
        }
        // 2. Check declared fields traversing class hierarchy
        var currentClass: Class<*>? = clazz
        while (currentClass != null && currentClass != MainAPI::class.java && currentClass != Any::class.java) {
            for (fieldName in listOf("baseApi", "original", "delegate", "base", "inner")) {
                try {
                    val field = currentClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val result = field.get(provider)
                    if (result is MainAPI) return result
                } catch (_: NoSuchFieldException) {
                } catch (_: Throwable) {}
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    /**
     * Traverses to the leaf provider (innermost base provider) in the delegate chain.
     * Cycle-safe via IdentityHashMap.
     */
    fun getBaseProvider(provider: MainAPI): MainAPI {
        var curr = provider
        val visited = Collections.newSetFromMap(IdentityHashMap<MainAPI, Boolean>())
        while (visited.add(curr)) {
            val next = getInnerDelegate(curr) ?: return curr
            curr = next
        }
        return curr
    }

    /**
     * Updates the inner delegate pointer of a wrapper node.
     */
    fun setInnerDelegate(parent: MainAPI, newDelegate: MainAPI): Boolean {
        if (parent is KitsunekkoEnrichedMainAPI) {
            parent.baseApi = newDelegate
            return true
        }

        val clazz = parent.javaClass
        // 1. Try public setter method
        for (setter in listOf("setBaseApi", "setOriginal", "setDelegate", "setBase", "setInner")) {
            try {
                val method = clazz.getMethod(setter, MainAPI::class.java)
                method.invoke(parent, newDelegate)
                return true
            } catch (_: NoSuchMethodException) {
            } catch (_: Throwable) {}
        }
        // 2. Try declared fields
        var currentClass: Class<*>? = clazz
        while (currentClass != null && currentClass != MainAPI::class.java && currentClass != Any::class.java) {
            for (fieldName in listOf("baseApi", "original", "delegate", "base", "inner")) {
                try {
                    val field = currentClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(parent, newDelegate)
                    return true
                } catch (_: NoSuchFieldException) {
                } catch (_: Throwable) {}
            }
            currentClass = currentClass.superclass
        }
        return false
    }

    /**
     * Traverses the delegate chain to check if this enricher is already wrapping the provider.
     * Supports cross-classloader inspection via class name matching.
     */
    fun isAlreadyHooked(provider: MainAPI): Boolean {
        var curr: MainAPI? = provider
        val visited = Collections.newSetFromMap(IdentityHashMap<MainAPI, Boolean>())
        while (curr != null && visited.add(curr)) {
            if (curr is KitsunekkoEnrichedMainAPI || curr.javaClass.name.contains("KitsunekkoEnrichedMainAPI")) {
                return true
            }
            curr = getInnerDelegate(curr)
        }
        return false
    }

    /**
     * Removes all instances of this enricher's wrapper from a decorator chain.
     */
    fun unwrapProviderChain(root: MainAPI): MainAPI {
        val isTarget = { p: MainAPI ->
            p is KitsunekkoEnrichedMainAPI || p.javaClass.name.contains("KitsunekkoEnrichedMainAPI")
        }

        var newRoot: MainAPI = root
        val visited = Collections.newSetFromMap(IdentityHashMap<MainAPI, Boolean>())

        // 1. Advance root if root itself is a target wrapper
        while (isTarget(newRoot) && visited.add(newRoot)) {
            val next = getInnerDelegate(newRoot) ?: break
            newRoot = next
        }

        // 2. Splice out any target wrappers deeper in the chain
        var prev: MainAPI? = if (!isTarget(newRoot)) newRoot else null
        var curr: MainAPI? = if (prev != null) getInnerDelegate(prev) else null

        while (curr != null && visited.add(curr)) {
            if (isTarget(curr)) {
                val next = getInnerDelegate(curr)
                if (prev != null && next != null) {
                    setInnerDelegate(prev, next)
                    curr = next
                } else {
                    prev = curr
                    curr = next
                }
            } else {
                prev = curr
                curr = getInnerDelegate(curr)
            }
        }

        return newRoot
    }

    fun detachHooks() {
        observerJob?.cancel()
        observerJob = null

        try {
            val allProviders = APIHolder.allProviders
            allProviders.withLock {
                for (i in 0 until allProviders.size) {
                    val provider = allProviders.getOrNull(i) ?: continue
                    val unwrapped = unwrapProviderChain(provider)
                    if (unwrapped !== provider) {
                        allProviders[i] = unwrapped
                        Log.i(TAG, "Unwrapped Kitsunekko Japanese Subtitle Enricher from provider: ${unwrapped.name}")
                        try {
                            APIHolder.removePluginMapping(provider)
                        } catch (_: Throwable) {}
                        try {
                            APIHolder.addPluginMapping(unwrapped)
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (e: Throwable) {
            logError(e)
        }

        hookedProviderNames.clear()
        episodeDataCache.clear()
        latestLoadedShow = null
    }

    @Synchronized
    fun scanAndHookProviders() {
        try {
            val allProviders = APIHolder.allProviders
            allProviders.withLock {
                for (i in 0 until allProviders.size) {
                    val provider = allProviders.getOrNull(i) ?: continue
                    if (isAlreadyHooked(provider) || provider.name.contains("Kitsunekko", ignoreCase = true)) {
                        continue
                    }

                    if (shouldHook(provider)) {
                        val enriched = KitsunekkoEnrichedMainAPI(provider)
                        allProviders[i] = enriched
                        val base = getBaseProvider(provider)
                        hookedProviderNames.add(base.name)
                        Log.i(TAG, "Successfully attached Kitsunekko Japanese Subtitle Enricher to provider: ${base.name}")

                        try {
                            APIHolder.addPluginMapping(enriched)
                        } catch (_: Throwable) {
                            // Non-fatal if mapping already exists
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            logError(e)
        }
    }

    private fun shouldHook(provider: MainAPI): Boolean {
        // 1. Deep recursive inspection: never hook if this wrapper already exists anywhere in the chain
        if (isAlreadyHooked(provider)) return false

        // 2. Identify innermost base provider
        val base = getBaseProvider(provider)

        // 3. Prevent wrapping native providers of this enricher
        if (base.name.contains("Kitsunekko", ignoreCase = true) || base.javaClass.name.contains("Kitsunekko", ignoreCase = true)) {
            return false
        }

        // 4. Check already hooked names cache
        if (hookedProviderNames.contains(base.name)) return false

        // 5. Check target filter criteria
        if (enrichAllProviders) return true
        return targetProviders.any { base.name.contains(it, ignoreCase = true) }
    }

    /**
     * Decorator wrapping MainAPI to inject Kitsunekko Japanese subtitles.
     */
    class KitsunekkoEnrichedMainAPI(var baseApi: MainAPI) : MainAPI() {

        init {
            this.name = baseApi.name
            this.mainUrl = baseApi.mainUrl
            this.lang = baseApi.lang
        }

        override val hasMainPage: Boolean get() = baseApi.hasMainPage
        override val hasQuickSearch: Boolean get() = baseApi.hasQuickSearch
        override val supportedTypes: Set<TvType> get() = baseApi.supportedTypes

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            return baseApi.getMainPage(page, request)
        }

        override suspend fun search(query: String): List<SearchResponse>? {
            return baseApi.search(query)
        }

        override suspend fun quickSearch(query: String): List<SearchResponse>? {
            return baseApi.quickSearch(query)
        }

        override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
            return baseApi.getVideoInterceptor(extractorLink)
        }

        override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
            return baseApi.getLoadUrl(name, id)
        }

        override suspend fun load(url: String): LoadResponse? {
            val response = baseApi.load(url) ?: return null
            try {
                val showTitle = response.name
                val altTitles = mutableListOf<String>()
                var showYear: Int? = null

                when (response) {
                    is AnimeLoadResponse -> {
                        response.engName?.let { altTitles.add(it) }
                        response.japName?.let { altTitles.add(it) }
                        response.synonyms?.let { altTitles.addAll(it) }
                        showYear = response.year

                        response.episodes?.values?.forEach { epList ->
                            epList.forEach { ep ->
                                val info = ShowInfo(
                                    title = showTitle,
                                    altTitles = altTitles,
                                    episodeNum = ep.episode,
                                    seasonNum = ep.season,
                                    year = showYear
                                )
                                episodeDataCache[ep.data] = info
                            }
                        }
                    }
                    is TvSeriesLoadResponse -> {
                        showYear = response.year
                        response.episodes?.forEach { ep ->
                            val info = ShowInfo(
                                title = showTitle,
                                altTitles = altTitles,
                                episodeNum = ep.episode,
                                seasonNum = ep.season,
                                year = showYear
                            )
                            episodeDataCache[ep.data] = info
                        }
                    }
                    is MovieLoadResponse -> {
                        showYear = response.year
                        val info = ShowInfo(
                            title = showTitle,
                            altTitles = altTitles,
                            episodeNum = 1,
                            seasonNum = 1,
                            year = showYear
                        )
                        response.dataUrl?.let { episodeDataCache[it] = info }
                    }
                }

                latestLoadedShow = ShowInfo(
                    title = showTitle,
                    altTitles = altTitles,
                    episodeNum = 1,
                    seasonNum = 1,
                    year = showYear
                )
                Log.d(TAG, "Cached show info for Kitsunekko: '$showTitle' (altTitles: ${altTitles.size})")
            } catch (e: Throwable) {
                logError(e)
            }
            return response
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean = withContext(Dispatchers.IO) {
            val subtitleScraped = AtomicBoolean(false)
            val activeScrapeJobs = ConcurrentHashMap.newKeySet<Job>()

            suspend fun scrapeAndEmitSubtitles(
                searchTitle: String,
                episodeNumber: Int?,
                seasonNumber: Int?,
                altTitles: List<String> = emptyList()
            ) {
                if (subtitleScraped.get()) return
                if (searchTitle.isBlank() || searchTitle.length < 2) return

                val targetEp = episodeNumber ?: 1
                val ctx = appContext ?: return

                try {
                    Log.d(TAG, "Scraping Kitsunekko subtitles for: '$searchTitle', ep: $targetEp")
                    val candidateTitles = buildList {
                        add(searchTitle)
                        addAll(altTitles)
                    }.distinct()

                    val subtitles = KitsunekkoScraper.fetchSubtitles(
                        titles = candidateTitles,
                        targetEpisode = targetEp,
                        context = ctx
                    )

                    if (subtitles.isNotEmpty()) {
                        subtitleScraped.set(true)
                        for (sub in subtitles) {
                            Log.i(TAG, "Injecting Kitsunekko Sub: [${sub.lang}] -> ${sub.url}")
                            subtitleCallback(sub)
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
                val seasonNum = cached.seasonNum ?: 1

                val job = launch {
                    scrapeAndEmitSubtitles(cached.title, epNum, seasonNum, cached.altTitles)
                }
                activeScrapeJobs.add(job)
            } else {
                // Strategy 2: Check if 'data' directly contains parseable anime title & episode
                val parsed = KitsunekkoMatcher.parseMediaInfo(data)
                if (parsed.cleanTitle.isNotBlank() && parsed.cleanTitle.length >= 3) {
                    val job = launch {
                        scrapeAndEmitSubtitles(parsed.cleanTitle, parsed.episodeNumber, parsed.seasonNumber)
                    }
                    activeScrapeJobs.add(job)
                }
            }

            // Strategy 3: Wrap stream callback for 0ms video passthrough + late discovery
            val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                // Immediate video passthrough (0ms delay)
                callback(link)

                if (!subtitleScraped.get()) {
                    var candidateTitle: String? = null
                    var candidateEp: Int? = null
                    var candidateSeason: Int? = null

                    val url = link.url
                    // 3a. Magnet display name (dn=)
                    if (url.contains("dn=")) {
                        try {
                            val dnMatch = Regex("""[?&]dn=([^&]+)""").find(url)
                            if (dnMatch != null) {
                                val decoded = URLDecoder.decode(dnMatch.groupValues[1], "UTF-8")
                                val p = KitsunekkoMatcher.parseMediaInfo(decoded)
                                if (p.cleanTitle.isNotBlank() && p.cleanTitle.length >= 3) {
                                    candidateTitle = p.cleanTitle
                                    candidateEp = p.episodeNumber
                                    candidateSeason = p.seasonNumber
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    // 3b. Direct media filename (.mkv / .mp4)
                    if (candidateTitle == null && (url.endsWith(".mkv", true) || url.endsWith(".mp4", true))) {
                        try {
                            val filename = url.substringAfterLast("/").substringBefore("?")
                            val decoded = URLDecoder.decode(filename, "UTF-8")
                            val p = KitsunekkoMatcher.parseMediaInfo(decoded)
                            if (p.cleanTitle.isNotBlank() && p.cleanTitle.length >= 3) {
                                candidateTitle = p.cleanTitle
                                candidateEp = p.episodeNumber
                                candidateSeason = p.seasonNumber
                            }
                        } catch (_: Throwable) {}
                    }

                    // 3c. Link name
                    if (candidateTitle == null && link.name.isNotBlank()) {
                        val p = KitsunekkoMatcher.parseMediaInfo(link.name)
                        if (p.cleanTitle.isNotBlank() && p.cleanTitle.length >= 3 && !p.cleanTitle.equals("Torrentio", true)) {
                            candidateTitle = p.cleanTitle
                            candidateEp = p.episodeNumber
                            candidateSeason = p.seasonNumber
                        }
                    }

                    if (candidateTitle != null) {
                        val job = launch {
                            scrapeAndEmitSubtitles(candidateTitle, candidateEp, candidateSeason)
                        }
                        activeScrapeJobs.add(job)
                    }
                }
            }

            // Execute base provider loadLinks
            val result = try {
                baseApi.loadLinks(data, isCasting, subtitleCallback, wrappedCallback)
            } catch (e: Throwable) {
                logError(e)
                false
            }

            // 4.5s bounded wait for asynchronous scraping jobs
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
