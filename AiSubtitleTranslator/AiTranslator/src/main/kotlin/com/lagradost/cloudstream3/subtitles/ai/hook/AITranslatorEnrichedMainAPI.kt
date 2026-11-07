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

    init {
        this.name = baseApi.name
        this.mainUrl = baseApi.mainUrl
        this.lang = baseApi.lang
    }

    // Live property delegation to baseApi
    override val hasMainPage: Boolean get() = baseApi.hasMainPage
    override val hasQuickSearch: Boolean get() = baseApi.hasQuickSearch
    override val supportedTypes: Set<TvType> get() = baseApi.supportedTypes
    override val vpnStatus: VPNStatus get() = baseApi.vpnStatus
    override val providerType: ProviderType get() = baseApi.providerType

    // Method delegation
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? =
        baseApi.getMainPage(page, request)

    override suspend fun search(query: String): List<SearchResponse>? =
        baseApi.search(query)

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        baseApi.quickSearch(query)

    override suspend fun load(url: String): LoadResponse? =
        baseApi.load(url)

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        baseApi.getVideoInterceptor(extractorLink)

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? =
        baseApi.getLoadUrl(name, id)

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
    ): Boolean = withContext(Dispatchers.IO) {
        if (!TranslatorConfig.isEnabled) {
            return@withContext try {
                baseApi.loadLinks(data, isCasting, subtitleCallback, callback)
            } catch (t: Throwable) {
                logE(TAG, "Unwrapped baseApi.loadLinks failed: ${t.message}", t)
                false
            }
        }

        // Cancel any active translation session from previous playback
        activeSession?.cancelSession()

        val session = AITranslationSession(
            mediaPayload = data,
            targetLang = TranslatorConfig.targetLanguage,
            playerSubtitleCallback = subtitleCallback
        )
        activeSession = session

        val interceptingSubtitleCallback: (SubtitleFile) -> Unit = { subFile ->
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
        try {
            baseApi.loadLinks(data, isCasting, interceptingSubtitleCallback, callback)
        } catch (t: Throwable) {
            logE(TAG, "Inner provider crashed during loadLinks: ${t.message}", t)
            activeSession?.cancelSession()
            activeSession = null
            false
        }
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
