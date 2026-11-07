@file:Suppress("DEPRECATION")
package tests.adversarial

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.subtitles.ai.AITranslatorPlugin
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslationClient
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitlePriorityEvaluator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting Phase 4 Dynamic Outermost Hook & Delivery Sessions Test Suite...")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun check(testName: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $testName")
            passed++
        } else {
            System.err.println("  [FAIL] $testName -> $details")
            failed++
        }
    }

    // -------------------------------------------------------------------------
    // Mock Base Providers and Mock Peer Enrichers
    // -------------------------------------------------------------------------
    open class MockBaseProvider(name: String, url: String) : MainAPI() {
        init {
            this.name = name
            this.mainUrl = url
        }
        override val supportedTypes: Set<TvType> get() = setOf(TvType.Anime, TvType.Movie)
        override val hasMainPage: Boolean get() = true
        override val hasQuickSearch: Boolean get() = true
        override suspend fun search(query: String): List<SearchResponse>? = emptyList()
        override suspend fun load(url: String): LoadResponse? = null
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean = true
    }

    // Peer enricher 1: Anime47-style wrapper declaring `var original: MainAPI`
    class MockAnime47EnrichedAPI(var original: MainAPI) : MainAPI() {
        init {
            this.name = original.name
            this.mainUrl = original.mainUrl
            this.lang = "vi"
        }
    }

    // Peer enricher 2: Kitsunekko-style wrapper declaring `var baseApi: MainAPI`
    class MockKitsunekkoEnrichedAPI(var baseApi: MainAPI) : MainAPI() {
        init {
            this.name = baseApi.name
            this.mainUrl = baseApi.mainUrl
            this.lang = "ja"
        }
    }

    // Peer enricher 3: Stremio-style wrapper declaring `var baseApi: MainAPI`
    class MockStremioEnrichedAPI(var baseApi: MainAPI) : MainAPI() {
        init {
            this.name = baseApi.name
            this.mainUrl = baseApi.mainUrl
            this.lang = "en"
        }
    }

    // Late enricher simulating an enricher loaded after AI Translator
    class MockLateEnricher(var baseApi: MainAPI) : MainAPI() {
        init {
            this.name = baseApi.name
            this.mainUrl = baseApi.mainUrl
        }
    }

    // =========================================================================
    // SECTION 1: Outermost Invariant Test (Triple Enricher Chaining)
    // =========================================================================
    println("\n--- [Section 1: Outermost Invariant Test] ---")
    val leafTorrentio = MockBaseProvider("Torrentio", "https://torrentio.strem.fun")
    val a47Tier = MockAnime47EnrichedAPI(leafTorrentio)
    val kitsuTier = MockKitsunekkoEnrichedAPI(a47Tier)
    val stremioTier = MockStremioEnrichedAPI(kitsuTier)

    // Clear and set APIHolder providers
    APIHolder.allProviders.clear()
    APIHolder.allProviders.add(stremioTier)

    AITranslatorHook.scanAndHoistHooks()

    check("APIHolder[0] hoisted to AITranslatorEnrichedMainAPI",
        APIHolder.allProviders.size == 1 && APIHolder.allProviders[0] is AITranslatorEnrichedMainAPI
    )

    val aiOutermost = APIHolder.allProviders[0] as AITranslatorEnrichedMainAPI
    check("Immediate delegate of AI wrapper is StremioEnrichedAPI",
        aiOutermost.baseApi === stremioTier
    )

    val resolvedBase = AITranslatorHook.getBaseProvider(aiOutermost)
    check("getBaseProvider successfully traverses 4-tier chain to leaf Torrentio",
        resolvedBase === leafTorrentio
    )

    val inner1 = AITranslatorHook.getInnerDelegate(aiOutermost)
    val inner2 = inner1?.let { AITranslatorHook.getInnerDelegate(it) }
    val inner3 = inner2?.let { AITranslatorHook.getInnerDelegate(it) }
    val inner4 = inner3?.let { AITranslatorHook.getInnerDelegate(it) }

    check("Chain hierarchy strictly ordered: AI -> Stremio -> Kitsunekko -> Anime47 -> Leaf",
        inner1 === stremioTier && inner2 === kitsuTier && inner3 === a47Tier && inner4 === leafTorrentio
    )

    // =========================================================================
    // SECTION 2: Idempotency & Unwrap Test
    // =========================================================================
    println("\n--- [Section 2: Idempotency & Late-Attachment Unwrap Test] ---")

    // Run scanAndHoistHooks 5 times in a loop
    for (pass in 1..5) {
        AITranslatorHook.scanAndHoistHooks()
    }

    check("Idempotent scanning: exactly 1 wrapper at head",
        APIHolder.allProviders.size == 1 && APIHolder.allProviders[0] is AITranslatorEnrichedMainAPI
    )
    val afterLoopHead = APIHolder.allProviders[0] as AITranslatorEnrichedMainAPI
    check("Idempotent scanning: inner delegate is NOT an AI wrapper (zero wrapper duplication)",
        afterLoopHead.baseApi !is AITranslatorEnrichedMainAPI
    )

    // Simulate late-attaching peer enricher wrapping our AI wrapper
    val lateEnricher = MockLateEnricher(afterLoopHead)
    APIHolder.allProviders[0] = lateEnricher

    // Scan should detect AI wrapper buried inside LateEnricher and hoist it back to outermost
    AITranslatorHook.scanAndHoistHooks()

    val reHoisted = APIHolder.allProviders[0] as? AITranslatorEnrichedMainAPI
    check("Late attachment: AI wrapper re-hoisted to outermost head",
        reHoisted != null && reHoisted.baseApi === lateEnricher
    )

    val lateDelegate = AITranslatorHook.getInnerDelegate(lateEnricher)
    check("Late attachment: buried AI wrapper spliced out of lateEnricher's delegate",
        lateDelegate === stremioTier && lateDelegate !is AITranslatorEnrichedMainAPI
    )

    // Complete unwrap test
    val completelyUnwrapped = AITranslatorHook.unwrapAITranslatorChain(APIHolder.allProviders[0])
    check("unwrapAITranslatorChain strips all AI decorators",
        completelyUnwrapped === lateEnricher &&
        AITranslatorHook.getInnerDelegate(completelyUnwrapped) === stremioTier
    )

    // =========================================================================
    // SECTION 3: Plugin Mapping Synchronization Test
    // =========================================================================
    println("\n--- [Section 3: Plugin Mapping Synchronization Test] ---")
    val animePaheLeaf = MockBaseProvider("AnimePahe", "https://animepahe.ru")
    APIHolder.allProviders.clear()
    APIHolder.allProviders.add(animePaheLeaf)

    AITranslatorHook.scanAndHoistHooks()

    val mappedApi = APIHolder.getApiFromNameNull("AnimePahe")
    check("Plugin mapping registered hoisted AITranslatorEnrichedMainAPI for Search/Bookmarks",
        mappedApi is AITranslatorEnrichedMainAPI && mappedApi.name == "AnimePahe"
    )

    val pluginInstance = AITranslatorPlugin()
    pluginInstance.beforeUnload()

    val restoredApi = APIHolder.getApiFromNameNull("AnimePahe")
    check("Plugin beforeUnload cleanly restores original provider in mapping registry",
        restoredApi === animePaheLeaf
    )
    check("APIHolder.allProviders restored to unwrapped base provider",
        APIHolder.allProviders[0] === animePaheLeaf
    )

    // =========================================================================
    // SECTION 4: 0ms Immediate Passthrough Test
    // =========================================================================
    println("\n--- [Section 4: 0ms Immediate Passthrough Test] ---")
    class PassthroughProvider : MockBaseProvider("StreamHub", "https://stream.hub") {
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            // Emit 5 video links
            for (i in 1..5) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Server $i",
                        url = "https://cdn.stream.hub/video$i.m3u8",
                        referer = "https://stream.hub",
                        quality = 1080,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
            // Emit 2 original subtitles
            subtitleCallback(SubtitleFile("English (Original)", "https://stream.hub/en.srt"))
            subtitleCallback(SubtitleFile("Japanese (Original)", "https://stream.hub/ja.ass"))
            return true
        }
    }

    val passthroughEnriched = AITranslatorEnrichedMainAPI(PassthroughProvider())
    val receivedLinks = CopyOnWriteArrayList<ExtractorLink>()
    val receivedSubs = CopyOnWriteArrayList<SubtitleFile>()

    val startTime = System.currentTimeMillis()
    val loadSuccess = passthroughEnriched.loadLinks(
        data = "https://stream.hub/watch/123",
        isCasting = false,
        subtitleCallback = { sub -> receivedSubs.add(sub) },
        callback = { link -> receivedLinks.add(link) }
    )
    val elapsedMs = System.currentTimeMillis() - startTime

    check("loadLinks executed successfully", loadSuccess)
    check("5/5 video extractor links passed through synchronously", receivedLinks.size == 5)
    check("2/2 original subtitles passed through immediately", receivedSubs.size == 2)
    check("Passthrough latency < 150ms (actual: ${elapsedMs}ms)", elapsedMs < 150L)
    passthroughEnriched.activeSession?.cancelSession()

    // =========================================================================
    // SECTION 5: Crash Barrier Isolation Test
    // =========================================================================
    println("\n--- [Section 5: Crash Barrier Isolation Test] ---")
    class CrashingLeafProvider : MockBaseProvider("BrokenLeaf", "https://broken.leaf") {
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            throw RuntimeException("Fatal leaf scraper decoder corrupted or network timeout")
        }
    }

    val crashingEnriched = AITranslatorEnrichedMainAPI(CrashingLeafProvider())
    var exceptionEscaped = false
    var resultFalse = false

    try {
        resultFalse = !crashingEnriched.loadLinks(
            data = "media_id_fail",
            isCasting = false,
            subtitleCallback = {},
            callback = {}
        )
    } catch (_: Throwable) {
        exceptionEscaped = true
    }

    check("Crash barrier: zero exceptions escaped loadLinks", !exceptionEscaped)
    check("Crash barrier: returned false gracefully", resultFalse)
    check("Crash barrier: activeSession cleaned up", crashingEnriched.activeSession == null)

    // =========================================================================
    // SECTION 6: Strategy A Batch Delivery Test (4-Worker Concurrency)
    // =========================================================================
    println("\n--- [Section 6: Strategy A (Fast Batch Delivery)] ---")
    TranslatorConfig.resetToDefaults()
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_A
    TranslatorConfig.targetLanguage = "vi"
    SubtitleCacheManager.clearAll(null)

    // Start local HTTP loopback server for subtitle streaming
    AITranslatorLocalServer.start(null)

    // Prepare mock subtitle with 60 cues (2 chunks of 30 cues)
    val srtContentBuilder = StringBuilder()
    for (i in 1..60) {
        val s = String.format("%02d", i % 60)
        srtContentBuilder.append("$i\n00:00:$s,000 --> 00:00:$s,900\nHello world dialogue line $i\n\n")
    }
    val rawSubText = srtContentBuilder.toString()
    val tempSubFile = File.createTempFile("p4_test_sub_", ".srt")
    tempSubFile.writeText(rawSubText, Charsets.UTF_8)
    tempSubFile.deleteOnExit()

    val emittedA = CopyOnWriteArrayList<SubtitleFile>()
    val mockClientA = object : AITranslationClient {
        val callCount = AtomicInteger(0)
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            callCount.incrementAndGet()
            val map = cues.associate { it.index to "Bản dịch tiếng Việt dòng ${it.index}" }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val sessionA = AITranslationSession(
        mediaPayload = "media_strategy_a",
        targetLang = "vi",
        playerSubtitleCallback = { sub -> emittedA.add(sub) },
        deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_A,
        clientOverride = mockClientA
    )

    sessionA.startSession(SubtitleFile("English", tempSubFile.absolutePath)).join()

    check("Strategy A: exactly 1 authoritative track emitted", emittedA.size == 1)
    val trackA = emittedA.firstOrNull()
    check("Strategy A: track label formatted with target language & model",
        trackA != null && trackA.lang.contains("Tiếng Việt") && trackA.lang.contains("[AI:")
    )
    check("Strategy A: track URL streamed via loopback server",
        trackA != null && trackA.url.startsWith("http://127.0.0.1:")
    )

    // Read streamed file
    if (trackA != null) {
        val streamPath = trackA.url.substringAfter("/sub/")
        val srtFile = File(SubtitleCacheManager.getFullDir(null), streamPath)
        val srtBody = if (srtFile.exists()) srtFile.readText() else java.net.URL(trackA.url).openStream().use { it.bufferedReader().readText() }
        check("Strategy A: emitted file contains 60 cues with valid timecodes",
            srtBody.contains("60\n") && srtBody.contains("Bản dịch tiếng Việt dòng 60")
        )
    }

    // =========================================================================
    // SECTION 7: Strategy B Skeleton Delivery Test
    // =========================================================================
    println("\n--- [Section 7: Strategy B (Progressive Skeleton Delivery)] ---")
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B
    TranslatorConfig.targetLanguage = "vi"
    SubtitleCacheManager.clearAll(null)

    val emittedB = CopyOnWriteArrayList<SubtitleFile>()
    val mockClientB = object : AITranslationClient {
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            // Introduce slight delay on chunk 2 so Chunk 1 skeleton is emitted first
            if (cues.firstOrNull()?.index ?: 0 > 30) {
                delay(120)
            }
            val map = cues.associate { it.index to "Dịch tiến trình dòng ${it.index}" }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    // Use fresh temp file with different text to avoid cache collision
    val tempSubFileB = File.createTempFile("p4_test_sub_b_", ".srt")
    tempSubFileB.writeText(rawSubText.replace("dialogue line", "dialogue B"), Charsets.UTF_8)
    tempSubFileB.deleteOnExit()

    val sessionB = AITranslationSession(
        mediaPayload = "media_strategy_b",
        targetLang = "vi",
        playerSubtitleCallback = { sub -> emittedB.add(sub) },
        deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B,
        clientOverride = mockClientB
    )

    sessionB.startSession(SubtitleFile("English", tempSubFileB.absolutePath)).join()

    check("Strategy B: emitted 2 tracks (skeleton draft + authoritative full)",
        emittedB.size == 2
    )

    val draftTrack = emittedB.getOrNull(0)
    val fullTrack = emittedB.getOrNull(1)

    check("Strategy B: first track is draft skeleton marked '(Đang dịch...)'",
        draftTrack != null && draftTrack.lang.contains("(Đang dịch...)") && draftTrack.url.contains("_v1.srt")
    )
    check("Strategy B: second track is authoritative marked '(Hoàn tất)'",
        fullTrack != null && fullTrack.lang.contains("(Hoàn tất)") && fullTrack.url.contains("_full.srt")
    )

    if (draftTrack != null) {
        val draftPath = draftTrack.url.substringAfter("/sub/")
        val draftFile = File(SubtitleCacheManager.getFullDir(null), draftPath)
        val draftText = if (draftFile.exists()) draftFile.readText() else java.net.URL(draftTrack.url).openStream().use { it.bufferedReader().readText() }
        check("Strategy B skeleton: Chunk 1 (cues 1-30) contains translated text",
            draftText.contains("Dịch tiến trình dòng 1")
        )
        check("Strategy B skeleton: Cues 31-60 contain original dialogue fallback (single-sample loading compliant)",
            draftText.contains("dialogue B 60")
        )
    }

    if (fullTrack != null) {
        val completedPath = fullTrack.url.substringAfter("/sub/")
        val completedFile = File(SubtitleCacheManager.getFullDir(null), completedPath)
        val completedText = if (completedFile.exists()) completedFile.readText() else java.net.URL(fullTrack.url).openStream().use { it.bufferedReader().readText() }
        check("Strategy B authoritative: all 60 cues translated in final track",
            completedText.contains("Dịch tiến trình dòng 60")
        )
    }

    // =========================================================================
    // SECTION 8: Target Language Bypass Test (-1000 Pts & 0 Token Waste)
    // =========================================================================
    println("\n--- [Section 8: Target Language Bypass Test] ---")
    TranslatorConfig.targetLanguage = "vi"
    val bypassCallCount = AtomicInteger(0)
    val bypassClient = object : AITranslationClient {
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            bypassCallCount.incrementAndGet()
            return TranslationResult(success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val sessionBypass = AITranslationSession(
        mediaPayload = "media_bypass",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = bypassClient
    )

    val viSubCandidate = SubtitleFile("Tiếng Việt (Anime47)", "https://anime47.com/vi.srt")
    val calculatedScore = SubtitlePriorityEvaluator.calculateScore(
        subFile = viSubCandidate,
        targetLang = "vi",
        priorityMode = TranslatorConfig.sourcePriorityMode,
        customList = TranslatorConfig.getCustomPriorityLanguages()
    )

    check("Evaluator returns SCORE_BYPASS_TARGET_LANG (-1000) for Vietnamese softsub",
        calculatedScore == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
    )

    sessionBypass.queueCandidate(viSubCandidate)
    delay(250) // Wait beyond debounce interval

    check("Target Language Bypass: isBypassed is marked true",
        sessionBypass.isBypassed
    )
    check("Target Language Bypass: 0 AI requests dispatched (zero token waste)",
        bypassCallCount.get() == 0
    )

    // =========================================================================
    // SECTION 9: Multi-Charset Decoding & BOM Detection Test
    // =========================================================================
    println("\n--- [Section 9: Multi-Charset Decoding & BOM Detection Test] ---")
    val mockCharsetClient = object : AITranslationClient {
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            val map = cues.associate { it.index to "Dịch dòng ${it.index}" }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    // 9.1 UTF-16LE with BOM
    val utf16LeSrt = "1\n00:00:01,000 --> 00:00:04,000\nHello UTF-16LE Subtitle\n\n"
    val utf16LeBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16LeSrt.toByteArray(Charsets.UTF_16LE)
    val utf16LeFile = File.createTempFile("p4_test_utf16le_", ".srt").apply {
        writeBytes(utf16LeBytes)
        deleteOnExit()
    }
    val emittedUtf16Le = CopyOnWriteArrayList<SubtitleFile>()
    val sessionUtf16Le = AITranslationSession(
        mediaPayload = "media_utf16le",
        targetLang = "vi",
        playerSubtitleCallback = { emittedUtf16Le.add(it) },
        clientOverride = mockCharsetClient
    )
    sessionUtf16Le.startSession(SubtitleFile("UTF16LE", utf16LeFile.absolutePath)).join()
    check("UTF-16LE with BOM decoded cleanly into valid subtitle track",
        sessionUtf16Le.isCompleted && emittedUtf16Le.isNotEmpty()
    )

    // 9.2 UTF-16BE with BOM
    val utf16BeSrt = "1\n00:00:01,000 --> 00:00:04,000\nHello UTF-16BE Subtitle\n\n"
    val utf16BeBytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + utf16BeSrt.toByteArray(Charsets.UTF_16BE)
    val utf16BeFile = File.createTempFile("p4_test_utf16be_", ".srt").apply {
        writeBytes(utf16BeBytes)
        deleteOnExit()
    }
    val emittedUtf16Be = CopyOnWriteArrayList<SubtitleFile>()
    val sessionUtf16Be = AITranslationSession(
        mediaPayload = "media_utf16be",
        targetLang = "vi",
        playerSubtitleCallback = { emittedUtf16Be.add(it) },
        clientOverride = mockCharsetClient
    )
    sessionUtf16Be.startSession(SubtitleFile("UTF16BE", utf16BeFile.absolutePath)).join()
    check("UTF-16BE with BOM decoded cleanly into valid subtitle track",
        sessionUtf16Be.isCompleted && emittedUtf16Be.isNotEmpty()
    )

    // 9.3 Shift-JIS Japanese Subtitle
    val sjisSrt = "1\n00:00:01,000 --> 00:00:04,000\nこんにちは世界！\n\n"
    val sjisBytes = sjisSrt.toByteArray(java.nio.charset.Charset.forName("Windows-31J"))
    val sjisFile = File.createTempFile("p4_test_sjis_", ".srt").apply {
        writeBytes(sjisBytes)
        deleteOnExit()
    }
    val emittedSjis = CopyOnWriteArrayList<SubtitleFile>()
    val sessionSjis = AITranslationSession(
        mediaPayload = "media_sjis",
        targetLang = "vi",
        playerSubtitleCallback = { emittedSjis.add(it) },
        clientOverride = mockCharsetClient
    )
    sessionSjis.startSession(SubtitleFile("ShiftJIS", sjisFile.absolutePath)).join()
    check("Shift-JIS / Windows-31J subtitle decoded cleanly into valid subtitle track",
        sessionSjis.isCompleted && emittedSjis.isNotEmpty()
    )

    // 9.4 UTF-8 with BOM
    val utf8BomSrt = "1\n00:00:01,000 --> 00:00:04,000\nHello UTF-8 with BOM\n\n"
    val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8BomSrt.toByteArray(Charsets.UTF_8)
    val utf8BomFile = File.createTempFile("p4_test_utf8bom_", ".srt").apply {
        writeBytes(utf8BomBytes)
        deleteOnExit()
    }
    val emittedUtf8Bom = CopyOnWriteArrayList<SubtitleFile>()
    val sessionUtf8Bom = AITranslationSession(
        mediaPayload = "media_utf8bom",
        targetLang = "vi",
        playerSubtitleCallback = { emittedUtf8Bom.add(it) },
        clientOverride = mockCharsetClient
    )
    sessionUtf8Bom.startSession(SubtitleFile("UTF8BOM", utf8BomFile.absolutePath)).join()
    check("UTF-8 with BOM decoded cleanly into valid subtitle track",
        sessionUtf8Bom.isCompleted && emittedUtf8Bom.isNotEmpty()
    )

    // Stop server cleanly
    AITranslatorLocalServer.stop()

    // =========================================================================
    // Summary
    // =========================================================================
    println("\n================================================================================")
    println(">>> Phase 4 Test Results: $passed PASSED, $failed FAILED (Total ${passed + failed})")
    println("================================================================================")

    if (failed > 0) {
        System.exit(1)
    }
}
