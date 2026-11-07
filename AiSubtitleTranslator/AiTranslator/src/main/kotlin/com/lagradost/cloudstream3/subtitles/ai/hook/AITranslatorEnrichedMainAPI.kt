package com.lagradost.cloudstream3.subtitles.ai.hook

import android.util.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor

/**
 * Outermost Decorator for CloudStream MainAPI.
 * Enforces the Outermost Position Invariant, intercepts subtitle streams with 0ms passthrough,
 * and provides a strict Crash Barrier isolating inner provider exceptions.
 */
open class AITranslatorEnrichedMainAPI(var baseApi: MainAPI) : MainAPI() {

    companion object {
        private const val TAG = "AITranslatorAPI"
    }

    // Interoperability alias with peer enrichers (Anime47Hook, etc.)
    var original: MainAPI
        get() = baseApi
        set(value) { baseApi = value }

    // Dynamic property delegation to baseApi
    override var name: String
        get() = if (baseApi === this) "AITranslatorEnrichedMainAPI" else baseApi.name
        set(value) {
            if (baseApi !== this) baseApi.name = value
        }

    override var mainUrl: String
        get() = if (baseApi === this) "" else baseApi.mainUrl
        set(value) {
            if (baseApi !== this) baseApi.mainUrl = value
        }

    override var lang: String
        get() = if (baseApi === this) "en" else baseApi.lang
        set(value) {
            if (baseApi !== this) baseApi.lang = value
        }

    override var storedCredentials: String?
        get() = if (baseApi === this) null else baseApi.storedCredentials
        set(value) {
            if (baseApi !== this) baseApi.storedCredentials = value
        }

    override var canBeOverridden: Boolean
        get() = if (baseApi === this) true else baseApi.canBeOverridden
        set(value) {
            if (baseApi !== this) baseApi.canBeOverridden = value
        }

    override var sequentialMainPage: Boolean
        get() = if (baseApi === this) false else baseApi.sequentialMainPage
        set(value) {
            if (baseApi !== this) baseApi.sequentialMainPage = value
        }

    override var sequentialMainPageDelay: Long
        get() = if (baseApi === this) 0L else baseApi.sequentialMainPageDelay
        set(value) {
            if (baseApi !== this) baseApi.sequentialMainPageDelay = value
        }

    override var sequentialMainPageScrollDelay: Long
        get() = if (baseApi === this) 0L else baseApi.sequentialMainPageScrollDelay
        set(value) {
            if (baseApi !== this) baseApi.sequentialMainPageScrollDelay = value
        }

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
    override val mainPage: List<com.lagradost.cloudstream3.MainPageData> get() = baseApi.mainPage
    override val loadLinksTimeoutMs: Long? get() = baseApi.loadLinksTimeoutMs
    override val getMainPageTimeoutMs: Long? get() = baseApi.getMainPageTimeoutMs
    override val searchTimeoutMs: Long? get() = baseApi.searchTimeoutMs
    override val quickSearchTimeoutMs: Long? get() = baseApi.quickSearchTimeoutMs
    override val loadTimeoutMs: Long? get() = baseApi.loadTimeoutMs

