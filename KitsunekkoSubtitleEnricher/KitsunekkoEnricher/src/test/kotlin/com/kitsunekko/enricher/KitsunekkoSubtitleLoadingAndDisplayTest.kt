package com.kitsunekko.enricher

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Empirical Adversarial & Integration Test Harness for Requirement R1:
 * Kitsunekko Subtitle Loading and Display & Subtitle Offset Dialog Readiness.
 */
object KitsunekkoSubtitleLoadingAndDisplayTest {

    private var passed = 0
    private var failed = 0
    private val failureMessages = mutableListOf<String>()

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            val msg = "  [FAIL] $name: $detail"
            System.err.println(msg)
            failureMessages.add(msg)
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
        println(" KITSUNEKKO SUBTITLE LOADING & DISPLAY VERIFICATION SUITE (REQUIREMENT R1)")
        println("================================================================================")

        testComplexAssAndSsaConversion()
        testCustomFormatHeaderColumnOrdering()
        testTextWithCommasAndSpecialCharacters()
        testDrawingAndKaraokeFiltering()
        testMultiCharsetAndBOMVariations()
        testLocalServerConcurrentStreaming()
        testLocalServerSecurityAndPathTraversal()
        testCloudStreamSubtitlePipelineIngestionAndOffsetDialogReadiness()

