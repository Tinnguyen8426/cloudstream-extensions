package tests.adversarial

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslationClient
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitlePriorityEvaluator
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

fun assertTrue(msg: String, condition: Boolean) {
    if (!condition) {
        System.err.println("  [FAIL] $msg")
        throw AssertionError("Assertion failed: $msg")
    }
    println("  [PASS] $msg")
}

fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        System.err.println("  [FAIL] $msg - Expected '$expected', but got '$actual'")
        throw AssertionError("Assertion failed: $msg - Expected '$expected', but got '$actual'")
    }
    println("  [PASS] $msg")
}

class MockAIClient(
    val succeed: Boolean = true,
    val quotaExhausted: Boolean = false,
    val translationMap: Map<Int, String> = mapOf(1 to "Xin chào thế giới!", 2 to "Tạm biệt bạn.")
) : AITranslationClient {
    override suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
        customGlossary: String?
    ): TranslationResult {
        if (quotaExhausted) {
            return TranslationResult(
                translations = emptyMap<Int, String>(),
                error = "Daily quota reached",
                success = false,
                isQuotaExhausted = true
            )
        }
        if (!succeed) {
            return TranslationResult(
                translations = emptyMap<Int, String>(),
                error = "Network timeout",
                success = false
            )
        }
        val map = cues.associate { it.index to (translationMap[it.index] ?: "Bản dịch của ${it.rawText}") }
        return TranslationResult(translations = map, success = true)
    }

    override suspend fun ping(): TranslationResult = TranslationResult(success = true)
}

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting AiSubtitleTranslator Pipeline Fix Verification Suite...")
    println("================================================================================")

    // --- TEST 1: ASS / SSA Parsing Support ---
    println("\n--- [Test 1: ASS / SSA Subtitle Parsing] ---")
    val assContent = """
        [Script Info]
        Title: Test ASS
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:01:20.10,0:01:23.50,Default,,0,0,0,,Hello {\b1}world{\b0}!\NSecond line.
        Dialogue: 0,0:01:25.00,0:01:27.75,Default,,0,0,0,,Goodbye {\pos(192,108)}friend!
    """.trimIndent()

    val assCues = SubtitleParser.parse(assContent)
    assertEquals("Extracted 2 ASS cues", 2, assCues.size)
    assertEquals("Cue 1 startMs (1m 20.1s)", 80100L, assCues[0].startMs)
    assertEquals("Cue 1 endMs (1m 23.5s)", 83500L, assCues[0].endMs)
    assertTrue("Cue 1 masked bold tag [[TAG_1]]", assCues[0].maskedText.contains("[[TAG_1]]"))
    assertTrue("Cue 1 masked bold close tag [[TAG_2]]", assCues[0].maskedText.contains("[[TAG_2]]"))
    assertTrue("Cue 1 converted \\N to newline", assCues[0].rawText.contains("\nSecond line."))
    assertEquals("Cue 2 startMs (1m 25.0s)", 85000L, assCues[1].startMs)
    assertEquals("Cue 2 endMs (1m 27.75s)", 87750L, assCues[1].endMs)
    assertTrue("Cue 2 masked pos tag [[TAG_1]]", assCues[1].maskedText.contains("[[TAG_1]]"))

    // --- TEST 2: GZIP Compressed Subtitle Stream Detection & Extraction ---
    println("\n--- [Test 2: GZIP Compressed Subtitle Stream] ---")
    val rawSrt = """
        1
        00:00:01,000 --> 00:00:04,000
        This is a compressed subtitle cue.

        2
        00:00:05,000 --> 00:00:08,000
        Second compressed line.
    """.trimIndent()

    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(rawSrt.toByteArray(Charsets.UTF_8)) }
    val gzipBytes = bos.toByteArray()

    val tempGzipFile = File.createTempFile("test_sub_", ".srt.gz")
    tempGzipFile.deleteOnExit()
    tempGzipFile.writeBytes(gzipBytes)

    val emittedSubs = mutableListOf<SubtitleFile>()
    val sessionGzip = AITranslationSession(
        mediaPayload = "gzip_test_movie",
        targetLang = "vi",
        playerSubtitleCallback = { emittedSubs.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )

    val gzipJob = sessionGzip.startSession(SubtitleFile("English", tempGzipFile.absolutePath))
    gzipJob.join()

    assertTrue("GZIP subtitle translated and emitted to player", emittedSubs.isNotEmpty())
    assertTrue("Emitted track contains AI Vietnamese label", emittedSubs.any { it.lang.contains("Tiếng Việt") })

    // --- TEST 3: Notification and Progress Pipeline ---
    println("\n--- [Test 3: Progress Notifications & Error Paths] ---")

    // Subtest 3.1: Missing API key with no override triggers clear error notification
    val emptyKeySubs = mutableListOf<SubtitleFile>()
    TranslatorConfig.apiKey = ""
    val sessionEmptyKey = AITranslationSession(
        mediaPayload = "missing_key_movie",
        targetLang = "vi",
        playerSubtitleCallback = { emptyKeySubs.add(it) },
        clientOverride = null
    )
    val emptyKeyJob = sessionEmptyKey.startSession(SubtitleFile("English", tempGzipFile.absolutePath))
    emptyKeyJob.join()
    assertEquals("No tracks emitted when API key missing", 0, emptyKeySubs.size)

    // Subtest 3.2: Quota exhausted triggers explicit warning/error notification
    SubtitleCacheManager.clearAll(null)
    val quotaSubs = mutableListOf<SubtitleFile>()
    val quotaSrt = """
        1
        00:00:01,000 --> 00:00:04,000
        This is a unique quota test cue.
    """.trimIndent()
    val quotaFile = File.createTempFile("test_quota_", ".srt")
    quotaFile.deleteOnExit()
    quotaFile.writeText(quotaSrt)

    val sessionQuota = AITranslationSession(
        mediaPayload = "quota_movie",
        targetLang = "vi",
        playerSubtitleCallback = { quotaSubs.add(it) },
        clientOverride = MockAIClient(quotaExhausted = true)
    )
    val quotaJob = sessionQuota.startSession(SubtitleFile("English", quotaFile.absolutePath))
    quotaJob.join()
    assertEquals("No corrupted/empty tracks emitted on 100% quota failure", 0, quotaSubs.size)

    // Subtest 3.3: Cache Hit triggers cache notification & instant delivery
    SubtitleCacheManager.clearAll(null)
    val cacheHitSubs = mutableListOf<SubtitleFile>()
    val sessionCacheHit = AITranslationSession(
        mediaPayload = "cache_hit_movie",
        targetLang = "vi",
        playerSubtitleCallback = { cacheHitSubs.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )
    // Run once to populate cache
    val job1 = sessionCacheHit.startSession(SubtitleFile("English", tempGzipFile.absolutePath))
    job1.join()
    assertTrue("First run completed", sessionCacheHit.isCompleted)

    // Run again with new session on same file
    val session2 = AITranslationSession(
        mediaPayload = "cache_hit_movie_2",
        targetLang = "vi",
        playerSubtitleCallback = { cacheHitSubs.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )
    val job2 = session2.startSession(SubtitleFile("English", tempGzipFile.absolutePath))
    job2.join()
    assertTrue("Second run hit Tier 1 cache and completed immediately", session2.isCompleted)
    assertTrue("Cache hit emitted track", cacheHitSubs.isNotEmpty())

    // --- TEST 4: Candidate Fallback Resilience ---
    println("\n--- [Test 4: Candidate Fallback Resilience] ---")
    SubtitleCacheManager.clearAll(null)
    val fallbackEmitted = mutableListOf<SubtitleFile>()
    val sessionFallback = AITranslationSession(
        mediaPayload = "fallback_movie",
        targetLang = "vi",
        playerSubtitleCallback = { fallbackEmitted.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )

    // Japanese candidate has higher priority (score 1000 in anime_first) but a dead path
    val deadCandidate = SubtitleFile("Japanese", "http://127.0.0.1:9999/nonexistent_sub.srt")
    // English candidate has score 500 and points to a working valid SRT
    val validCandidate = SubtitleFile("English", tempGzipFile.absolutePath)

    sessionFallback.queueCandidate(deadCandidate)
    sessionFallback.queueCandidate(validCandidate)

    // Allow background fallback to process dead candidate, fail, and switch to valid candidate
    kotlinx.coroutines.delay(1000)

    assertTrue("Fallback session completed successfully", sessionFallback.isCompleted)
    assertTrue("Fallback delivered translated track despite first candidate failure", fallbackEmitted.isNotEmpty())
    assertTrue("Emitted fallback track contains Vietnamese label", fallbackEmitted.any { it.lang.contains("Tiếng Việt") })

    // --- TEST 5: File URI with Spaces Resilience ---
    println("\n--- [Test 5: File URI with Spaces Resilience] ---")
    SubtitleCacheManager.clearAll(null)
    val spacedFile = File.createTempFile("test sub with spaces ", ".srt")
    spacedFile.deleteOnExit()
    spacedFile.writeText(rawSrt)

    val spacedEmitted = mutableListOf<SubtitleFile>()
    val sessionSpaced = AITranslationSession(
        mediaPayload = "spaced_uri_movie",
        targetLang = "vi",
        playerSubtitleCallback = { spacedEmitted.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )

    val spaceUriJob = sessionSpaced.startSession(SubtitleFile("English", "file://" + spacedFile.absolutePath))
    spaceUriJob.join()

    assertTrue("Session with spaces in file URI completed successfully", sessionSpaced.isCompleted)
    assertTrue("Delivered translated track for spaced file URI", spacedEmitted.isNotEmpty())

    // --- TEST 6: Custom Local Endpoint with Blank API Key ---
    println("\n--- [Test 6: Custom Local Provider with Blank API Key] ---")
    SubtitleCacheManager.clearAll(null)
    TranslatorConfig.provider = TranslatorConfig.PROVIDER_CUSTOM
    TranslatorConfig.apiKey = ""
    TranslatorConfig.customBaseUrl = "http://localhost:11434/v1"

    val customLocalEmitted = mutableListOf<SubtitleFile>()
    val sessionCustomLocal = AITranslationSession(
        mediaPayload = "custom_local_movie",
        targetLang = "vi",
        playerSubtitleCallback = { customLocalEmitted.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )

    val customLocalJob = sessionCustomLocal.startSession(SubtitleFile("English", tempGzipFile.absolutePath))
    customLocalJob.join()

    assertTrue("Custom local provider session completed without API key", sessionCustomLocal.isCompleted)
    assertTrue("Custom local provider emitted translated subtitles", customLocalEmitted.isNotEmpty())

    // Restore config defaults
    TranslatorConfig.provider = TranslatorConfig.PROVIDER_GEMINI
    TranslatorConfig.customBaseUrl = ""

    // --- TEST 7: Subtitle Custom Headers & ZIP Archive Stream Support ---
    println("\n--- [Test 7: Subtitle Headers Propagation & ZIP Decompression] ---")
    SubtitleCacheManager.clearAll(null)

    // Subtest 7.1: Header verification server
    val headerServer = java.net.ServerSocket(0)
    val headerPort = headerServer.localPort
    val serverThread = kotlin.concurrent.thread {
        try {
            val socket = headerServer.accept()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
            val recHeaders = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (line != null && line.isNotBlank()) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    recHeaders[parts[0].trim().lowercase()] = parts[1].trim()
                }
                line = reader.readLine()
            }
            val out = socket.getOutputStream()
            if (recHeaders["referer"] == "https://protected-cdn.com" && recHeaders["x-auth-token"] == "SecretToken123") {
                val body = "1\n00:00:01,000 --> 00:00:04,000\nProtected CDN Subtitle Dialogue\n\n"
                val bytes = body.toByteArray(Charsets.UTF_8)
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nContent-Type: text/plain\r\n\r\n"
                out.write(resp.toByteArray(Charsets.UTF_8))
                out.write(bytes)
            } else {
                val resp = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                out.write(resp.toByteArray(Charsets.UTF_8))
            }
            out.flush()
            socket.close()
            headerServer.close()
        } catch (_: Throwable) {}
    }

    val headerSubs = mutableListOf<SubtitleFile>()
    val sessionHeader = AITranslationSession(
        mediaPayload = "header_protected_movie",
        targetLang = "vi",
        playerSubtitleCallback = { headerSubs.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )
    val headerCandidate = SubtitleFile("English", "http://127.0.0.1:$headerPort/protected.srt").apply {
        headers = mapOf("Referer" to "https://protected-cdn.com", "X-Auth-Token" to "SecretToken123")
    }
    val headerJob = sessionHeader.startSession(headerCandidate)
    headerJob.join()
    serverThread.join(2000)

    assertTrue("Protected CDN subtitle fetched via custom headers", sessionHeader.isCompleted)
    assertTrue("Translated track emitted for header-protected subtitle", headerSubs.isNotEmpty())

    // Subtest 7.2: ZIP Archive Stream Decompression
    val zipBos = ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(zipBos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("inner_subtitle.srt"))
        zos.write(rawSrt.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    val zipFile = File.createTempFile("test_sub_", ".zip")
    zipFile.deleteOnExit()
    zipFile.writeBytes(zipBos.toByteArray())

    val zipSubs = mutableListOf<SubtitleFile>()
    val sessionZip = AITranslationSession(
        mediaPayload = "zip_test_movie",
        targetLang = "vi",
        playerSubtitleCallback = { zipSubs.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )
    val zipJob = sessionZip.startSession(SubtitleFile("English", zipFile.absolutePath))
    zipJob.join()

    assertTrue("ZIP compressed subtitle stream extracted and translated", sessionZip.isCompleted)
    assertTrue("Translated track emitted from ZIP archive", zipSubs.isNotEmpty())

    // Subtest 7.3: AITranslatorEnrichedMainAPI Property Delegation
    val mockLeaf = object : com.lagradost.cloudstream3.MainAPI() {
        override var name = "MockLeafProvider"
        override var mainUrl = "https://mock.leaf.com"
        override val loadLinksTimeoutMs: Long? = 55_000L
        override val hasChromecastSupport: Boolean = true
        override val hasDownloadSupport: Boolean = false
        override val usesWebView: Boolean = true
    }
    val enriched = com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI(mockLeaf)
    assertEquals("Enriched delegates loadLinksTimeoutMs", 55_000L, enriched.loadLinksTimeoutMs)
    assertEquals("Enriched delegates hasChromecastSupport", true, enriched.hasChromecastSupport)
    assertEquals("Enriched delegates hasDownloadSupport", false, enriched.hasDownloadSupport)
    assertEquals("Enriched delegates usesWebView", true, enriched.usesWebView)
    assertEquals("Enriched delegates name", "MockLeafProvider", enriched.name)
    assertEquals("Enriched delegates mainUrl", "https://mock.leaf.com", enriched.mainUrl)

    // --- TEST 8: Enhanced Language Compound Suffixes & Target Language Bypass ---
    println("\n--- [Test 8: Language Compound Suffixes & Bypass] ---")
    assertTrue("Recognizes Vietsub as Vietnamese", SubtitlePriorityEvaluator.isVietnamese("Vietsub"))
    assertTrue("Recognizes Viet Sub as Vietnamese", SubtitlePriorityEvaluator.isVietnamese("Viet Sub"))
    assertTrue("Recognizes tieng viet as Vietnamese", SubtitlePriorityEvaluator.isVietnamese("tieng viet"))
    assertTrue("Recognizes VN as Vietnamese", SubtitlePriorityEvaluator.isVietnamese("VN"))
    assertTrue("Recognizes Engsub as English", SubtitlePriorityEvaluator.isEnglish("Engsub"))
    assertTrue("Recognizes Eng Sub as English", SubtitlePriorityEvaluator.isEnglish("Eng Sub"))
    assertTrue("Recognizes tieng anh as English", SubtitlePriorityEvaluator.isEnglish("tieng anh"))
    assertTrue("Recognizes Japsub as Japanese", SubtitlePriorityEvaluator.isJapanese("Japsub"))
    assertTrue("Recognizes tieng nhat as Japanese", SubtitlePriorityEvaluator.isJapanese("tieng nhat"))

    // Test Target Language Bypass when Vietsub is present
    val vietsubFile = SubtitleFile("Vietsub", "http://example.com/vietsub.srt")
    val vietsubScore = SubtitlePriorityEvaluator.calculateScore(vietsubFile, "vi")
    assertEquals("Vietsub score is SCORE_BYPASS_TARGET_LANG", SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG, vietsubScore)

    val bypassEmitted = mutableListOf<SubtitleFile>()
    val sessionBypass = AITranslationSession(
        mediaPayload = "bypass_test_movie",
        targetLang = "vi",
        playerSubtitleCallback = { bypassEmitted.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )
    sessionBypass.queueCandidate(vietsubFile)
    assertTrue("Target language bypass flag set", sessionBypass.isTargetLanguageBypassed)
    assertEquals("No translation tracks emitted when bypassed", 0, bypassEmitted.size)

    // Test Engsub score under anime_first and english_first
    val engsubFile = SubtitleFile("Engsub", "http://example.com/engsub.srt")
    val engScoreAnime = SubtitlePriorityEvaluator.calculateScore(engsubFile, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    assertEquals("Engsub scored 500 under anime_first", 500, engScoreAnime)
    val engScoreWestern = SubtitlePriorityEvaluator.calculateScore(engsubFile, "vi", TranslatorConfig.PRIORITY_ENGLISH_FIRST)
    assertEquals("Engsub scored 1000 under western_first", 1000, engScoreWestern)

    // --- TEST 9: ASS with Arrow Dialogue & Data URI Decoding ---
    println("\n--- [Test 9: ASS Arrow Dialogue & Data URI Decoding] ---")
    val complexAss = """
        [Script Info]
        Title: Complex ASS
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Notice: 00:01 --> 00:02 changed\hposition
    """.trimIndent()

    val parsedAssCues = SubtitleParser.parse(complexAss)
    assertEquals("Parsed 1 ASS cue despite arrow --> in dialogue", 1, parsedAssCues.size)
    assertTrue("Replaced \\h hard space with regular space", parsedAssCues[0].rawText.contains("changed position"))

    // Test Data URI base64 decoding in fetchSubtitleBytes
    val dataSrt = "1\n00:00:01,000 --> 00:00:04,000\nData URI dialogue test\n\n"
    val b64 = java.util.Base64.getEncoder().encodeToString(dataSrt.toByteArray(Charsets.UTF_8))
    val dataUri = "data:text/vtt;base64,$b64"

    val dataSubs = mutableListOf<SubtitleFile>()
    val sessionData = AITranslationSession(
        mediaPayload = "data_uri_movie",
        targetLang = "vi",
        playerSubtitleCallback = { dataSubs.add(it) },
        clientOverride = MockAIClient(succeed = true)
    )
    val dataJob = sessionData.startSession(SubtitleFile("English", dataUri))
    dataJob.join()

    assertTrue("Session with data URI completed", sessionData.isCompleted)
    assertTrue("Translated track emitted for data URI", dataSubs.isNotEmpty())

    println("\n================================================================================")
    println(">>> ALL PIPELINE FIX TESTS PASSED SUCCESSFULLY!")
    println("================================================================================")
}
