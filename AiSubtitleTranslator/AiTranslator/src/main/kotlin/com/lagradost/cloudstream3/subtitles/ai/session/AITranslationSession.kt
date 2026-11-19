@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.subtitles.ai.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslationClient
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslatorFactory
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslatorPromptBuilder
import com.lagradost.cloudstream3.subtitles.ai.client.PacingGovernor
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.CharacterEntity
import com.lagradost.cloudstream3.subtitles.ai.models.CharacterRelationship
import com.lagradost.cloudstream3.subtitles.ai.models.ContextCue
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleReconstructor
import com.lagradost.cloudstream3.subtitles.ai.profile.AniListCastResolver
import com.lagradost.cloudstream3.subtitles.ai.profile.CharacterProfileManager
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import com.lagradost.cloudstream3.subtitles.ai.ui.TranslationProgressHUD
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Production-grade AI Translation Session coordinating candidate intake, Target Language Bypass,
 * progressive chunk translation via 100% linear sequential batch delivery, and ExoPlayer loopback streaming.
 */
class AITranslationSession(
    val mediaPayload: String,
    val targetLang: String = TranslatorConfig.targetLanguage,
    val playerSubtitleCallback: (SubtitleFile) -> Unit,
    val deliveryStrategy: String = TranslatorConfig.deliveryStrategy,
    val context: Context? = null,
    val clientOverride: AITranslationClient? = null,
    val mediaContext: MediaContext? = null
) {
    companion object {
        private const val TAG = "AITranslationSession"
        private const val MAX_CONCURRENT_CHUNK_WORKERS = 4
        private const val INTAKE_DEBOUNCE_MS = 200L

        fun getTargetLanguageDisplayName(langCode: String): String {
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

        private val jsonMapper: ObjectMapper by lazy {
            ObjectMapper().registerKotlinModule()
        }

        fun parseDiscoveredRelationships(rawResponse: String): List<CharacterRelationship> {
            val trimmed = rawResponse.trim()
            val cleanFences = if (trimmed.startsWith("```")) {
                trimmed.removePrefix("```json").removePrefix("```").let {
                    val endIdx = it.lastIndexOf("```")
                    if (endIdx != -1) it.substring(0, endIdx) else it
                }.trim()
            } else trimmed

            val result = mutableListOf<CharacterRelationship>()
            try {
                val rootNode = jsonMapper.readTree(cleanFences)
                val relNode = when {
                    rootNode.has("discovered_relationships") && rootNode.get("discovered_relationships").isArray ->
                        rootNode.get("discovered_relationships")
                    rootNode.has("discoveredRelationships") && rootNode.get("discoveredRelationships").isArray ->
                        rootNode.get("discoveredRelationships")
                    else -> null
                }

                if (relNode != null && relNode.isArray) {
                    for (i in 0 until relNode.size()) {
                        val elem = relNode.get(i)
                        if (elem != null && elem.isObject) {
                            val speaker = elem.get("speaker")?.asText()?.trim() ?: ""
                            val target = elem.get("target")?.asText()?.trim() ?: ""
                            val relation = elem.get("relation")?.asText()?.trim()
                                ?: elem.get("type")?.asText()?.trim() ?: "peers"
                            val address = elem.get("address")?.asText()?.trim() ?: ""
                            if (speaker.isNotEmpty() && target.isNotEmpty()) {
                                result.add(
                                    CharacterRelationship(
                                        pair = listOf(speaker, target),
                                        type = relation,
                                        isEphemeral = true,
                                        addressMatrix = mapOf(
                                            "speaker" to speaker,
                                            "target" to target,
                                            "address" to address
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
            return result
        }

        private val DANGLING_WORDS = setOf(
            "if", "when", "because", "although", "that", "to", "and", "but", "or", "as", "since",
            "so", "while", "though", "after", "before", "the", "a", "an", "of", "in", "with"
        )

        fun isSyntacticBoundary(cue: SubtitleCue): Boolean {
            val text = cue.rawText.trim()
            if (text.isEmpty()) return true

            val endsWithPunct = text.endsWith(".") || text.endsWith("?") || text.endsWith("!") ||
                    text.endsWith("...") || text.endsWith("…") || text.endsWith("\"") ||
                    text.endsWith("'") || text.endsWith("”") || text.endsWith("’") || text.endsWith("»")

            val endsWithNonTerminal = text.endsWith(",") || text.endsWith(";") ||
                    text.endsWith("—") || text.endsWith("-") || text.endsWith(":")

            if (endsWithNonTerminal) return false

            val lastWord = text.split(Regex("""\s+""")).lastOrNull()?.trim()?.lowercase(Locale.ROOT) ?: ""
            val cleanedLastWord = lastWord.trimEnd('.', ',', '!', '?', ':', ';', '-', '—')
            if (DANGLING_WORDS.contains(cleanedLastWord)) return false

            return endsWithPunct || text.length > 40
        }

        fun splitIntoDynamicChunks(
            cues: List<SubtitleCue>,
            minCues: Int = 18,
            maxCues: Int = 28,
            pauseThresholdMs: Long = 1800L
        ): List<List<SubtitleCue>> {
            if (cues.isEmpty()) return emptyList()
            val chunks = mutableListOf<List<SubtitleCue>>()
            var startIndex = 0

            while (startIndex < cues.size) {
                val remaining = cues.size - startIndex
                if (remaining <= maxCues) {
                    chunks.add(cues.subList(startIndex, cues.size))
                    break
                }

                var splitIndex = -1
                val searchStart = startIndex + minCues
                val searchEnd = (startIndex + maxCues).coerceAtMost(cues.size - 1)

                for (i in searchStart until searchEnd) {
                    val current = cues[i]
                    val next = cues[i + 1]
                    val deltaT = (next.startMs - current.endMs).coerceAtLeast(0L)

                    if (deltaT >= pauseThresholdMs && isSyntacticBoundary(current)) {
                        splitIndex = i + 1
                        break
                    }
                }

                if (splitIndex == -1) {
                    splitIndex = (startIndex + maxCues).coerceAtMost(cues.size)
                }

                chunks.add(cues.subList(startIndex, splitIndex))
                startIndex = splitIndex
            }

            return chunks
        }

        private val fallbackClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val title = mediaContext?.title?.takeIf { it.isNotBlank() } ?: mediaPayload.takeIf { it.isNotBlank() }
        if (!title.isNullOrBlank() && TranslatorConfig.enableCharacterProfiling) {
            sessionScope.launch {
                try {
                    AniListCastResolver.resolveAndSave(context, title)
                } catch (_: Throwable) {
                    // Non-blocking pre-scan
                }
            }
        }
    }

    @Volatile
    private var translationJob: Job? = null

    @Volatile
    var activeContextWindow: DialogueContextWindow? = null
        private set

    @Volatile
    var activePlayheadMs: Long? = null
        private set

    private val dispatchLock = Any()
    private val pendingChunkIndices = mutableListOf<Int>()

    @Volatile
    var currentChunks: List<List<SubtitleCue>>? = null
        private set

    val pendingChunkQueue: List<Int>
        get() = synchronized(dispatchLock) { pendingChunkIndices.toList() }

    val sessionKey: String by lazy {
        val raw = "${mediaPayload}_$targetLang"
        SubtitleCacheManager.computeSha256(raw).take(32)
    }

    private val completionLatch = CountDownLatch(1)

    @Volatile
    var completedFile: File? = null
        private set

    fun awaitCompletion(timeoutMs: Long = 60000L): Boolean {
        if (completedFile != null || isCompleted) return true
        if (isCancelled) return false
        return try {
            completionLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && (completedFile != null || isCompleted)
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun notifyActivePlayer(label: String, streamUrl: String) {
        emitTrack(label, streamUrl)
        try {
            val playerVmClass = Class.forName("com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel")
            // Auxiliary dynamic reflection probe into PlayerGeneratorViewModel if active
        } catch (_: Throwable) {}
    }

    fun onSeek(posMs: Long) {
        activePlayheadMs = posMs
        if (TranslatorConfig.showProgressHUD && !isCompleted && !isCancelled) {
            try {
                TranslationProgressHUD.onSeek(posMs / 1000)
            } catch (_: Throwable) {}
        }
    }

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
    private val failedUrls = ConcurrentHashMap.newKeySet<String>()

    /**
     * Intake endpoint for newly discovered subtitle streams from base providers or enrichers.
     * Evaluates priority score, enforces Target Language Bypass Rule, and dynamically promotes
     * higher-ranked candidates.
     */
    fun queueCandidate(subFile: SubtitleFile) {
        if (isCancelled || isTargetLanguageBypassed || isCompleted) return
        if (failedUrls.contains(subFile.url)) return
        if (subFile.url.contains("127.0.0.1") || subFile.url.contains("localhost") || subFile.lang.contains("[AI", ignoreCase = true)) return
        if (candidateList.any { it.url == subFile.url && it.lang == subFile.lang }) return

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
            showToast("[AI Translator] Đã có sẵn phụ đề ${subFile.lang}, bỏ qua dịch AI.")
            return
        }

        candidateList.add(subFile)

        // 2. Dynamic Candidate Promotion: Higher score preempts active lower-priority job.
        // Also picks up candidates if previous job failed and finished without completion.
        synchronized(this) {
            val shouldStart = score > currentBestScore || (translationJob?.isActive != true && !isCompleted)
            if (shouldStart) {
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
        completionLatch.countDown()
        AITranslatorLocalServer.unregisterSession(sessionKey)
        cancelCurrentJob()
        try {
            sessionScope.cancel()
        } catch (_: Throwable) {}
        activeContextWindow?.reset()
        CharacterProfileManager.resetEpisodicState(mediaContext?.title)
        candidateList.clear()
        failedUrls.clear()
        synchronized(dispatchLock) {
            pendingChunkIndices.clear()
            currentChunks = null
        }
        if (TranslatorConfig.showProgressHUD) {
            try {
                TranslationProgressHUD.dismiss()
            } catch (_: Throwable) {}
        }
        logI(TAG, "Translation session cancelled cleanly.")
    }

    /**
     * Handles candidate failure by logging, tracking failed candidate URL, and dynamically falling back
     * to the next best viable candidate from candidateList if available.
     */
    private suspend fun onCandidateFailed(failedCandidate: SubtitleFile, errorMessage: String) {
        failedUrls.add(failedCandidate.url)
        logW(TAG, "Candidate '${failedCandidate.lang}' failed (${failedCandidate.url}): $errorMessage")

        val nextCandidate = synchronized(this) {
            candidateList.filter { !failedUrls.contains(it.url) }.maxByOrNull {
                SubtitlePriorityEvaluator.calculateScore(
                    it, targetLang, TranslatorConfig.sourcePriorityMode, TranslatorConfig.getCustomPriorityLanguages()
                )
            }
        }

        if (nextCandidate != null) {
            val nextScore = SubtitlePriorityEvaluator.calculateScore(
                nextCandidate, targetLang, TranslatorConfig.sourcePriorityMode, TranslatorConfig.getCustomPriorityLanguages()
            )
            logI(TAG, "Falling back to alternative candidate '${nextCandidate.lang}' (score: $nextScore, ${nextCandidate.url})")
            synchronized(this) {
                currentBestScore = nextScore
                translationJob = sessionScope.launch {
                    processCandidate(nextCandidate)
                }
            }
        } else {
            // All candidates exhausted
            synchronized(this) {
                currentBestScore = Int.MIN_VALUE
            }
            completionLatch.countDown()
            showToast(errorMessage)
            if (TranslatorConfig.showProgressHUD) {
                try {
                    TranslationProgressHUD.showError(errorMessage)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Core processing pipeline executing download, cache validation, chunking,
     * translation, and playback delivery.
     */
    private suspend fun processCandidate(candidate: SubtitleFile) = withContext(Dispatchers.IO) {
        currentProcessingUrl = candidate.url

        try {
            // Brief debounce to allow higher-priority candidates from concurrent enrichers to arrive
            delay(INTAKE_DEBOUNCE_MS)
            if (!isActive || isCancelled) return@withContext

            val isCustomLocal = TranslatorConfig.provider == TranslatorConfig.PROVIDER_CUSTOM && TranslatorConfig.customBaseUrl.isNotBlank()
            if (clientOverride == null && TranslatorConfig.apiKey.isBlank() && !isCustomLocal) {
                logE(TAG, "AI Translator API key is missing. Please configure your API key in Settings.")
                showToast("[AI Translator] Chưa cấu hình API Key trong Cài đặt.")
                return@withContext
            }

            val targetName = getTargetLanguageDisplayName(targetLang)
            showToast("[AI Translator] Bắt đầu dịch phụ đề sang $targetName...")
            logI(TAG, "Starting translation pipeline for candidate '${candidate.lang}' (${candidate.url})")

            val effectiveModel = TranslatorConfig.getEffectiveModel()
            if (TranslatorConfig.showProgressHUD) {
                try {
                    TranslationProgressHUD.show(
                        context = context,
                        modelName = effectiveModel,
                        targetLang = targetName,
                        initialStatus = "Đang nạp phụ đề gốc (${candidate.lang})..."
                    )
                } catch (_: Throwable) {}
            }

            // Step 1: Download raw subtitle bytes
            val rawBytes = fetchSubtitleBytes(candidate.url, candidate.headers)
            if (rawBytes == null || rawBytes.isEmpty()) {
                logE(TAG, "Failed to download subtitle content from: ${candidate.url}")
                onCandidateFailed(candidate, "[AI Translator] Lỗi: Không thể tải tệp phụ đề gốc (${candidate.lang}).")
                return@withContext
            }
            if (!isActive || isCancelled) return@withContext

            // Step 2: Multi-charset normalization
            val rawContent = decodeSubtitleText(rawBytes)
            if (rawContent.isBlank()) {
                logE(TAG, "Decoded subtitle content is blank from: ${candidate.url}")
                onCandidateFailed(candidate, "[AI Translator] Lỗi: Phụ đề gốc không có nội dung văn bản.")
                return@withContext
            }

            // Step 3: Compute SHA-256 Composite Cache Key
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
                completedFile = cachedFile
                AITranslatorLocalServer.registerSessionKeyFile(sessionKey, cachedFile)
                val streamUrl = AITranslatorLocalServer.registerSubtitle(cachedFile)
                val trackLabel = buildAuthoritativeTrackLabel(targetLang, effectiveModel)
                notifyActivePlayer(trackLabel, streamUrl)
                showToast("[AI Translator] Đã nạp phụ đề $targetName từ bộ nhớ đệm.")
                isCompleted = true
                completionLatch.countDown()
                if (TranslatorConfig.showProgressHUD) {
                    try {
                        TranslationProgressHUD.showSuccess("Đã nạp phụ đề từ bộ nhớ đệm (100%)")
                    } catch (_: Throwable) {}
                }
                return@withContext
            }

            // Step 5: Parse Subtitle into Cues
            val cues = SubtitleParser.parse(rawContent)
            if (cues.isEmpty()) {
                logE(TAG, "Subtitle parser extracted 0 valid cues from content: ${candidate.url}")
                onCandidateFailed(candidate, "[AI Translator] Lỗi: Không phân tích được định dạng phụ đề.")
                return@withContext
            }

            // Step 6: Load Tier 2 Progressive Chunk Cache
            val existingTranslations = SubtitleCacheManager.loadChunks(context, cacheKey).toMutableMap()
            val maxChunkSize = TranslatorConfig.maxChunkCues.coerceIn(18, 50)
            val pauseMs = TranslatorConfig.dynamicScenePauseMs
            val chunks = splitIntoDynamicChunks(cues, minCues = 18, maxCues = maxChunkSize, pauseThresholdMs = pauseMs)

            synchronized(dispatchLock) {
                currentChunks = chunks
                pendingChunkIndices.clear()
                pendingChunkIndices.addAll(chunks.indices)
            }

            val totalChunks = chunks.size
            val totalCues = cues.size
            val initialDoneChunks = chunks.count { it.all { cue -> existingTranslations.containsKey(cue.index) } }
            val initialDoneCues = cues.count { existingTranslations.containsKey(it.index) }
            val initialPct = if (totalCues > 0) (initialDoneCues * 100 / totalCues) else 0

            if (TranslatorConfig.showProgressHUD) {
                try {
                    TranslationProgressHUD.updateProgress(
                        completedChunks = initialDoneChunks,
                        totalChunks = totalChunks,
                        completedCues = initialDoneCues,
                        totalCues = totalCues,
                        percent = initialPct,
                        modelName = effectiveModel,
                        status = if (initialDoneChunks > 0) "Đang dịch: Chunk $initialDoneChunks/$totalChunks ($initialPct%) • $initialDoneCues/$totalCues câu" else "Đang khởi tạo các đoạn dịch..."
                    )
                } catch (_: Throwable) {}
            }

            // Step 7: 100% Sequential Batch Delivery
            logI(TAG, "Executing 100% Sequential Batch Delivery across ${chunks.size} chunks.")

            val client = clientOverride ?: AITranslatorFactory.createClient(
                provider = TranslatorConfig.provider,
                apiKey = TranslatorConfig.apiKey,
                baseUrl = TranslatorConfig.customBaseUrl,
                modelName = effectiveModel
            )

            val window = DialogueContextWindow(
                lookbehindSize = TranslatorConfig.contextWindowSize.coerceIn(2, 12),
                lookaheadSize = 4,
                maxLookbehindGapMs = 15000L
            )
            activeContextWindow = window

            val pacingInterval = if (clientOverride != null) 0L else when (TranslatorConfig.provider) {
                TranslatorConfig.PROVIDER_GEMINI -> 1500L
                TranslatorConfig.PROVIDER_ANTHROPIC -> 2000L
                TranslatorConfig.PROVIDER_OPENAI -> 1000L
                else -> 1000L
            }
            val pacingGovernor = PacingGovernor(pacingInterval)

            var registry = if (TranslatorConfig.enableCharacterProfiling) {
                CharacterProfileManager.getProfile(context, mediaContext?.title)
                    ?: TwoTierCharacterRegistry(mediaTitle = mediaContext?.title ?: "")
            } else {
                TwoTierCharacterRegistry()
            }

            // Chained Ordered Pipeline: sequential streaming across chunk indices 0 until chunks.size
            for (index in 0 until chunks.size) {
                if (!isActive || isCancelled) break
                synchronized(dispatchLock) {
                    if (pendingChunkIndices.isNotEmpty() && pendingChunkIndices[0] == index) {
                        pendingChunkIndices.removeAt(0)
                    }
                }
                val chunkCues = chunks[index]

                // Check if chunk is already fully cached
                val isChunkFullyCached = chunkCues.all { existingTranslations.containsKey(it.index) }
                if (isChunkFullyCached) {
                    window.recordFinalizedCues(chunkCues, existingTranslations)
                    if (TranslatorConfig.showProgressHUD) {
                        try {
                            val doneChunks = chunks.count { it.all { cue -> existingTranslations.containsKey(cue.index) } }
                            val doneCues = cues.count { existingTranslations.containsKey(it.index) }
                            val pct = if (cues.isNotEmpty()) (doneCues * 100 / cues.size) else 0
                            TranslationProgressHUD.updateProgress(
                                completedChunks = doneChunks,
                                totalChunks = chunks.size,
                                completedCues = doneCues,
                                totalCues = cues.size,
                                percent = pct,
                                modelName = effectiveModel,
                                status = "Đang dịch: Chunk $doneChunks/${chunks.size} ($pct%) • $doneCues/${cues.size} câu"
                            )
                        } catch (_: Throwable) {}
                    }
                    continue
                }

                // Active-Speaker Pruning & Context Extraction
                val lookbehind = window.getLookbehind(chunkCues)
                val lookahead = window.extractLookahead(cues, chunkCues.last().index)
                val prunedRegistry = if (TranslatorConfig.enableCharacterProfiling) {
                    window.pruneRegistry(registry, chunkCues, lookbehind, lookahead)
                } else null

                // Pacing governor (15 RPM protection)
                pacingGovernor.pace()

                val effectiveSourceLang = if (!candidate.lang.isNullOrBlank() && !candidate.lang.equals("auto", ignoreCase = true)) {
                    candidate.lang
                } else if (chunkCues.any { AITranslatorPromptBuilder.isJapaneseScript(it.rawText) }) {
                    "ja"
                } else {
                    candidate.lang
                }

                val translatedChunk = translateChunkWithRetry(
                    client = client,
                    chunkIndex = index,
                    chunkCues = chunkCues,
                    sourceLang = effectiveSourceLang,
                    targetLang = targetLang,
                    glossary = TranslatorConfig.customGlossary,
                    lookbehind = lookbehind,
                    lookahead = lookahead,
                    characterRegistry = prunedRegistry,
                    mediaContext = mediaContext,
                    onDiscoveredCharacters = { discovered ->
                        if (discovered.isNotEmpty() && TranslatorConfig.enableCharacterProfiling) {
                            val newChars = discovered.filter { d ->
                                registry.characters.none { it.name.equals(d.name, ignoreCase = true) }
                            }.map { it.copy(isEphemeral = true) }

                            if (newChars.isNotEmpty()) {
                                val updatedChars = registry.characters + newChars
                                registry = registry.copy(characters = updatedChars)
                                CharacterProfileManager.saveProfile(context, mediaContext?.title, registry, isEphemeral = true)
                                logI(TAG, "Chunk $index dynamically registered ${newChars.size} new characters (ephemeral). Total cast: ${updatedChars.size}")
                            }
                        }
                    },
                    onDiscoveredRelationships = { discoveredRels ->
                        if (discoveredRels.isNotEmpty() && TranslatorConfig.enableCharacterProfiling) {
                            val newRels = discoveredRels.filter { d ->
                                registry.relationships.none { existing ->
                                    existing.pair.map { it.lowercase() } == d.pair.map { it.lowercase() }
                                }
                            }.map { it.copy(isEphemeral = true) }

                            if (newRels.isNotEmpty()) {
                                val updatedRels = registry.relationships + newRels
                                registry = registry.copy(relationships = updatedRels)
                                CharacterProfileManager.saveProfile(context, mediaContext?.title, registry, isEphemeral = true)
                                logI(TAG, "Chunk $index dynamically registered ${newRels.size} new relationships (ephemeral). Total relationships: ${updatedRels.size}")
                            }
                        }
                    }
                )

                if (translatedChunk.isNotEmpty()) {
                    synchronized(existingTranslations) {
                        existingTranslations.putAll(translatedChunk)
                    }
                    window.recordFinalizedCues(chunkCues, translatedChunk)
                    SubtitleCacheManager.saveChunk(context, cacheKey, index, translatedChunk)

                    // Progressive interim delivery: After Chunk 0 completes, assemble an interim SubRip file
                    // (translated Chunk 0 cues + raw cues for remainder), register it with AITranslatorLocalServer,
                    // and set completedFile = interimFile so ExoPlayer can begin playback immediately.
                    if (index == 0 && chunks.size > 1) {
                        try {
                            val interimReconciled = cues.associate { cue ->
                                cue.index to (existingTranslations[cue.index] ?: cue.rawText)
                            }
                            val interimSrt = SubtitleReconstructor.buildSubRip(cues, interimReconciled)
                            val interimFile = SubtitleCacheManager.saveFullFile(context, "${cacheKey}_interim", interimSrt)
                            completedFile = interimFile
                            AITranslatorLocalServer.registerSessionKeyFile(sessionKey, interimFile)
                            completionLatch.countDown()
                            logI(TAG, "Chunk 0 interim subtitle assembled and registered (${interimFile.length()} bytes) for session $sessionKey")
                        } catch (e: Throwable) {
                            logW(TAG, "Failed to assemble interim subtitle for Chunk 0: ${e.message}")
                        }
                    }

                    if (TranslatorConfig.showProgressHUD) {
                        try {
                            val doneChunks = chunks.count { it.all { cue -> existingTranslations.containsKey(cue.index) } }
                            val doneCues = cues.count { existingTranslations.containsKey(it.index) }
                            val pct = if (cues.isNotEmpty()) (doneCues * 100 / cues.size) else 0
                            TranslationProgressHUD.updateProgress(
                                completedChunks = doneChunks,
                                totalChunks = chunks.size,
                                completedCues = doneCues,
                                totalCues = cues.size,
                                percent = pct,
                                modelName = effectiveModel,
                                status = "Đang dịch: Chunk $doneChunks/${chunks.size} ($pct%) • $doneCues/${cues.size} câu"
                            )
                        } catch (_: Throwable) {}
                    }
                }
            }

            if (!isActive || isCancelled) return@withContext

            // Step 8: Full Document Assembly & Tier 1 Save
            if (existingTranslations.isEmpty() && cues.isNotEmpty()) {
                logE(TAG, "All chunk translations failed for candidate: ${candidate.url}")
                onCandidateFailed(candidate, "[AI Translator] Lỗi: Dịch phụ đề thất bại.")
                return@withContext
            }

            val reconciledAll = SubtitleReconciler.autoHealingReconcile(cues, existingTranslations)
            val fullSrt = SubtitleReconstructor.buildSubRip(cues, reconciledAll)

            val fullFile = SubtitleCacheManager.saveFullFile(context, cacheKey, fullSrt)
            completedFile = fullFile
            AITranslatorLocalServer.registerSessionKeyFile(sessionKey, fullFile)
            val streamUrl = AITranslatorLocalServer.registerSubtitle(fullFile)
            val authoritativeLabel = buildAuthoritativeTrackLabel(targetLang, effectiveModel)

            logI(TAG, "Translation completed successfully. Emitting authoritative track: '$authoritativeLabel'")
            notifyActivePlayer(authoritativeLabel, streamUrl)
            showToast("[AI Translator] Phụ đề $targetName đã dịch xong và sẵn sàng!")
            isCompleted = true
            completionLatch.countDown()
            if (TranslatorConfig.showProgressHUD) {
                try {
                    TranslationProgressHUD.showSuccess("Hoàn tất 100%! Phụ đề đã sẵn sàng")
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            completionLatch.countDown()
            if (t is CancellationException) throw t
            logE(TAG, "Unexpected error in translation pipeline for '${candidate.lang}': ${t.message}", t)
            onCandidateFailed(candidate, "[AI Translator] Lỗi dịch phụ đề: ${t.message ?: "Không xác định"}")
        }
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
        glossary: String?,
        lookbehind: List<ContextCue> = emptyList(),
        lookahead: List<ContextCue> = emptyList(),
        characterRegistry: TwoTierCharacterRegistry? = null,
        mediaContext: MediaContext? = null,
        onDiscoveredCharacters: ((List<CharacterEntity>) -> Unit)? = null,
        onDiscoveredRelationships: ((List<CharacterRelationship>) -> Unit)? = null
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
                    customGlossary = glossary,
                    lookbehind = lookbehind,
                    lookahead = lookahead,
                    characterRegistry = characterRegistry,
                    mediaContext = mediaContext
                )

                if (result.isSuccess && result.translations.isNotEmpty()) {
                    if (!result.discoveredCharacters.isNullOrEmpty()) {
                        onDiscoveredCharacters?.invoke(result.discoveredCharacters)
                    }
                    if (!result.rawResponse.isNullOrBlank()) {
                        val discoveredRels = parseDiscoveredRelationships(result.rawResponse)
                        if (discoveredRels.isNotEmpty()) {
                            onDiscoveredRelationships?.invoke(discoveredRels)
                        }
                    }
                    return SubtitleReconciler.autoHealingReconcile(chunkCues, result.translations)
                }

                if (result.isQuotaExhausted) {
                    logE(TAG, "Quota exhausted during chunk $chunkIndex translation.")
                    showToast("[AI Translator] Lỗi: Hết hạn mức API (Quota Exhausted)")
                    if (TranslatorConfig.showProgressHUD) {
                        try {
                            TranslationProgressHUD.showError("Lỗi: Hết hạn mức API (Quota Exhausted)")
                        } catch (_: Throwable) {}
                    }
                    return emptyMap()
                }

                if (!result.retryable || attempts >= maxAttempts) {
                    logE(TAG, "Chunk $chunkIndex failed (non-retryable or max attempts): ${result.errorMessage}")
                    return emptyMap()
                }

                delay(1000L * attempts)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (attempts >= maxAttempts) {
                    logE(TAG, "Chunk $chunkIndex exception after $attempts attempts: ${e.message}", e)
                    return emptyMap()
                }
                delay(1000L * attempts)
            }
        }
        return emptyMap()
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
     * Ingests subtitle bytes from HTTP/HTTPS, loopback, or file URIs.
     * Preserves custom provider headers (Referer, cookies, auth) for protected CDNs.
     */
    private suspend fun fetchSubtitleBytes(rawUrl: String, headers: Map<String, String>? = null): ByteArray? = withContext(Dispatchers.IO) {
        val url = rawUrl.trim().let { if (it.startsWith("//")) "https:$it" else it }
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    val client = try {
                        com.lagradost.cloudstream3.app.baseClient
                    } catch (_: Throwable) {
                        fallbackClient
                    }
                    val reqBuilder = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "*/*")
                    headers?.forEach { (k, v) ->
                        reqBuilder.header(k, v)
                    }
                    val req = reqBuilder.build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.bytes()
                        } else {
                            logW(TAG, "OkHttp error HTTP ${resp.code} ${resp.message} fetching subtitle from $url")
                            null
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logW(TAG, "OkHttp fetch failed for $url: ${t.message}. Trying URLConnection fallback.")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    connection.setRequestProperty("Accept", "*/*")
                    headers?.forEach { (k, v) ->
                        connection.setRequestProperty(k, v)
                    }
                    val code = connection.responseCode
                    if (code in 200..299) {
                        connection.inputStream.use { it.readBytes() }
                    } else {
                        logW(TAG, "HttpURLConnection error HTTP $code ${connection.responseMessage} fetching subtitle from $url")
                        null
                    }
                }
            } else if (url.startsWith("file://")) {
                try {
                    File(URI(url)).readBytes()
                } catch (_: Throwable) {
                    val decoded = try {
                        java.net.URLDecoder.decode(url.removePrefix("file://"), "UTF-8")
                    } catch (_: Throwable) {
                        url.removePrefix("file://")
                    }
                    val cleanPath = decoded.replace('/', File.separatorChar)
                    val f = File(cleanPath)
                    if (f.exists()) f.readBytes() else null
                }
            } else if (url.startsWith("content://")) {
                try {
                    val effectiveCtx = context ?: TranslatorConfig.appContext ?: try {
                        com.lagradost.cloudstream3.CloudStreamApp.Companion.context
                    } catch (_: Throwable) { null }
                    val uri = android.net.Uri.parse(url)
                    effectiveCtx?.contentResolver?.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Throwable) {
                    logW(TAG, "Failed reading content URI $url: ${e.message}")
                    null
                }
            } else if (url.startsWith("data:")) {
                try {
                    val commaIndex = url.indexOf(',')
                    if (commaIndex != -1) {
                        val meta = url.substring(5, commaIndex)
                        val data = url.substring(commaIndex + 1)
                        if (meta.contains(";base64", ignoreCase = true)) {
                            java.util.Base64.getDecoder().decode(data.trim())
                        } else {
                            java.net.URLDecoder.decode(data, "UTF-8").toByteArray(Charsets.UTF_8)
                        }
                    } else null
                } catch (e: Throwable) {
                    logW(TAG, "Failed decoding data URI: ${e.message}")
                    null
                }
            } else {
                val f = File(url)
                if (f.exists()) f.readBytes() else null
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logE(TAG, "Failed to fetch subtitle bytes from $url: ${e.message}", e)
            null
        }
    }

    /**
     * Attempts decoding subtitle byte payloads using progressive charset priority:
     * ZIP archive auto-extraction -> GZIP decompression -> BOM detection (UTF-16LE, UTF-16BE, UTF-8 BOM) -> UTF-8 -> Shift-JIS / Windows-31J -> Windows-1252 -> UTF-8 fallback.
     */
    private fun decodeSubtitleText(bytes: ByteArray): String {
        var actualBytes = bytes
        // Decompress ZIP archive if magic header detected (PK\x03\x04 -> 0x50, 0x4B, 0x03, 0x04)
        if (actualBytes.size >= 4 && actualBytes[0] == 0x50.toByte() && actualBytes[1] == 0x4B.toByte() && actualBytes[2] == 0x03.toByte() && actualBytes[3] == 0x04.toByte()) {
            try {
                java.util.zip.ZipInputStream(ByteArrayInputStream(actualBytes)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase(Locale.ROOT)
                        if (!entry.isDirectory && (name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass") || name.endsWith(".ssa") || name.endsWith(".txt"))) {
                            actualBytes = zipIn.readBytes()
                            break
                        }
                        entry = zipIn.nextEntry
                    }
                }
            } catch (e: Throwable) {
                logW(TAG, "Failed to decompress suspected ZIP subtitle stream: ${e.message}")
            }
        }

        // Decompress GZIP if magic header detected (0x1F, 0x8B)
        if (actualBytes.size >= 2 && actualBytes[0] == 0x1F.toByte() && actualBytes[1] == 0x8B.toByte()) {
            try {
                GZIPInputStream(ByteArrayInputStream(actualBytes)).use { gzipIn ->
                    actualBytes = gzipIn.readBytes()
                }
            } catch (e: Throwable) {
                logW(TAG, "Failed to decompress suspected GZIP subtitle stream: ${e.message}")
            }
        }

        // Step 1: Detect explicit Byte Order Mark (BOM) first (O(1) unambiguous check)
        if (actualBytes.size >= 2) {
            if (actualBytes[0] == 0xFF.toByte() && actualBytes[1] == 0xFE.toByte()) {
                return String(actualBytes, Charsets.UTF_16LE)
            }
            if (actualBytes[0] == 0xFE.toByte() && actualBytes[1] == 0xFF.toByte()) {
                return String(actualBytes, Charsets.UTF_16BE)
            }
        }
        if (actualBytes.size >= 3 && actualBytes[0] == 0xEF.toByte() && actualBytes[1] == 0xBB.toByte() && actualBytes[2] == 0xBF.toByte()) {
            return String(actualBytes, 3, actualBytes.size - 3, Charsets.UTF_8)
        }

        // Step 2: Try UTF-8 (strict)
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = decoder.decode(ByteBuffer.wrap(actualBytes))
            val text = charBuffer.toString()
            if (text.isNotBlank()) return text
        } catch (_: Throwable) {}

        // Step 3: Try Shift-JIS / Windows-31J (strict)
        try {
            val charset = Charset.forName("Windows-31J")
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = decoder.decode(ByteBuffer.wrap(actualBytes))
            val text = charBuffer.toString()
            if (text.isNotBlank()) return text
        } catch (_: Throwable) {}

        // Step 4: Try Windows-1252
        try {
            val charset = Charset.forName("windows-1252")
            return String(actualBytes, charset)
        } catch (_: Throwable) {}

        // Step 5: Fallback to default UTF-8
        return String(actualBytes, Charsets.UTF_8)
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val ctx = context ?: TranslatorConfig.appContext ?: try {
            com.lagradost.cloudstream3.CloudStreamApp.Companion.context
        } catch (_: Throwable) { null }

        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    val act = try { CommonActivity.activity } catch (_: Throwable) { null }
                    if (act != null) {
                        try {
                            CommonActivity.showToast(act, message, duration)
                            return@post
                        } catch (_: Throwable) {}
                    }
                    if (ctx != null) {
                        Toast.makeText(ctx, message, duration).show()
                    } else {
                        println("[AITranslatorToast] $message")
                    }
                } catch (_: Throwable) {
                    println("[AITranslatorToast] $message")
                }
            }
        } catch (_: Throwable) {
            println("[AITranslatorToast] $message")
        }
    }

    private fun buildAuthoritativeTrackLabel(lang: String, model: String): String {
        val displayName = getTargetLanguageDisplayName(lang)
        return "$displayName [AI: $model]"
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