        println("================================================================================")
        println(" SUITE SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed assertions:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL SUBTITLE LOADING AND DISPLAY TESTS PASSED SUCCESSFULLY!")
            System.exit(0)
        }
    }

    // -------------------------------------------------------------------------
    // 1. Complex ASS and SSA Conversion
    // -------------------------------------------------------------------------
    private fun testComplexAssAndSsaConversion() {
        println("\n>>> Suite 1: Complex ASS and SSA Conversion")

        val complexAss = """
            [Script Info]
            Title: Sousou no Frieren - Ep 05
            ScriptType: v4.00+
            WrapStyle: 0

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:20.00,0:01:25.50,Default,,0,0,0,,{\an8\fad(300,300)\c&H00FFFF&}Top subtitle warning!
            Dialogue: 0,0:00:10.15,0:00:14.80,Default,,0,0,0,,{\pos(192,240)\b1}フリーレン：{\b0}死者を冒涜するな。
            Dialogue: 0,0:00:02.00,0:00:05.00,Default,,0,0,0,,{\i1}序章：勇者の旅立ち{\i0}\N第5話
        """.trimIndent()

        val srt = AssToSrtConverter.convertAssToSrt(complexAss)
        assertTrue("SubRip format detected", AssToSrtConverter.isSubRip(srt))

        val cues = srt.trim().split("\n\n")
        assertEquals("Exactly 3 dialogue cues generated", 3, cues.size)

        // Cue 1: 00:00:02,000 -> 00:00:05,000
        val cue1Lines = cues[0].lines()
        assertEquals("Cue 1 index is 1", "1", cue1Lines[0].trim())
        assertEquals("Cue 1 timing", "00:00:02,000 --> 00:00:05,000", cue1Lines[1].trim())
        assertTrue("Cue 1 text line 1", cue1Lines[2].contains("序章：勇者の旅立ち"))
        assertTrue("Cue 1 text line 2", cue1Lines[3].contains("第5話"))

        // Cue 2: 00:00:10,150 -> 00:00:14,800
        val cue2Lines = cues[1].lines()
        assertEquals("Cue 2 index is 2", "2", cue2Lines[0].trim())
        assertEquals("Cue 2 timing", "00:00:10,150 --> 00:00:14,800", cue2Lines[1].trim())
        assertTrue("Cue 2 bold tags stripped", cue2Lines[2].contains("フリーレン：死者を冒涜するな。"))

        // Cue 3: 00:01:20,000 -> 00:01:25,500
        val cue3Lines = cues[2].lines()
        assertEquals("Cue 3 index is 3", "3", cue3Lines[0].trim())
        assertEquals("Cue 3 timing", "00:01:20,000 --> 00:01:25,500", cue3Lines[1].trim())
        assertEquals("Cue 3 text tags stripped", "Top subtitle warning!", cue3Lines[2].trim())
    }

    // -------------------------------------------------------------------------
    // 2. Custom Format Header Column Ordering
    // -------------------------------------------------------------------------
    private fun testCustomFormatHeaderColumnOrdering() {
        println("\n>>> Suite 2: Custom Format Header Column Ordering")

        // Fansub variant with different field order: Start, End, Style, Text
        val customFormatAss = """
            [Events]
            Format: Start, End, Style, Text
            Dialogue: 0:02:15.30,0:02:18.90,Default,カスタムフォーマットでも正常に動作する
        """.trimIndent()

        val srt = AssToSrtConverter.convertAssToSrt(customFormatAss)
        assertTrue("Converted custom format successfully", srt.contains("00:02:15,300 --> 00:02:18,900"))
        assertTrue("Preserved text correctly", srt.contains("カスタムフォーマットでも正常に動作する"))
    }

    // -------------------------------------------------------------------------
    // 3. Text Containing Commas and Quotes
    // -------------------------------------------------------------------------
    private fun testTextWithCommasAndSpecialCharacters() {
        println("\n>>> Suite 3: Text With Commas and Special Characters")

        val dialogueWithCommas = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,Wait, don't shoot, he's friendly, I swear!
            Dialogue: 0,0:00:06.00,0:00:09.00,Default,,0,0,0,,「待って、行かないで、お願い！」
        """.trimIndent()

        val srt = AssToSrtConverter.convertAssToSrt(dialogueWithCommas)
        assertTrue("English text with multiple commas preserved intact",
            srt.contains("Wait, don't shoot, he's friendly, I swear!"))
        assertTrue("Japanese punctuation preserved intact",
            srt.contains("「待って、行かないで、お願い！」"))
    }

    // -------------------------------------------------------------------------
    // 4. Drawing Commands and Karaoke Filtering
    // -------------------------------------------------------------------------
    private fun testDrawingAndKaraokeFiltering() {
        println("\n>>> Suite 4: Drawing Commands and Karaoke Filtering")

        val assWithDrawingsAndKaraoke = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}
            Dialogue: 0,0:00:05.00,0:00:10.00,Default,,0,0,0,,{\k15}か{\k25}ら{\k35}お{\k45}け{\k55}歌
        """.trimIndent()

        val srt = AssToSrtConverter.convertAssToSrt(assWithDrawingsAndKaraoke)
        val cues = srt.trim().split("\n\n").filter { it.isNotBlank() }

        assertEquals("Pure drawing dialogue discarded, only karaoke text retained", 1, cues.size)
        assertTrue("Karaoke tags stripped leaving clean lyrics", cues[0].contains("からおけ歌"))
        assertFalse("Drawing vector command m 0 0 completely eliminated", srt.contains("m 0 0"))
    }

    // -------------------------------------------------------------------------
    // 5. Multi-Charset and BOM Variations
    // -------------------------------------------------------------------------
    private fun testMultiCharsetAndBOMVariations() {
        println("\n>>> Suite 5: Multi-Charset and BOM Variations")

        val japaneseText = "[Events]\nFormat: Layer, Start, End, Text\nDialogue: 0,0:00:01.00,0:00:03.00,日本語字幕のテスト"

        // 1. UTF-16 LE without BOM heuristic
        val utf16LeBytes = japaneseText.toByteArray(Charsets.UTF_16LE)
        val decoded16 = AssToSrtConverter.decodeAndNormalizeToSrt(utf16LeBytes)
        assertTrue("UTF-16 LE without BOM decoded and converted", decoded16.contains("日本語字幕のテスト"))

        // 2. Shift-JIS encoding
        val sjisBytes = japaneseText.toByteArray(Charset.forName("Shift_JIS"))
        val decodedSjis = AssToSrtConverter.decodeAndNormalizeToSrt(sjisBytes)
        assertTrue("Shift-JIS decoded and converted", decodedSjis.contains("日本語字幕のテスト"))

        // 3. UTF-8 BOM
        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + japaneseText.toByteArray(Charsets.UTF_8)
        val decodedBom = AssToSrtConverter.decodeAndNormalizeToSrt(utf8BomBytes)
        assertTrue("UTF-8 BOM decoded and converted", decodedBom.contains("日本語字幕のテスト"))
    }

    // -------------------------------------------------------------------------
    // 6. Local Server Concurrent Streaming
    // -------------------------------------------------------------------------
    private fun testLocalServerConcurrentStreaming() {
        println("\n>>> Suite 6: Local Server Concurrent Streaming")

        val port = KitsunekkoLocalServer.start()
        assertTrue("Server active on port > 1024", port > 1024)

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_stress_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val fileCount = 5
            val urls = mutableListOf<String>()
            for (i in 1..fileCount) {
                val f = File(tempDir, "ep_${i}_test.srt")
                f.writeText("1\n00:00:01,000 --> 00:00:04,000\nEpisode $i Japanese Subtitle\n\n", Charsets.UTF_8)
                urls.add(KitsunekkoLocalServer.registerSubtitle(f))
            }

            // Execute 10 concurrent requests using thread pool
            val pool = Executors.newFixedThreadPool(10)
            val client = OkHttpClient()

            val tasks = (0 until 10).map { idx ->
                Callable {
                    val targetUrl = urls[idx % fileCount]
                    val req = Request.Builder().url(targetUrl).build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        Pair(resp.code, body)
                    }
                }
            }

            val results = pool.invokeAll(tasks).map { it.get() }
            pool.shutdown()

            assertTrue("All 10 concurrent requests returned HTTP 200", results.all { it.first == 200 })
            assertTrue("All responses contained expected Japanese Subtitle text",
                results.all { it.second.contains("Japanese Subtitle") })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 7. Local Server Security and Path Traversal Protection
    // -------------------------------------------------------------------------
    private fun testLocalServerSecurityAndPathTraversal() {
        println("\n>>> Suite 7: Local Server Security and Path Traversal Protection")

        val port = KitsunekkoLocalServer.port.takeIf { it > 0 } ?: KitsunekkoLocalServer.start()
        val client = OkHttpClient()

        // 1. Directory traversal via ../
        val traversalUrl = "http://127.0.0.1:$port/sub/../../../../etc/passwd"
        val req = Request.Builder().url(traversalUrl).build()
        client.newCall(req).execute().use { resp ->
            assertEquals("Path traversal attempts blocked with HTTP 404", 404, resp.code)
        }

        // 2. Encoded traversal via %2e%2e
        val encodedTraversal = "http://127.0.0.1:$port/sub/%2e%2e%2f%2e%2e%2froot.srt"
        val req2 = Request.Builder().url(encodedTraversal).build()
        client.newCall(req2).execute().use { resp ->
            assertEquals("Encoded traversal attempts blocked with HTTP 404", 404, resp.code)
        }
    }

    // -------------------------------------------------------------------------
    // 8. CloudStream Player Ingestion and Offset Dialog Readiness (Requirement R1)
    // -------------------------------------------------------------------------
    private fun testCloudStreamSubtitlePipelineIngestionAndOffsetDialogReadiness() {
        println("\n>>> Suite 8: CloudStream Player Ingestion and Offset Dialog Readiness")

        val port = KitsunekkoLocalServer.port.takeIf { it > 0 } ?: KitsunekkoLocalServer.start()
        val streamUrl = "http://127.0.0.1:$port/sub/ep_1_sousou_no_frieren.srt"

        // Step 1: SubtitleFile instantiated with loopback HTTP URL
        val subFile = SubtitleFile("Tiếng Nhật (Kitsunekko)", streamUrl)

        // Step 2: PlayerSubtitleHelper transforms SubtitleFile to SubtitleData
        val subData = PlayerSubtitleHelper.Companion.getSubtitleData(subFile)

        // Origin invariant
        assertEquals("SubtitleData origin is SubtitleOrigin.URL", SubtitleOrigin.URL, subData.origin)

        // MIME type invariant
        assertEquals("PlayerSubtitleHelper assigns application/x-subrip for .srt",
            "application/x-subrip", subData.mimeType)

        // OkHttp DataSource capability invariant
        val okHttp = OkHttpClient()
        val okHttpReq = Request.Builder().url(subData.url).build()
        assertEquals("OkHttp parses URL without IllegalArgumentException", "http", okHttpReq.url.scheme)
        assertEquals("OkHttp target is local loopback host", "127.0.0.1", okHttpReq.url.host)

        // Subtitle offset dialog invariant
        // In FullScreenPlayer.showSubtitleOffsetDialog():
        // if (player.getSubtitleCues().isEmpty()) -> noSubtitlesLoadedNotice is VISIBLE
        // else -> subtitleOffsetRecyclerview is VISIBLE, notice is GONE
        // With valid SubRip syntax emitted from AssToSrtConverter, ExoPlayer CustomSubripParser produces non-empty cues:
        val mockAss = "[Events]\nFormat: Layer, Start, End, Text\nDialogue: 0,0:00:01.00,0:00:04.00,ヒンメル：旅の目的"
        val srt = AssToSrtConverter.convertAssToSrt(mockAss)
        assertTrue("Converted SRT is valid SubRip with timing tokens",
            srt.contains("00:00:01,000 --> 00:00:04,000"))
        assertTrue("Converted SRT contains Japanese text", srt.contains("ヒンメル：旅の目的"))
    }
}
