@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.subtitles.ai.session

import android.util.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.profile.AniListCastResolver
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Source Subtitle Fallback Extractor for CloudStream AI Subtitle Translator.
 *
 * Activated only when external subtitle enrichers/providers yield 0 subtitle candidates:
 * - Tier A: Scans up to 3 priority HLS (.m3u8) / DASH (.mpd) manifests (<= 3.5s each) for embedded
 *   subtitle tracks (#EXT-X-MEDIA:TYPE=SUBTITLES / <AdaptationSet contentType="text">), enforcing
 *   Target Language Bypass and unrolling WebVTT .m3u8 segment playlists.
 * - Tier B: Inspects PlayerGeneratorViewModel.currentSubtitles via pure Java reflection for
 *   EMBEDDED_IN_VIDEO target-language bypass, and queries CloudStream core
 *   AccountManager.Companion.getSubtitleProviders() (SubDL, SubSource, OpenSubtitles, Addic7ed)
 *   via SubtitleRepo.getApi() within <= 4s total timeout.
 *
 * Strictly complies with DEX packaging whitelist (zero org.w3c.dom / javax.xml / org.xmlpull /
 * androidx / gson / kotlinx.serialization compile-time references).
 */
object StreamSubtitleExtractor {

    private const val TAG = "StreamSubtitleExtractor"
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    sealed class FallbackDiscoveryResult {
        data class Bypassed(val matchedLang: String, val source: String) : FallbackDiscoveryResult()
        data class CandidatesFound(val candidates: List<SubtitleFile>, val source: String) : FallbackDiscoveryResult()
        object NoneFound : FallbackDiscoveryResult()
    }

    private val defaultHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .writeTimeout(3500, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val HLS_ATTR_REGEX = Regex("""([A-Z0-9-]+)=("([^"]*)"|([^,\s]*))""", RegexOption.IGNORE_CASE)
    private val DASH_ADAPTATION_SET_REGEX = Regex(
        """<AdaptationSet\b([^>]*)>(.*?)</AdaptationSet>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val DASH_REPRESENTATION_REGEX = Regex(
        """<Representation\b([^>]*)/?>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val DASH_CONTENT_TYPE_TEXT_REGEX = Regex("""contentType\s*=\s*["']text["']""", RegexOption.IGNORE_CASE)
    private val DASH_TEXT_MIME_REGEX = Regex(
        """mimeType\s*=\s*["'][^"']*(?:text/vtt|application/ttml\+xml|x-subrip|text/srt)[^"']*["']""",
        RegexOption.IGNORE_CASE
    )
    private val DASH_TEXT_CODEC_REGEX = Regex(
        """codecs\s*=\s*["'][^"']*(?:wvtt|stpp)[^"']*["']""",
        RegexOption.IGNORE_CASE
    )
    private val DASH_LANG_REGEX = Regex("""\blang\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val DASH_LABEL_REGEX = Regex("""<Label[^>]*>([^<]+)</Label>""", RegexOption.IGNORE_CASE)
    private val DASH_BASE_URL_REGEX = Regex("""<BaseURL[^>]*>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE)
    private val DASH_SEGMENT_TEMPLATE_MEDIA_REGEX = Regex(
        """<SegmentTemplate\b[^>]*\bmedia\s*=\s*["']([^"'$]+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val HLS_KEY_METHOD_REGEX = Regex("""#EXT-X-KEY:[^\r\n]*METHOD=([A-Z0-9-]+)""", RegexOption.IGNORE_CASE)
    private val VTT_TIMING_ARROW_REGEX = Regex("""(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,4}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,4}""")

    fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        val raw = relativeOrAbsolute.trim()
        if (raw.isEmpty()) return raw
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("data:", ignoreCase = true) ||
            raw.startsWith("file:", ignoreCase = true)
        ) {
            return raw
        }
        if (raw.startsWith("//")) {
            val scheme = if (baseUrl.startsWith("http://", ignoreCase = true)) "http:" else "https:"
            return "$scheme$raw"
        }
        return try {
            URI(baseUrl).resolve(raw).toString()
        } catch (_: Throwable) {
            raw
        }
    }

    fun isHlsPlaylist(text: String, url: String = ""): Boolean {
        val clean = text.trimStart('\uFEFF', '\uFFFE', ' ', '\t', '\r', '\n')
        if (!clean.startsWith("#EXTM3U", ignoreCase = true)) return false
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        return clean.contains("#EXTINF:", ignoreCase = true) ||
            clean.contains("#EXT-X-TARGETDURATION", ignoreCase = true) ||
            clean.contains("#EXT-X-STREAM-INF", ignoreCase = true) ||
            clean.contains("#EXT-X-MEDIA:", ignoreCase = true) ||
            cleanUrl.endsWith(".m3u8", ignoreCase = true)
    }

    fun parseHlsSubtitles(
        manifestText: String,
        baseUrl: String,
        headers: Map<String, String> = emptyMap()
    ): List<SubtitleFile> {
        if (manifestText.isBlank()) return emptyList()
        val results = mutableListOf<SubtitleFile>()
        val seenKeys = HashSet<String>()

        for (rawLine in manifestText.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) continue

            val payload = line.substringAfter(":", "")
            val attrs = LinkedHashMap<String, String>()
            for (match in HLS_ATTR_REGEX.findAll(payload)) {
                val key = match.groupValues[1].uppercase(Locale.ROOT)
                val value = if (match.groupValues[3].isNotEmpty() || match.groupValues[2].startsWith("\"")) {
                    match.groupValues[3]
                } else {
                    match.groupValues[4]
                }
                attrs[key] = value.trim()
            }

            if (!attrs["TYPE"].equals("SUBTITLES", ignoreCase = true)) continue
            val uri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: continue
            val resolvedUrl = resolveUrl(baseUrl, uri)
            val language = attrs["LANGUAGE"].orEmpty()
            val name = attrs["NAME"].orEmpty()

            val label = when {
                name.isNotBlank() && language.isNotBlank() && !name.contains(language, ignoreCase = true) ->
                    "$name ($language)"
                name.isNotBlank() -> name
                language.isNotBlank() -> language
                else -> "und"
            }

            val dedupKey = "$label|$resolvedUrl"
            if (seenKeys.add(dedupKey)) {
                val subFile = SubtitleFile(lang = label, url = resolvedUrl)
                if (headers.isNotEmpty()) {
                    subFile.headers = headers
                }
                results.add(subFile)
            }
        }
        return results
    }

    fun parseDashSubtitles(
        mpdXml: String,
        baseUrl: String,
        headers: Map<String, String> = emptyMap()
    ): List<SubtitleFile> {
        if (mpdXml.isBlank()) return emptyList()
        val results = mutableListOf<SubtitleFile>()
        val seenKeys = HashSet<String>()

        for (match in DASH_ADAPTATION_SET_REGEX.findAll(mpdXml)) {
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]

            val repAttrs = DASH_REPRESENTATION_REGEX.findAll(body)
                .joinToString(" ") { it.groupValues[1] }
            val combinedAttrs = "$attrs $repAttrs"

            val isTextTrack = DASH_CONTENT_TYPE_TEXT_REGEX.containsMatchIn(combinedAttrs) ||
                DASH_TEXT_MIME_REGEX.containsMatchIn(combinedAttrs) ||
                DASH_TEXT_CODEC_REGEX.containsMatchIn(combinedAttrs)
            if (!isTextTrack) continue

            val subPath = DASH_BASE_URL_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()
                ?: DASH_SEGMENT_TEMPLATE_MEDIA_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()
                ?: continue
            if (subPath.isBlank()) continue

            val resolvedUrl = resolveUrl(baseUrl, subPath)
            val lang = DASH_LANG_REGEX.find(attrs)?.groupValues?.getOrNull(1)?.trim()
                ?: DASH_LANG_REGEX.find(repAttrs)?.groupValues?.getOrNull(1)?.trim()
                ?: ""
            val labelText = DASH_LABEL_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            val label = when {
                labelText.isNotBlank() && lang.isNotBlank() && !labelText.contains(lang, ignoreCase = true) ->
                    "$labelText ($lang)"
                labelText.isNotBlank() -> labelText
                lang.isNotBlank() -> lang
                else -> "und"
            }

            val dedupKey = "$label|$resolvedUrl"
            if (seenKeys.add(dedupKey)) {
                val subFile = SubtitleFile(lang = label, url = resolvedUrl)
                if (headers.isNotEmpty()) {
                    subFile.headers = headers
                }
                results.add(subFile)
            }
        }
        return results
    }

    /**
     * Unrolls an HLS WebVTT segment playlist (.m3u8) into a single clean WebVTT byte payload.
     * Rejects encrypted (#EXT-X-KEY) or fMP4-wrapped (#EXT-X-MAP) subtitle playlists cleanly,
     * strips per-segment WEBVTT/X-TIMESTAMP-MAP/Kind/Language headers, and deduplicates
     * boundary cues repeated across adjacent segments.
     */
    suspend fun unrollHlsVttPlaylist(
        playlistText: String,
        playlistUrl: String,
        headers: Map<String, String> = emptyMap(),
        fetchBytes: suspend (String, Map<String, String>?) -> ByteArray?,
        maxSegments: Int = 500,
        depth: Int = 0
    ): ByteArray? {
        if (depth >= 2 || playlistText.isBlank()) return null

        val trimmedStart = playlistText.trimStart('\uFEFF', '\uFFFE', ' ', '\t', '\r', '\n')
        if (trimmedStart.startsWith("WEBVTT", ignoreCase = true)) {
            return trimmedStart.toByteArray(Charsets.UTF_8)
        }
        if (!isHlsPlaylist(playlistText, playlistUrl)) {
            return null
        }

        val keyMatch = HLS_KEY_METHOD_REGEX.find(playlistText)
        if (keyMatch != null && !keyMatch.groupValues[1].equals("NONE", ignoreCase = true)) {
            logW(TAG, "Encrypted HLS subtitle playlist (METHOD=${keyMatch.groupValues[1]}) is unsupported.")
            return null
        }
        if (playlistText.contains("#EXT-X-MAP:", ignoreCase = true)) {
            logW(TAG, "fMP4 HLS subtitle playlist (#EXT-X-MAP) is unsupported as plain text.")
            return null
        }

        val lines = playlistText.replace("\r\n", "\n").replace('\r', '\n').lines()
        if (lines.any { it.trim().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            val variantLine = lines.firstOrNull { l ->
                val t = l.trim()
                t.isNotEmpty() && !t.startsWith("#")
            } ?: return null
            val variantUrl = resolveUrl(playlistUrl, variantLine)
            val variantBytes = fetchBytes(variantUrl, headers.ifEmpty { null }) ?: return null
            val variantText = decodeUtf8NoBom(variantBytes)
            return unrollHlsVttPlaylist(variantText, variantUrl, headers, fetchBytes, maxSegments, depth + 1)
        }

        val segmentUrls = mutableListOf<String>()
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            segmentUrls.add(resolveUrl(playlistUrl, trimmed))
            if (segmentUrls.size >= maxSegments) break
        }
        if (segmentUrls.isEmpty()) return null

        val cueBlocks = mutableListOf<String>()
        var lastCueKey: String? = null

        val completed = withTimeoutOrNull(15000L) {
            for (segUrl in segmentUrls) {
                if (!currentCoroutineContext().isActive) return@withTimeoutOrNull false
                val segBytes = fetchBytes(segUrl, headers.ifEmpty { null }) ?: continue
                val segText = decodeUtf8NoBom(segBytes)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                val extracted = extractVttCueBlocks(segText)
                for ((dedupKey, blockText) in extracted) {
                    if (dedupKey != lastCueKey) {
                        cueBlocks.add(blockText)
                        lastCueKey = dedupKey
                    }
                }
            }
            true
        }
        if (completed == null && cueBlocks.isEmpty()) return null
        if (cueBlocks.isEmpty()) return null

        val combined = buildString {
            append("WEBVTT\n\n")
            append(cueBlocks.joinToString("\n\n"))
            append("\n")
        }
        return combined.toByteArray(Charsets.UTF_8)
    }

    internal fun extractVttCueBlocks(segmentText: String): List<Pair<String, String>> {
        val lines = segmentText.lines()
        val blocks = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            if (!VTT_TIMING_ARROW_REGEX.containsMatchIn(line)) {
                i++
                continue
            }

            val timingLine = line.trim()
            var optionalId: String? = null
            if (i > 0) {
                val prev = lines[i - 1].trim()
                val prevIsHeader = prev.isEmpty() ||
                    prev.startsWith("WEBVTT", ignoreCase = true) ||
                    prev.startsWith("X-TIMESTAMP-MAP", ignoreCase = true) ||
                    prev.startsWith("Kind:", ignoreCase = true) ||
                    prev.startsWith("Language:", ignoreCase = true) ||
                    prev.startsWith("NOTE", ignoreCase = true) ||
                    prev.equals("STYLE", ignoreCase = true) ||
                    prev.equals("REGION", ignoreCase = true) ||
                    prev == "}" ||
                    prev.contains("-->")
                val twoBackBlank = (i - 2 < 0) || lines[i - 2].trim().isEmpty()
                if (!prevIsHeader && twoBackBlank) {
                    optionalId = prev
                }
            }

            val bodyLines = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size) {
                val nextLine = lines[j].trimEnd()
                if (nextLine.isBlank()) break
                if (VTT_TIMING_ARROW_REGEX.containsMatchIn(nextLine)) break
                if (nextLine.trim().startsWith("WEBVTT", ignoreCase = true) ||
                    nextLine.trim().startsWith("X-TIMESTAMP-MAP", ignoreCase = true)
                ) {
                    break
                }
                bodyLines.add(nextLine)
                j++
            }

            if (bodyLines.isNotEmpty()) {
                val bodyJoined = bodyLines.joinToString("\n").trim()
                if (bodyJoined.isNotEmpty()) {
                    val dedupKey = "$timingLine\n$bodyJoined"
                    val fullBlock = if (optionalId != null) {
                        "$optionalId\n$timingLine\n$bodyJoined"
                    } else {
                        "$timingLine\n$bodyJoined"
                    }
                    blocks.add(dedupKey to fullBlock)
                }
            }
            i = if (j < lines.size && lines[j].isBlank()) j + 1 else j
        }
        return blocks
    }

    private fun decodeUtf8NoBom(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        return text.trimStart('\uFEFF', '\uFFFE')
    }

    internal suspend fun fetchTextBounded(
        url: String,
        headers: Map<String, String> = emptyMap(),
        httpClient: OkHttpClient = defaultHttpClient,
        timeoutMs: Long = 3500L
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "*/*")
                headers.forEach { (k, v) ->
                    if (k.isNotBlank()) reqBuilder.header(k, v)
                }
                executeCancellableTextCall(httpClient, reqBuilder.build())
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logW(TAG, "Bounded manifest fetch failed for $url: ${t.message}")
                null
            }
        }
    }

    private suspend fun executeCancellableTextCall(
        client: OkHttpClient,
        request: Request
    ): String? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        val completed = AtomicBoolean(false)
        val cancelledByCoroutine = AtomicBoolean(false)

        cont.invokeOnCancellation {
            cancelledByCoroutine.set(true)
            try {
                call.cancel()
            } catch (_: Throwable) {}
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (completed.compareAndSet(false, true) && cont.isActive) {
                    if (cancelledByCoroutine.get()) {
                        cont.resumeWithException(CancellationException("Manifest HTTP call cancelled", e))
                    } else {
                        cont.resume(null)
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(body)
                    }
                } catch (t: Throwable) {
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        if (cancelledByCoroutine.get()) {
                            cont.resumeWithException(CancellationException("Manifest read cancelled", t))
                        } else {
                            cont.resume(null)
                        }
                    }
                }
            }
        })
    }

    /**
     * Tier B1: Safely inspects PlayerGeneratorViewModel.currentSubtitles (and state.subtitles)
     * via pure Java reflection without compile-time AndroidX dependencies.
     * Returns matched language label if an EMBEDDED_IN_VIDEO track matches targetLang.
     */
    fun hasEmbeddedTargetLanguageTrack(
        targetLang: String,
        playerVmOrActivityOverride: Any? = null
    ): String? {
        try {
            val vm = resolvePlayerViewModel(playerVmOrActivityOverride) ?: return null
            val subtitleSet = extractSubtitlesCollectionFromVm(vm) ?: return null

            for (subData in subtitleSet) {
                if (subData == null) continue
                val originStr = try {
                    subData.javaClass.getMethod("getOrigin").invoke(subData)?.toString()
                } catch (_: Throwable) {
                    null
                }
                if (originStr != "EMBEDDED_IN_VIDEO") continue

                val candidateLabels = listOfNotNull(
                    invokeStringGetter(subData, "getLanguageCode"),
                    invokeStringGetter(subData, "getIETF_tag"),
                    invokeStringGetter(subData, "getName"),
                    invokeStringGetter(subData, "getOriginalName")
                ).filter { it.isNotBlank() }

                for (label in candidateLabels) {
                    if (SubtitlePriorityEvaluator.isMatchingLanguage(label, targetLang)) {
                        return label
                    }
                }
            }
        } catch (t: Throwable) {
            logW(TAG, "Tier B1 embedded track check skipped: ${t.message}")
        }
        return null
    }

    private fun resolvePlayerViewModel(overrideObj: Any?): Any? {
        if (overrideObj != null) {
            // Direct ViewModel or mock ViewModel with getCurrentSubtitles/getState
            val hasVmMethod = try {
                overrideObj.javaClass.getMethod("getCurrentSubtitles")
                true
            } catch (_: Throwable) {
                try {
                    overrideObj.javaClass.getMethod("getState")
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            if (hasVmMethod) return overrideObj
        }

        val activity = overrideObj ?: try {
            CommonActivity.activity
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val fm = activity.javaClass.getMethod("getSupportFragmentManager").invoke(activity) ?: return null
            findViewModelInFragmentManager(fm)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findViewModelInFragmentManager(fragmentManager: Any): Any? {
        val fragments = try {
            @Suppress("UNCHECKED_CAST")
            fragmentManager.javaClass.getMethod("getFragments").invoke(fragmentManager) as? Collection<*>
        } catch (_: Throwable) {
            null
        } ?: return null

        for (frag in fragments) {
            if (frag == null) continue
            val fragName = frag.javaClass.name
            if (fragName.contains("GeneratorPlayer", ignoreCase = true) ||
                fragName.contains("Player", ignoreCase = true)
            ) {
                // Direct declared field 'viewModel'
                var cls: Class<*>? = frag.javaClass
                while (cls != null && cls != Any::class.java) {
                    try {
                        val field = cls.getDeclaredField("viewModel")
                        field.isAccessible = true
                        val vm = field.get(frag)
                        if (vm != null) return vm
                    } catch (_: Throwable) {}
                    cls = cls.superclass
                }

                // Fallback via ViewModelProvider(fragment).get(PlayerGeneratorViewModel::class.java)
                try {
                    val vmpClass = Class.forName("androidx.lifecycle.ViewModelProvider")
                    val storeOwnerClass = Class.forName("androidx.lifecycle.ViewModelStoreOwner")
                    if (storeOwnerClass.isInstance(frag)) {
                        val vmpCtor = vmpClass.getConstructor(storeOwnerClass)
                        val vmp = vmpCtor.newInstance(frag)
                        val vmClass = Class.forName("com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel")
                        val vm = vmpClass.getMethod("get", Class::class.java).invoke(vmp, vmClass)
                        if (vm != null) return vm
                    }
                } catch (_: Throwable) {}
            }

            // Recurse into childFragmentManager
            try {
                val childFm = frag.javaClass.getMethod("getChildFragmentManager").invoke(frag)
                if (childFm != null) {
                    val nested = findViewModelInFragmentManager(childFm)
                    if (nested != null) return nested
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun extractSubtitlesCollectionFromVm(vm: Any): Collection<*>? {
        // 1. Try vm.getCurrentSubtitles().getValue().getValue()
        try {
            val liveData = vm.javaClass.getMethod("getCurrentSubtitles").invoke(vm)
            if (liveData != null) {
                val videoLive = liveData.javaClass.getMethod("getValue").invoke(liveData)
                if (videoLive != null) {
                    val setVal = try {
                        videoLive.javaClass.getMethod("getValue").invoke(videoLive)
                    } catch (_: Throwable) {
                        videoLive
                    }
                    if (setVal is Collection<*>) return setVal
                }
            }
        } catch (_: Throwable) {}

        // 2. Fallback to vm.getState().getSubtitles()
        try {
            val state = vm.javaClass.getMethod("getState").invoke(vm)
            if (state != null) {
                val subs = state.javaClass.getMethod("getSubtitles").invoke(state)
                if (subs is Collection<*>) return subs
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun invokeStringGetter(target: Any, methodName: String): String? {
        return try {
            target.javaClass.getMethod(methodName).invoke(target)?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Tier B2: Queries CloudStream core AccountManager.Companion.getSubtitleProviders()
     * (SubDL, SubSource, OpenSubtitles, Addic7ed) via SubtitleRepo.getApi() within <= 4s timeout.
     */
    suspend fun querySubtitleApiProviders(
        title: String,
        year: Int? = null,
        imdbId: String? = null,
        targetLang: String,
        sourcePriorityMode: String = TranslatorConfig.sourcePriorityMode,
        customPriorityList: List<String> = TranslatorConfig.getCustomPriorityLanguages(),
        apisOverride: List<Pair<SubtitleAPI, AuthData?>>? = null,
        timeoutMs: Long = 4000L,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): FallbackDiscoveryResult {
        val cleanTitle = AniListCastResolver.cleanAnimeTitle(title)
        if (cleanTitle.isBlank() && imdbId.isNullOrBlank()) return FallbackDiscoveryResult.NoneFound

        val providers = apisOverride ?: resolveCoreSubtitleApis()
        if (providers.isEmpty()) return FallbackDiscoveryResult.NoneFound
        logI(TAG, "SubtitleAPI query episode-aware: ep=$episodeNumber season=$seasonNumber")

        val cleanTarget = targetLang.trim().lowercase(Locale.ROOT)
        val searchLangs = buildSearchLanguages(targetLang, sourcePriorityMode, customPriorityList)
        val fallbackLangs = searchLangs.filter { !it.equals(cleanTarget, ignoreCase = true) }
        val collected = mutableListOf<SubtitleFile>()
        val seenUrls = HashSet<String>()

        val timedResult = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                // Phase 1: Scan targetLang across ALL core providers first so a later provider's
                // native target-language track triggers Bypass even if an earlier provider has EN/JA.
                if (cleanTarget.isNotBlank()) {
                    for ((api, auth) in providers) {
                        if (!currentCoroutineContext().isActive) break
                        try {
                            val targetQuery = AbstractSubtitleEntities.SubtitleSearch(
                                query = cleanTitle.ifBlank { imdbId ?: "" },
                                lang = cleanTarget,
                                imdbId = imdbId,
                                epNumber = episodeNumber,
                                seasonNumber = seasonNumber,
                                year = year
                            )
                            val targetEntities = (api.search(auth, targetQuery) ?: emptyList()).filter { entity ->
                                seasonNumber == null || entity.seasonNumber == null || entity.seasonNumber == seasonNumber
                            }
                            val targetEntity = targetEntities.firstOrNull { entity ->
                                SubtitlePriorityEvaluator.isMatchingLanguage(entity.lang, targetLang)
                            }
                            if (targetEntity != null) {
                                return@withContext FallbackDiscoveryResult.Bypassed(
                                    matchedLang = targetEntity.lang,
                                    source = api.javaClass.simpleName.ifBlank { "SubtitleAPI" }
                                )
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            logW(TAG, "SubtitleAPI.search failed for targetLang=$cleanTarget: ${t.message}")
                        }
                    }
                }

                // Phase 2: No provider holds targetLang; collect non-target fallback candidates
                for ((api, auth) in providers) {
                    if (!currentCoroutineContext().isActive) break
                    for (langCode in fallbackLangs) {
                        if (!currentCoroutineContext().isActive) break
                        try {
                            val query = AbstractSubtitleEntities.SubtitleSearch(
                                query = cleanTitle.ifBlank { imdbId ?: "" },
                                lang = langCode,
                                imdbId = imdbId,
                                epNumber = episodeNumber,
                                seasonNumber = seasonNumber,
                                year = year
                            )
                            val entities = (api.search(auth, query) ?: emptyList()).filter { entity ->
                                seasonNumber == null || entity.seasonNumber == null || entity.seasonNumber == seasonNumber
                            }
                            if (entities.isEmpty()) continue

                            // Check immediate Target Language Bypass on search results
                            val targetEntity = entities.firstOrNull { entity ->
                                SubtitlePriorityEvaluator.isMatchingLanguage(entity.lang, targetLang)
                            }
                            if (targetEntity != null) {
                                return@withContext FallbackDiscoveryResult.Bypassed(
                                    matchedLang = targetEntity.lang,
                                    source = api.javaClass.simpleName.ifBlank { "SubtitleAPI" }
                                )
                            }

                            // Resolve top matching entities to downloadable SubtitleFiles
                            val topEntities = entities.take(2)
                            for (entity in topEntities) {
                                if (!currentCoroutineContext().isActive) break
                                try {
                                    val resource = api.resource(auth, entity)
                                    for (res in resource.getSubtitles()) {
                                        val resUrl = res.url.trim()
                                        if (resUrl.isBlank() || !seenUrls.add(resUrl)) continue
                                        val label = entity.lang.ifBlank { res.name?.ifBlank { langCode } ?: langCode }
                                        val sf = SubtitleFile(lang = label, url = resUrl)
                                        if (!entity.headers.isNullOrEmpty()) {
                                            sf.headers = entity.headers
                                        }
                                        val score = SubtitlePriorityEvaluator.calculateScore(
                                            subFile = sf,
                                            targetLang = targetLang,
                                            priorityMode = sourcePriorityMode,
                                            customList = customPriorityList
                                        )
                                        if (score == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
                                            return@withContext FallbackDiscoveryResult.Bypassed(
                                                matchedLang = label,
                                                source = api.javaClass.simpleName.ifBlank { "SubtitleAPI" }
                                            )
                                        }
                                        collected.add(sf)
                                    }
                                } catch (t: Throwable) {
                                    if (t is CancellationException) throw t
                                    logW(TAG, "SubtitleAPI.resource failed: ${t.message}")
                                }
                            }
                            if (collected.isNotEmpty()) break
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            logW(TAG, "SubtitleAPI.search failed for lang=$langCode: ${t.message}")
                        }
                    }
                    if (collected.isNotEmpty()) break
                }
                null
            }
        }

        if (timedResult != null) return timedResult
        return if (collected.isNotEmpty()) {
            FallbackDiscoveryResult.CandidatesFound(collected, "AccountManager.subtitleProviders")
        } else {
            FallbackDiscoveryResult.NoneFound
        }
    }

    private fun buildSearchLanguages(
        targetLang: String,
        priorityMode: String,
        customList: List<String>
    ): List<String> {
        val langs = LinkedHashSet<String>()
        val cleanTarget = targetLang.trim().lowercase(Locale.ROOT)
        if (cleanTarget.isNotBlank()) langs.add(cleanTarget)
        when (priorityMode) {
            TranslatorConfig.PRIORITY_ANIME_FIRST -> {
                langs.add("ja")
                langs.add("en")
            }
            TranslatorConfig.PRIORITY_CUSTOM -> {
                customList.forEach { if (it.isNotBlank()) langs.add(it.trim().lowercase(Locale.ROOT)) }
                langs.add("en")
            }
            else -> {
                langs.add("en")
                langs.add("ja")
            }
        }
        return langs.toList()
    }

    private fun resolveCoreSubtitleApis(): List<Pair<SubtitleAPI, AuthData?>> {
        val list = mutableListOf<Pair<SubtitleAPI, AuthData?>>()
        try {
            val amClass = Class.forName("com.lagradost.cloudstream3.syncproviders.AccountManager")
            val companionField = amClass.getDeclaredField("Companion")
            companionField.isAccessible = true
            val companion = companionField.get(null) ?: return emptyList()
            val repos = companion.javaClass.getMethod("getSubtitleProviders").invoke(companion) as? Array<*>
                ?: return emptyList()
            for (repo in repos) {
                if (repo == null) continue
                try {
                    val requiresLogin = (repo.javaClass.getMethod("getRequiresLogin").invoke(repo) as? Boolean) ?: false
                    val authData = repo.javaClass.getMethod("authData").invoke(repo) as? AuthData
                    if (requiresLogin && authData == null) continue
                    val api = repo.javaClass.getMethod("getApi").invoke(repo) as? SubtitleAPI ?: continue
                    list.add(api to authData)
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            logW(TAG, "AccountManager.getSubtitleProviders reflection skipped: ${t.message}")
        }
        return list
    }

    /**
     * Full source subtitle fallback coordinator invoked when baseApi.loadLinks produces 0 subtitle candidates.
     */
    suspend fun discoverFallbackSubtitles(
        capturedLinks: List<ExtractorLink>,
        targetLang: String,
        title: String?,
        year: Int? = null,
        imdbId: String? = null,
        sourcePriorityMode: String = TranslatorConfig.sourcePriorityMode,
        customPriorityList: List<String> = TranslatorConfig.getCustomPriorityLanguages(),
        httpClient: OkHttpClient = defaultHttpClient,
        playerVmOrActivityOverride: Any? = null,
        apisOverride: List<Pair<SubtitleAPI, AuthData?>>? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): FallbackDiscoveryResult {
        // Tier B1: Check if player already has EMBEDDED_IN_VIDEO target-language track
        val embeddedTarget = hasEmbeddedTargetLanguageTrack(targetLang, playerVmOrActivityOverride)
        if (embeddedTarget != null) {
            return FallbackDiscoveryResult.Bypassed(embeddedTarget, "EMBEDDED_IN_VIDEO")
        }

        // Tier A: Scan up to 3 priority HLS (.m3u8) / DASH (.mpd) links
        val priorityLinks = capturedLinks
            .filter { link ->
                val url = link.url.trim()
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
            }
            .filter { link ->
                val lowerUrl = link.url.substringBefore('?').lowercase(Locale.ROOT)
                link.isM3u8 || link.isDash || lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".mpd") ||
                    link.url.contains(".m3u8", ignoreCase = true) || link.url.contains(".mpd", ignoreCase = true)
            }
            .sortedByDescending { it.quality }
            .take(3)

        val manifestCandidates = mutableListOf<SubtitleFile>()
        for (link in priorityLinks) {
            if (!currentCoroutineContext().isActive) break
            val reqHeaders = LinkedHashMap<String, String>()
            reqHeaders.putAll(link.headers)
            if (link.referer.isNotBlank()) {
                reqHeaders.putIfAbsent("Referer", link.referer)
            }

            val manifestText = fetchTextBounded(link.url, reqHeaders, httpClient, timeoutMs = 3500L) ?: continue
            val isDash = link.isDash || link.url.contains(".mpd", ignoreCase = true) ||
                manifestText.contains("<MPD", ignoreCase = true)
            val parsed = if (isDash) {
                parseDashSubtitles(manifestText, link.url, reqHeaders)
            } else {
                parseHlsSubtitles(manifestText, link.url, reqHeaders)
            }
            if (parsed.isNotEmpty()) {
                val best = SubtitlePriorityEvaluator.selectBestSubtitle(
                    candidates = parsed,
                    targetLang = targetLang,
                    priorityMode = sourcePriorityMode,
                    customList = customPriorityList
                )
                if (best != null && SubtitlePriorityEvaluator.calculateScore(
                        best, targetLang, sourcePriorityMode, customPriorityList
                    ) == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
                ) {
                    return FallbackDiscoveryResult.Bypassed(best.lang, "StreamManifest")
                }
                manifestCandidates.addAll(parsed)
                break
            }
        }

        if (manifestCandidates.isNotEmpty()) {
            return FallbackDiscoveryResult.CandidatesFound(manifestCandidates, "StreamManifest")
        }

        // Tier B2: Core AccountManager.subtitleProviders fallback
        if (!title.isNullOrBlank() || !imdbId.isNullOrBlank()) {
            return querySubtitleApiProviders(
                title = title.orEmpty(),
                year = year,
                imdbId = imdbId,
                targetLang = targetLang,
                sourcePriorityMode = sourcePriorityMode,
                customPriorityList = customPriorityList,
                apisOverride = apisOverride,
                timeoutMs = 4000L,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber
            )
        }

        return FallbackDiscoveryResult.NoneFound
    }

    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] [INFO] $msg") }
    }

    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) { System.err.println("[$tag] [WARN] $msg") }
    }
}
