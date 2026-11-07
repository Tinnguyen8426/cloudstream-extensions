@file:Suppress("DEPRECATION")
package tests.adversarial

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslationClient
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitlePriorityEvaluator
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitleReconciler
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import java.io.File
import java.net.URL
import java.nio.charset.Charset
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting Challenger 2: ExoPlayer Ingestion & Delivery Stress Suite...")
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

    // Helper to format ms to SRT timestamp string
    fun formatMs(ms: Long): String {
        val h = ms / 3600_000L
        val m = (ms % 3600_000L) / 60_000L
        val s = (ms % 60_000L) / 1000L
        val milli = ms % 1000L
        return String.format("%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    // Start embedded local HTTP loopback server
    AITranslatorLocalServer.start(null)

    // =========================================================================
    // SECTION 1: ExoPlayer Single-Sample Timeline Coverage (Strategy A & B)
    // =========================================================================
    println("\n--- [Section 1: ExoPlayer Single-Sample Timeline Coverage] ---")

    // 1.1 Strategy A: 120 cues over a 45-minute media timeline
    TranslatorConfig.resetToDefaults()
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_A
    TranslatorConfig.targetLanguage = "vi"
    SubtitleCacheManager.clearAll(null)

    val totalCues = 120
    val durationMs = 45L * 60 * 1000 // 45 minutes
    val intervalMs = durationMs / totalCues

    val srt120Builder = StringBuilder()
    for (i in 1..totalCues) {
        val startMs = (i - 1) * intervalMs
        val endMs = startMs + (intervalMs - 100).coerceAtLeast(1000)
        srt120Builder.append("$i\n${formatMs(startMs)} --> ${formatMs(endMs)}\nDialogue line number $i in 45-min media\n\n")
    }
    val raw120Text = srt120Builder.toString()
    val subFile120 = File.createTempFile("p4_c2_sub120_", ".srt")
    subFile120.writeText(raw120Text, Charsets.UTF_8)
    subFile120.deleteOnExit()

    val emittedA = CopyOnWriteArrayList<SubtitleFile>()
    val mockClientA = object : AITranslationClient {
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            val map = cues.associate { it.index to "Bản dịch tiếng Việt dòng ${it.index}" }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val sessionA = AITranslationSession(
        mediaPayload = "media_strat_a_120",
        targetLang = "vi",
        playerSubtitleCallback = { sub -> emittedA.add(sub) },
        deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_A,
        clientOverride = mockClientA
    )

    sessionA.startSession(SubtitleFile("English", subFile120.absolutePath)).join()

    check("1.1 Strategy A: exactly 1 authoritative track emitted", emittedA.size == 1)
    val trackA = emittedA.firstOrNull()
    check("1.1 Strategy A: track streamed via loopback server", trackA != null && trackA.url.startsWith("http://127.0.0.1:"))

    if (trackA != null) {
        val srtBody = URL(trackA.url).openStream().use { it.bufferedReader().readText() }
        val parsedCuesA = SubtitleParser.parse(srtBody)
        check("1.1 Strategy A: full document contains all 120 cues", parsedCuesA.size == 120)
        check("1.1 Strategy A: first cue timestamp starts near 00:00:00", parsedCuesA.first().startMs == 0L)
        check("1.1 Strategy A: last cue timestamp extends to ~45 minutes", parsedCuesA.last().endMs >= 44 * 60 * 1000)
        check("1.1 Strategy A: all 120 cues translated in authoritative track",
            parsedCuesA.all { it.rawText.startsWith("Bản dịch tiếng Việt dòng") }
        )
    }

    // 1.2 Strategy B: 120 cues with delayed subsequent chunks (Draft Skeleton + Authoritative Full)
    TranslatorConfig.resetToDefaults()
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
            // Introduce artificial delay on chunks > 0 so Chunk 0 emits the skeleton first
            if ((cues.firstOrNull()?.index ?: 0) > 30) {
                delay(150)
            }
            val map = cues.associate { it.index to "Dịch Strategy B dòng ${it.index}" }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val subFile120B = File.createTempFile("p4_c2_sub120b_", ".srt")
    subFile120B.writeText(raw120Text.replace("Dialogue line", "Line B"), Charsets.UTF_8)
    subFile120B.deleteOnExit()

    val sessionB = AITranslationSession(
        mediaPayload = "media_strat_b_120",
        targetLang = "vi",
        playerSubtitleCallback = { sub -> emittedB.add(sub) },
        deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B,
        clientOverride = mockClientB
    )

    sessionB.startSession(SubtitleFile("English", subFile120B.absolutePath)).join()

    check("1.2 Strategy B: emitted 2 tracks (draft skeleton then authoritative full)", emittedB.size == 2)
    val draftTrack = emittedB.getOrNull(0)
    val fullTrack = emittedB.getOrNull(1)

    check("1.2 Strategy B: track 0 is draft skeleton (_v1.srt)",
        draftTrack != null && draftTrack.lang.contains("(Đang dịch...)") && draftTrack.url.contains("_v1.srt")
    )
    check("1.2 Strategy B: track 1 is authoritative full (_full.srt)",
        fullTrack != null && fullTrack.lang.contains("(Hoàn tất)") && fullTrack.url.contains("_full.srt")
    )

    if (draftTrack != null) {
        val draftBody = URL(draftTrack.url).openStream().use { it.bufferedReader().readText() }
        val draftCues = SubtitleParser.parse(draftBody)
        check("1.2 Strategy B Skeleton: contains ALL 120 cues (ExoPlayer SingleSampleMediaSource compliant)",
            draftCues.size == 120
        )
        check("1.2 Strategy B Skeleton: timeline covers full 45 minutes to EOF",
            draftCues.last().endMs >= 44 * 60 * 1000
        )
        check("1.2 Strategy B Skeleton: Cues 1..30 contain translated text",
            draftCues.take(30).all { it.rawText.startsWith("Dịch Strategy B dòng") }
        )
        check("1.2 Strategy B Skeleton: Cues 31..120 contain original dialogue fallback",
            draftCues.drop(30).all { it.rawText.contains("Line B number") }
        )
    }

    if (fullTrack != null) {
        val fullBody = URL(fullTrack.url).openStream().use { it.bufferedReader().readText() }
        val fullCues = SubtitleParser.parse(fullBody)
        check("1.2 Strategy B Full: contains all 120 cues", fullCues.size == 120)
        check("1.2 Strategy B Full: 100% of cues (1..120) translated",
            fullCues.all { it.rawText.startsWith("Dịch Strategy B dòng") }
        )
    }

    // 1.3 Strategy B under Partial Chunk API Failure (Chunks 2, 3, 4 return failure)
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B
    SubtitleCacheManager.clearAll(null)

    val emittedBFail = CopyOnWriteArrayList<SubtitleFile>()
    val failingClient = object : AITranslationClient {
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            val firstIdx = cues.firstOrNull()?.index ?: 0
            return if (firstIdx <= 30) {
                // Chunk 0 succeeds
                val map = cues.associate { it.index to "Thành công dòng ${it.index}" }
                TranslationResult(translations = map, success = true)
            } else {
                // Chunks > 0 fail permanently
                TranslationResult(success = false, error = "API 500 Internal Server Error", retryable = false)
            }
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val subFile120Fail = File.createTempFile("p4_c2_sub_fail_", ".srt")
    subFile120Fail.writeText(raw120Text.replace("Dialogue line", "FailTest line"), Charsets.UTF_8)
    subFile120Fail.deleteOnExit()

    val sessionBFail = AITranslationSession(
        mediaPayload = "media_strat_b_fail",
        targetLang = "vi",
        playerSubtitleCallback = { sub -> emittedBFail.add(sub) },
        deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B,
        clientOverride = failingClient
    )

    sessionBFail.startSession(SubtitleFile("English", subFile120Fail.absolutePath)).join()

    val fullFailTrack = emittedBFail.lastOrNull()
    if (fullFailTrack != null) {
        val fullFailBody = URL(fullFailTrack.url).openStream().use { it.bufferedReader().readText() }
        val fullFailCues = SubtitleParser.parse(fullFailBody)
        check("1.3 Chunk Failure Resilience: full track STILL has all 120 cues despite API failures",
            fullFailCues.size == 120
        )
        check("1.3 Chunk Failure Resilience: failed chunks gracefully fall back to rawText",
            fullFailCues.drop(30).all { it.rawText.contains("FailTest line") }
        )
    }

    // 1.4 Single-cue subtitle & Zero-cue subtitle
    val subFile1CueA = File.createTempFile("p4_c2_sub_1cue_a_", ".srt")
    subFile1CueA.writeText("1\n00:01:00,000 --> 00:01:05,000\nOnly one lone dialogue line A\n\n", Charsets.UTF_8)
    subFile1CueA.deleteOnExit()

    val emitted1CueA = CopyOnWriteArrayList<SubtitleFile>()
    val session1CueA = AITranslationSession(
        mediaPayload = "media_1cue_a",
        targetLang = "vi",
        playerSubtitleCallback = { sub -> emitted1CueA.add(sub) },
        deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_A,
        clientOverride = mockClientA
    )
    session1CueA.startSession(SubtitleFile("English", subFile1CueA.absolutePath)).join()
    check("1.4 Single-cue (Strategy A): exactly 1 authoritative track translated", emitted1CueA.size == 1)

    val subFile1CueB = File.createTempFile("p4_c2_sub_1cue_b_", ".srt")
    subFile1CueB.writeText("1\n00:01:00,000 --> 00:01:05,000\nOnly one lone dialogue line B\n\n", Charsets.UTF_8)
    subFile1CueB.deleteOnExit()

    val emitted1CueB = CopyOnWriteArrayList<SubtitleFile>()
    val session1CueB = AITranslationSession(
        mediaPayload = "media_1cue_b",
        targetLang = "vi",
        playerSubtitleCallback = { sub -> emitted1CueB.add(sub) },
        deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B,
        clientOverride = mockClientA
    )
    session1CueB.startSession(SubtitleFile("English", subFile1CueB.absolutePath)).join()
    check("1.4 Single-cue (Strategy B): emits draft skeleton and authoritative full", emitted1CueB.size == 2)

    val subFile0Cue = File.createTempFile("p4_c2_sub_0cue_", ".srt")
    subFile0Cue.writeText("\n   \n\n", Charsets.UTF_8)
    subFile0Cue.deleteOnExit()

    val emitted0Cue = CopyOnWriteArrayList<SubtitleFile>()
    val session0Cue = AITranslationSession(
        mediaPayload = "media_0cue",
        targetLang = "vi",
        playerSubtitleCallback = { sub -> emitted0Cue.add(sub) },
        clientOverride = mockClientA
    )
    session0Cue.startSession(SubtitleFile("English", subFile0Cue.absolutePath)).join()
    check("1.4 Zero-cue: rejects blank subtitle and emits 0 corrupt tracks", emitted0Cue.isEmpty())

    // =========================================================================
    // SECTION 2: Target Language Bypass Correctness (0 AI Requests)
    // =========================================================================
    println("\n--- [Section 2: Target Language Bypass Correctness] ---")

    val bypassRequests = AtomicInteger(0)
    val countingBypassClient = object : AITranslationClient {
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            bypassRequests.incrementAndGet()
            return TranslationResult(success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    // 2.1 Direct bypass: Target language arrives
    val sessionBypass1 = AITranslationSession(
        mediaPayload = "media_bypass_1",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = countingBypassClient
    )
    sessionBypass1.queueCandidate(SubtitleFile("Tiếng Việt (Anime47)", "https://anime47.com/vi.srt"))
    delay(250)

    check("2.1 Target Language Bypass: isTargetLanguageBypassed == true", sessionBypass1.isTargetLanguageBypassed)
    check("2.1 Target Language Bypass: 0 network requests dispatched", bypassRequests.get() == 0)

    // 2.2 Late arriving target language preempts in-flight low-priority candidate
    val sessionBypass2 = AITranslationSession(
        mediaPayload = "media_bypass_2",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = countingBypassClient
    )
    // Queue English first
    sessionBypass2.queueCandidate(SubtitleFile("English", subFile120.absolutePath))
    delay(50) // English is debouncing
    // Vietnamese arrives!
    sessionBypass2.queueCandidate(SubtitleFile("Vietnamese", "https://vn.sub/vietnamese.srt"))
    delay(300)

    check("2.2 Late Bypass: target language candidate aborts English translation", sessionBypass2.isTargetLanguageBypassed)
    check("2.2 Late Bypass: 0 AI network requests executed", bypassRequests.get() == 0)

    // 2.3 Comprehensive Language Matching & Normalization Matrix
    val bypassLabels = listOf(
        "vi", "vie", "VIE", "Vietnamese", "VIETNAMESE",
        "Tiếng Việt", "Tiếng Việt (Anime47)", "Ti?ng Vi?t",
        "vi-VN", "vi_VN"
    )
    var allLabelsBypassed = true
    for (label in bypassLabels) {
        val score = SubtitlePriorityEvaluator.calculateScore(
            SubtitleFile(label, "https://sub.url"),
            targetLang = "vi"
        )
        if (score != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            allLabelsBypassed = false
            System.err.println("  [FAIL] Label '$label' failed to trigger bypass score (-1000), got: $score")
        }
    }
    check("2.3 Bypass Matrix: all 10 Vietnamese label variations yield SCORE_BYPASS_TARGET_LANG (-1000)", allLabelsBypassed)

    // 2.4 Non-target languages must NOT bypass
    val nonBypassLabels = listOf("English", "Japanese", "Spanish", "French", "Chinese", "Korean")
    var noFalsePositives = true
    for (label in nonBypassLabels) {
        val score = SubtitlePriorityEvaluator.calculateScore(
            SubtitleFile(label, "https://sub.url"),
            targetLang = "vi"
        )
        if (score == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            noFalsePositives = false
            System.err.println("  [FAIL] Label '$label' falsely triggered bypass for target 'vi'!")
        }
    }
    check("2.4 Bypass Matrix: zero false positive bypass triggers for foreign languages", noFalsePositives)

    // =========================================================================
    // SECTION 3: LLM Relative Index Desynchronization Auto-Healing
    // =========================================================================
    println("\n--- [Section 3: LLM Relative Index Desynchronization Auto-Healing] ---")

    // Helper to generate mock cues
    fun makeCues(startIdx: Int, count: Int): List<SubtitleCue> {
        return (startIdx until (startIdx + count)).map { idx ->
            SubtitleCue(
                index = idx,
                startMs = idx * 1000L,
                endMs = idx * 1000L + 800L,
                rawText = "Original dialogue $idx",
                maskedText = "Original dialogue $idx",
                tagMap = emptyMap()
            )
        }
    }

    // 3.1 Chunk 2 (cues 31..60) with relative 1..30 returned by LLM
    val chunk2Cues = makeCues(31, 30)
    val relativeTrans30 = (1..30).associateWith { "Dịch tương đối $it" }

    val healedChunk2 = SubtitleReconciler.autoHealingReconcile(chunk2Cues, relativeTrans30)
    check("3.1 Relative Desync: reconciled map size matches chunk size (30)", healedChunk2.size == 30)

    var chunk2AllMapped = true
    for (idx in 31..60) {
        val expectedOffset = idx - 30
        val actual = healedChunk2[idx]
        if (actual != "Dịch tương đối $expectedOffset") {
            chunk2AllMapped = false
            System.err.println("  [FAIL] Cue $idx expected 'Dịch tương đối $expectedOffset' but got '$actual'")
        }
    }
    check("3.1 Relative Desync: 100% recovery (30/30 cues mapped by 1-based offset)", chunk2AllMapped)

    // 3.2 Chunk 5 (cues 121..150) with relative 1..30 returned by LLM
    val chunk5Cues = makeCues(121, 30)
    val healedChunk5 = SubtitleReconciler.autoHealingReconcile(chunk5Cues, relativeTrans30)
    check("3.2 Deep Chunk Desync: cues 121..150 100% healed from 1..30",
        healedChunk5.size == 30 && healedChunk5[121] == "Dịch tương đối 1" && healedChunk5[150] == "Dịch tương đối 30"
    )

    // 3.3 Partial LLM return: LLM dropped item 15 from 1..30
    val droppedItem15Trans = relativeTrans30.filterKeys { it != 15 }
    val healedDropped = SubtitleReconciler.autoHealingReconcile(chunk2Cues, droppedItem15Trans)
    check("3.3 Dropped Line Healing: map size is 30", healedDropped.size == 30)
    check("3.3 Dropped Line Healing: Cue 45 (offset 15) safely falls back to original rawText",
        healedDropped[45] == "Original dialogue 45"
    )
    check("3.3 Dropped Line Healing: Cue 46 (offset 16) correctly receives translation 16",
        healedDropped[46] == "Dịch tương đối 16"
    )

    // 3.4 Hallucinated keys: LLM returned keys 1..30 plus extraneous keys 999 and -5
    val hallucinatedTrans = relativeTrans30.toMutableMap().apply {
        put(999, "Hallucinated 999")
        put(-5, "Hallucinated -5")
    }
    val healedHallucination = SubtitleReconciler.autoHealingReconcile(chunk2Cues, hallucinatedTrans)
    check("3.4 Hallucination Guard: extraneous keys ignored; size strictly 30", healedHallucination.size == 30)
    check("3.4 Hallucination Guard: keys 999 and -5 stripped",
        !healedHallucination.containsKey(999) && !healedHallucination.containsKey(-5)
    )

    // 3.5 Empty translation map fallback
    val healedEmpty = SubtitleReconciler.autoHealingReconcile(chunk2Cues, emptyMap())
    check("3.5 Empty Response: 100% fallback to rawText for all cues",
        healedEmpty.size == 30 && healedEmpty.all { (k, v) -> v == "Original dialogue $k" }
    )

    // =========================================================================
    // SECTION 4: Multi-Charset Decoding Resilience
    // =========================================================================
    println("\n--- [Section 4: Multi-Charset Decoding Resilience] ---")

    val charsetReceived = CopyOnWriteArrayList<SubtitleFile>()
    val charsetClient = object : AITranslationClient {
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            val map = cues.associate { it.index to "Dịch Charset ${it.index}" }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    fun testCharsetFile(name: String, bytes: ByteArray): Pair<Boolean, SubtitleFile?> {
        val f = File.createTempFile("p4_c2_charset_${name}_", ".srt")
        f.writeBytes(bytes)
        f.deleteOnExit()

        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val session = AITranslationSession(
            mediaPayload = "charset_$name",
            targetLang = "vi",
            playerSubtitleCallback = { emitted.add(it) },
            clientOverride = charsetClient
        )
        runBlocking {
            session.startSession(SubtitleFile("TestCharset", f.absolutePath)).join()
        }
        return Pair(session.isCompleted, emitted.firstOrNull())
    }

    // 4.1 Shift-JIS / Windows-31J Japanese Anime Subtitle
    val sjisSrt = "1\n00:00:01,000 --> 00:00:04,000\nこんにちは世界！お元気ですか？\n\n2\n00:00:05,000 --> 00:00:08,000\nさようなら、また明日！\n\n"
    val sjisBytes = sjisSrt.toByteArray(Charset.forName("Windows-31J"))
    val (sjisSuccess, sjisTrack) = testCharsetFile("sjis", sjisBytes)
    check("4.1 Shift-JIS: Japanese subtitle decoded successfully without error", sjisSuccess && sjisTrack != null)
    if (sjisTrack != null) {
        val srtContent = URL(sjisTrack.url).openStream().use { it.bufferedReader().readText() }
        check("4.1 Shift-JIS: translated track emitted properly with 2 cues", srtContent.contains("Dịch Charset 1") && srtContent.contains("Dịch Charset 2"))
    }

    // 4.2 UTF-16 Little-Endian with BOM (\xFF\xFE)
    val utf16LeSrt = "1\n00:00:01,000 --> 00:00:04,000\nHello UTF-16LE Subtitle\n\n"
    val utf16LeBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16LeSrt.toByteArray(Charsets.UTF_16LE)
    val (utf16LeSuccess, utf16LeTrack) = testCharsetFile("utf16le", utf16LeBytes)
    check("4.2 UTF-16LE: Little-Endian with BOM decoded cleanly", utf16LeSuccess && utf16LeTrack != null)

    // 4.3 UTF-16 Big-Endian with BOM (\xFE\xFF)
    val utf16BeSrt = "1\n00:00:01,000 --> 00:00:04,000\nHello UTF-16BE Subtitle\n\n"
    val utf16BeBytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + utf16BeSrt.toByteArray(Charsets.UTF_16BE)
    val (utf16BeSuccess, utf16BeTrack) = testCharsetFile("utf16be", utf16BeBytes)
    check("4.3 UTF-16BE: Big-Endian with BOM decoded cleanly", utf16BeSuccess && utf16BeTrack != null)

    // 4.4 Corrupted UTF-8 Multibyte Sequences (Incomplete 3-byte and 4-byte sequences)
    val corruptBytes = byteArrayOf(
        0x31, 0x0A, // "1\n"
        0x30, 0x30, 0x3A, 0x30, 0x30, 0x3A, 0x30, 0x31, 0x2C, 0x30, 0x30, 0x30, // "00:00:01,000"
        0x20, 0x2D, 0x2D, 0x3E, 0x20, // " --> "
        0x30, 0x30, 0x3A, 0x30, 0x30, 0x3A, 0x30, 0x34, 0x2C, 0x30, 0x30, 0x30, 0x0A, // "00:00:04,000\n"
        0xE3.toByte(), 0x81.toByte(), // Truncated UTF-8 hiragana (missing 3rd byte)
        0xF0.toByte(), 0x90.toByte(), // Truncated 4-byte UTF-8
        0x41, 0x42, 0x43, 0x0A, 0x0A // "ABC\n\n"
    )
    var corruptCrashed = false
    try {
        val (corruptSuccess, _) = testCharsetFile("corrupt_utf8", corruptBytes)
        check("4.4 Corrupted UTF-8: session decoded without CharacterCodingException crash", true)
    } catch (t: Throwable) {
        corruptCrashed = true
        System.err.println("  [FAIL] Corrupted UTF-8 crashed with: ${t.message}")
    }
    check("4.4 Corrupted UTF-8: zero uncaught decoding exceptions", !corruptCrashed)

    // 4.5 Binary Garbage / Non-text File (10KB of pure random bytes)
    val randomBytes = ByteArray(10240)
    Random(42).nextBytes(randomBytes)
    var binaryCrashed = false
    try {
        val (binarySuccess, binaryTrack) = testCharsetFile("binary_garbage", randomBytes)
        check("4.5 Binary Garbage: random binary handled safely without crash", !binaryCrashed)
        check("4.5 Binary Garbage: zero corrupt subtitle tracks emitted", binaryTrack == null)
    } catch (t: Throwable) {
        binaryCrashed = true
        System.err.println("  [FAIL] Binary garbage crashed with: ${t.message}")
    }
    check("4.5 Binary Garbage: zero crashes on non-text inputs", !binaryCrashed)

    // =========================================================================
    // SECTION 5: Dynamic Promotion Race & Mid-Stream Cancellation
    // =========================================================================
    println("\n--- [Section 5: Dynamic Promotion Race & Mid-Stream Cancellation] ---")

    // 5.1 Concurrent candidate promotion: 10 threads rapidly queue different languages
    val emittedRace = CopyOnWriteArrayList<SubtitleFile>()
    val raceSession = AITranslationSession(
        mediaPayload = "race_promotion",
        targetLang = "vi",
        playerSubtitleCallback = { emittedRace.add(it) },
        clientOverride = mockClientA
    )

    val candidatesToQueue = listOf(
        SubtitleFile("Spanish", subFile120.absolutePath),
        SubtitleFile("French", subFile120.absolutePath),
        SubtitleFile("English", subFile120.absolutePath),
        SubtitleFile("German", subFile120.absolutePath),
        SubtitleFile("Japanese", subFile120.absolutePath), // Highest score (1000 in anime_first)
        SubtitleFile("Italian", subFile120.absolutePath),
        SubtitleFile("Korean", subFile120.absolutePath),
        SubtitleFile("Chinese", subFile120.absolutePath)
    )

    val jobs = candidatesToQueue.map { cand ->
        launch {
            raceSession.queueCandidate(cand)
        }
    }
    jobs.joinAll()
    delay(400) // Wait for debounce and processing to complete

    check("5.1 Candidate Race: Japanese (highest score: 1000) won promotion",
        raceSession.currentBestScore == 1000
    )
    raceSession.cancelSession()

    // 5.2 Mid-stream cancellation: cancelSession terminates coroutines and emits nothing further
    val emittedCancel = CopyOnWriteArrayList<SubtitleFile>()
    val slowCancelClient = object : AITranslationClient {
        override suspend fun translateChunk(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?
        ): TranslationResult {
            delay(500)
            return TranslationResult(success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val cancelSession = AITranslationSession(
        mediaPayload = "cancel_test",
        targetLang = "vi",
        playerSubtitleCallback = { emittedCancel.add(it) },
        clientOverride = slowCancelClient
    )

    val runJob = cancelSession.startSession(SubtitleFile("English", subFile120.absolutePath))
    delay(50) // Let it start
    cancelSession.cancelSession()
    runJob.join()

    check("5.2 Mid-Stream Cancel: isCancelled == true", cancelSession.isCancelled)
    check("5.2 Mid-Stream Cancel: zero tracks emitted after cancellation", emittedCancel.isEmpty())

    // Stop local loopback server cleanly
    AITranslatorLocalServer.stop()

    // =========================================================================
    // Summary
    // =========================================================================
    println("\n================================================================================")
    println(">>> Challenger 2 Test Results: $passed PASSED, $failed FAILED (Total ${passed + failed})")
    println("================================================================================")

    if (failed > 0) {
        System.exit(1)
    }
}
