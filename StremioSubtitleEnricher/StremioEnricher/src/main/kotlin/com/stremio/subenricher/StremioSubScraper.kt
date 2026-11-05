package com.stremio.subenricher

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import com.lagradost.nicehttp.NiceResponse

// =========================================================================
// Network Response Lifecycle Extensions
// =========================================================================

internal inline fun <R> NiceResponse.use(block: (NiceResponse) -> R): R {
    val raw = this.okhttpResponse
    return try {
        block(this)
    } finally {
        try {
            raw.close()
        } catch (_: Throwable) {}
    }
}

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
 * Core asynchronous subtitle scraping engine for Stremio ecosystems.
 * Queries Kitsu, Cinemeta, OpenSubtitles v3, and custom Stremio subtitle addons with
 * thread-safe dual LRU memory caching (32 subtitles, 24 IDs) and 34-language normalization.
 */
object StremioSubScraper {

    private const val TAG = "StremioSubScraper"

    private const val KITSU_BASE = "https://anime-kitsu.strem.fun"
    private const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
    private const val OPENSUBTITLES_BASE = "https://opensubtitles-v3.strem.io"

    // Configuration properties managed by StremioSubPlugin & StremioConfig
    @Volatile var activeLanguageFilter: String = LanguageFilterMode.VI_EN
    @Volatile var customLanguageCodes: Set<String> = emptySet()
    @Volatile var customAddonUrls: List<String> = emptyList()
    @Volatile var isKitsuEnabled: Boolean = true
    @Volatile var isOpenSubtitlesEnabled: Boolean = true

    // =========================================================================
    // 1. Thread-Safe Dual LRU Memory Caches (32 Subtitles, 24 IDs)
    // =========================================================================

    private class LruCache<K, V>(private val maxEntries: Int) :
        LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    val resultCache = Collections.synchronizedMap(LruCache<String, List<StremioSubtitleTrack>>(32))
    val kitsuIdCache = Collections.synchronizedMap(LruCache<String, String>(24))
    val imdbIdCache = Collections.synchronizedMap(LruCache<String, String>(24))

    fun clearCache() {
        resultCache.clear()
        kitsuIdCache.clear()
        imdbIdCache.clear()
        Log.i(TAG, "Cleared all in-memory LRU caches.")
    }

    fun clearCaches() = clearCache()

    fun getCacheStats(): String =
        "Subtitles Cache: ${resultCache.size}/32, Kitsu IDs: ${kitsuIdCache.size}/24, IMDb IDs: ${imdbIdCache.size}/24"

