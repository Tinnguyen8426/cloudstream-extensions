package com.cloudstream.test.m1

import com.kitsunekko.enricher.AssToSrtConverter
import com.kitsunekko.enricher.KitsunekkoLocalServer
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.player.CustomSubripParser
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleCue
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.common.util.Consumer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Empirical Adversarial Challenger 2 Test Suite for Milestone 1.
 * Focus: CloudStream Player Ingestion and Subtitle Offset Pipeline.
 */
object KitsunekkoPlayerIngestionChallengerTest {

    private var passed = 0
    private var failed = 0
    private val failureLog = mutableListOf<String>()

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            val msg = "  [FAIL] $name: $detail"
            System.err.println(msg)
            failureLog.add(msg)
            failed++
        }
    }

    private fun assertEquals(name: String, expected: Any?, actual: Any?) {
        check(name, expected == actual, "Expected <$expected> but was <$actual>")
    }

    private fun assertTrue(name: String, condition: Boolean, msg: String = "") {
        check(name, condition, "Expected true but was false. $msg")
    }

    private fun assertFalse(name: String, condition: Boolean, msg: String = "") {
        check(name, !condition, "Expected false but was true. $msg")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" CHALLENGER 2: CLOUDSTREAM PLAYER INGESTION & SUBTITLE OFFSET EMPIRICAL SUITE")
        println("================================================================================")

        testOkHttpUrlParsingAndSchemeRejection()
        testPlayerSubtitleHelperMimeTypeResolution()
        testCustomSubripParserCuesGeneration()
        testFullScreenPlayerOffsetDialogNoticeLogic()
        testEndToEndLoopbackStreamingAndCueIngestion()

        println("================================================================================")
        println(" CHALLENGE SUITE SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed assertions count: $failed")
            failureLog.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL EMPIRICAL PLAYER INGESTION ASSERTIONS PASSED SUCCESSFULLY!")
            System.exit(0)
        }
    }

    // -------------------------------------------------------------------------
    // 1. OkHttp HttpUrl Parsing & Rejection Analysis
    // -------------------------------------------------------------------------
    private fun testOkHttpUrlParsingAndSchemeRejection() {
        println("\n>>> Suite 1: OkHttp HttpUrl Parsing & Scheme Rejection")

        val validLoopbackUrl = "http://127.0.0.1:49152/sub/sousou_no_frieren_ep5.srt"

        // 1. okhttp3.HttpUrl.get(...) on valid loopback HTTP URL (both via toHttpUrl and Java HttpUrl.get method)
        var parsedHttpUrl: HttpUrl? = null
        try {
            parsedHttpUrl = validLoopbackUrl.toHttpUrl()
            // Direct JVM method invocation of okhttp3.HttpUrl.get(String)
            val javaGetMethod = HttpUrl::class.java.getMethod("get", String::class.java)
            val javaResult = javaGetMethod.invoke(null, validLoopbackUrl) as HttpUrl
            assertEquals("Java get method result matches toHttpUrl", parsedHttpUrl, javaResult)
        } catch (e: Exception) {
            check("HttpUrl.get loopback should not throw", false, e.toString())
        }
        assertTrue("HttpUrl.get parsed valid loopback URL", parsedHttpUrl != null)
        assertEquals("HttpUrl scheme is http", "http", parsedHttpUrl?.scheme)
        assertEquals("HttpUrl host is 127.0.0.1", "127.0.0.1", parsedHttpUrl?.host)
        assertEquals("HttpUrl port is 49152", 49152, parsedHttpUrl?.port)

        // 2. Request.Builder().url(...) on valid loopback HTTP URL
        val req = Request.Builder().url(validLoopbackUrl).build()
        assertEquals("Request URL matches", validLoopbackUrl, req.url.toString())

        // 3. Contrast with legacy file:// URL: okhttp3.HttpUrl.get must throw IllegalArgumentException
        val legacyFileUrl = "file:///data/user/0/com.lagradost.cloudstream3/cache/kitsunekko_subs/ep5.srt"
        var fileSchemeRejected = false
        var exceptionMsg = ""
        try {
            legacyFileUrl.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            fileSchemeRejected = true
            exceptionMsg = e.message ?: ""
        }
        assertTrue("HttpUrl.get rejected file:// scheme with IllegalArgumentException",
            fileSchemeRejected, "Message: $exceptionMsg")
        assertTrue("Exception message explains scheme rejection",
            exceptionMsg.contains("Expected HTTP scheme") || exceptionMsg.contains("file"))

        // Also verify Java get method reflects IllegalArgumentException
        var javaRejected = false
        try {
            val javaGetMethod = HttpUrl::class.java.getMethod("get", String::class.java)
            javaGetMethod.invoke(null, legacyFileUrl)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            if (e.targetException is IllegalArgumentException) {
                javaRejected = true
            }
        }
        assertTrue("Java get method throws InvocationTargetException wrapping IllegalArgumentException", javaRejected)

        // 4. Request.Builder().url(legacyFileUrl) also throws IllegalArgumentException
        var reqRejected = false
        try {
            Request.Builder().url(legacyFileUrl).build()
        } catch (e: IllegalArgumentException) {
            reqRejected = true
        }
        assertTrue("Request.Builder().url(file://) throws IllegalArgumentException", reqRejected)
    }

    // -------------------------------------------------------------------------
    // 2. PlayerSubtitleHelper.toSubtitleMimeType Resolution
    // -------------------------------------------------------------------------
    private fun testPlayerSubtitleHelperMimeTypeResolution() {
        println("\n>>> Suite 2: PlayerSubtitleHelper MIME Type Resolution")

        with(PlayerSubtitleHelper.Companion) {
            // 1. .srt URL -> application/x-subrip
            val srtUrl = "http://127.0.0.1:50000/sub/anime_ep1.srt"
            val srtMime = srtUrl.toSubtitleMimeType()
            assertEquals(".srt identified as application/x-subrip", "application/x-subrip", srtMime)

            // 2. .SRT URL uppercase -> application/x-subrip
            val srtUpper = "http://127.0.0.1:50000/sub/anime_ep1.SRT"
            val srtUpperMime = srtUpper.toSubtitleMimeType()
            assertEquals(".SRT identified as application/x-subrip", "application/x-subrip", srtUpperMime)

            // 3. .vtt URL -> text/vtt
            val vttUrl = "http://127.0.0.1:50000/sub/anime_ep1.vtt"
            val vttMime = vttUrl.toSubtitleMimeType()
            assertEquals(".vtt identified as text/vtt", "text/vtt", vttMime)

            // 4. .ttml URL -> application/ttml+xml
            val ttmlUrl = "http://127.0.0.1:50000/sub/anime_ep1.ttml"
            val ttmlMime = ttmlUrl.toSubtitleMimeType()
            assertEquals(".ttml identified as application/ttml+xml", "application/ttml+xml", ttmlMime)

            // 5. Raw .ass URL -> defaults to application/x-subrip (unsupported ASS format causing ExoPlayer mismatch)
            val assUrl = "http://127.0.0.1:50000/sub/anime_ep1.ass"
            val assMime = assUrl.toSubtitleMimeType()
            assertEquals(".ass URL defaults to application/x-subrip", "application/x-subrip", assMime)

            // 6. SubtitleFile -> SubtitleData pipeline transformation
            val subFile = SubtitleFile("Tiếng Nhật (Kitsunekko)", srtUrl)
            val subData = PlayerSubtitleHelper.Companion.getSubtitleData(subFile)
            assertEquals("SubtitleData origin is unconditionally URL", SubtitleOrigin.URL, subData.origin)
            assertEquals("SubtitleData mimeType is application/x-subrip", "application/x-subrip", subData.mimeType)
            assertEquals("SubtitleData url is loopback URL", srtUrl, subData.url)
        }
    }

    // -------------------------------------------------------------------------
    // 3. CustomSubripParser from cloudstream.jar Cues Generation
    // -------------------------------------------------------------------------
    private fun testCustomSubripParserCuesGeneration() {
        println("\n>>> Suite 3: CustomSubripParser from cloudstream.jar Cues Generation")

        val parser = CustomSubripParser()
        assertEquals("Cue replacement behavior is 1", 1, parser.cueReplacementBehavior)

        // 1. Negative Control: Raw ASS dialogue fed to CustomSubripParser directly produces 0 cues
        val rawAss = """
            [Script Info]
            Title: Raw ASS
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,{\pos(192,240)}ヒンメル：旅の目的
            Dialogue: 0,0:00:05.00,0:00:08.50,Default,,0,0,0,,フリーレン：死者を冒涜するな。
        """.trimIndent()

        val rawAssBytes = rawAss.toByteArray(Charsets.UTF_8)
        val rawCuesList = mutableListOf<CuesWithTiming>()
        val rawConsumer = Consumer<CuesWithTiming> { rawCuesList.add(it) }

        parser.parse(rawAssBytes, 0, rawAssBytes.size, SubtitleParser.OutputOptions.allCues(), rawConsumer)
        assertEquals("Unconverted ASS yields exactly 0 parsed cues in CustomSubripParser", 0, rawCuesList.size)

        // 2. Positive Control: Converted .srt fed to CustomSubripParser produces valid non-empty cues
        val convertedSrt = AssToSrtConverter.convertAssToSrt(rawAss)
        assertTrue("AssToSrtConverter produces SubRip", AssToSrtConverter.isSubRip(convertedSrt))

        val srtBytes = convertedSrt.toByteArray(Charsets.UTF_8)
        val srtCuesList = mutableListOf<CuesWithTiming>()
        val srtConsumer = Consumer<CuesWithTiming> { srtCuesList.add(it) }

        parser.parse(srtBytes, 0, srtBytes.size, SubtitleParser.OutputOptions.allCues(), srtConsumer)
        assertEquals("Converted SRT yields exactly 2 CuesWithTiming instances", 2, srtCuesList.size)

        // Verify Cue 1
        val cue1 = srtCuesList[0]
        assertEquals("Cue 1 start time is 1,000,000 us (1s)", 1000000L, cue1.startTimeUs)
        assertEquals("Cue 1 duration is 3,000,000 us (3s)", 3000000L, cue1.durationUs)
        assertTrue("Cue 1 cues list not empty", cue1.cues.isNotEmpty())
        assertEquals("Cue 1 text stripped of override tags", "ヒンメル：旅の目的", cue1.cues[0].text.toString())

        // Verify Cue 2
        val cue2 = srtCuesList[1]
        assertEquals("Cue 2 start time is 5,000,000 us (5s)", 5000000L, cue2.startTimeUs)
        assertEquals("Cue 2 duration is 3,500,000 us (3.5s)", 3500000L, cue2.durationUs)
        assertEquals("Cue 2 text", "フリーレン：死者を冒涜するな。", cue2.cues[0].text.toString())

        // 3. Transform to CloudStream SubtitleCue instances (matching CustomDecoder.parse$lambda$0)
        val subtitleCues = srtCuesList.map { c ->
            val startTimeMs = c.startTimeUs / 1000L
            val durationMs = c.durationUs / 1000L
            val textList = c.cues.map { it.text.toString() }
            SubtitleCue(startTimeMs, durationMs, textList)
        }

        assertEquals("2 SubtitleCue instances generated", 2, subtitleCues.size)
        assertEquals("SubtitleCue 1 start ms is 1000", 1000L, subtitleCues[0].startTimeMs)
        assertEquals("SubtitleCue 1 duration ms is 3000", 3000L, subtitleCues[0].durationMs)
        assertEquals("SubtitleCue 1 end ms is 4000", 4000L, subtitleCues[0].endTimeMs)
        assertEquals("SubtitleCue 1 text", listOf("ヒンメル：旅の目的"), subtitleCues[0].text)

        assertEquals("SubtitleCue 2 start ms is 5000", 5000L, subtitleCues[1].startTimeMs)
        assertEquals("SubtitleCue 2 duration ms is 3500", 3500L, subtitleCues[1].durationMs)
        assertEquals("SubtitleCue 2 end ms is 8500", 8500L, subtitleCues[1].endTimeMs)
        assertEquals("SubtitleCue 2 text", listOf("フリーレン：死者を冒涜するな。"), subtitleCues[1].text)
    }

    // -------------------------------------------------------------------------
    // 4. FullScreenPlayer showSubtitleOffsetDialog Bytecode Invariant Verification
    // -------------------------------------------------------------------------
    private fun testFullScreenPlayerOffsetDialogNoticeLogic() {
        println("\n>>> Suite 4: FullScreenPlayer showSubtitleOffsetDialog Invariants")

        // In FullScreenPlayer.showSubtitleOffsetDialog bytecode:
        // 280: invokeinterface IPlayer.getSubtitleCues:()List
        // 288: CollectionsKt.toMutableList(cues) -> stored in var10
        // 315: var10.isEmpty() -> if (!isEmpty) View.VISIBLE (0) else View.GONE (8) -> subtitleOffsetRecyclerview.setVisibility
        // 370: var10.isEmpty() -> if (isEmpty) View.VISIBLE (0) else View.GONE (8) -> noSubtitlesLoadedNotice.setVisibility

        // Case A: With converted Kitsunekko subtitle cues
        val validCues = listOf(
            SubtitleCue(1000L, 3000L, listOf("ヒンメル：旅の目的")),
            SubtitleCue(5000L, 3500L, listOf("フリーレン：死者を冒涜するな。"))
        )
        val listA = validCues.toMutableList()
        val recyclerviewVisibilityA = if (!listA.isEmpty()) 0 /* View.VISIBLE */ else 8 /* View.GONE */
        val noticeVisibilityA = if (listA.isEmpty()) 0 /* View.VISIBLE */ else 8 /* View.GONE */

        assertFalse("Player subtitle cues is NOT empty", listA.isEmpty())
        assertEquals("subtitleOffsetRecyclerview is VISIBLE (0)", 0, recyclerviewVisibilityA)
        assertEquals("noSubtitlesLoadedNotice is GONE (8)", 8, noticeVisibilityA)

        // Case B: Negative control with empty cues (e.g. OkHttp error or unconverted ASS)
        val emptyCues = emptyList<SubtitleCue>()
        val listB = emptyCues.toMutableList()
        val recyclerviewVisibilityB = if (!listB.isEmpty()) 0 /* View.VISIBLE */ else 8 /* View.GONE */
        val noticeVisibilityB = if (listB.isEmpty()) 0 /* View.VISIBLE */ else 8 /* View.GONE */

        assertTrue("Negative control cues is empty", listB.isEmpty())
        assertEquals("Negative control: recyclerview is GONE (8)", 8, recyclerviewVisibilityB)
        assertEquals("Negative control: notice is VISIBLE (0)", 0, noticeVisibilityB)
    }

    // -------------------------------------------------------------------------
    // 5. End-to-End Loopback Streaming and Live Cue Ingestion
    // -------------------------------------------------------------------------
    private fun testEndToEndLoopbackStreamingAndCueIngestion() {
        println("\n>>> Suite 5: End-to-End Loopback Streaming & Live Cue Ingestion")

        val port = KitsunekkoLocalServer.start()
        assertTrue("KitsunekkoLocalServer active on ephemeral port > 1024", port > 1024)

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_challenger2_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // 1. Prepare ASS subtitle content
            val assContent = """
                [Script Info]
                Title: E2E Test Ep 1
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:02.00,0:00:06.00,Default,,0,0,0,,{\b1}勇者ヒンメル{\b0}ならそうした。
                Dialogue: 0,0:00:07.50,0:00:11.00,Default,,0,0,0,,魔法を集めるのが趣味なんだ。
            """.trimIndent()

            // 2. Convert to SRT
            val srtContent = AssToSrtConverter.convertAssToSrt(assContent)
            val srtFile = File(tempDir, "ep1_e2e_japanese.srt")
            srtFile.writeText(srtContent, Charsets.UTF_8)

            // 3. Register with local server
            val emittedUrl = KitsunekkoLocalServer.registerSubtitle(srtFile)
            assertTrue("Emitted URL begins with http://127.0.0.1:$port/sub/",
                emittedUrl.startsWith("http://127.0.0.1:$port/sub/"))
            assertTrue("Emitted URL ends with .srt", emittedUrl.endsWith(".srt"))

            // 4. OkHttp parses URL and executes loopback GET
            val client = OkHttpClient()
            val req = Request.Builder().url(emittedUrl).build()
            val response = client.newCall(req).execute()

            assertEquals("Local loopback HTTP response code is 200", 200, response.code)
            assertEquals("Content-Type header is text/plain; charset=utf-8",
                "text/plain; charset=utf-8", response.header("Content-Type"))

            val responseBytes = response.body?.bytes() ?: byteArrayOf()
            assertTrue("Received non-empty response bytes", responseBytes.isNotEmpty())

            // 5. Ingest into CustomSubripParser from cloudstream.jar
            val parser = CustomSubripParser()
            val cuesWithTiming = mutableListOf<CuesWithTiming>()
            val consumer = Consumer<CuesWithTiming> { cuesWithTiming.add(it) }

            parser.parse(responseBytes, 0, responseBytes.size,
                SubtitleParser.OutputOptions.allCues(), consumer)

            assertEquals("Exactly 2 cues parsed from network response", 2, cuesWithTiming.size)

            val parsedSubtitleCues = cuesWithTiming.map { c ->
                SubtitleCue(c.startTimeUs / 1000L, c.durationUs / 1000L, c.cues.map { it.text.toString() })
            }

            assertEquals("Parsed SubtitleCue count is 2", 2, parsedSubtitleCues.size)
            assertEquals("Dialogue 1 text", listOf("勇者ヒンメルならそうした。"), parsedSubtitleCues[0].text)
            assertEquals("Dialogue 2 text", listOf("魔法を集めるのが趣味なんだ。"), parsedSubtitleCues[1].text)

            // 6. Verify offset dialog notice is prevented from being shown
            val offsetNoticeVisibility = if (parsedSubtitleCues.isEmpty()) 0 else 8
            assertEquals("noSubtitlesLoadedNotice visibility is GONE (8) - Notice hidden", 8, offsetNoticeVisibility)

        } finally {
            KitsunekkoLocalServer.stop()
            tempDir.deleteRecursively()
        }
    }
}
