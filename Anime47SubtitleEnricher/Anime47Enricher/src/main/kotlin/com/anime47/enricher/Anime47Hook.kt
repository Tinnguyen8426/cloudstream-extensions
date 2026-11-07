package com.anime47.enricher

import android.util.Log
import com.anime47.enricher.models.SubtitleTrack
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
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

    private var observerJob: Job? = null

    /**
     * Initializes and attaches hooks to all registered CloudStream providers.
     * Continuously observes for any providers registered dynamically later.
     */
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
     * Resolves the immediate inner delegate of a wrapped provider.
     * Supports both direct EnrichedMainAPI instances and external wrappers via reflection.
     */
    fun getInnerDelegate(provider: MainAPI): MainAPI? {
        if (provider is EnrichedMainAPI) return provider.baseApi

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
        if (parent is EnrichedMainAPI) {
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
            if (curr is EnrichedMainAPI || curr.javaClass.name.contains("Anime47Hook\$EnrichedMainAPI") || curr.javaClass.name.contains("Anime47EnrichedMainAPI")) {
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
            p is EnrichedMainAPI || p.javaClass.name.contains("Anime47Hook\$EnrichedMainAPI") || p.javaClass.name.contains("Anime47EnrichedMainAPI")
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

    /**
     * Safely synchronizes provider mapping in APIHolder.apis:
     * 1. Purges any pre-existing entries with matching name.
     * 2. Appends the new canonical wrapper via APIHolder.addPluginMapping.
     * Ensures exactly ONE entry per provider exists in APIHolder.apis and UI source list.
     */
    fun syncPluginMapping(provider: MainAPI) {
        try {
            APIHolder.apis.withLock {
                APIHolder.apis = APIHolder.apis.filter { it.name != provider.name }
            }
            APIHolder.addPluginMapping(provider)
        } catch (_: Throwable) {
            try {
                APIHolder.addPluginMapping(provider)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Atomically deduplicates APIHolder.apis by provider name.
     */
    fun deduplicateApis() {
        try {
            APIHolder.apis.withLock {
                APIHolder.apis = APIHolder.apis.distinctBy { it.name }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Detaches all Anime47 hooks from APIHolder.allProviders and restores original delegates.
     */
    @Synchronized
    fun detachHooks() {
        try {
            observerJob?.cancel()
            observerJob = null

            val allProviders = APIHolder.allProviders
            allProviders.withLock {
                for (i in 0 until allProviders.size) {
                    val provider = allProviders.getOrNull(i) ?: continue
                    if (isAlreadyHooked(provider)) {
                        val unwrapped = unwrapProviderChain(provider)
                        allProviders[i] = unwrapped
                        Log.i(TAG, "Successfully detached Anime47 Subtitle Enricher from provider: ${provider.name}")
                        syncPluginMapping(unwrapped)
                    }
                }
            }
            hookedProviderNames.clear()
            deduplicateApis()
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
                // Defensive deduplication of allProviders
                val seenNames = mutableSetOf<String>()
                var idx = 0
                while (idx < allProviders.size) {
                    val p = allProviders.getOrNull(idx) ?: break
                    if (!seenNames.add(p.name)) {
                        allProviders.removeAt(idx)
                    } else {
                        idx++
                    }
                }

                for (i in 0 until allProviders.size) {
                    val provider = allProviders.getOrNull(i) ?: continue
                    if (isAlreadyHooked(provider) || provider.name.contains("Anime47")) {
                        continue
                    }

                    if (shouldHook(provider)) {
                        val enriched = EnrichedMainAPI(provider)
                        allProviders[i] = enriched
                        hookedProviderNames.add(provider.name)
                        Log.i(TAG, "Successfully attached Anime47 Subtitle Enricher to provider: ${provider.name}")

                        syncPluginMapping(enriched)
                    }
                }
            }
            deduplicateApis()
        } catch (e: Throwable) {
            logError(e)
        }
    }

    private fun shouldHook(provider: MainAPI): Boolean {
        if (isAlreadyHooked(provider)) return false
        val base = getBaseProvider(provider)
        if (base.name.contains("Anime47", ignoreCase = true)) return false
        if (hookedProviderNames.contains(base.name) || hookedProviderNames.contains(provider.name)) return false
        if (enrichAllProviders) return true
        return targetProviders.any { base.name.contains(it, ignoreCase = true) || provider.name.contains(it, ignoreCase = true) }
    }

    /**
     * EnrichedMainAPI wraps a MainAPI instance, delegating all operations to it,
     * while intercepting load to cache Anime titles, and loadLinks to inject Vietsub from Anime47.
     */
    open class EnrichedMainAPI(var baseApi: MainAPI) : MainAPI() {

        var original: MainAPI
            get() = baseApi
            set(value) { baseApi = value }

        override var name: String
            get() = if (baseApi === this) javaClass.simpleName else baseApi.name
            set(value) { if (baseApi !== this) baseApi.name = value }

        override var mainUrl: String
            get() = if (baseApi === this) "" else baseApi.mainUrl
            set(value) { if (baseApi !== this) baseApi.mainUrl = value }

        override var lang: String
            get() = if (baseApi === this) "en" else baseApi.lang
            set(value) { if (baseApi !== this) baseApi.lang = value }

        override var storedCredentials: String?
            get() = if (baseApi === this) null else baseApi.storedCredentials
            set(value) { if (baseApi !== this) baseApi.storedCredentials = value }

        override var canBeOverridden: Boolean
            get() = if (baseApi === this) true else baseApi.canBeOverridden
            set(value) { if (baseApi !== this) baseApi.canBeOverridden = value }

        override var sequentialMainPage: Boolean
            get() = if (baseApi === this) false else baseApi.sequentialMainPage
            set(value) { if (baseApi !== this) baseApi.sequentialMainPage = value }

        override var sequentialMainPageDelay: Long
            get() = if (baseApi === this) 0L else baseApi.sequentialMainPageDelay
            set(value) { if (baseApi !== this) baseApi.sequentialMainPageDelay = value }

        override var sequentialMainPageScrollDelay: Long
            get() = if (baseApi === this) 0L else baseApi.sequentialMainPageScrollDelay
            set(value) { if (baseApi !== this) baseApi.sequentialMainPageScrollDelay = value }

        override val mainPage: List<MainPageData>
            get() = if (baseApi === this) emptyList() else baseApi.mainPage

        override val hasMainPage: Boolean get() = baseApi.hasMainPage
        override val hasQuickSearch: Boolean get() = baseApi.hasQuickSearch
        override val supportedTypes: Set<TvType> get() = baseApi.supportedTypes
        override val vpnStatus: VPNStatus get() = baseApi.vpnStatus
        override val providerType: ProviderType get() = baseApi.providerType
        override val instantLinkLoading: Boolean get() = baseApi.instantLinkLoading
        override val hasChromecastSupport: Boolean get() = baseApi.hasChromecastSupport
        override val hasDownloadSupport: Boolean get() = baseApi.hasDownloadSupport
        override val usesWebView: Boolean get() = baseApi.usesWebView
        override val supportedSyncNames: Set<SyncIdName> get() = baseApi.supportedSyncNames
        override val loadLinksTimeoutMs: Long? get() = baseApi.loadLinksTimeoutMs
        override val getMainPageTimeoutMs: Long? get() = baseApi.getMainPageTimeoutMs
        override val searchTimeoutMs: Long? get() = baseApi.searchTimeoutMs
        override val quickSearchTimeoutMs: Long? get() = baseApi.quickSearchTimeoutMs
        override val loadTimeoutMs: Long? get() = baseApi.loadTimeoutMs

        private fun sanitizeMainPageRequest(request: MainPageRequest, baseUrl: String): MainPageRequest {
            val rawData = request.data
            val base = baseUrl.trimEnd('/')
            val isAbsolute = rawData.startsWith("http://", ignoreCase = true) || rawData.startsWith("https://", ignoreCase = true)
            val safeData = when {
                isAbsolute -> rawData
                rawData.isNotBlank() && base.isNotBlank() && !base.equals("NONE", ignoreCase = true) -> {
                    val path = if (rawData.startsWith("/")) rawData else "/$rawData"
                    "$base$path"
                }
                rawData.isBlank() && base.isNotBlank() && !base.equals("NONE", ignoreCase = true) -> base
                else -> rawData
            }
            return if (safeData == request.data) request else MainPageRequest(request.name, safeData, request.horizontalImages)
        }

        private fun sanitizeUrl(url: String, baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            val isAbsolute = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
            return when {
                isAbsolute -> url
                url.isNotBlank() && base.isNotBlank() && !base.equals("NONE", ignoreCase = true) -> {
                    val path = if (url.startsWith("/")) url else "/$url"
                    "$base$path"
                }
                url.isBlank() && base.isNotBlank() && !base.equals("NONE", ignoreCase = true) -> base
                else -> url
            }
        }

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            return try {
                val safeReq = sanitizeMainPageRequest(request, baseApi.mainUrl)
                baseApi.getMainPage(page, safeReq)
            } catch (t: Throwable) {
                logError(t)
                null
            }
        }

        override suspend fun search(query: String): List<SearchResponse>? {
            return try {
                baseApi.search(query)
            } catch (t: Throwable) {
                logError(t)
                null
            }
        }

        override suspend fun search(query: String, page: Int): SearchResponseList? {
            return try {
                baseApi.search(query, page)
            } catch (t: Throwable) {
                logError(t)
                null
            }
        }

        override suspend fun quickSearch(query: String): List<SearchResponse>? {
            return try {
                baseApi.quickSearch(query)
            } catch (t: Throwable) {
                logError(t)
                null
            }
        }

        override suspend fun extractorVerifierJob(extractorData: String?) {
            try {
                baseApi.extractorVerifierJob(extractorData)
            } catch (t: Throwable) {
                logError(t)
            }
        }

        override suspend fun load(url: String): LoadResponse? {
            val safeUrl = sanitizeUrl(url, baseApi.mainUrl)
            val response = try {
                baseApi.load(safeUrl) ?: return null
            } catch (e: Throwable) {
                logError(e)
                return null
            }
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
                    episodeNum = null,
                    seasonNum = 1
                )
                Log.d(TAG, "Cached anime details for: '$showTitle' (altTitles: ${altTitles.size})")
            } catch (e: Throwable) {
                logError(e)
            }
            return response
        }

        override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
            return baseApi.getVideoInterceptor(extractorLink)
        }

        override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
            return baseApi.getLoadUrl(name, id)
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean = withContext(Dispatchers.IO) {
            val subtitleScraped = AtomicBoolean(false)
            val scrapeTriggered = AtomicBoolean(false)
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

                if (scrapeTriggered.compareAndSet(false, true)) {
                    val job = launch {
                        scrapeAndEmitSubtitles(cached.title, epNum, cached.altTitles)
                    }
                    activeScrapeJobs.add(job)
                }
            } else {
                // Strategy 2: Check if 'data' directly contains parseable anime title & episode
                val parsedData = Anime47Matcher.parseMediaInfo(data)
                if (parsedData.cleanTitle.isNotBlank() && parsedData.cleanTitle.length >= 3) {
                    if (scrapeTriggered.compareAndSet(false, true)) {
                        val job = launch {
                            scrapeAndEmitSubtitles(parsedData.cleanTitle, parsedData.episodeNumber)
                        }
                        activeScrapeJobs.add(job)
                    }
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

                    if (candidateTitle != null && scrapeTriggered.compareAndSet(false, true)) {
                        val job = launch {
                            scrapeAndEmitSubtitles(candidateTitle, candidateEp)
                        }
                        activeScrapeJobs.add(job)
                    }
                }
            }

            // Execute original provider loadLinks
            val result = try {
                baseApi.loadLinks(data, isCasting, subtitleCallback, wrappedCallback)
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

            // Cancel any remaining active scrape jobs immediately so withContext exits <= 4.5s
            for (job in activeScrapeJobs) {
                if (job.isActive) {
                    try { job.cancel() } catch (_: Throwable) {}
                }
            }

            result
        }
    }
}