    // =========================================================================
    // 2. Network Client with Exponential Backoff & Fast 404 Abort
    // =========================================================================

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*"
    )

    suspend fun getWithRetry(url: String, attempts: Int = 3, timeoutSec: Long = 8): String? {
        var lastException: Throwable? = null
        for (attempt in 1..attempts) {
            var shouldRetry = false
            try {
                val result = app.get(
                    url = url,
                    headers = defaultHeaders,
                    timeout = timeoutSec
                ).use { response ->
                    if (response.isSuccessful) {
                        return response.safeText()
                    }
                    val code = response.code
                    if (code == 404) {
                        // Fast fail: 404 Not Found indicates endpoint or resource does not exist
                        return null
                    }
                    if (code in 500..599 || code == 429) {
                        Log.w(TAG, "HTTP $code on attempt $attempt for: $url")
                        shouldRetry = true
                        null
                    } else {
                        return null
                    }
                }
                if (result != null) return result
                if (shouldRetry && attempt < attempts) {
                    delay(if (attempt == 1) 800L else 1200L)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastException = e
                Log.w(TAG, "Network failure on attempt $attempt for $url: ${e.message}")
                if (attempt < attempts) {
                    delay(if (attempt == 1) 800L else 1200L)
                }
            }
        }
        if (lastException != null) {
            Log.w(TAG, "All $attempts attempts exhausted for: $url (lastError: ${lastException.message})")
        }
        return null
    }

    inline fun <reified T : Any> safeParseJson(jsonStr: String): T? {
        return try {
            AppUtils.parseJson<T>(jsonStr)
        } catch (e: Throwable) {
            try {
                com.google.gson.Gson().fromJson(jsonStr, T::class.java)
            } catch (e2: Throwable) {
                null
            }
        }
    }

    // =========================================================================
    // 3. 34-Language Normalization & Preference Filtering
    // =========================================================================

    private data class LangInfo(val code2: String, val displayName: String)

    private val languageMap: Map<String, LangInfo> = buildMap {
        fun reg(code2: String, name: String, vararg keys: String) {
            val info = LangInfo(code2, name)
            put(code2.lowercase(), info)
            for (k in keys) put(k.lowercase(), info)
        }

        reg("vi", "Tiếng Việt", "vie", "vi-vn", "vietnamese")
        reg("en", "English", "eng", "en-us", "en-gb")
        reg("ja", "Japanese", "jpn", "jap")
        reg("ko", "Korean", "kor")
        reg("zh", "Chinese", "zho", "chi", "zhs", "zht", "zh-cn", "zh-tw")
        reg("es", "Spanish", "spa", "es-es", "es-mx")
        reg("fr", "French", "fra", "fre")
        reg("de", "German", "deu", "ger")
        reg("ru", "Russian", "rus")
        reg("pt", "Portuguese", "por", "pt-br", "pt-pt")
        reg("it", "Italian", "ita")
        reg("th", "Thai", "tha")
        reg("id", "Indonesian", "ind", "in")
        reg("ar", "Arabic", "ara")
        reg("hi", "Hindi", "hin")
        reg("pl", "Polish", "pol")
        reg("tr", "Turkish", "tur")
        reg("nl", "Dutch", "nld", "dut")
        reg("cs", "Czech", "ces", "cze")
        reg("uk", "Ukrainian", "ukr")
        reg("ro", "Romanian", "ron", "rum")
        reg("el", "Greek", "ell", "gre")
        reg("sv", "Swedish", "swe")
        reg("da", "Danish", "dan")
        reg("fi", "Finnish", "fin")
        reg("no", "Norwegian", "nor", "nob", "nno")
        reg("hu", "Hungarian", "hun")
        reg("bg", "Bulgarian", "bul")
        reg("he", "Hebrew", "heb", "iw")
        reg("ms", "Malay", "msa", "may")
        reg("tl", "Filipino", "fil", "tgl", "tagalog")
        reg("fa", "Persian", "fas", "per", "farsi")
        reg("ca", "Catalan", "cat")
        reg("hr", "Croatian", "hrv")
    }

    fun normalizeLanguage(rawLang: String?): Pair<String, String> {
        val raw = rawLang?.trim()?.lowercase() ?: return Pair("unknown", "Unknown (Stremio)")
        val found = languageMap[raw]
        if (found != null) {
            return Pair(found.code2, "${found.displayName} (Stremio)")
        }
        val prefix = raw.substringBefore('-').substringBefore('_')
        val prefixMatch = languageMap[prefix]
        if (prefixMatch != null) {
            return Pair(prefixMatch.code2, "${prefixMatch.displayName} (Stremio)")
        }
        if (raw.length == 2 && raw.all { it in 'a'..'z' }) {
            return Pair(raw, "${raw.uppercase()} (Stremio)")
        }
        return Pair("unknown", "${raw.uppercase()} (Stremio)")
    }

    fun isLanguageAllowed(langCode: String, filterMode: String = activeLanguageFilter): Boolean {
        val clean = langCode.lowercase().trim()
        return when (filterMode) {
            LanguageFilterMode.VI_EN -> clean == "vi" || clean == "en"
            LanguageFilterMode.VI_ONLY -> clean == "vi"
            LanguageFilterMode.EN_ONLY -> clean == "en"
            LanguageFilterMode.ALL -> true
            LanguageFilterMode.CUSTOM -> customLanguageCodes.isEmpty() || clean in customLanguageCodes
            else -> clean == "vi" || clean == "en"
        }
    }

    // =========================================================================
    // 4. Kitsu Anime ID Resolution & Subtitle Fetching
    // =========================================================================

    suspend fun resolveKitsuId(titles: List<String>): String? {
        val nonBlank = titles.map { it.trim() }.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return null

        for (candidate in nonBlank) {
            val key = candidate.lowercase()
            val cached = synchronized(kitsuIdCache) { kitsuIdCache[key] }
            if (cached != null) return cached
        }

        val primaryTitle = nonBlank.first()
        val encoded = URLEncoder.encode(primaryTitle, "UTF-8")
        val url = "$KITSU_BASE/catalog/anime/kitsu-search/search=$encoded.json"

        val rawJson = getWithRetry(url) ?: return null
        val catalog = safeParseJson<StremioCatalog>(rawJson) ?: return null
        val metas = catalog.metas

        var bestId: String? = null
        var bestScore = 0

        for (meta in metas) {
            val mid = meta.id ?: continue
            if (!mid.startsWith("kitsu:")) continue
            val name = meta.name ?: continue
            val score = StremioSubMatcher.bestMatchScore(name, nonBlank)
            if (score > bestScore && score >= 40) {
                bestScore = score
                bestId = mid.substringAfter(':')
            }
        }

        if (bestId != null) {
            Log.d(TAG, "Resolved Kitsu ID: '$primaryTitle' -> kitsu:$bestId (score=$bestScore)")
            synchronized(kitsuIdCache) {
                kitsuIdCache[primaryTitle.lowercase()] = bestId
            }
        }
        return bestId
    }

    suspend fun fetchKitsuSubtitles(
        prefix: String,
        idValue: String,
        episode: Int
    ): List<StremioSubtitleTrack> {
        val cacheKey = "kitsu:$prefix:$idValue:$episode"
        synchronized(resultCache) { resultCache[cacheKey] }?.let { return it }

        val idSegment = URLEncoder.encode("$prefix:$idValue:$episode", "UTF-8")
        val url = "$KITSU_BASE/subtitles/anime/$idSegment.json"

        val rawJson = getWithRetry(url) ?: return emptyList()
        val parsed = safeParseJson<StremioSubtitles>(rawJson) ?: return emptyList()

        val results = mutableListOf<StremioSubtitleTrack>()
        for (sub in parsed.subtitles) {
            val subUrl = sub.url ?: continue
            val (code, displayLabel) = normalizeLanguage(sub.lang ?: sub.langCode)
            val track = StremioSubtitleTrack(
                url = subUrl,
                lang = displayLabel,
                langCode = code,
                sourceName = "kitsu",
                title = sub.subtitleFileName ?: sub.title,
                episode = sub.episode ?: episode
            )
            results.add(track)
        }

        synchronized(resultCache) {
            resultCache[cacheKey] = results
        }
        Log.d(TAG, "Fetched ${results.size} subtitles from Kitsu ($prefix:$idValue, Ep $episode)")
        return results
    }

    // =========================================================================
    // 5. Cinemeta IMDb ID Resolution with Year Bonus & Fallback
    // =========================================================================

    suspend fun resolveImdbId(titles: List<String>, year: Int?): String? {
        val nonBlank = titles.map { it.trim() }.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return null

        for (candidate in nonBlank) {
            val key = candidate.lowercase()
            val cached = synchronized(imdbIdCache) { imdbIdCache[key] }
            if (cached != null) return cached
        }

        val primaryTitle = nonBlank.first()
        val encoded = URLEncoder.encode(primaryTitle, "UTF-8")

        // Step 1: Try series catalog search
        val seriesUrl = "$CINEMETA_BASE/catalog/series/top/search=$encoded.json"
        var metas: List<StremioMeta> = safeParseJson<StremioCatalog>(getWithRetry(seriesUrl) ?: "")?.metas ?: emptyList()

        var bestId: String? = null
        var bestScore = 0

        for (meta in metas) {
            val mid = meta.id ?: continue
            if (!mid.startsWith("tt")) continue
            val name = meta.name ?: continue
            val score = StremioSubMatcher.scoreCandidate(name, meta.releaseInfo, nonBlank, year)
            if (score > bestScore && score >= 40) {
                bestScore = score
                bestId = mid
            }
        }

        // Step 2: Fall back to movie catalog search if series yielded no qualified match
        if (bestId == null) {
            val movieUrl = "$CINEMETA_BASE/catalog/movie/top/search=$encoded.json"
            metas = safeParseJson<StremioCatalog>(getWithRetry(movieUrl) ?: "")?.metas ?: emptyList()
            for (meta in metas) {
                val mid = meta.id ?: continue
                if (!mid.startsWith("tt")) continue
                val name = meta.name ?: continue
                val score = StremioSubMatcher.scoreCandidate(name, meta.releaseInfo, nonBlank, year)
                if (score > bestScore && score >= 40) {
                    bestScore = score
                    bestId = mid
                }
            }
        }

        if (bestId != null) {
            Log.d(TAG, "Resolved Cinemeta IMDb ID: '$primaryTitle' -> $bestId (score=$bestScore)")
            synchronized(imdbIdCache) {
                imdbIdCache[primaryTitle.lowercase()] = bestId
            }
        }
        return bestId
    }

    // =========================================================================
    // 6. OpenSubtitles v3 Subtitle Fetching
    // =========================================================================

    suspend fun fetchOpenSubtitles(
        imdbId: String,
        season: Int?,
        episode: Int?
    ): List<StremioSubtitleTrack> {
        val isSeries = season != null && episode != null
        val cacheKey = if (isSeries) "os:series:$imdbId:$season:$episode" else "os:movie:$imdbId"
        synchronized(resultCache) { resultCache[cacheKey] }?.let { return it }

        val pathSegment = if (isSeries) {
            "series/" + URLEncoder.encode("$imdbId:$season:$episode", "UTF-8")
        } else {
            "movie/" + URLEncoder.encode(imdbId, "UTF-8")
        }
        val url = "$OPENSUBTITLES_BASE/subtitles/$pathSegment.json"

        val rawJson = getWithRetry(url) ?: return emptyList()
        val parsed = safeParseJson<StremioSubtitles>(rawJson) ?: return emptyList()

        val results = mutableListOf<StremioSubtitleTrack>()
        for (sub in parsed.subtitles) {
            val subUrl = sub.url ?: continue
            val (code, displayLabel) = normalizeLanguage(sub.lang ?: sub.langCode)
            val track = StremioSubtitleTrack(
                url = subUrl,
                lang = displayLabel,
                langCode = code,
                sourceName = "opensubtitles",
                title = sub.title ?: sub.subtitleFileName,
                episode = sub.episode ?: episode
            )
            results.add(track)
        }

        synchronized(resultCache) {
            resultCache[cacheKey] = results
        }
        Log.d(TAG, "Fetched ${results.size} subtitles from OpenSubtitles ($imdbId, S${season ?: 1}E${episode ?: 1})")
        return results
    }

    // =========================================================================
    // 7. Custom Addon Subtitle Fetching
    // =========================================================================

    suspend fun fetchCustomAddonSubtitles(
        addonBaseUrl: String,
        imdbId: String,
        season: Int?,
        episode: Int?
    ): List<StremioSubtitleTrack> {
        val sanitizedBase = addonBaseUrl.trim().trimEnd('/')
        if (!sanitizedBase.startsWith("http://") && !sanitizedBase.startsWith("https://")) {
            return emptyList()
        }

        val isSeries = season != null && episode != null
        val cacheKey = "addon:$sanitizedBase:$imdbId:${season ?: 1}:${episode ?: 1}"
        synchronized(resultCache) { resultCache[cacheKey] }?.let { return it }

        val pathSegment = if (isSeries) {
            "series/" + URLEncoder.encode("$imdbId:$season:$episode", "UTF-8")
        } else {
            "movie/" + URLEncoder.encode(imdbId, "UTF-8")
        }
        val url = "$sanitizedBase/subtitles/$pathSegment.json"

        val rawJson = getWithRetry(url) ?: return emptyList()
        val parsed = safeParseJson<StremioSubtitles>(rawJson) ?: return emptyList()

        val results = mutableListOf<StremioSubtitleTrack>()
        for (sub in parsed.subtitles) {
            val subUrl = sub.url ?: continue
            val (code, displayLabel) = normalizeLanguage(sub.lang ?: sub.langCode)
            val track = StremioSubtitleTrack(
                url = subUrl,
                lang = displayLabel,
                langCode = code,
                sourceName = "custom_addon",
                title = sub.title ?: sub.subtitleFileName,
                episode = sub.episode ?: episode
            )
            results.add(track)
        }

        synchronized(resultCache) {
            resultCache[cacheKey] = results
        }
        Log.d(TAG, "Fetched ${results.size} subtitles from Addon: $sanitizedBase")
        return results
    }

    // =========================================================================
    // 8. End-to-End Async Scraping Pipeline
    // =========================================================================

    /**
     * Primary entry point called by StremioSubHook.
     * Concurrently scrapes Kitsu, OpenSubtitles v3, and configured custom addons.
     * Emits deduplicated, language-filtered subtitle tracks both incrementally
     * via emitCallback and as a complete aggregated list upon completion.
     */
    suspend fun fetchSubtitles(
        title: String,
        episode: Int? = 1,
        season: Int? = 1,
        year: Int? = null,
        alternativeTitles: List<String> = emptyList(),
        malId: Int? = null,
        aniId: Int? = null,
        emitCallback: ((StremioSubtitleTrack) -> Unit)? = null
    ): List<StremioSubtitleTrack> = coroutineScope {
        val queryTitles = buildList {
            if (title.isNotBlank()) add(title)
            for (alt in alternativeTitles) {
                if (alt.isNotBlank() && !contains(alt)) add(alt)
            }
        }
        if (queryTitles.isEmpty()) return@coroutineScope emptyList()

        val epNum = episode ?: 1
        val seasonNum = season ?: 1
        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val collectedTracks = Collections.synchronizedList(mutableListOf<StremioSubtitleTrack>())

        fun processAndEmit(track: StremioSubtitleTrack) {
            if (!isLanguageAllowed(track.langCode)) return
            if (seenUrls.add(track.url)) {
                collectedTracks.add(track)
                emitCallback?.invoke(track)
            }
        }

        val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        // Pipeline 1: Kitsu Anime Subtitles
        if (isKitsuEnabled) {
            // 1a. MAL ID direct lookup
            if (malId != null) {
                tasks.add(async(Dispatchers.IO) {
                    try {
                        val tracks = fetchKitsuSubtitles("mal", malId.toString(), epNum)
                        tracks.forEach { processAndEmit(it) }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        logError(e)
                    }
                })
            }

            // 1b. Kitsu ID keyword lookup
            tasks.add(async(Dispatchers.IO) {
                try {
                    val kitsuId = resolveKitsuId(queryTitles)
                    if (kitsuId != null) {
                        val tracks = fetchKitsuSubtitles("kitsu", kitsuId, epNum)
                        tracks.forEach { processAndEmit(it) }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    logError(e)
                }
            })
        }

        // Pipeline 2 & 3: OpenSubtitles v3 & Custom Addons via Cinemeta IMDb ID
        if (isOpenSubtitlesEnabled || customAddonUrls.isNotEmpty()) {
            tasks.add(async(Dispatchers.IO) {
                try {
                    val imdbId = resolveImdbId(queryTitles, year)
                    if (imdbId != null) {
                        coroutineScope {
                            val subTasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                            if (isOpenSubtitlesEnabled) {
                                subTasks.add(async(Dispatchers.IO) {
                                    val tracks = fetchOpenSubtitles(imdbId, seasonNum, epNum)
                                    tracks.forEach { processAndEmit(it) }
                                })
                            }

                            for (addon in customAddonUrls) {
                                subTasks.add(async(Dispatchers.IO) {
                                    val tracks = fetchCustomAddonSubtitles(addon, imdbId, seasonNum, epNum)
                                    tracks.forEach { processAndEmit(it) }
                                })
                            }

                            subTasks.awaitAll()
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    logError(e)
                }
            })
        }

        tasks.awaitAll()
        Log.i(TAG, "Scraping complete for '$title': yielded ${collectedTracks.size} unique subtitles.")
        collectedTracks
    }
}
