@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.subtitles.ai.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
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
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
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
import java.io.IOException
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    val mediaContext: MediaContext? = null,
    val manual: Boolean = false,
    val playerDurationProvider: (() -> Long?)? = null,
    val onInterimTrack: ((SubtitleFile) -> Unit)? = null,
    val onFinalTrack: ((SubtitleFile) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AITranslationSession"
        private const val MAX_CONCURRENT_CHUNK_WORKERS = 4
        private const val INTAKE_DEBOUNCE_MS = 200L
        private const val PREEMPTION_SCORE_MARGIN = 100
        private const val PREEMPTION_COOLDOWN_MS = 10_000L
        private const val MAX_PREEMPTIONS = 2

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

        /**
         * Splits cues progressively: small Chunk 0 (12-15 cues) for fast Interim Play,
         * followed by larger Chunks 1..N (25-45 cues) to cut HTTP round-trips.
         */
        fun splitIntoProgressiveChunks(
            cues: List<SubtitleCue>,
            chunk0Min: Int = 12,
            chunk0Max: Int = 15,
            minCues: Int = 25,
            maxCues: Int = 45,
            pauseThresholdMs: Long = 1800L
        ): List<List<SubtitleCue>> {
            if (cues.isEmpty()) return emptyList()
            if (cues.size <= chunk0Max) return listOf(cues)

            var splitIndex = chunk0Max
            val startIdx = (chunk0Min - 1).coerceAtLeast(0)
            val endIdx = chunk0Max.coerceAtMost(cues.size - 1)
            for (i in startIdx until endIdx) {
                val deltaT = (cues[i + 1].startMs - cues[i].endMs).coerceAtLeast(0L)
                if (deltaT >= pauseThresholdMs && isSyntacticBoundary(cues[i])) {
                    splitIndex = i + 1
                    break
                }
            }
            val chunk0 = cues.subList(0, splitIndex)
            val rest = cues.subList(splitIndex, cues.size)
            if (rest.isEmpty()) return listOf(chunk0)
            return listOf(chunk0) + splitIntoDynamicChunks(
                cues = rest,
                minCues = minCues.coerceAtMost(maxCues),
                maxCues = maxCues,
                pauseThresholdMs = pauseThresholdMs
            )
        }

        private val SFX_TAG_REGEX = Regex("""\[[^\]]*\]|\([^)]*\)|<[^>]*>|\{[^}]*\}|\[\[\s*TAG_\d+\s*\]\]""", RegexOption.IGNORE_CASE)

        private val PROPER_NOUN_STRIP_REGEX = Regex("""<[^>]*>|\{[^}]*\}|\[\[\s*TAG_\d+\s*\]\]""", RegexOption.IGNORE_CASE)

        private val PROPER_NOUN_TRIM_CHARS = charArrayOf(
            '"', '\'', '`', '.', ',', '!', '?', ';', ':', '(', ')', '[', ']', '{', '}', '—', '–', '-', '…'
        )

        private val CONTRACTION_SUFFIXES = listOf(
            "'s", "’s", "'t", "’t", "n't", "n’t", "'re", "’re", "'ve", "’ve", "'ll", "’ll", "'d", "’d", "'m", "’m"
        )

        private val COMMON_ENGLISH_NON_NAME_WORDS = setOf(
            "i", "me", "my", "mine", "myself", "you", "your", "yours", "yourself",
            "he", "him", "his", "himself", "she", "her", "hers", "herself",
            "it", "its", "itself", "we", "us", "our", "ours", "ourselves",
            "they", "them", "their", "theirs", "themselves",
            "this", "that", "these", "those", "who", "whom", "whose", "which", "what",
            "the", "a", "an", "some", "any", "no", "none", "all", "every", "each",
            "both", "few", "more", "most", "other", "another", "such",
            "everyone", "everybody", "everything", "someone", "somebody", "something",
            "anyone", "anybody", "anything", "nobody", "nothing",
            "is", "am", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "done", "doing",
            "will", "would", "shall", "should", "can", "could", "may", "might", "must",
            "go", "goes", "going", "went", "gone", "come", "comes", "coming", "came",
            "get", "gets", "got", "gotten", "give", "gives", "gave", "given",
            "take", "takes", "took", "taken", "make", "makes", "made",
            "see", "sees", "saw", "seen", "look", "looks", "looked", "looking",
            "know", "knows", "knew", "known", "think", "thinks", "thought",
            "want", "wants", "wanted", "need", "needs", "needed",
            "help", "helps", "helped", "wait", "waits", "waited", "stop", "run",
            "say", "says", "said", "tell", "tells", "told", "ask", "asked",
            "love", "like", "hate", "feel", "keep", "let", "put", "try", "leave",
            "listen", "watch", "move", "stay", "hold", "stand", "sit", "turn",
            "not", "never", "always", "now", "then", "here", "there", "where", "when", "why", "how",
            "very", "too", "so", "just", "only", "even", "still", "already", "again",
            "well", "really", "actually", "maybe", "perhaps", "almost", "enough",
            "in", "on", "at", "to", "for", "of", "with", "from", "by", "about",
            "into", "over", "under", "after", "before", "between", "through", "out", "up", "down",
            "and", "or", "but", "if", "because", "while", "though", "until", "since",
            "yes", "yeah", "yep", "nope", "nah", "okay", "ok", "please", "sorry",
            "thanks", "thank", "hello", "hi", "hey", "bye", "goodbye", "good", "bad",
            "morning", "night", "today", "tomorrow", "yesterday", "right", "wrong",
            "man", "boy", "girl", "guys", "friend", "sir", "ma'am",
            "destroy", "check", "system", "status", "call", "follow", "open", "close",
            "kill", "save", "fight", "shut", "evil", "robots", "robot", "warning",
            "alert", "start", "attack", "escape", "activate", "offline", "online",
            "mission", "target", "sector", "security", "protocol", "access", "denied",
            "granted", "emergency", "critical", "failure", "override", "shutdown",
            "reboot", "engage", "fire", "hide", "protect", "defend", "break", "enter",
            "exit", "read", "write", "send", "bring", "carry", "catch", "drop",
            "push", "pull", "raise", "lower", "cut", "burn", "freeze", "danger",
            "error", "power", "world", "people", "human", "humans", "city", "earth",
            "home", "time", "life", "death", "game", "war", "battle", "match",
            "round", "win", "lose", "dead", "alive", "free", "dark", "light", "high",
            "low", "big", "small", "new", "old", "first", "last", "next", "final",
            "real", "true", "false", "full", "empty", "ready", "done"
        )

        fun hasDialogueLetters(rawText: String?): Boolean {
            if (rawText.isNullOrBlank()) return false
            val stripped = SFX_TAG_REGEX.replace(rawText, "").trim()
            return stripped.any { it.isLetter() }
        }

        fun isStandaloneProperNoun(rawText: String?): Boolean {
            if (rawText.isNullOrBlank()) return false
            val cleaned = PROPER_NOUN_STRIP_REGEX.replace(rawText, " ").trim()
            if (cleaned.isEmpty()) return false
            val tokens = cleaned.split(Regex("\\s+"))
                .map { it.trim(*PROPER_NOUN_TRIM_CHARS) }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty() || tokens.size > 3) return false

            for (tok in tokens) {
                val low = tok.lowercase(Locale.ROOT)
                if (COMMON_ENGLISH_NON_NAME_WORDS.contains(low)) return false
                if (CONTRACTION_SUFFIXES.any { low.endsWith(it) }) return false
                if (!tok[0].isUpperCase()) return false
                if (tok.length > 2 && tok.none { it.isLowerCase() }) {
                    // Reject ALL-CAPS shouting phrases like "STOP RIGHT NOW"
                    return false
                }
                if (!tok.all { it.isLetter() || it == '-' || it == '\'' || it == '’' || it == '.' }) {
                    return false
                }
            }
            return true
        }

        /**
         * Detects untranslated / raw-fallback dialogue lines while sparing short loanwords,
         * standalone proper nouns (even styled/over 12 chars), and non-alphabetic markup.
         */
        fun isUntranslatedCue(rawText: String, translatedText: String?, targetLang: String = "vi"): Boolean {
            if (translatedText.isNullOrBlank()) return true
            val cleanRaw = rawText.trim()
            val cleanTrans = translatedText.trim()
            if (cleanRaw.isEmpty()) return false
            if (!cleanRaw.equals(cleanTrans, ignoreCase = true)) return false
            if (isStandaloneProperNoun(cleanRaw)) return false
            val stripped = PROPER_NOUN_STRIP_REGEX.replace(cleanRaw, " ").trim()
            if (!stripped.any { it.isLetter() }) return false
            val words = stripped.split(Regex("\\s+")).filter { it.isNotBlank() }
            return words.size >= 3 || stripped.length > 12
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

    @Volatile
    private var castResolutionJob: Job? = null

    private val preAnnounced = AtomicBoolean(false)
    private val recordedLinks = CopyOnWriteArrayList<ExtractorLink>()
    private val registryLock = Any()
    private val quotaToastShown = AtomicBoolean(false)

    @Volatile
    private var loadLinksSettled: CompletableDeferred<Unit>? = null

    fun beginLoadLinksWindow() {
        loadLinksSettled = CompletableDeferred()
    }

    fun endLoadLinksWindow() {
        loadLinksSettled?.complete(Unit)
    }

    init {
        val title = mediaContext?.title?.takeIf { it.isNotBlank() } ?: mediaPayload.takeIf { it.isNotBlank() }
        if (!title.isNullOrBlank() && TranslatorConfig.enableCharacterProfiling && (mediaContext != null || clientOverride == null)) {
            castResolutionJob = sessionScope.launch {
                try {
                    AniListCastResolver.resolveUniversalCast(context, title, mediaContext)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
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

    @Volatile
    private var completionLatch: CountDownLatch = CountDownLatch(1)

    @Volatile
    var completedFile: File? = null
        private set

    private fun reprioritizePendingChunksLocked(posMs: Long) {
        val chunksSnapshot = currentChunks ?: return
        if (pendingChunkIndices.size <= 1) return
        pendingChunkIndices.sortWith(
            compareBy<Int>(
                { idx ->
                    val chunk = chunksSnapshot.getOrNull(idx)
                    if (chunk.isNullOrEmpty()) 3
                    else {
                        val startMs = chunk.first().startMs
                        val endMs = chunk.last().endMs
                        when {
                            startMs <= posMs && posMs <= endMs -> 0
                            startMs > posMs -> 1
                            else -> 2
                        }
                    }
                },
                { idx ->
                    chunksSnapshot.getOrNull(idx)?.firstOrNull()?.startMs ?: Long.MAX_VALUE
                },
                { idx -> idx }
            )
        )
    }

    fun awaitCompletion(timeoutMs: Long = 60000L): Boolean {
        if (isTargetLanguageBypassed || isCancelled) return false
        if (completedFile != null || isCompleted) return true
        return try {
            val latch = completionLatch
            val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (isTargetLanguageBypassed || isCancelled) return false
            finished && (completedFile != null || isCompleted)
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
        synchronized(dispatchLock) {
            reprioritizePendingChunksLocked(posMs)
        }
        if ((TranslatorConfig.showProgressHUD || manual) && !isCompleted && !isCancelled) {
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
    private val ownTrackPaths = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var acceptedCandidateCount: Int = 0
        private set

    val isTranslationInFlight: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    private var preemptionCount: Int = 0

    @Volatile
    private var lastPreemptionAtMs: Long = 0L

    fun recordExtractorLink(link: ExtractorLink) {
        recordedLinks.add(link)
    }

    fun preAnnounceIfNeeded(subFile: SubtitleFile) {
        if (isTargetLanguageBypassed || isCancelled) return
        val score = SubtitlePriorityEvaluator.calculateScore(
            subFile = subFile,
            targetLang = targetLang,
            priorityMode = TranslatorConfig.sourcePriorityMode,
            customList = TranslatorConfig.getCustomPriorityLanguages()
        )
        if (score == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) return
        if (preAnnounced.compareAndSet(false, true)) {
            try {
                val streamKey = sessionKey
                val streamUrl = AITranslatorLocalServer.getSubtitleUrl(streamKey)
                AITranslatorLocalServer.registerSession(streamKey, this)
                val targetDisplayName = getTargetLanguageDisplayName(targetLang)
                val preAnnounceLabel = "[AI] $targetDisplayName"
                logI(TAG, "Pre-announcing AI subtitle track: '$preAnnounceLabel' -> $streamUrl")
                emitTrack(preAnnounceLabel, streamUrl)
            } catch (t: Throwable) {
                logW(TAG, "Pre-announcement failed: ${t.message}")
            }
        }
    }

    fun launchSourceFallbackDiscovery(
        capturedLinks: List<ExtractorLink>,
        imdbId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ) {
        if (isCancelled || isTargetLanguageBypassed || isCompleted) return
        sessionScope.launch {
            try {
                // Staged grace probes so async peer enrichers (Kitsunekko scrape ~seconds) can deliver first
                for (graceStepMs in longArrayOf(400L, 1100L, 1500L)) {
                    delay(graceStepMs)
                    if (!isActive || isCancelled || isTargetLanguageBypassed || isCompleted) return@launch
                    if (acceptedCandidateCount > 0 || candidateList.isNotEmpty() || translationJob?.isActive == true) {
                        logI(TAG, "Fallback discovery deferred: accepted candidates already present.")
                        return@launch
                    }
                }

                val linksSnapshot = (capturedLinks + recordedLinks).distinctBy { it.url }
                val title = mediaContext?.title?.takeIf { it.isNotBlank() } ?: mediaPayload.takeIf { it.isNotBlank() }
                logI(TAG, "Fallback discovery starting (episode-aware): ep=$episodeNumber season=$seasonNumber")
                val discovery = StreamSubtitleExtractor.discoverFallbackSubtitles(
                    capturedLinks = linksSnapshot,
                    targetLang = targetLang,
                    title = title,
                    year = mediaContext?.year,
                    imdbId = imdbId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber
                )
                if (!isActive || isCancelled) return@launch

                when (discovery) {
                    is StreamSubtitleExtractor.FallbackDiscoveryResult.Bypassed -> {
                        logI(TAG, "Source fallback detected target language (${discovery.matchedLang} via ${discovery.source}); bypassing AI translation.")
                        isTargetLanguageBypassed = true
                        cancelCurrentJob()
                        candidateList.clear()
                        completionLatch.countDown()
                        AITranslatorLocalServer.unregisterSession(sessionKey)
                        showToast("[AI Translator] Đã có sẵn phụ đề ${discovery.matchedLang} trong nguồn phát, bỏ qua dịch AI.")
                    }
                    is StreamSubtitleExtractor.FallbackDiscoveryResult.CandidatesFound -> {
                        var announced = preAnnounced.get()
                        for (sub in discovery.candidates) {
                            if (isCancelled || isTargetLanguageBypassed) break
                            try {
                                playerSubtitleCallback(sub)
                            } catch (_: Throwable) {}
                            val accepted = queueCandidate(sub)
                            if (accepted && !announced) {
                                preAnnounceIfNeeded(sub)
                                announced = preAnnounced.get()
                            }
                        }
                    }
                    is StreamSubtitleExtractor.FallbackDiscoveryResult.NoneFound -> {
                        logI(TAG, "Source fallback discovery found 0 additional subtitles.")
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logW(TAG, "Source fallback discovery failed: ${t.message}")
            }
        }
    }

    /**
     * Intake endpoint for newly discovered subtitle streams from base providers or enrichers.
     * Evaluates priority score, enforces Target Language Bypass Rule, and dynamically promotes
     * higher-ranked candidates.
     */
    fun queueCandidate(subFile: SubtitleFile): Boolean {
        if (isCancelled || isTargetLanguageBypassed) return false
        // Manual mode never auto-selects or auto-queues tracks
        if (manual) return false
        if (failedUrls.contains(subFile.url)) return false
        if (subFile.lang.contains("[AI", ignoreCase = true) || AITranslatorLocalServer.isOwnSubtitleUrl(subFile.url)) {
            logI(TAG, "Skipping own AI track candidate '${subFile.lang}' -> ${subFile.url}")
            return false
        }
        if (candidateList.any { it.url == subFile.url && it.lang == subFile.lang }) return false

        val score = SubtitlePriorityEvaluator.calculateScore(
            subFile = subFile,
            targetLang = targetLang,
            priorityMode = TranslatorConfig.sourcePriorityMode,
            customList = TranslatorConfig.getCustomPriorityLanguages()
        )

        // 1. Target Language Bypass Rule: only while no translation work exists.
        // A late target-language track must never destroy a running/finished translation.
        if (score == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            synchronized(this) {
                if (translationJob?.isActive == true || isCompleted || currentProcessingUrl != null) {
                    logI(TAG, "Ignoring late target-language candidate '${subFile.lang}'; active translation preserved.")
                    return false
                }
                logI(TAG, "Target language bypass triggered for '${subFile.lang}'. Skipping AI translation.")
                isTargetLanguageBypassed = true
                cancelCurrentJob()
                candidateList.clear()
                completionLatch.countDown()
                AITranslatorLocalServer.unregisterSession(sessionKey)
                showToast("[AI Translator] Đã có sẵn phụ đề ${subFile.lang}, bỏ qua dịch AI.")
                return false
            }
        }

        candidateList.add(subFile)
        acceptedCandidateCount += 1

        // 2. Dynamic Candidate Promotion: Higher score preempts active lower-priority job.
        // 3. Post-completion preemption: a clearly better late candidate may replace a finished translation
        //    (bounded by score margin, cooldown and a hard cap to prevent loops).
        synchronized(this) {
            val now = System.currentTimeMillis()
            val isUpgradeAfterCompletion = isCompleted &&
                score > currentBestScore + PREEMPTION_SCORE_MARGIN &&
                preemptionCount < MAX_PREEMPTIONS &&
                (now - lastPreemptionAtMs) >= PREEMPTION_COOLDOWN_MS
            val shouldStart = isUpgradeAfterCompletion ||
                (!isCompleted && (score > currentBestScore || translationJob?.isActive != true))
            if (!shouldStart) return true

            if (isUpgradeAfterCompletion) {
                logI(TAG, "Post-completion preemption: candidate '${subFile.lang}' (score: $score > current: $currentBestScore + $PREEMPTION_SCORE_MARGIN)")
                preemptionCount += 1
                lastPreemptionAtMs = now
                isCompleted = false
                completedFile = null
                completionLatch = CountDownLatch(1)
                AITranslatorLocalServer.registerSession(sessionKey, this)
            } else {
                logI(TAG, "Promoting subtitle candidate '${subFile.lang}' (score: $score > current: $currentBestScore)")
            }
            currentBestScore = score
            cancelCurrentJob()
            translationJob = sessionScope.launch {
                processCandidate(subFile, preAnnounceAfterDebounce = true)
            }
        }
        return true
    }

    /**
     * Explicitly starts a translation session for a candidate file (manual mode entry point).
     */
    fun startSession(subtitleFile: SubtitleFile): Job {
        cancelCurrentJob()
        synchronized(this) {
            if (manual) {
                // Allow retry after a HUD cancel without reopening the episode
                isCancelled = false
                isTargetLanguageBypassed = false
            }
            val score = SubtitlePriorityEvaluator.calculateScore(
                subFile = subtitleFile,
                targetLang = targetLang,
                priorityMode = TranslatorConfig.sourcePriorityMode,
                customList = TranslatorConfig.getCustomPriorityLanguages()
            )
            currentBestScore = maxOf(currentBestScore, score)
            isCompleted = false
            completedFile = null
            completionLatch = CountDownLatch(1)
            AITranslatorLocalServer.registerSession(sessionKey, this)
            val job = sessionScope.launch {
                processCandidate(subtitleFile, preAnnounceAfterDebounce = false)
            }
            translationJob = job
            return job
        }
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
     * Called when loadLinks starts for new media, playback is aborted, or user taps HUD Cancel.
     */
    @JvmOverloads
    fun cancelSession(userInitiated: Boolean = false) {
        if (isCancelled) return
        isCancelled = true
        try {
            loadLinksSettled?.complete(Unit)
        } catch (_: Throwable) {}
        completionLatch.countDown()
        AITranslatorLocalServer.unregisterSession(sessionKey)
        try {
            castResolutionJob?.cancel()
        } catch (_: Throwable) {}
        castResolutionJob = null
        cancelCurrentJob()
        try {
            if (!manual) sessionScope.cancel()
        } catch (_: Throwable) {}
        activeContextWindow?.reset()
        CharacterProfileManager.resetEpisodicState(mediaContext?.title)
        candidateList.clear()
        failedUrls.clear()
        synchronized(dispatchLock) {
            pendingChunkIndices.clear()
            currentChunks = null
        }
        if (userInitiated) {
            showToast("Đã hủy dịch phụ đề.")
        }
        if (TranslatorConfig.showProgressHUD || manual) {
            try {
                TranslationProgressHUD.dismiss()
            } catch (_: Throwable) {}
        }
        logI(TAG, "Translation session cancelled cleanly (userInitiated=$userInitiated).")
    }

    /**
     * Handles candidate failure by logging, tracking failed candidate URL, and dynamically falling back
     * to the next best viable candidate from candidateList if available.
     */
    private suspend fun onCandidateFailed(failedCandidate: SubtitleFile, errorMessage: String, silent: Boolean = false) {
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
            if (!silent && (TranslatorConfig.showProgressHUD || manual)) {
                try {
                    TranslationProgressHUD.showError(errorMessage)
                } catch (_: Throwable) {}
            }
        }
    }

    private data class ChunkBisectResult(
        val translations: Map<Int, String>,
        val droppedCueIds: Set<Int> = emptySet()
    )

    /**
     * Core processing pipeline executing download, cache validation, chunking,
     * translation, and playback delivery.
     */
    private suspend fun processCandidate(
        candidate: SubtitleFile,
        preAnnounceAfterDebounce: Boolean = false
    ) = withContext(Dispatchers.IO) {
        currentProcessingUrl = candidate.url

        try {
            // Brief debounce to allow higher-priority candidates from concurrent enrichers to arrive
            delay(INTAKE_DEBOUNCE_MS)
            // Wait for active baseApi.loadLinks window (if any) to settle before pre-announcing [AI]
            loadLinksSettled?.await()
            if (!isActive || isCancelled || isTargetLanguageBypassed) return@withContext

            if (preAnnounceAfterDebounce) {
                preAnnounceIfNeeded(candidate)
            }

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
            if (TranslatorConfig.showProgressHUD || manual) {
                try {
                    TranslationProgressHUD.show(
                        context = context,
                        modelName = effectiveModel,
                        targetLang = targetName,
                        initialStatus = "Đang nạp phụ đề gốc (${candidate.lang})...",
                        onCancel = { cancelSession(userInitiated = true) },
                        force = manual
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

            // Step 4: Tier 1 Cache Verification (0ms delivery on hit, with untranslated-cue poison guard)
            val cachedFile = SubtitleCacheManager.getFullFile(context, cacheKey)
            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0L) {
                val sourceCuesForVerify = SubtitleParser.parse(rawContent)
                val cachedCues = try {
                    SubtitleParser.parse(cachedFile.readText(Charsets.UTF_8))
                } catch (_: Throwable) {
                    emptyList()
                }
                val isCacheClean = sourceCuesForVerify.isNotEmpty() &&
                    cachedCues.size == sourceCuesForVerify.size &&
                    sourceCuesForVerify.zip(cachedCues).none { (src, tr) ->
                        isUntranslatedCue(src.rawText, tr.rawText, targetLang)
                    }
                if (isCacheClean) {
                    logI(TAG, "Tier 1 Full Cache HIT for key: $cacheKey")
                    completedFile = cachedFile
                    AITranslatorLocalServer.registerSessionKeyFile(sessionKey, cachedFile)
                    val streamUrl = AITranslatorLocalServer.registerSubtitle(cachedFile)
                    val trackLabel = buildAuthoritativeTrackLabel(targetLang, effectiveModel)
                    val finalUrl = appendCacheBuster(streamUrl, cachedFile)
                    notifyActivePlayer(trackLabel, finalUrl)
                    onFinalTrack?.invoke(SubtitleFile(lang = trackLabel, url = finalUrl))
                    showToast("[AI Translator] Đã nạp phụ đề $targetName từ bộ nhớ đệm.")
                    isCompleted = true
                    completionLatch.countDown()
                    if (TranslatorConfig.showProgressHUD || manual) {
                        try {
                            TranslationProgressHUD.showSuccess("Đã nạp phụ đề từ bộ nhớ đệm (100%)")
                        } catch (_: Throwable) {}
                    }
                    return@withContext
                } else {
                    logW(TAG, "Tier 1 Full Cache integrity failed for $cacheKey (src=${sourceCuesForVerify.size}, cached=${cachedCues.size}). Purging poisoned file.")
                    try { cachedFile.delete() } catch (_: Throwable) {}
                }
            }

            // Step 5: Parse Subtitle into Cues
            val cues = SubtitleParser.parse(rawContent)
            if (cues.isEmpty()) {
                logE(TAG, "Subtitle parser extracted 0 valid cues from content: ${candidate.url}")
                onCandidateFailed(candidate, "[AI Translator] Lỗi: Không phân tích được định dạng phụ đề.")
                return@withContext
            }

            // Step 5b: Partial-track safeguard (auto mode only). Rejects signs/songs/truncated tracks
            // before spending API tokens, and tries the next candidate instead.
            if (!manual) {
                val durationMs = try { playerDurationProvider?.invoke() } catch (_: Throwable) { null }
                if (SubtitleCoverageValidator.isLikelyPartial(cues, durationMs)) {
                    logW(TAG, "Rejecting partial subtitle candidate '${candidate.lang}' (${cues.size} cues, playerDurationMs=$durationMs). Trying next candidate.")
                    onCandidateFailed(
                        candidate,
                        "[AI Translator] Phụ đề nguồn có vẻ không đầy đủ (${candidate.lang}).",
                        silent = true
                    )
                    return@withContext
                }
            }

            // Step 6: Load Tier 2 Progressive Chunk Cache & Split Progressively (Chunk 0: 12-15, Chunks 1..N: 25-45)
            val existingTranslations = ConcurrentHashMap<Int, String>(SubtitleCacheManager.loadChunks(context, cacheKey))
            val maxChunkSize = TranslatorConfig.maxChunkCues.coerceIn(25, 50)
            val pauseMs = TranslatorConfig.dynamicScenePauseMs
            val chunks = splitIntoProgressiveChunks(
                cues = cues,
                chunk0Min = 12,
                chunk0Max = 15,
                minCues = minOf(25, maxChunkSize),
                maxCues = maxChunkSize,
                pauseThresholdMs = pauseMs
            )

            synchronized(dispatchLock) {
                currentChunks = chunks
                pendingChunkIndices.clear()
                pendingChunkIndices.addAll(chunks.indices)
                activePlayheadMs?.let { reprioritizePendingChunksLocked(it) }
            }

            val totalChunks = chunks.size
            val totalCues = cues.size
            val initialDoneChunks = chunks.count { it.all { cue -> existingTranslations.containsKey(cue.index) } }
            val initialDoneCues = cues.count { existingTranslations.containsKey(it.index) }
            val initialPct = if (totalCues > 0) (initialDoneCues * 100 / totalCues) else 0

            if (TranslatorConfig.showProgressHUD || manual) {
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

            val client = clientOverride ?: AITranslatorFactory.createClient(
                provider = TranslatorConfig.provider,
                apiKey = TranslatorConfig.apiKey,
                baseUrl = TranslatorConfig.customBaseUrl,
                modelName = effectiveModel
            )

            val lookbehindSize = TranslatorConfig.contextWindowSize.coerceIn(2, 12)
            val window = DialogueContextWindow(
                lookbehindSize = lookbehindSize,
                lookaheadSize = 8,
                maxLookbehindGapMs = 15000L
            )
            activeContextWindow = window

            val pacingInterval = if (clientOverride != null || TranslatorConfig.highSpeedMode) 0L else when (TranslatorConfig.provider) {
                TranslatorConfig.PROVIDER_GEMINI -> 1500L
                TranslatorConfig.PROVIDER_ANTHROPIC -> 2000L
                TranslatorConfig.PROVIDER_OPENAI -> 1000L
                else -> 1000L
            }
            val pacingGovernor = PacingGovernor(pacingInterval, highSpeedMode = TranslatorConfig.highSpeedMode)

            // Await non-blocking Cast Resolution job (if active) before Pass 0 Global Film Bible Pre-Scan
            val castJob = castResolutionJob
            if (castJob != null && castJob.isActive) {
                withTimeoutOrNull(3200L) { castJob.join() }
            }
            if (!isActive || isCancelled) return@withContext

            val titleKey = mediaContext?.title?.takeIf { it.isNotBlank() } ?: mediaPayload.takeIf { it.isNotBlank() }
            var registry = if (TranslatorConfig.enableCharacterProfiling) {
                val cleanTitle = titleKey?.let { AniListCastResolver.cleanAnimeTitle(it) }?.takeIf { it.isNotBlank() }
                (cleanTitle?.let { CharacterProfileManager.getProfile(context, it) }
                    ?: titleKey?.let { CharacterProfileManager.getProfile(context, it) }
                    ?: TwoTierCharacterRegistry(mediaTitle = titleKey ?: ""))
            } else {
                TwoTierCharacterRegistry()
            }

            // Pass 0: Global Film Bible Pre-Scan (when >= 40 cues and registry lacks rich Film Bible)
            val isRichBible = registry.relationships.isNotEmpty() ||
                registry.autoGlossary.isNotEmpty() ||
                (registry.toneGuide.isNotBlank() && !registry.toneGuide.startsWith("Genres:", ignoreCase = true))
            if (TranslatorConfig.enableCharacterProfiling && cues.size >= 40 && !isRichBible && !isCancelled && isActive) {
                if (TranslatorConfig.showProgressHUD || manual) {
                    try {
                        TranslationProgressHUD.updateStatus("Đang phân tích ngữ cảnh toàn phim...")
                    } catch (_: Throwable) {}
                }
                try {
                    val sampled = AITranslatorPromptBuilder.sampleRepresentativeCues(cues, maxSamples = 165)
                    val (sysPrompt, userPrompt) = AITranslatorPromptBuilder.buildFilmBiblePrompt(
                        mediaContext = mediaContext,
                        sampledCues = sampled,
                        targetLang = targetLang
                    )
                    val rawBible = client.generateJsonPrompt(sysPrompt, userPrompt, timeoutSeconds = 12L)
                    val bible = rawBible?.let { AITranslationParser.parseFilmBible(it) }
                    if (bible != null) {
                        val bibleReg = bible.toRegistry(titleKey ?: "")
                        val existingNames = bibleReg.characters.map { it.name.lowercase(Locale.ROOT) }.toSet()
                        val mergedChars = bibleReg.characters + registry.characters.filter {
                            it.name.lowercase(Locale.ROOT) !in existingNames
                        }
                        val mergedTone = bibleReg.toneGuide.ifBlank { registry.toneGuide }
                        val mergedGlossary = registry.autoGlossary + bibleReg.autoGlossary
                        registry = registry.copy(
                            mediaTitle = bibleReg.mediaTitle.ifBlank { registry.mediaTitle },
                            characters = mergedChars,
                            relationships = bibleReg.relationships,
                            autoGlossary = mergedGlossary,
                            toneGuide = mergedTone
                        )
                        if (!titleKey.isNullOrBlank()) {
                            val cleanTitle = AniListCastResolver.cleanAnimeTitle(titleKey)
                            if (cleanTitle.isNotBlank()) {
                                CharacterProfileManager.saveProfile(context, cleanTitle, registry, isEphemeral = false)
                            }
                            if (titleKey != cleanTitle) {
                                CharacterProfileManager.saveProfile(context, titleKey, registry, isEphemeral = false)
                            }
                        }
                        logI(TAG, "Pass 0 Global Film Bible extracted: ${registry.characters.size} chars, ${registry.relationships.size} rels, ${registry.autoGlossary.size} glossary terms.")
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logW(TAG, "Pass 0 Film Bible pre-scan skipped (non-blocking): ${t.message}")
                }
            }

            // Step 7: Group chunks into independent scene blocks (>= 15s gap) for optional highSpeedMode parallelism
            data class ChunkWorkItem(val index: Int, val cues: List<SubtitleCue>)
            val sceneGroups = mutableListOf<MutableList<ChunkWorkItem>>()
            for (idx in chunks.indices) {
                val chunk = chunks[idx]
                if (sceneGroups.isEmpty()) {
                    sceneGroups.add(mutableListOf(ChunkWorkItem(idx, chunk)))
                } else {
                    val prevLastEnd = chunks[idx - 1].last().endMs
                    val currFirstStart = chunk.first().startMs
                    if ((currFirstStart - prevLastEnd) >= 15_000L) {
                        sceneGroups.add(mutableListOf(ChunkWorkItem(idx, chunk)))
                    } else {
                        sceneGroups.last().add(ChunkWorkItem(idx, chunk))
                    }
                }
            }

            val quotaAborted = AtomicBoolean(false)

            val onDiscoveredChars: (Int, List<CharacterEntity>) -> Unit = { chunkIdx, discovered ->
                if (discovered.isNotEmpty() && TranslatorConfig.enableCharacterProfiling) {
                    synchronized(registryLock) {
                        val newChars = discovered.filter { d ->
                            registry.characters.none { it.name.equals(d.name, ignoreCase = true) }
                        }.map { it.copy(isEphemeral = true) }

                        if (newChars.isNotEmpty()) {
                            val updatedChars = registry.characters + newChars
                            registry = registry.copy(characters = updatedChars)
                            CharacterProfileManager.saveProfile(context, mediaContext?.title, registry, isEphemeral = true)
                            logI(TAG, "Chunk $chunkIdx dynamically registered ${newChars.size} new characters (ephemeral). Total cast: ${updatedChars.size}")
                        }
                    }
                }
            }

            val onDiscoveredRels: (Int, List<CharacterRelationship>) -> Unit = { chunkIdx, discoveredRels ->
                if (discoveredRels.isNotEmpty() && TranslatorConfig.enableCharacterProfiling) {
                    synchronized(registryLock) {
                        val newRels = discoveredRels.filter { d ->
                            registry.relationships.none { existing ->
                                existing.pair.map { it.lowercase() } == d.pair.map { it.lowercase() }
                            }
                        }.map { it.copy(isEphemeral = true) }

                        if (newRels.isNotEmpty()) {
                            val updatedRels = registry.relationships + newRels
                            registry = registry.copy(relationships = updatedRels)
                            CharacterProfileManager.saveProfile(context, mediaContext?.title, registry, isEphemeral = true)
                            logI(TAG, "Chunk $chunkIdx dynamically registered ${newRels.size} new relationships (ephemeral). Total relationships: ${updatedRels.size}")
                        }
                    }
                }
            }

            val sessionDroppedCueIds = ConcurrentHashMap.newKeySet<Int>()

            suspend fun processSingleChunk(
                index: Int,
                chunkCues: List<SubtitleCue>,
                sceneWindow: DialogueContextWindow,
                initialDigest: String
            ): String {
                var localDigest = initialDigest
                val isChunkFullyCached = chunkCues.all { existingTranslations.containsKey(it.index) }
                if (isChunkFullyCached) {
                    sceneWindow.recordFinalizedCues(chunkCues, existingTranslations)
                    localDigest = buildRollingSceneDigest(chunkCues, existingTranslations)
                    if (TranslatorConfig.showProgressHUD || manual) {
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
                    return localDigest
                }

                val lookbehind = sceneWindow.getLookbehind(chunkCues)
                val lookahead = sceneWindow.extractLookahead(cues, chunkCues.last().index)
                val prunedRegistry = if (TranslatorConfig.enableCharacterProfiling) {
                    synchronized(registryLock) {
                        sceneWindow.pruneRegistry(
                            fullRegistry = registry,
                            targetCues = chunkCues,
                            lookbehind = lookbehind,
                            lookahead = lookahead,
                            maxTokens = 1200.0,
                            maxCharacters = 20
                        )
                    }
                } else null

                val effectiveSourceLang = if (!candidate.lang.isNullOrBlank() && !candidate.lang.equals("auto", ignoreCase = true)) {
                    candidate.lang
                } else if (chunkCues.any { AITranslatorPromptBuilder.isJapaneseScript(it.rawText) }) {
                    "ja"
                } else {
                    candidate.lang
                }

                val chunkResult = translateChunkWithBisect(
                    client = client,
                    pacingGovernor = pacingGovernor,
                    sceneWindow = sceneWindow,
                    allCues = cues,
                    chunkIndex = index,
                    subCues = chunkCues,
                    sourceLang = effectiveSourceLang,
                    targetLang = targetLang,
                    glossary = TranslatorConfig.customGlossary,
                    lookbehind = lookbehind,
                    lookahead = lookahead,
                    prunedRegistry = prunedRegistry,
                    fullRegistryProvider = { synchronized(registryLock) { registry } },
                    mediaContext = mediaContext,
                    rollingDigest = localDigest,
                    onDigestUpdate = { localDigest = it },
                    quotaAborted = quotaAborted,
                    onDiscoveredCharacters = { discovered -> onDiscoveredChars(index, discovered) },
                    onDiscoveredRelationships = { discoveredRels -> onDiscoveredRels(index, discoveredRels) }
                )

                val translatedChunk = chunkResult.translations
                val droppedIds = chunkResult.droppedCueIds
                if (translatedChunk.isNotEmpty()) {
                    for (id in translatedChunk.keys) {
                        if (droppedIds.contains(id)) {
                            sessionDroppedCueIds.add(id)
                        } else {
                            sessionDroppedCueIds.remove(id)
                        }
                    }
                    existingTranslations.putAll(translatedChunk)
                    sceneWindow.recordFinalizedCues(chunkCues, translatedChunk)
                    localDigest = buildRollingSceneDigest(chunkCues, translatedChunk)

                    val cleanEntries = translatedChunk.filter { (id, text) ->
                        val c = chunkCues.firstOrNull { it.index == id }
                        c != null &&
                            !droppedIds.contains(id) &&
                            !isUntranslatedCue(
                                c.rawText,
                                SubtitleReconstructor.unmaskTags(text, c.tagMap),
                                targetLang
                            )
                    }
                    if (cleanEntries.isNotEmpty()) {
                        SubtitleCacheManager.saveChunk(context, cacheKey, index, cleanEntries)
                    }

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
                            if (manual) {
                                try {
                                    val interimUrl = AITranslatorLocalServer.getSubtitleUrl(sessionKey)
                                    val interimLabel = "[AI] ${getTargetLanguageDisplayName(targetLang)} (tạm)"
                                    emitTrack(interimLabel, interimUrl)
                                    onInterimTrack?.invoke(SubtitleFile(lang = interimLabel, url = interimUrl))
                                    logI(TAG, "Manual interim track auto-switched: '$interimLabel' -> $interimUrl")
                                } catch (t: Throwable) {
                                    logW(TAG, "Manual interim track switch failed: ${t.message}")
                                }
                            }
                        } catch (e: Throwable) {
                            logW(TAG, "Failed to assemble interim subtitle for Chunk 0: ${e.message}")
                        }
                    }

                    if (TranslatorConfig.showProgressHUD || manual) {
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
                return localDigest
            }

            suspend fun executeSceneGroup(group: List<ChunkWorkItem>, sceneWindow: DialogueContextWindow) {
                var localDigest = ""
                for (item in group) {
                    if (!currentCoroutineContext().isActive || isCancelled || quotaAborted.get()) break
                    val index = item.index
                    val chunkCues = item.cues
                    synchronized(dispatchLock) {
                        pendingChunkIndices.removeAll { it == index }
                    }
                    localDigest = processSingleChunk(index, chunkCues, sceneWindow, localDigest)
                }
            }

            if (TranslatorConfig.highSpeedMode && sceneGroups.size > 1) {
                logI(TAG, "Executing High-Speed Scene Parallelism (Semaphore 3) across ${sceneGroups.size} independent scene groups (${chunks.size} chunks).")
                val semaphore = Semaphore(3)
                coroutineScope {
                    sceneGroups.map { group ->
                        async {
                            semaphore.withPermit {
                                if (!isActive || isCancelled || quotaAborted.get()) return@withPermit
                                val groupWindow = DialogueContextWindow(
                                    lookbehindSize = lookbehindSize,
                                    lookaheadSize = 8,
                                    maxLookbehindGapMs = 15000L
                                )
                                executeSceneGroup(group, groupWindow)
                            }
                        }
                    }.awaitAll()
                }
            } else {
                logI(TAG, "Executing Sequential Batch Delivery across ${chunks.size} chunks (playhead-prioritized queue).")
                var seqDigest = ""
                var prevSeqIndex: Int? = null
                while (currentCoroutineContext().isActive && !isCancelled && !quotaAborted.get()) {
                    val nextIndex = synchronized(dispatchLock) {
                        if (pendingChunkIndices.isEmpty()) null else pendingChunkIndices.removeAt(0)
                    } ?: break
                    if (prevSeqIndex != null && nextIndex != prevSeqIndex + 1) {
                        seqDigest = ""
                    }
                    seqDigest = processSingleChunk(nextIndex, chunks[nextIndex], window, seqDigest)
                    prevSeqIndex = nextIndex
                }
            }

            if (!isActive || isCancelled) return@withContext

            // Step 8: Full Document Assembly & Tier 1 Cache Integrity Guard
            if (existingTranslations.isEmpty() && cues.isNotEmpty()) {
                logE(TAG, "All chunk translations failed for candidate: ${candidate.url}")
                onCandidateFailed(candidate, "[AI Translator] Lỗi: Dịch phụ đề thất bại.")
                return@withContext
            }

            val reconciledAll = SubtitleReconciler.autoHealingReconcile(cues, existingTranslations)
            val fullSrt = SubtitleReconstructor.buildSubRip(cues, reconciledAll)

            val failedOrUntranslated = cues.count { cue ->
                val t = existingTranslations[cue.index]
                t.isNullOrBlank() ||
                    (sessionDroppedCueIds.contains(cue.index) && hasDialogueLetters(cue.rawText)) ||
                    isUntranslatedCue(
                        cue.rawText,
                        SubtitleReconstructor.unmaskTags(t, cue.tagMap),
                        targetLang
                    )
            }
            val allClean = failedOrUntranslated == 0 && !quotaAborted.get()

            val fullFile = if (allClean) {
                SubtitleCacheManager.saveFullFile(context, cacheKey, fullSrt)
            } else {
                logW(TAG, "Translation incomplete ($failedOrUntranslated untranslated/missing cues, quotaAborted=${quotaAborted.get()}). Emitting _interim SRT without poisoning Tier 1 cache.")
                SubtitleCacheManager.saveFullFile(context, "${cacheKey}_interim", fullSrt)
            }
            completedFile = fullFile
            AITranslatorLocalServer.registerSessionKeyFile(sessionKey, fullFile)
            val streamUrl = AITranslatorLocalServer.registerSubtitle(fullFile)
            val authoritativeLabel = buildAuthoritativeTrackLabel(targetLang, effectiveModel)
            val finalUrl = appendCacheBuster(streamUrl, fullFile)

            logI(TAG, "Translation completed (allClean=$allClean). Emitting authoritative track: '$authoritativeLabel'")
            notifyActivePlayer(authoritativeLabel, finalUrl)
            try {
                onFinalTrack?.invoke(SubtitleFile(lang = authoritativeLabel, url = finalUrl))
            } catch (t: Throwable) {
                logW(TAG, "Final track callback failed: ${t.message}")
            }
            if (allClean) {
                showToast("[AI Translator] Phụ đề $targetName đã dịch xong và sẵn sàng!")
            } else {
                showToast("[AI Translator] Đã phát bản dịch tạm thời (còn $failedOrUntranslated câu chưa dịch).")
            }
            isCompleted = true
            completionLatch.countDown()
            if (TranslatorConfig.showProgressHUD || manual) {
                try {
                    if (allClean) {
                        TranslationProgressHUD.showSuccess("Hoàn tất 100%! Phụ đề đã sẵn sàng")
                    } else {
                        TranslationProgressHUD.showSuccess("Đã phát bản dịch tạm (thiếu $failedOrUntranslated câu)")
                    }
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            completionLatch.countDown()
            if (t is CancellationException) throw t
            logE(TAG, "Unexpected error in translation pipeline for '${candidate.lang}': ${t.message}", t)
            onCandidateFailed(candidate, "[AI Translator] Lỗi dịch phụ đề: ${t.message ?: "Không xác định"}")
        }
    }

    private fun buildRollingSceneDigest(chunkCues: List<SubtitleCue>, translations: Map<Int, String>): String {
        val snippets = chunkCues.takeLast(3).mapNotNull { cue ->
            val txt = (translations[cue.index] ?: cue.rawText).trim().replace("\n", " ")
            if (txt.isBlank()) return@mapNotNull null
            val prefix = if (!cue.speaker.isNullOrBlank()) "${cue.speaker}: " else ""
            "$prefix${txt.take(65)}"
        }
        return snippets.joinToString(" | ").take(240)
    }

    /**
     * Translates a chunk (or bisected subchunk) with bounded retry + Adaptive Bisect Split-Retry.
     * Triggers bisection down to min 8 cues per half (sequentially preserving lookbehind & digest)
     * on non-quota failures, truncated JSON, or excessive untranslated cues.
     */
    private suspend fun translateChunkWithBisect(
        client: AITranslationClient,
        pacingGovernor: PacingGovernor,
        sceneWindow: DialogueContextWindow,
        allCues: List<SubtitleCue>,
        chunkIndex: Int,
        subCues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
        glossary: String?,
        lookbehind: List<ContextCue>,
        lookahead: List<ContextCue>,
        prunedRegistry: TwoTierCharacterRegistry?,
        fullRegistryProvider: () -> TwoTierCharacterRegistry,
        mediaContext: MediaContext?,
        rollingDigest: String,
        onDigestUpdate: (String) -> Unit,
        quotaAborted: AtomicBoolean,
        onDiscoveredCharacters: ((List<CharacterEntity>) -> Unit)? = null,
        onDiscoveredRelationships: ((List<CharacterRelationship>) -> Unit)? = null
    ): ChunkBisectResult {
        var attempts = 0
        val maxAttempts = 2
        var bestPartial: Map<Int, String>? = null
        var bestDroppedIds: Set<Int> = emptySet()
        var bestPenalty = Int.MAX_VALUE

        while (attempts < maxAttempts) {
            if (!currentCoroutineContext().isActive || isCancelled || quotaAborted.get()) {
                return ChunkBisectResult(emptyMap())
            }
            attempts++
            pacingGovernor.pace()

            var shouldRetry = false
            try {
                val result = client.translateChunkWithSceneDigest(
                    cues = subCues,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    customGlossary = glossary,
                    lookbehind = lookbehind,
                    lookahead = lookahead,
                    characterRegistry = prunedRegistry,
                    mediaContext = mediaContext,
                    rollingSceneDigest = rollingDigest.takeIf { it.isNotBlank() }
                )

                if (result.isQuotaExhausted) {
                    logE(TAG, "Quota exhausted during chunk $chunkIndex translation.")
                    quotaAborted.set(true)
                    if (quotaToastShown.compareAndSet(false, true)) {
                        showToast("[AI Translator] Lỗi: Hết hạn mức API (Quota Exhausted)")
                        if (TranslatorConfig.showProgressHUD || manual) {
                            try {
                                TranslationProgressHUD.showError("Lỗi: Hết hạn mức API (Quota Exhausted)")
                            } catch (_: Throwable) {}
                        }
                    }
                    return ChunkBisectResult(emptyMap())
                }

                if (result.isSuccess && result.translations.isNotEmpty()) {
                    val reconcileDetail = SubtitleReconciler.autoHealingReconcileDetailed(subCues, result.translations)
                    val reconciled = reconcileDetail.translations
                    val missingDialogueIds = reconcileDetail.droppedCueIds.filterTo(LinkedHashSet()) { idx ->
                        subCues.firstOrNull { it.index == idx }?.let { hasDialogueLetters(it.rawText) } == true
                    }
                    val droppedCueCount = missingDialogueIds.size
                    val untranslatedCount = subCues.count { cue ->
                        isUntranslatedCue(
                            cue.rawText,
                            SubtitleReconstructor.unmaskTags(reconciled[cue.index] ?: "", cue.tagMap),
                            targetLang
                        )
                    }
                    val qualityPenalty = droppedCueCount + untranslatedCount

                    if (qualityPenalty < bestPenalty) {
                        bestPenalty = qualityPenalty
                        bestPartial = reconciled
                        bestDroppedIds = missingDialogueIds
                    }

                    if (droppedCueCount == 0 && untranslatedCount == 0) {
                        if (!result.discoveredCharacters.isNullOrEmpty()) {
                            onDiscoveredCharacters?.invoke(result.discoveredCharacters)
                        }
                        if (!result.rawResponse.isNullOrBlank()) {
                            val discoveredRels = parseDiscoveredRelationships(result.rawResponse)
                            if (discoveredRels.isNotEmpty()) {
                                onDiscoveredRelationships?.invoke(discoveredRels)
                            }
                        }
                        return ChunkBisectResult(reconciled, emptySet())
                    }

                    logW(TAG, "Chunk $chunkIndex (size=${subCues.size}) quality check failed on attempt $attempts (dropped=$droppedCueCount, untranslated=$untranslatedCount).")
                    shouldRetry = attempts < maxAttempts
                } else {
                    logW(TAG, "Chunk $chunkIndex (size=${subCues.size}) failed on attempt $attempts: ${result.errorMessage}")
                    shouldRetry = result.retryable && attempts < maxAttempts
                    if (!result.retryable && subCues.size >= 16) {
                        // Break out immediately to bisect large chunk on non-retryable / parse failure
                        break
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logW(TAG, "Chunk $chunkIndex (size=${subCues.size}) exception on attempt $attempts: ${e.message}")
                shouldRetry = attempts < maxAttempts
            }

            if (shouldRetry) {
                delay(1000L * attempts)
            } else {
                break
            }
        }

        // Adaptive Bisect Split-Retry when chunk >= 16 cues (min 8 cues per half)
        if (subCues.size >= 16 && !isCancelled && !quotaAborted.get() && currentCoroutineContext().isActive) {
            val mid = subCues.size / 2
            val leftCues = subCues.subList(0, mid)
            val rightCues = subCues.subList(mid, subCues.size)
            logI(TAG, "Adaptive Bisect Split-Retry on chunk $chunkIndex: ${subCues.size} -> ${leftCues.size} + ${rightCues.size}")

            val leftLookahead = sceneWindow.extractLookahead(allCues, leftCues.last().index)
            val leftPruned = if (TranslatorConfig.enableCharacterProfiling) {
                synchronized(registryLock) {
                    sceneWindow.pruneRegistry(fullRegistryProvider(), leftCues, lookbehind, leftLookahead, 1200.0, 20)
                }
            } else null

            var currentDigest = rollingDigest
            val leftRes = translateChunkWithBisect(
                client = client,
                pacingGovernor = pacingGovernor,
                sceneWindow = sceneWindow,
                allCues = allCues,
                chunkIndex = chunkIndex,
                subCues = leftCues,
                sourceLang = sourceLang,
                targetLang = targetLang,
                glossary = glossary,
                lookbehind = lookbehind,
                lookahead = leftLookahead,
                prunedRegistry = leftPruned,
                fullRegistryProvider = fullRegistryProvider,
                mediaContext = mediaContext,
                rollingDigest = currentDigest,
                onDigestUpdate = {
                    currentDigest = it
                    onDigestUpdate(it)
                },
                quotaAborted = quotaAborted,
                onDiscoveredCharacters = onDiscoveredCharacters,
                onDiscoveredRelationships = onDiscoveredRelationships
            )

            if (leftRes.translations.isNotEmpty()) {
                sceneWindow.recordFinalizedCues(leftCues, leftRes.translations)
                currentDigest = buildRollingSceneDigest(leftCues, leftRes.translations)
                onDigestUpdate(currentDigest)
            }

            if (isCancelled || quotaAborted.get() || !currentCoroutineContext().isActive) {
                return leftRes
            }

            val rightLookbehind = sceneWindow.getLookbehind(rightCues)
            val rightLookahead = sceneWindow.extractLookahead(allCues, rightCues.last().index)
            val rightPruned = if (TranslatorConfig.enableCharacterProfiling) {
                synchronized(registryLock) {
                    sceneWindow.pruneRegistry(fullRegistryProvider(), rightCues, rightLookbehind, rightLookahead, 1200.0, 20)
                }
            } else null

            val rightRes = translateChunkWithBisect(
                client = client,
                pacingGovernor = pacingGovernor,
                sceneWindow = sceneWindow,
                allCues = allCues,
                chunkIndex = chunkIndex,
                subCues = rightCues,
                sourceLang = sourceLang,
                targetLang = targetLang,
                glossary = glossary,
                lookbehind = rightLookbehind,
                lookahead = rightLookahead,
                prunedRegistry = rightPruned,
                fullRegistryProvider = fullRegistryProvider,
                mediaContext = mediaContext,
                rollingDigest = currentDigest,
                onDigestUpdate = onDigestUpdate,
                quotaAborted = quotaAborted,
                onDiscoveredCharacters = onDiscoveredCharacters,
                onDiscoveredRelationships = onDiscoveredRelationships
            )

            val combined = leftRes.translations + rightRes.translations
            val combinedDropped = leftRes.droppedCueIds + rightRes.droppedCueIds
            if (combined.isNotEmpty()) return ChunkBisectResult(combined, combinedDropped)
        }

        return ChunkBisectResult(bestPartial ?: emptyMap(), bestDroppedIds)
    }

    /**
     * Emits a track safely to playerSubtitleCallback, guarding against exceptions and duplicate emissions.
     */
    private fun emitTrack(label: String, url: String) {
        if (isCancelled) return
        val key = "$label|$url"
        if (emittedUrls.add(key)) {
            try {
                ownTrackPaths.add(trackPath(url))
                playerSubtitleCallback(SubtitleFile(lang = label, url = url))
            } catch (t: Throwable) {
                logW(TAG, "Error invoking playerSubtitleCallback: ${t.message}")
            }
        }
    }

    private fun trackPath(url: String): String {
        return try {
            URI(url.substringBefore("?")).path ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun appendCacheBuster(url: String, file: File?): String {
        if (url.contains("?")) return url
        val version = try { file?.lastModified() ?: 0L } catch (_: Throwable) { 0L }
        return "$url?v=$version"
    }

    private fun resolveFallbackHeadersFromLinks(targetUrl: String): Map<String, String> {
        if (recordedLinks.isEmpty()) return emptyMap()
        val targetHost = try { URI(targetUrl).host?.lowercase(Locale.ROOT) } catch (_: Throwable) { null }
        val matchingLink = recordedLinks.firstOrNull { link ->
            val linkHost = try { URI(link.url).host?.lowercase(Locale.ROOT) } catch (_: Throwable) { null }
            targetHost != null && linkHost == targetHost && (link.headers.isNotEmpty() || link.referer.isNotBlank())
        } ?: recordedLinks.firstOrNull { it.headers.isNotEmpty() || it.referer.isNotBlank() }
            ?: return emptyMap()

        val merged = LinkedHashMap<String, String>()
        merged.putAll(matchingLink.headers)
        if (matchingLink.referer.isNotBlank()) {
            merged.putIfAbsent("Referer", matchingLink.referer)
        }
        return merged
    }

    private suspend fun executeCancellableBytesCall(
        client: okhttp3.OkHttpClient,
        request: okhttp3.Request
    ): Pair<Int, ByteArray?> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        val completed = AtomicBoolean(false)
        val cancelledByCoroutine = AtomicBoolean(false)

        cont.invokeOnCancellation {
            cancelledByCoroutine.set(true)
            try {
                call.cancel()
            } catch (_: Throwable) {}
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (completed.compareAndSet(false, true) && cont.isActive) {
                    if (cancelledByCoroutine.get()) {
                        cont.resumeWithException(CancellationException("Subtitle download cancelled", e))
                    } else {
                        cont.resumeWithException(e)
                    }
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val result = response.use { resp ->
                        val bytes = if (resp.isSuccessful) resp.body?.bytes() else null
                        Pair(resp.code, bytes)
                    }
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(result)
                    }
                } catch (t: Throwable) {
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        if (cancelledByCoroutine.get()) {
                            cont.resumeWithException(CancellationException("Subtitle body read cancelled", t))
                        } else {
                            cont.resumeWithException(t)
                        }
                    }
                }
            }
        })
    }

    /**
     * Ingests subtitle bytes from HTTP/HTTPS, loopback, or file URIs.
     * Supports 0ms coroutine-cancellable OkHttp calls, ExtractorLink referer/headers fallback,
     * and recursive HLS WebVTT (.m3u8) segment unrolling.
     */
    private suspend fun fetchSubtitleBytes(
        rawUrl: String,
        headers: Map<String, String>? = null,
        unrollHls: Boolean = true
    ): ByteArray? = withContext(Dispatchers.IO) {
        val url = rawUrl.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val fallbackHeaders = resolveFallbackHeadersFromLinks(url)
        val effectiveHeaders: Map<String, String> = if (!headers.isNullOrEmpty()) {
            headers
        } else {
            fallbackHeaders
        }

        val fetchedBytes: ByteArray? = try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    val client = try {
                        com.lagradost.cloudstream3.app.baseClient
                    } catch (_: Throwable) {
                        fallbackClient
                    }

                    fun buildReq(reqHeaders: Map<String, String>): okhttp3.Request {
                        val reqBuilder = okhttp3.Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept", "*/*")
                        reqHeaders.forEach { (k, v) ->
                            if (k.isNotBlank()) reqBuilder.header(k, v)
                        }
                        return reqBuilder.build()
                    }

                    var (code, bytes) = executeCancellableBytesCall(client, buildReq(effectiveHeaders))
                    if ((code == 401 || code == 403) && fallbackHeaders.isNotEmpty()) {
                        val mergedRetryHeaders = LinkedHashMap<String, String>()
                        mergedRetryHeaders.putAll(fallbackHeaders)
                        effectiveHeaders.forEach { (k, v) -> mergedRetryHeaders[k] = v }
                        if (mergedRetryHeaders != effectiveHeaders) {
                            logI(TAG, "HTTP $code fetching $url; retrying with captured ExtractorLink headers/referer.")
                            val retryRes = executeCancellableBytesCall(client, buildReq(mergedRetryHeaders))
                            code = retryRes.first
                            bytes = retryRes.second
                        }
                    }
                    if (bytes == null && code !in 200..299) {
                        logW(TAG, "OkHttp error HTTP $code fetching subtitle from $url")
                    }
                    bytes
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logW(TAG, "OkHttp fetch failed for $url: ${t.message}. Trying URLConnection fallback.")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    connection.setRequestProperty("Accept", "*/*")
                    effectiveHeaders.forEach { (k, v) ->
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

        if (fetchedBytes != null && unrollHls) {
            val preview = try { String(fetchedBytes, Charsets.UTF_8) } catch (_: Throwable) { "" }
            if (StreamSubtitleExtractor.isHlsPlaylist(preview, url)) {
                logI(TAG, "Detected HLS .m3u8 subtitle playlist at $url; unrolling VTT segments...")
                return@withContext StreamSubtitleExtractor.unrollHlsVttPlaylist(
                    playlistText = preview,
                    playlistUrl = url,
                    headers = effectiveHeaders,
                    fetchBytes = { segUrl, segHeaders -> fetchSubtitleBytes(segUrl, segHeaders, unrollHls = false) },
                    depth = 0
                )
            }
        }

        fetchedBytes
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
