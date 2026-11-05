package com.kitsunekko.enricher

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.util.Collections
import java.util.LinkedHashMap
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
 * Asynchronous scraping and caching engine for kitsunekko.net Japanese subtitles.
 * Provides 24-hour dual-tier root caching, folder discovery, and episode subtitle resolution.
 */
object KitsunekkoScraper {

    private const val TAG = "KitsunekkoScraper"

    // Base endpoints
    const val BASE_URL = "https://kitsunekko.net"
    const val ROOT_URL = "https://kitsunekko.net/dirlist.php?dir=subtitles%2Fjapanese%2F"

    // Cache parameters
    const val ROOT_CACHE_FILE = "kitsunekko_root_cache.json"
    const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L  // 24 hours
    const val MAX_SUBTITLES_PER_QUERY = 12

    // Default headers
    val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ja,en-US;q=0.9,en;q=0.8"
    )

    // Precompiled regular expressions
    val folderRegex = Regex(
        """<a\s+href="(/dirlist\.php\?dir=[^"]+japanese%2F[^"]+%2F)"[^>]*>(?:<strong>)?([^<]+)""",
        RegexOption.IGNORE_CASE
    )

    val fileRegex = Regex(
        """<a\s+href="((?:subtitles|/subtitles)[^"]+\.(?:ass|srt|ssa|zip))"""",
        RegexOption.IGNORE_CASE
    )

    // Concurrency controls for root directory caching
    private val rootCacheMutex = Mutex()
    @Volatile private var memoryRootFolders: List<KitsunekkoFolder>? = null
    @Volatile private var memoryRootTimestamp: Long = 0L

    // In-memory folder HTML cache (LRU 16 folders) to avoid redundant requests during episode switching
    private class FolderLruCache(val max: Int = 16) : LinkedHashMap<String, List<KitsunekkoSubtitle>>(max + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<KitsunekkoSubtitle>>?): Boolean = size > max
    }
    private val folderSubtitlesCache = Collections.synchronizedMap(FolderLruCache(16))

    fun init(context: Context) {
        // Pre-warm disk cache check
        val file = getRootCacheFile(context)
        if (file.exists() && file.length() > 0L && memoryRootFolders == null) {
            val disk = loadRootCacheFromDisk(context)
            if (disk != null) {
                memoryRootFolders = disk.second
                memoryRootTimestamp = disk.first
                Log.d(TAG, "Initialized root directory from disk (${disk.second.size} folders)")
            }
        }
    }

    // =========================================================================
    // 1. URL Path Encoding / Escaping
    // =========================================================================

    /**
     * Escapes problematic characters (spaces, brackets, quotes, hash) in URLs
     * while preserving percent-encoded tokens and forward slashes.
     */
    fun encodeUrlPath(path: String): String {
        return path
            .replace(" ", "%20")
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace("'", "%27")
            .replace("#", "%23")
            .replace("`", "%60")
    }

    // =========================================================================
    // 2. Safe JSON Helpers (Jackson + Gson Fallback)
    // =========================================================================

    inline fun <reified T : Any> safeParseJson(jsonStr: String): T? {
        return try {
            AppUtils.parseJson<T>(jsonStr)
        } catch (_: Throwable) {
            try {
                Gson().fromJson(jsonStr, T::class.java)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun safeToJson(obj: Any): String {
        return try {
            Gson().toJson(obj)
        } catch (_: Throwable) {
            ""
        }
    }

    // =========================================================================
    // 3. 24-Hour Root Directory Caching Pipeline
    // =========================================================================

    private fun getRootCacheFile(context: Context): File {
        return File(context.cacheDir, ROOT_CACHE_FILE)
    }

    private fun loadRootCacheFromDisk(context: Context): Pair<Long, List<KitsunekkoFolder>>? {
        val file = getRootCacheFile(context)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val jsonStr = file.readText(Charsets.UTF_8)
            val payload = safeParseJson<KitsunekkoRootCachePayload>(jsonStr)
            if (payload != null && payload.folders.isNotEmpty()) {
                Pair(payload.timestamp, payload.folders)
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed reading root cache from disk: ${e.message}")
            null
        }
    }

    private fun saveRootCacheToDisk(context: Context, timestamp: Long, folders: List<KitsunekkoFolder>) {
        try {
            val targetFile = getRootCacheFile(context)
            val tempFile = File(context.cacheDir, "${ROOT_CACHE_FILE}.tmp")
            val payload = KitsunekkoRootCachePayload(timestamp = timestamp, folders = folders)
            val jsonStr = safeToJson(payload)
            tempFile.writeText(jsonStr, Charsets.UTF_8)
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            Log.d(TAG, "Saved ${folders.size} folders to disk cache: ${targetFile.absolutePath}")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed saving root cache to disk: ${e.message}")
        }
    }

    /**
     * Fetches root directory anime folders with 24-hour in-memory and disk caching.
     * Thread-safe with Mutex synchronization.
     */
    suspend fun fetchRootFolders(context: Context?, forceRefresh: Boolean = false): List<KitsunekkoFolder> {
        val now = System.currentTimeMillis()

        // 1. Fast path: in-memory cache hit
        if (!forceRefresh) {
            val mem = memoryRootFolders
            if (mem != null && mem.isNotEmpty() && (now - memoryRootTimestamp < CACHE_VALIDITY_MS)) {
                return mem
            }
        }

        // 2. Synchronized fetch/disk check
        return rootCacheMutex.withLock {
            val currentMem = memoryRootFolders
            if (!forceRefresh && currentMem != null && currentMem.isNotEmpty() && (now - memoryRootTimestamp < CACHE_VALIDITY_MS)) {
                return@withLock currentMem
            }

            // Check disk cache if context available
            if (!forceRefresh && context != null) {
                val diskCache = loadRootCacheFromDisk(context)
                if (diskCache != null) {
                    val (diskTime, diskFolders) = diskCache
                    if (now - diskTime < CACHE_VALIDITY_MS) {
                        memoryRootFolders = diskFolders
                        memoryRootTimestamp = diskTime
                        Log.i(TAG, "Loaded ${diskFolders.size} anime folders from disk cache (age: ${(now - diskTime) / 3600000.0}h)")
                        return@withLock diskFolders
                    }
                }
            }

            // 3. Network fetch from Kitsunekko
            Log.i(TAG, "Fetching root directory from: $ROOT_URL (forceRefresh=$forceRefresh)")
            val fetchedFolders = try {
                app.get(
                    url = ROOT_URL,
                    headers = defaultHeaders,
                    timeout = 15
                ).use { response ->
                    if (response.isSuccessful) {
                        val html = response.safeText() ?: ""
                        val parsed = folderRegex.findAll(html).mapNotNull { m ->
                            val href = m.groupValues[1]
                            val title = m.groupValues[2].trim()
                            if (title.isNotBlank()) KitsunekkoFolder(name = title, path = href) else null
                        }.toList()
                        parsed
                    } else {
                        Log.w(TAG, "Kitsunekko root HTTP error: ${response.code}")
                        emptyList()
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed fetching Kitsunekko root: ${e.message}")
                emptyList()
            }

            if (fetchedFolders.isNotEmpty()) {
                memoryRootFolders = fetchedFolders
                memoryRootTimestamp = now
                if (context != null) {
                    withContext(Dispatchers.IO) {
                        saveRootCacheToDisk(context, now, fetchedFolders)
                    }
                }
                Log.i(TAG, "Successfully cached ${fetchedFolders.size} anime folders.")
                return@withLock fetchedFolders
            }

            // 4. Fallback to stale disk cache if network fails
            if (context != null) {
                val stale = loadRootCacheFromDisk(context)
                if (stale != null && stale.second.isNotEmpty()) {
                    Log.w(TAG, "Network failed; falling back to stale disk cache (${stale.second.size} folders).")
                    memoryRootFolders = stale.second
                    memoryRootTimestamp = stale.first
                    return@withLock stale.second
                }
            }

            memoryRootFolders ?: emptyList()
        }
    }

    /**
     * Refreshes the root directory cache and returns the number of folders discovered.
     */
    suspend fun refreshRootDirectory(context: Context): Int {
        val folders = fetchRootFolders(context, forceRefresh = true)
        return folders.size
    }

    // =========================================================================
    // 4. Folder Matching & Season Penalty
    // =========================================================================

    /**
     * Finds the best matching anime folders for a list of title candidates.
     * Incorporates Season Hint and penalty logic (-200 for mismatch).
     */
    fun findMatchingFolders(
        rootFolders: List<KitsunekkoFolder>,
        titles: List<String>,
        limit: Int = 2
    ): List<Pair<KitsunekkoFolder, Int>> {
        val nonBlankTitles = titles.map { it.trim() }.filter { it.isNotBlank() }
        if (nonBlankTitles.isEmpty() || rootFolders.isEmpty()) return emptyList()

        val seasonHint = KitsunekkoMatcher.seasonHintOf(nonBlankTitles)

        return rootFolders.mapNotNull { folder ->
            var score = KitsunekkoMatcher.bestMatchScore(folder.name, nonBlankTitles)
            val candSeason = KitsunekkoMatcher.seasonOf(folder.name)

            if (seasonHint != null && candSeason != null && candSeason != seasonHint) {
                score -= 200
            }
            if (seasonHint != null && candSeason == null) {
                score -= 10
            }
            if (seasonHint == null && candSeason != null) {
                score -= 15
            }

            if (score >= 40) folder to score else null
        }
        .sortedByDescending { it.second }
        .take(limit)
    }

    // =========================================================================
    // 5. Anime Folder Scraping
    // =========================================================================

    /**
     * Scrapes a single anime folder page to extract subtitle files (.ass, .srt, .ssa) and zip packages.
     */
    suspend fun scrapeFolderSubtitles(folder: KitsunekkoFolder): List<KitsunekkoSubtitle> {
        val cached = folderSubtitlesCache[folder.path]
        if (cached != null) return cached

        val folderUrl = "$BASE_URL" + encodeUrlPath(folder.path)
        Log.d(TAG, "Scraping anime folder: $folderUrl")

        return try {
            app.get(
                url = folderUrl,
                headers = defaultHeaders,
                timeout = 10
            ).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to load folder HTML: HTTP ${response.code}")
                    return emptyList()
                }

                val html = response.safeText() ?: return emptyList()
                val candSeason = KitsunekkoMatcher.seasonOf(folder.name)
                val results = mutableListOf<KitsunekkoSubtitle>()

                fileRegex.findAll(html).forEach { m ->
                    val rawHref = m.groupValues[1]
                    val rawFileName = rawHref.substringAfterLast('/')
                    val decodedFileName = try {
                        URLDecoder.decode(rawFileName, "UTF-8")
                    } catch (_: Throwable) {
                        rawFileName
                    }
                    val ext = decodedFileName.substringAfterLast('.', "").lowercase()
                    val isZip = (ext == "zip")
                    val fullUrl = "$BASE_URL/" + encodeUrlPath(rawHref.removePrefix("/"))
                    val ep = KitsunekkoMatcher.extractEpisodeNumber(decodedFileName)

                    results.add(
                        KitsunekkoSubtitle(
                            name = decodedFileName,
                            url = fullUrl,
                            isZip = isZip,
                            episodeNumber = ep,
                            seasonNumber = candSeason,
                            ext = ext,
                            rawHref = rawHref
                        )
                    )
                }

                folderSubtitlesCache[folder.path] = results
                Log.d(TAG, "Discovered ${results.size} files in folder '${folder.name}'")
                results
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error scraping folder '${folder.name}': ${e.message}")
            emptyList()
        }
    }

    // =========================================================================
    // 6. End-to-End Subtitle Discovery Pipeline
    // =========================================================================

    /**
     * Primary entry point invoked during CloudStream loadLinks.
     * Matches anime folders, parses files, extracts streaming zip entries,
     * and emits SubtitleFile objects.
     */
    suspend fun searchSubtitles(
        context: Context,
        titles: List<String>,
        episode: Int?,
        callback: ((SubtitleFile) -> Unit)? = null
    ): List<SubtitleFile> {
        val cleanTitles = titles.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanTitles.isEmpty()) return emptyList()

        val rootFolders = fetchRootFolders(context, forceRefresh = false)
        if (rootFolders.isEmpty()) return emptyList()

        val matchedFolders = findMatchingFolders(rootFolders, cleanTitles, limit = 2)
        if (matchedFolders.isEmpty()) {
            Log.d(TAG, "No matching folders found for titles: $cleanTitles")
            return emptyList()
        }

        Log.d(TAG, "Matched folders: ${matchedFolders.map { "${it.first.name} (${it.second}pts)" }}")

        val outSubtitles = mutableListOf<SubtitleFile>()
        val emittedUrls = mutableSetOf<String>()

        for ((folder, _) in matchedFolders) {
            val folderSubs = scrapeFolderSubtitles(folder)

            for (sub in folderSubs) {
                if (outSubtitles.size >= MAX_SUBTITLES_PER_QUERY) break

                if (sub.isZip) {
                    // Delegate to streaming ZipSubtitleExtractor
                    val extracted = ZipSubtitleExtractor.extractSubtitleFromZip(
                        context = context,
                        zipUrl = sub.url,
                        zipName = sub.name,
                        targetEpisode = episode
                    )
                    if (extracted != null && extracted.exists()) {
                        val fileUri = "file://${extracted.absolutePath}"
                        if (emittedUrls.add(fileUri)) {
                            val subFile = SubtitleFile("Tiếng Nhật (Kitsunekko)", fileUri)
                            outSubtitles.add(subFile)
                            callback?.invoke(subFile)
                            Log.i(TAG, "Injected extracted zip subtitle: ${extracted.name}")
                        }
                    }
                } else if (sub.ext in setOf("ass", "srt", "ssa")) {
                    // Direct subtitle file
                    val matchesEpisode = (episode == null) || (sub.episodeNumber == episode)
                    if (matchesEpisode) {
                        if (emittedUrls.add(sub.url)) {
                            val subFile = SubtitleFile("Tiếng Nhật (Kitsunekko)", sub.url)
                            outSubtitles.add(subFile)
                            callback?.invoke(subFile)
                            Log.i(TAG, "Injected direct subtitle: ${sub.name}")
                        }
                    }
                }
            }

            if (outSubtitles.size >= MAX_SUBTITLES_PER_QUERY) break
        }

        return outSubtitles
    }

    /**
     * Alias for searchSubtitles to guarantee compatibility with all consumer modules.
     */
    suspend fun fetchSubtitles(
        titles: List<String>,
        targetEpisode: Int?,
        context: Context,
        callback: ((SubtitleFile) -> Unit)? = null
    ): List<SubtitleFile> = searchSubtitles(context, titles, targetEpisode, callback)

    // =========================================================================
    // 7. Cache Metrics & UI Support
    // =========================================================================

    /**
     * Inspects cache state and returns summary metrics for Settings UI.
     */
    fun getCacheSummary(context: Context): KitsunekkoCacheSummary {
        val now = System.currentTimeMillis()
        val diskFile = getRootCacheFile(context)
        val rootTime = if (memoryRootTimestamp > 0L) memoryRootTimestamp else if (diskFile.exists()) diskFile.lastModified() else 0L

        val rootAgeHours = if (rootTime > 0L) {
            (now - rootTime).toDouble() / (1000.0 * 3600.0)
        } else {
            -1.0
        }

        val subsDir = File(context.cacheDir, "kitsunekko_subs")
        val zipDir = File(context.cacheDir, "kitsunekko_cache")

        val cachedSubsCount = if (subsDir.exists()) (subsDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") }?.size ?: 0) else 0

        val subsBytes = if (subsDir.exists()) {
            subsDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") }?.sumOf { it.length() } ?: 0L
        } else 0L

        val zipBytes = if (zipDir.exists()) {
            zipDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") }?.sumOf { it.length() } ?: 0L
        } else 0L

        val rootBytes = if (diskFile.exists()) diskFile.length() else 0L
        val totalBytes = subsBytes + zipBytes + rootBytes
        val itemCount = memoryRootFolders?.size ?: 0

        return KitsunekkoCacheSummary(
            rootCacheAgeHours = rootAgeHours,
            cachedSubsCount = cachedSubsCount,
            cacheSizeBytes = totalBytes,
            directoryItemCount = itemCount,
            directoryLastUpdated = rootTime,
            zipCacheBytes = zipBytes,
            subsCacheBytes = subsBytes
        )
    }

    /** Alias for getCacheSummary */
    fun getCacheMetrics(context: Context): KitsunekkoCacheSummary = getCacheSummary(context)

    /**
     * Clears in-memory and disk caches.
     */
    fun clearCache(context: Context, includeRootCache: Boolean = false) {
        folderSubtitlesCache.clear()

        // Delegate temp cache cleaning to ZipSubtitleExtractor
        ZipSubtitleExtractor.clearAllCache(context)

        if (includeRootCache) {
            memoryRootFolders = null
            memoryRootTimestamp = 0L
            val rootFile = getRootCacheFile(context)
            if (rootFile.exists()) rootFile.delete()
            Log.i(TAG, "Cleared root directory cache.")
        }

        Log.i(TAG, "Cleared Kitsunekko caches (includeRoot=$includeRootCache).")
    }

    /** Clears memory caches */
    fun clearMemoryCache() {
        folderSubtitlesCache.clear()
        memoryRootFolders = null
        memoryRootTimestamp = 0L
    }
}
