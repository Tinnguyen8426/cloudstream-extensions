@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.subtitles.ai.session

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslationClient
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslatorFactory
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleReconstructor
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Production-grade AI Translation Session coordinating candidate intake, Target Language Bypass,
 * progressive chunk translation via a bounded 4-worker coroutine pool, Strategy A batch delivery,
 * Strategy B skeleton fallback, and ExoPlayer loopback streaming.
 */
class AITranslationSession(
    val mediaPayload: String,
    val targetLang: String = TranslatorConfig.targetLanguage,
    val playerSubtitleCallback: (SubtitleFile) -> Unit,
    val deliveryStrategy: String = TranslatorConfig.deliveryStrategy,
    val context: Context? = null,
    val clientOverride: AITranslationClient? = null
) {
    companion object {
        private const val TAG = "AITranslationSession"
        private const val MAX_CONCURRENT_CHUNK_WORKERS = 4
        private const val INTAKE_DEBOUNCE_MS = 200L
        private const val SKELETON_THRESHOLD_CHUNKS = 20 // Activate Strategy B if > 600 cues in auto mode
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var translationJob: Job? = null

    @Volatile
    var isCancelled: Boolean = false
        private set

    @Volatile
    var isTargetLanguageBypassed: Boolean = false
        private set

    val isBypassed: Boolean
        get() = isTargetLanguageBypassed

    @Volatile
    var isCompleted: Boolean = false
        private set

    @Volatile
    var currentBestScore: Int = Int.MIN_VALUE
        private set

    @Volatile
    var currentProcessingUrl: String? = null
        private set

    private val candidateList = CopyOnWriteArrayList<SubtitleFile>()
    private val emittedUrls = ConcurrentHashMap.newKeySet<String>()

    /**
     * Intake endpoint for newly discovered subtitle streams from base providers or enrichers.
     * Evaluates priority score, enforces Target Language Bypass Rule, and dynamically promotes
     * higher-ranked candidates.
     */
    fun queueCandidate(subFile: SubtitleFile) {
        if (isCancelled || isTargetLanguageBypassed) return

        val score = SubtitlePriorityEvaluator.calculateScore(
            subFile = subFile,
            targetLang = targetLang,
            priorityMode = TranslatorConfig.sourcePriorityMode,
            customList = TranslatorConfig.getCustomPriorityLanguages()
        )

        // 1. Target Language Bypass Rule: 0 redundant requests if target language already exists
        if (score == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            logI(TAG, "Target language bypass triggered for '${subFile.lang}'. Skipping AI translation.")
            isTargetLanguageBypassed = true
            cancelCurrentJob()
            candidateList.clear()
            return
        }

        candidateList.add(subFile)

        // 2. Dynamic Candidate Promotion: Higher score preempts active lower-priority job
        synchronized(this) {
            if (score > currentBestScore) {
                logI(TAG, "Promoting subtitle candidate '${subFile.lang}' (score: $score > current: $currentBestScore)")
                currentBestScore = score
                cancelCurrentJob()
                translationJob = sessionScope.launch {
                    processCandidate(subFile)
                }
            }
        }
    }

    /**
     * Explicitly starts a translation session for a candidate file.
     */
    fun startSession(subtitleFile: SubtitleFile): Job {
        cancelCurrentJob()
        val job = sessionScope.launch {
            processCandidate(subtitleFile)
        }
        translationJob = job
        return job
    }

    /**
     * Cancels any running translation job without cancelling the session scope entirely.
     */
    private fun cancelCurrentJob() {
        try {
            translationJob?.cancel()
        } catch (_: Throwable) {}
        translationJob = null
    }

    /**
     * Completely cancels the active session and all underlying coroutines.
     * Called when loadLinks starts for new media or playback is aborted.
     */
    fun cancelSession() {
        if (isCancelled) return
        isCancelled = true
        cancelCurrentJob()
        try {
            sessionScope.cancel()
        } catch (_: Throwable) {}
        candidateList.clear()
        logI(TAG, "Translation session cancelled cleanly.")
    }

    /**
     * Core processing pipeline executing download, cache validation, chunking,
     * translation, and playback delivery.
     */
    private suspend fun processCandidate(candidate: SubtitleFile) = withContext(Dispatchers.IO) {
        currentProcessingUrl = candidate.url

        // Brief debounce to allow higher-priority candidates from concurrent enrichers to arrive
        delay(INTAKE_DEBOUNCE_MS)
        if (!isActive || isCancelled) return@withContext

        logI(TAG, "Starting translation pipeline for candidate '${candidate.lang}' (${candidate.url})")

        // Step 1: Download raw subtitle bytes
        val rawBytes = fetchSubtitleBytes(candidate.url)
        if (rawBytes == null || rawBytes.isEmpty()) {
            logW(TAG, "Failed to download subtitle content from: ${candidate.url}")
            return@withContext
        }
        if (!isActive || isCancelled) return@withContext

        // Step 2: Multi-charset normalization
        val rawContent = decodeSubtitleText(rawBytes)
        if (rawContent.isBlank()) {
            logW(TAG, "Decoded subtitle content is blank from: ${candidate.url}")
            return@withContext
        }

        // Step 3: Compute SHA-256 Composite Cache Key
        val effectiveModel = TranslatorConfig.getEffectiveModel()
        val cacheKey = SubtitleCacheManager.buildCacheKey(
            content = rawContent,
            targetLang = targetLang,
            provider = TranslatorConfig.provider,
            model = effectiveModel,
            glossary = TranslatorConfig.customGlossary
        )

        // Step 4: Tier 1 Cache Verification (0ms delivery on hit)
        val cachedFile = SubtitleCacheManager.getFullFile(context, cacheKey)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0L) {
            logI(TAG, "Tier 1 Full Cache HIT for key: $cacheKey")
            val streamUrl = AITranslatorLocalServer.registerSubtitle(cachedFile)
            val trackLabel = buildAuthoritativeTrackLabel(targetLang, effectiveModel)
            emitTrack(trackLabel, streamUrl)
            isCompleted = true
            return@withContext
        }

        // Step 5: Parse Subtitle into Cues
        val cues = SubtitleParser.parse(rawContent)
        if (cues.isEmpty()) {
            logW(TAG, "Subtitle parser extracted 0 valid cues from content.")
            return@withContext
        }

        // Step 6: Load Tier 2 Progressive Chunk Cache
        val existingTranslations = SubtitleCacheManager.loadChunks(context, cacheKey).toMutableMap()
        val chunkSize = TranslatorConfig.chunkSize.coerceIn(10, 80)
        val chunks = cues.chunked(chunkSize)

        // Step 7: Determine Delivery Strategy (Strategy A vs Strategy B)
        val isStrategyB = isStrategyBActive(chunks.size)
        logI(TAG, "Executing ${if (isStrategyB) "Strategy B (Progressive Skeleton)" else "Strategy A (Batch Delivery)"} across ${chunks.size} chunks.")

        val client = clientOverride ?: AITranslatorFactory.createClient(
            provider = TranslatorConfig.provider,
            apiKey = TranslatorConfig.apiKey,
            baseUrl = TranslatorConfig.customBaseUrl,
            modelName = effectiveModel
        )

        val semaphore = Semaphore(MAX_CONCURRENT_CHUNK_WORKERS)
        var skeletonEmitted = false

        // Strategy B Early Trigger: If Chunk 0 is already in Tier 2 cache, emit skeleton immediately
        if (isStrategyB && chunks.isNotEmpty()) {
            val chunk0Cues = chunks[0]
            val chunk0Cached = chunk0Cues.all { existingTranslations.containsKey(it.index) }
            if (chunk0Cached) {
                emitDraftSkeleton(cues, existingTranslations, cacheKey)
                skeletonEmitted = true
            }
        }

        // Translate chunks using bounded 4-worker coroutine pool
        val deferredJobs = chunks.mapIndexed { index, chunkCues ->
            async {
                if (!isActive || isCancelled) return@async

                // Check if chunk is already fully cached
                val isChunkFullyCached = chunkCues.all { existingTranslations.containsKey(it.index) }
                if (isChunkFullyCached) {
                    if (isStrategyB && index == 0 && !skeletonEmitted) {
                        emitDraftSkeleton(cues, existingTranslations, cacheKey)
                        skeletonEmitted = true
                    }
                    return@async
                }

                // Acquire concurrency permit
                semaphore.withPermit {
                    if (!isActive || isCancelled) return@withPermit

                    val translatedChunk = translateChunkWithRetry(
                        client = client,
                        chunkIndex = index,
                        chunkCues = chunkCues,
                        sourceLang = candidate.lang,
                        targetLang = targetLang,
                        glossary = TranslatorConfig.customGlossary
                    )

                    if (translatedChunk.isNotEmpty()) {
                        synchronized(existingTranslations) {
                            existingTranslations.putAll(translatedChunk)
                        }
                        SubtitleCacheManager.saveChunk(context, cacheKey, index, translatedChunk)
                    }

                    // Strategy B Skeleton Trigger: Emit as soon as Chunk 0 finishes
                    if (isStrategyB && index == 0 && !skeletonEmitted) {
                        emitDraftSkeleton(cues, existingTranslations, cacheKey)
                        skeletonEmitted = true
                    }
                }
            }
        }

        // Await all chunk coroutines
        deferredJobs.awaitAll()

        if (!isActive || isCancelled) return@withContext

        // Step 8: Full Document Assembly & Tier 1 Save
        val reconciledAll = SubtitleReconciler.autoHealingReconcile(cues, existingTranslations)
        val fullSrt = SubtitleReconstructor.buildSubRip(cues, reconciledAll)

        val fullFile = SubtitleCacheManager.saveFullFile(context, cacheKey, fullSrt)
        val fileToStream = if (isStrategyB) {
            // Also write to <cacheKey>_full.srt for Strategy B compliance and stream it
            try {
                SubtitleCacheManager.saveFullFile(context, "${cacheKey}_full", fullSrt)
            } catch (_: Throwable) {
                fullFile
            }
        } else {
            fullFile
        }

        val streamUrl = AITranslatorLocalServer.registerSubtitle(fileToStream)
        val authoritativeLabel = if (isStrategyB) {
            "[AI] ${getTargetLanguageDisplayName(targetLang)} (Hoàn tất)"
        } else {
            buildAuthoritativeTrackLabel(targetLang, effectiveModel)
        }

        logI(TAG, "Translation completed successfully. Emitting authoritative track: '$authoritativeLabel'")
        emitTrack(authoritativeLabel, streamUrl)
        isCompleted = true
    }

    /**
     * Translates a chunk of cues with exponential backoff retries and auto-healing desync reconciliation.
     * If all attempts fail, safely falls back to source dialogue text (cue.rawText).
     */
    private suspend fun translateChunkWithRetry(
        client: AITranslationClient,
        chunkIndex: Int,
        chunkCues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
        glossary: String?
    ): Map<Int, String> {
        var attempts = 0
        val maxAttempts = 3

        while (attempts < maxAttempts) {
            attempts++
            try {
                val result = client.translateChunk(
                    cues = chunkCues,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    customGlossary = glossary
                )

                if (result.isSuccess && result.translations.isNotEmpty()) {
                    return SubtitleReconciler.autoHealingReconcile(chunkCues, result.translations)
                }

                if (result.isQuotaExhausted) {
                    logW(TAG, "Quota exhausted during chunk $chunkIndex translation. Falling back to source text.")
                    return SubtitleReconciler.autoHealingReconcile(chunkCues, emptyMap())
                }

                if (!result.retryable || attempts >= maxAttempts) {
                    logW(TAG, "Chunk $chunkIndex failed (non-retryable or max attempts): ${result.errorMessage}")
                    return SubtitleReconciler.autoHealingReconcile(chunkCues, emptyMap())
                }

                delay(1000L * attempts)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (attempts >= maxAttempts) {
                    logW(TAG, "Chunk $chunkIndex exception after $attempts attempts: ${e.message}")
                    return SubtitleReconciler.autoHealingReconcile(chunkCues, emptyMap())
                }
                delay(1000L * attempts)
            }
        }
        return SubtitleReconciler.autoHealingReconcile(chunkCues, emptyMap())
    }

    /**
     * Strategy B: Emits the Full Cue Skeleton as soon as Chunk 1 completes.
     * Cues 1-30 are translated; Cues 31-N fall back to original source text.
     * ExoPlayer indexes all timecodes without dialogue dropping out.
     */
    private fun emitDraftSkeleton(
        allCues: List<SubtitleCue>,
        currentTranslations: Map<Int, String>,
        cacheKey: String
    ) {
        try {
            val skeletonSrt = SubtitleReconstructor.buildSubRip(allCues, currentTranslations)
            val v1File = SubtitleCacheManager.saveFullFile(context, "${cacheKey}_v1", skeletonSrt)
            val v1Url = AITranslatorLocalServer.registerSubtitle(v1File)
            val draftLabel = "[AI] ${getTargetLanguageDisplayName(targetLang)} (Đang dịch...)"
            logI(TAG, "Emitting Strategy B draft skeleton track: '$draftLabel' -> $v1Url")
            emitTrack(draftLabel, v1Url)
        } catch (e: Throwable) {
            logW(TAG, "Failed to emit Strategy B draft skeleton: ${e.message}")
        }
    }

    /**
     * Emits a track safely to playerSubtitleCallback, guarding against exceptions and duplicate emissions.
     */
    private fun emitTrack(label: String, url: String) {
        if (isCancelled) return
        val key = "$label|$url"
        if (emittedUrls.add(key)) {
            try {
                playerSubtitleCallback(SubtitleFile(lang = label, url = url))
            } catch (t: Throwable) {
                logW(TAG, "Error invoking playerSubtitleCallback: ${t.message}")
            }
        }
    }

    /**
     * Determines whether Strategy B should be used based on config or chunk count.
     */
    private fun isStrategyBActive(totalChunks: Int): Boolean {
        val strat = deliveryStrategy.lowercase(Locale.ROOT).trim()
        return when {
            strat == TranslatorConfig.DELIVERY_STRATEGY_B || strat == TranslatorConfig.STRATEGY_SKELETON -> true
            strat == TranslatorConfig.DELIVERY_STRATEGY_A || strat == TranslatorConfig.STRATEGY_BATCH -> false
            else -> totalChunks > SKELETON_THRESHOLD_CHUNKS // Auto: long media (>600 cues) uses Strategy B
        }
    }

    /**
     * Ingests subtitle bytes from HTTP/HTTPS, loopback, or file URIs.
     */
    private suspend fun fetchSubtitleBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    val req = okhttp3.Request.Builder().url(url).build()
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .readTimeout(java.time.Duration.ofSeconds(15))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.bytes() else null
                    }
                } catch (_: Throwable) {
                    URL(url).openStream().use { it.readBytes() }
                }
            } else if (url.startsWith("file://")) {
                File(URI(url)).readBytes()
            } else {
                val f = File(url)
                if (f.exists()) f.readBytes() else null
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logW(TAG, "Failed to fetch subtitle bytes from $url: ${e.message}")
            null
        }
    }

    /**
     * Attempts decoding subtitle byte payloads using progressive charset priority:
     * BOM detection (UTF-16LE, UTF-16BE, UTF-8 BOM) -> UTF-8 -> Shift-JIS / Windows-31J -> Windows-1252 -> UTF-8 fallback.
     */
    private fun decodeSubtitleText(bytes: ByteArray): String {
        // Step 1: Detect explicit Byte Order Mark (BOM) first (O(1) unambiguous check)
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, Charsets.UTF_16BE)
            }
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }

        // Step 2: Try UTF-8 (strict)
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = decoder.decode(ByteBuffer.wrap(bytes))
            val text = charBuffer.toString()
            if (text.isNotBlank()) return text
        } catch (_: Throwable) {}

        // Step 3: Try Shift-JIS / Windows-31J (strict)
        try {
            val charset = Charset.forName("Windows-31J")
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = decoder.decode(ByteBuffer.wrap(bytes))
            val text = charBuffer.toString()
            if (text.isNotBlank()) return text
        } catch (_: Throwable) {}

        // Step 4: Try Windows-1252
        try {
            val charset = Charset.forName("windows-1252")
            return String(bytes, charset)
        } catch (_: Throwable) {}

        // Step 5: Fallback to default UTF-8
        return String(bytes, Charsets.UTF_8)
    }

    private fun buildAuthoritativeTrackLabel(lang: String, model: String): String {
        val displayName = getTargetLanguageDisplayName(lang)
        return "$displayName [AI: $model]"
    }

    private fun getTargetLanguageDisplayName(langCode: String): String {
        return when (langCode.lowercase(Locale.ROOT).trim()) {
            "vi", "vie" -> "Tiếng Việt"
            "en", "eng" -> "English"
            "ja", "jpn" -> "日本語"
            "es", "spa" -> "Español"
            "fr", "fra" -> "Français"
            "zh", "zho" -> "中文"
            "ko", "kor" -> "한국어"
            "de", "deu" -> "Deutsch"
            else -> Locale.forLanguageTag(langCode).getDisplayName(Locale.ROOT).ifBlank { langCode.uppercase() }
        }
    }

    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] [INFO] $msg") }
    }

    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) { System.err.println("[$tag] [WARN] $msg") }
    }
}