    private fun isNonHttpPayload(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("{") || trimmed.startsWith("[") ||
            trimmed.startsWith("urn:", ignoreCase = true) ||
            trimmed.startsWith("magnet:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true)
        ) {
            return true
        }
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            val scheme = trimmed.substring(0, colonIdx)
            if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
                if (scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } && scheme[0].isLetter()) {
                    return true
                }
            }
        }
        if (trimmed.all { it.isDigit() } || (trimmed.startsWith("tt") && trimmed.length > 2 && trimmed.substring(2).all { it.isDigit() })) {
            return true
        }
        return false
    }

    private fun sanitizeMainPageRequest(request: MainPageRequest, baseUrl: String): MainPageRequest {
        val rawData = request.data
        if (isNonHttpPayload(rawData)) return request
        val trimmed = rawData.trim()
        val isAbsolute = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        if (isAbsolute) return request
        val base = baseUrl.trimEnd('/')
        val safeData = when {
            trimmed.isNotBlank() && base.isNotBlank() && !base.equals("NONE", ignoreCase = true) -> {
                val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
                "$base$path"
            }
            trimmed.isBlank() && base.isNotBlank() && !base.equals("NONE", ignoreCase = true) -> base
            else -> rawData
        }
        return if (safeData == request.data) request else MainPageRequest(request.name, safeData, request.horizontalImages)
    }

    private fun sanitizeUrl(url: String, baseUrl: String): String {
        if (isNonHttpPayload(url)) return url
        val trimmed = url.trim()
        val isAbsolute = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        if (isAbsolute) return url
        val base = baseUrl.trimEnd('/')
        return when {
            trimmed.isNotBlank() && base.isNotBlank() && !base.equals("NONE", ignoreCase = true) -> {
                val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
                "$base$path"
            }
            trimmed.isBlank() && base.isNotBlank() && !base.equals("NONE", ignoreCase = true) -> base
            else -> url
        }
    }

    // Method delegation with Crash Barrier isolation
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val safeReq = sanitizeMainPageRequest(request, baseApi.mainUrl)
            baseApi.getMainPage(page, safeReq)
        } catch (t: Throwable) {
            logE(TAG, "Caught leaf provider exception during getMainPage for '${baseApi.name}': ${t.message}", t)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            baseApi.search(query)
        } catch (t: Throwable) {
            logE(TAG, "Caught leaf provider exception during search for '${baseApi.name}': ${t.message}", t)
            null
        }
    }

    override suspend fun search(query: String, page: Int): com.lagradost.cloudstream3.SearchResponseList? {
        return try {
            baseApi.search(query, page)
        } catch (t: Throwable) {
            logE(TAG, "Caught leaf provider exception during paginated search for '${baseApi.name}': ${t.message}", t)
            null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return try {
            baseApi.quickSearch(query)
        } catch (t: Throwable) {
            logE(TAG, "Caught leaf provider exception during quickSearch for '${baseApi.name}': ${t.message}", t)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val safeUrl = sanitizeUrl(url, baseApi.mainUrl)
            baseApi.load(safeUrl)
        } catch (t: Throwable) {
            logE(TAG, "Caught leaf provider exception during load for '${baseApi.name}': ${t.message}", t)
            null
        }
    }

    override suspend fun extractorVerifierJob(extractorData: String?) {
        try {
            baseApi.extractorVerifierJob(extractorData)
        } catch (t: Throwable) {
            logE(TAG, "Error in extractorVerifierJob for '${baseApi.name}': ${t.message}", t)
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return try {
            baseApi.getVideoInterceptor(extractorLink)
        } catch (t: Throwable) {
            logE(TAG, "Error in getVideoInterceptor for '${baseApi.name}': ${t.message}", t)
            null
        }
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        return try {
            baseApi.getLoadUrl(name, id)
        } catch (t: Throwable) {
            logE(TAG, "Error in getLoadUrl for '${baseApi.name}': ${t.message}", t)
            null
        }
    }

    @Volatile var activeSession: AITranslationSession? = null

    /**
     * Intercepts loadLinks with:
     * 1. Crash Barrier (try-catch) isolating inner leaf runtime exceptions.
     * 2. Dual-path interceptingSubtitleCallback:
     *    - Path 1: 0ms synchronous immediate emission to player callback.
     *    - Path 2: Asynchronous candidate discovery and session queuing.
     * 3. 0ms immediate video link passthrough.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logI(TAG, "loadLinks invoked for provider '${baseApi.name}', enabled=${TranslatorConfig.isEnabled}")

        if (!TranslatorConfig.isEnabled) {
            return try {
                baseApi.loadLinks(data, isCasting, subtitleCallback, callback)
            } catch (t: Throwable) {
                logE(TAG, "Unwrapped baseApi.loadLinks failed: ${t.message}", t)
                false
            }
        }

        // Cancel any active translation session from previous playback
        activeSession?.cancelSession()

        val ctx = TranslatorConfig.appContext ?: AITranslatorHook.appContext
        val session = AITranslationSession(
            mediaPayload = data,
            targetLang = TranslatorConfig.targetLanguage,
            playerSubtitleCallback = subtitleCallback,
            context = ctx
        )
        activeSession = session

        var candidateCount = 0
        val interceptingSubtitleCallback: (SubtitleFile) -> Unit = { subFile ->
            candidateCount++
            logI(TAG, "Intercepted subtitle from '${baseApi.name}': lang='${subFile.lang}', url='${subFile.url}'")

            // PATH 1: SYNCHRONOUS 0ms PASSTHROUGH TO PLAYER
            try {
                subtitleCallback(subFile)
            } catch (t: Throwable) {
                logW(TAG, "Player subtitle callback threw: ${t.message}")
            }

            // PATH 2: ASYNCHRONOUS CANDIDATE DISCOVERY & QUEUEING
            try {
                session.queueCandidate(subFile)
            } catch (t: Throwable) {
                logW(TAG, "Session queueCandidate threw: ${t.message}")
            }
        }

        // CRASH BARRIER: Wrap inner provider invocation in try-catch
        val result = try {
            baseApi.loadLinks(data, isCasting, interceptingSubtitleCallback, callback)
        } catch (t: Throwable) {
            logE(TAG, "Inner provider crashed during loadLinks: ${t.message}", t)
            activeSession?.cancelSession()
            activeSession = null
            false
        }

        logI(TAG, "baseApi.loadLinks for '${baseApi.name}' finished (result=$result, candidateCount=$candidateCount)")
        if (candidateCount == 0) {
            logW(TAG, "Provider '${baseApi.name}' produced 0 subtitle candidates. For torrents, ensure external subtitle enrichers like StremioSubtitleEnricher are installed.")
        }
        return result
    }

    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] [INFO] $msg") }
    }

    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) { System.err.println("[$tag] [WARN] $msg") }
    }

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] [ERROR] $msg")
            tr?.printStackTrace(System.err)
        }
    }
}
