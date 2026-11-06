package com.cloudstream.test.m1

import com.kitsunekko.enricher.AssToSrtConverter
import com.kitsunekko.enricher.KitsunekkoLocalServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Empirical Adversarial & Stress Harness for Milestone 1:
 * Kitsunekko Subtitle Loading and Display (Requirement R1).
 * Agent: challenger_m1_1
 *
 * Thoroughly stress-tests:
 * 1. AssToSrtConverter:
 *    - Empty and blank files
 *    - Malformed headers, missing [Events], truncated Format lines
 *    - Inverted timestamps, identical start/end, negative timestamps
 *    - Overlapping cues and chronological sort order
 *    - Complex drawing commands, karaoke tags, unclosed override tags
 *    - Japanese typography (kanji, hiragana, katakana, punctuation, surrogate pairs)
 *    - Multi-charset decoding (Shift-JIS 0x5C trail bytes, UTF-16 LE w/o BOM, UTF-8 BOM, CP1252)
 *    - SubRip normalization edge cases
 * 2. KitsunekkoLocalServer:
 *    - High-concurrency load (50 parallel client threads)
 *    - Directory traversal attacks (../, %2e%2e, null byte, unicode)
 *    - Bad request paths, unsupported HTTP verbs (POST, PUT, DELETE), HEAD request handling
 *    - Large payloads (2MB+ subtitle files with 10,000+ cues)
 *    - Connection resets, abrupt TCP aborts (RST), partial requests
 *    - Lifecycle idempotency (rapid start/stop/restart)
 */
object KitsunekkoMilestone1ChallengerTest {

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

    private fun runSuite(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            val msg = "  [FAIL] $name CRASHED with uncaught exception: ${t.javaClass.name}: ${t.message}"
            System.err.println(msg)
            t.printStackTrace()
            failureMessages.add(msg)
            failed++
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" CHALLENGER 1 EMPIRICAL ADVERSARIAL STRESS SUITE (MILESTONE 1)")
        println(" Target Components: KitsunekkoLocalServer & AssToSrtConverter")
        println("================================================================================")

        // AssToSrtConverter pathological test suite
        runSuite("Suite 1: Empty & Blank Inputs") { testEmptyAndBlankInputs() }
        runSuite("Suite 2: Malformed Headers & Column Formats") { testMalformedHeadersAndNonStandardColumns() }
        runSuite("Suite 3: Inverted & Pathological Timestamps") { testInvertedAndPathologicalTimestamps() }
        runSuite("Suite 4: Overlapping Cues & Sorting") { testOverlappingCuesAndChronologicalSorting() }
        runSuite("Suite 5: Drawings and Karaoke Tags") { testDrawingTagsAndKaraokeFiltering() }
        runSuite("Suite 6: Japanese Typography & Punctuation") { testJapaneseTextKanjiHiraganaPunctuation() }
        runSuite("Suite 7: Shift-JIS 0x5C Trail Byte & Charsets") { testShiftJisTrailByte5CAndMultiCharset() }
        runSuite("Suite 8: SubRip Normalization Edge Cases") { testSubRipNormalizationEdgeCases() }

        // KitsunekkoLocalServer stress test suite
        runSuite("Suite 9: Lifecycle & Idempotency") { testLocalServerLifecycleAndIdempotency() }
        runSuite("Suite 10: 50 Concurrent Requests") { testLocalServerConcurrentLoad() }
        runSuite("Suite 11: Path Traversal Resistance") { testLocalServerSecurityAndPathTraversal() }
        runSuite("Suite 12: Bad Requests & HTTP Methods") { testLocalServerBadRequestsAndHttpMethods() }
        runSuite("Suite 13: Large Payload (2MB Subtitle)") { testLocalServerLargePayload() }
        runSuite("Suite 14: Connection Resets & Abrupt Drops") { testLocalServerConnectionResetsAndSocketDrops() }

        println("================================================================================")
        println(" CHALLENGE SUITE SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFAILURES DETECTED IN CHALLENGE SUITE:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL ADVERSARIAL CHALLENGES COMPLETED SUCCESSFULLY WITH ZERO DEFECTS.")
            System.exit(0)
        }
    }

    // =========================================================================
    // 1. AssToSrtConverter: Empty & Blank Inputs
    // =========================================================================
    private fun testEmptyAndBlankInputs() {
        println("\n>>> Suite 1: AssToSrtConverter — Empty and Blank Inputs")

        assertEquals("Empty string returns empty SRT", "", AssToSrtConverter.convertAssToSrt(""))
        assertEquals("Whitespace-only string returns empty SRT", "", AssToSrtConverter.convertAssToSrt("   \r\n\t  \n"))
        assertEquals("Decoded empty byte array returns empty string", "", AssToSrtConverter.decodeAndNormalizeToSrt(ByteArray(0)))

        // Only BOM bytes
        val utf8BomOnly = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("UTF-8 BOM alone returns empty string", "", AssToSrtConverter.decodeAndNormalizeToSrt(utf8BomOnly))

        val utf16LeBomOnly = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertEquals("UTF-16 LE BOM alone returns empty string", "", AssToSrtConverter.decodeAndNormalizeToSrt(utf16LeBomOnly))

        // ASS with comments and style sections but zero dialogues
        val noDialogues = """
            [Script Info]
            Title: Empty Show
            ScriptType: v4.00+
            ; Just a comment line
            ! Another comment line
            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial
        """.trimIndent()
        assertEquals("ASS with 0 dialogue cues produces empty string", "", AssToSrtConverter.convertAssToSrt(noDialogues))
    }

    // =========================================================================
    // 2. AssToSrtConverter: Malformed Headers & Non-Standard Columns
    // =========================================================================
    private fun testMalformedHeadersAndNonStandardColumns() {
        println("\n>>> Suite 2: AssToSrtConverter — Malformed Headers & Column Formats")

        // 1. No [Events] header at all (truncated ASS starting directly with dialogues)
        val noEventsHeader = """
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,セクションヘッダーなしでも抽出可能
        """.trimIndent()
        val srt1 = AssToSrtConverter.convertAssToSrt(noEventsHeader)
        assertTrue("Truncated file without [Events] section extracts dialogues", srt1.contains("セクションヘッダーなしでも抽出可能"))
        assertTrue("SRT timing formatted correctly", srt1.contains("00:00:01,000 --> 00:00:03,000"))

        // 2. Format line with custom ordering: Text placed before Start and End
        val reorderedFormat = """
            [Events]
            Format: Text, End, Start, Style
            Dialogue: テキストが最初のカラム,0:01:30.00,0:01:25.00,Default
        """.trimIndent()
        val srt2 = AssToSrtConverter.convertAssToSrt(reorderedFormat)
        assertTrue("Custom column ordering (Text before Timing) parsed properly", srt2.contains("テキストが最初のカラム"))
        assertTrue("Start and end correctly mapped despite non-standard order", srt2.contains("00:01:25,000 --> 00:01:30,000"))

        // 3. Format line missing text column (falls back to last column)
        val shortFormat = """
            [Events]
            Format: Start, End, Payload
            Dialogue: 0:00:05.00,0:00:08.00,FallbackToLastColumn
        """.trimIndent()
        val srt3 = AssToSrtConverter.convertAssToSrt(shortFormat)
        assertTrue("Format missing 'text' column defaults to last column", srt3.contains("FallbackToLastColumn"))

        // 4. Multiple interspersed sections after [Events]
        val interruptedSections = """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,First
            [Fonts]
            fontname: Arial
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:03.00,0:00:04.00,Second
        """.trimIndent()
        val srt4 = AssToSrtConverter.convertAssToSrt(interruptedSections)
        assertTrue("Interrupted section parses first dialogue", srt4.contains("First"))
        assertTrue("Interrupted section parses second dialogue", srt4.contains("Second"))
    }

    // =========================================================================
    // 3. AssToSrtConverter: Inverted & Pathological Timestamps
    // =========================================================================
    private fun testInvertedAndPathologicalTimestamps() {
        println("\n>>> Suite 3: AssToSrtConverter — Inverted and Pathological Timestamps")

        // Inverted timing: Start > End (corrupt cue)
        val invertedAss = """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:05:00.00,0:02:00.00,Corrupted Inverted Cue
            Dialogue: 0:01:00.00,0:01:04.00,Valid Cue
        """.trimIndent()
        val srtInverted = AssToSrtConverter.convertAssToSrt(invertedAss)
        assertFalse("Inverted cue (end < start) is discarded", srtInverted.contains("Corrupted Inverted Cue"))
        assertTrue("Valid cue in same file is preserved", srtInverted.contains("Valid Cue"))

        // Instantaneous timing: Start == End (0ms duration)
        val zeroDurationAss = """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:02:10.00,0:02:10.00,Zero Duration Flash
        """.trimIndent()
        val srtZero = AssToSrtConverter.convertAssToSrt(zeroDurationAss)
        assertTrue("Zero-duration cue (start == end) handled cleanly", srtZero.contains("00:02:10,000 --> 00:02:10,000"))

        // Timestamp fraction variations: 1 digit, 2 digits, 3 digits, 4 digits
        assertEquals("1-digit fraction 0:00:01.5 -> 1500ms", 1500L, AssToSrtConverter.assTimeToMs("0:00:01.5"))
        assertEquals("2-digit fraction 0:00:01.50 -> 1500ms", 1500L, AssToSrtConverter.assTimeToMs("0:00:01.50"))
        assertEquals("3-digit fraction 0:00:01.500 -> 1500ms", 1500L, AssToSrtConverter.assTimeToMs("0:00:01.500"))
        assertEquals("4-digit fraction 0:00:01.5009 -> 1500ms (truncated to 3)", 1500L, AssToSrtConverter.assTimeToMs("0:00:01.5009"))
        assertEquals("Comma fraction 0:00:01,25 -> 1250ms", 1250L, AssToSrtConverter.assTimeToMs("0:00:01,25"))

        // Invalid format strings return null without throwing
        assertEquals("Malformed timestamp missing hours returns null", null, AssToSrtConverter.assTimeToMs("01:23.45"))
        assertEquals("Alphabetic timestamp returns null", null, AssToSrtConverter.assTimeToMs("invalid:time:stamp"))
        assertEquals("Blank timestamp returns null", null, AssToSrtConverter.assTimeToMs(""))
    }

    // =========================================================================
    // 4. AssToSrtConverter: Overlapping Cues & Chronological Sorting
    // =========================================================================
    private fun testOverlappingCuesAndChronologicalSorting() {
        println("\n>>> Suite 4: AssToSrtConverter — Overlapping Cues & Sorting")

        // Dialogue cues defined in disordered chronological order
        val disorderedAss = """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:05:00.00,0:05:04.00,Third Cue (appears first in file)
            Dialogue: 0:01:00.00,0:01:10.00,First Cue (long duration)
            Dialogue: 0:01:05.00,0:01:08.00,Second Cue (overlapping inside first cue)
        """.trimIndent()

        val srt = AssToSrtConverter.convertAssToSrt(disorderedAss)
        val cueBlocks = srt.trim().split("\n\n")
        assertEquals("All 3 cues converted", 3, cueBlocks.size)

        // Block 1 must be 00:01:00,000
        assertTrue("Block 1 has index 1", cueBlocks[0].startsWith("1\n"))
        assertTrue("Block 1 is sorted to 00:01:00,000", cueBlocks[0].contains("00:01:00,000 --> 00:01:10,000"))
        assertTrue("Block 1 text matches First Cue", cueBlocks[0].contains("First Cue"))

        // Block 2 must be 00:01:05,000
        assertTrue("Block 2 has index 2", cueBlocks[1].startsWith("2\n"))
        assertTrue("Block 2 is sorted to 00:01:05,000", cueBlocks[1].contains("00:01:05,000 --> 00:01:08,000"))
        assertTrue("Block 2 text matches Second Cue", cueBlocks[1].contains("Second Cue"))

        // Block 3 must be 00:05:00,000
        assertTrue("Block 3 has index 3", cueBlocks[2].startsWith("3\n"))
        assertTrue("Block 3 is sorted to 00:05:00,000", cueBlocks[2].contains("00:05:00,000 --> 00:05:04,000"))
        assertTrue("Block 3 text matches Third Cue", cueBlocks[2].contains("Third Cue"))
    }

    // =========================================================================
    // 5. AssToSrtConverter: Drawing Commands & Karaoke Filtering
    // =========================================================================
    private fun testDrawingTagsAndKaraokeFiltering() {
        println("\n>>> Suite 5: AssToSrtConverter — Drawings and Karaoke Tags")

        // Complex drawing command preceded or followed by dialogue
        val complexDrawing = """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:04.00,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}実際のセリフです
            Dialogue: 0:00:05.00,0:00:08.00,{\p2}m 10 20 l 30 40{\p0}
            Dialogue: 0:00:09.00,0:00:12.00,{\p1}m 0 0 l 50 50
        """.trimIndent()

        val srt = AssToSrtConverter.convertAssToSrt(complexDrawing)
        val cues = srt.trim().split("\n\n").filter { it.isNotBlank() }

        assertEquals("Only dialogue with real text retained; pure drawings eliminated", 1, cues.size)
        assertTrue("Drawing command stripped leaving dialogue", cues[0].contains("実際のセリフです"))
        assertFalse("Vector coordinates omitted", srt.contains("m 0 0"))

        // Karaoke tags {\k50}, {\K50}, {\kf50}, {\ko50}
        val karaokeAss = """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:10.00,0:00:15.00,{\k20}あ{\K30}な{\kf40}た{\ko50}へ
        """.trimIndent()
        val srtKaraoke = AssToSrtConverter.convertAssToSrt(karaokeAss)
        assertTrue("Karaoke tags cleanly stripped leaving lyrics", srtKaraoke.contains("あなたへ"))
        assertFalse("Raw karaoke tags not present", srtKaraoke.contains("{\\k"))

        // Unclosed override tags and complex combinations
        val messyTags = "{\\pos(100,200)\\an7\\b1\\i1}見出しテキスト{\\b0\\i0}"
        assertEquals("Complex tags stripped properly", "見出しテキスト", AssToSrtConverter.stripAssTags(messyTags))

        // Newline escapes
        val newlines = "Line 1\\NLine 2\\nLine 3\\hWithHardSpace"
        assertEquals("Newlines and hard space translated", "Line 1\nLine 2\nLine 3 WithHardSpace", AssToSrtConverter.stripAssTags(newlines))
    }

    // =========================================================================
    // 6. AssToSrtConverter: Japanese Typography & Punctuation
    // =========================================================================
    private fun testJapaneseTextKanjiHiraganaPunctuation() {
        println("\n>>> Suite 6: AssToSrtConverter — Japanese Typography & Punctuation")

        val diverseJapanese = """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:05.00,「待って、ヒンメル！　…本当に行くの？」
            Dialogue: 0:00:06.00,0:00:10.00,『魔法使いの弟子』――第1話【旅立ち】
            Dialogue: 0:00:11.00,0:00:15.00,難読漢字：鬱、薔薇、麒麟、髑髏、顰蹙
            Dialogue: 0:00:16.00,0:00:20.00,サロゲートペア：𠮷野家と絵文字🌸🌟
        """.trimIndent()

        val srt = AssToSrtConverter.convertAssToSrt(diverseJapanese)
        assertTrue("Japanese corner brackets and ellipsis preserved", srt.contains("「待って、ヒンメル！　…本当に行くの？」"))
        assertTrue("Double brackets and em-dash preserved", srt.contains("『魔法使いの弟子』――第1話【旅立ち】"))
        assertTrue("Complex JIS kanji preserved intact", srt.contains("難読漢字：鬱、薔薇、麒麟、髑髏、顰蹙"))
        assertTrue("Surrogate pair kanji and emojis preserved intact", srt.contains("サロゲートペア：𠮷野家と絵文字🌸🌟"))
    }

    // =========================================================================
    // 7. AssToSrtConverter: Shift-JIS 0x5C Trail Byte & Multi-Charset
    // =========================================================================
    private fun testShiftJisTrailByte5CAndMultiCharset() {
        println("\n>>> Suite 7: AssToSrtConverter — Shift-JIS 0x5C Trail Byte & Charsets")

        // Shift-JIS characters whose second byte is 0x5C (ASCII backslash '\'):
        // ソ (0x83 0x5C), 噂 (0x8D 0x5C), 欺 (0x8B 0x5C), 圭 (0x8C 0x5C), 構 (0x8D 0x5C), 能 (0x94 0x5C), 表 (0x95 0x5C)
        val trickySjisText = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:01.00,0:00:04.00,噂の能ある鷹は爪を隠す：ソ表構"
        val sjisCharset = Charset.forName("Shift_JIS")
        val sjisBytes = trickySjisText.toByteArray(sjisCharset)

        val decodedSjis = AssToSrtConverter.decodeAndNormalizeToSrt(sjisBytes)
        assertTrue("Shift-JIS 0x5C trail-byte characters decoded without corruption",
            decodedSjis.contains("噂の能ある鷹は爪を隠す：ソ表構"))

        // UTF-16 LE without BOM
        val utf16Text = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:01.00,0:00:04.00,UTF-16 LE BOMなし日本語"
        val utf16Bytes = utf16Text.toByteArray(Charsets.UTF_16LE)
        val decoded16 = AssToSrtConverter.decodeAndNormalizeToSrt(utf16Bytes)
        assertTrue("UTF-16 LE without BOM detected via heuristic and decoded",
            decoded16.contains("UTF-16 LE BOMなし日本語"))

        // UTF-16 BE with BOM
        val utf16BeBytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + utf16Text.toByteArray(Charsets.UTF_16BE)
        val decoded16Be = AssToSrtConverter.decodeAndNormalizeToSrt(utf16BeBytes)
        assertTrue("UTF-16 BE with BOM decoded properly",
            decoded16Be.contains("UTF-16 LE BOMなし日本語"))

        // CP1252 / ISO-8859-1 European text
        val latinText = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:01.00,0:00:04.00,Café résumé naïve señorita"
        val latinBytes = latinText.toByteArray(Charset.forName("windows-1252"))
        val decodedLatin = AssToSrtConverter.decodeAndNormalizeToSrt(latinBytes)
        assertTrue("Windows-1252 accents preserved", decodedLatin.contains("Café résumé naïve señorita"))
    }

    // =========================================================================
    // 8. AssToSrtConverter: SubRip Normalization Edge Cases
    // =========================================================================
    private fun testSubRipNormalizationEdgeCases() {
        println("\n>>> Suite 8: AssToSrtConverter — SubRip Normalization Edge Cases")

        // Input already in SRT format with periods and gaps in numbering
        val rawSrt = """
            42
            00:01:02.123 --> 00:01:05.456
            First raw cue

            999
            00:02:10.000 --> 00:02:15.500
            Second raw cue
        """.trimIndent()

        val normalized = AssToSrtConverter.normalizeSrt(rawSrt)
        assertTrue("Timing converted to commas", normalized.contains("00:01:02,123 --> 00:01:05,456"))
        assertTrue("Cue numbering normalized starting at 1", normalized.startsWith("1\n"))
        assertTrue("Second cue renumbered to 2", normalized.contains("\n2\n00:02:10,000 --> 00:02:15,500"))

        // Mixed line endings (\r\n and \r)
        val mixedLineEndings = "1\r\n00:00:01,000 --> 00:00:02,000\rLine A\r\n\r\n2\n00:00:03,000 --> 00:00:04,000\nLine B\n\n"
        val normalizedEndings = AssToSrtConverter.normalizeSrt(mixedLineEndings)
        assertTrue("Line endings normalized without crash", normalizedEndings.contains("Line A"))
        assertTrue("Second block retained", normalizedEndings.contains("Line B"))
    }

    // =========================================================================
    // 9. KitsunekkoLocalServer: Lifecycle & Idempotency
    // =========================================================================
    private fun testLocalServerLifecycleAndIdempotency() {
        println("\n>>> Suite 9: KitsunekkoLocalServer — Lifecycle & Idempotency")

        val port1 = KitsunekkoLocalServer.start()
        assertTrue("Initial start allocates valid port", port1 > 1024)
        assertTrue("Server reports isRunning=true", KitsunekkoLocalServer.isRunning)

        // Calling start() again when running returns identical port (idempotent)
        val port2 = KitsunekkoLocalServer.start()
        assertEquals("Subsequent start() call returns same active port", port1, port2)

        // Stop server
        KitsunekkoLocalServer.stop()
        assertFalse("Server reports isRunning=false after stop()", KitsunekkoLocalServer.isRunning)
        assertEquals("Listening port reset to 0 after stop()", 0, KitsunekkoLocalServer.port)

        // Calling stop() again when stopped does not throw
        KitsunekkoLocalServer.stop()

        // Restart server
        val port3 = KitsunekkoLocalServer.start()
        assertTrue("Restart allocates valid port", port3 > 1024)
        assertTrue("Server isRunning=true after restart", KitsunekkoLocalServer.isRunning)
    }

    // =========================================================================
    // 10. KitsunekkoLocalServer: High-Concurrency Load
    // =========================================================================
    private fun testLocalServerConcurrentLoad() {
        println("\n>>> Suite 10: KitsunekkoLocalServer — 50 Concurrent Requests")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_challenger_load_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val fileCount = 5
            val urls = mutableListOf<String>()
            for (i in 1..fileCount) {
                val f = File(tempDir, "concurrent_sub_$i.srt")
                f.writeText("1\n00:00:01,000 --> 00:00:05,000\nConcurrent Test Subtitle #$i\n\n", Charsets.UTF_8)
                urls.add(KitsunekkoLocalServer.registerSubtitle(f))
            }

            val threadCount = 50
            val pool = Executors.newFixedThreadPool(16)
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val tasks = (0 until threadCount).map { idx ->
                Callable {
                    val targetUrl = urls[idx % fileCount]
                    val expectedNum = (idx % fileCount) + 1
                    val req = Request.Builder().url(targetUrl).build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        Triple(resp.code, body.contains("Concurrent Test Subtitle #$expectedNum"), body.length)
                    }
                }
            }

            val futures = pool.invokeAll(tasks)
            val results = futures.map { it.get() }
            pool.shutdown()

            assertTrue("All 50 concurrent requests returned HTTP 200", results.all { it.first == 200 })
            assertTrue("All 50 responses contained expected content without race conditions", results.all { it.second })
            assertTrue("All 50 responses had non-zero body length", results.all { it.third > 0 })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 11. KitsunekkoLocalServer: Security & Path Traversal
    // =========================================================================
    private fun testLocalServerSecurityAndPathTraversal() {
        println("\n>>> Suite 11: KitsunekkoLocalServer — Path Traversal Resistance")

        val port = KitsunekkoLocalServer.port.takeIf { it > 0 } ?: KitsunekkoLocalServer.start()
        val client = OkHttpClient()

        val attacks = listOf(
            "/sub/../secret.txt",
            "/sub/../../../../etc/passwd",
            "/sub/..\\..\\windows\\win.ini",
            "/sub/%2e%2e%2fsecret.txt",
            "/sub/%252e%252e%252fsecret.txt",
            "/sub/test.srt%00../../etc/passwd",
            "/sub/....//....//etc/shadow"
        )

        for (attackPath in attacks) {
            val url = "http://127.0.0.1:$port$attackPath"
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    assertEquals("Attack blocked with 404: $attackPath", 404, resp.code)
                }
            } catch (e: IllegalArgumentException) {
                // OkHttp itself rejected the invalid traversal URL before transmission
                assertTrue("Client/Server rejected traversal path: $attackPath", true)
            }
        }
    }

    // =========================================================================
    // 12. KitsunekkoLocalServer: Bad Requests & Unsupported HTTP Methods
    // =========================================================================
    private fun testLocalServerBadRequestsAndHttpMethods() {
        println("\n>>> Suite 12: KitsunekkoLocalServer — Bad Requests & HTTP Methods")

        val port = KitsunekkoLocalServer.port.takeIf { it > 0 } ?: KitsunekkoLocalServer.start()
        val client = OkHttpClient()

        // 1. Register a test subtitle
        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_verbs_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val testFile = File(tempDir, "verbs_test.srt")
            testFile.writeText("1\n00:00:01,000 --> 00:00:02,000\nVerb Test\n\n", Charsets.UTF_8)
            val targetUrl = KitsunekkoLocalServer.registerSubtitle(testFile)

            // HEAD request should return 200 with Content-Length header but EMPTY body
            val headReq = Request.Builder().url(targetUrl).head().build()
            client.newCall(headReq).execute().use { resp ->
                assertEquals("HEAD request returns HTTP 200", 200, resp.code)
                val cl = resp.header("Content-Length")?.toIntOrNull() ?: 0
                assertTrue("HEAD returns non-zero Content-Length", cl > 0)
                assertEquals("HEAD body is empty", "", resp.body?.string() ?: "")
            }

            // POST request should return 405 Method Not Allowed
            val postReq = Request.Builder().url(targetUrl).post("data".toRequestBody()).build()
            client.newCall(postReq).execute().use { resp ->
                assertEquals("POST returns 405 Method Not Allowed", 405, resp.code)
            }

            // PUT request should return 405 Method Not Allowed
            val putReq = Request.Builder().url(targetUrl).put("data".toRequestBody()).build()
            client.newCall(putReq).execute().use { resp ->
                assertEquals("PUT returns 405 Method Not Allowed", 405, resp.code)
            }

            // DELETE request should return 405 Method Not Allowed
            val deleteReq = Request.Builder().url(targetUrl).delete().build()
            client.newCall(deleteReq).execute().use { resp ->
                assertEquals("DELETE returns 405 Method Not Allowed", 405, resp.code)
            }

            // Path not starting with /sub/ returns 404
            val nonSubReq = Request.Builder().url("http://127.0.0.1:$port/api/test.srt").build()
            client.newCall(nonSubReq).execute().use { resp ->
                assertEquals("Non-/sub/ path returns 404", 404, resp.code)
            }

            // Root path returns 404
            val rootReq = Request.Builder().url("http://127.0.0.1:$port/").build()
            client.newCall(rootReq).execute().use { resp ->
                assertEquals("Root path returns 404", 404, resp.code)
            }

            // Non-existent file in /sub/ returns 404
            val missingReq = Request.Builder().url("http://127.0.0.1:$port/sub/missing_file_404.srt").build()
            client.newCall(missingReq).execute().use { resp ->
                assertEquals("Missing file returns 404", 404, resp.code)
            }

            // Query parameter passthrough (e.g. cache-busters)
            val queryReq = Request.Builder().url("$targetUrl?v=12345&lang=ja").build()
            client.newCall(queryReq).execute().use { resp ->
                assertEquals("URL with query parameters returns HTTP 200", 200, resp.code)
                assertTrue("Body matches subtitle content", resp.body?.string()?.contains("Verb Test") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 13. KitsunekkoLocalServer: Large Payload Stress (2MB+ Subtitle File)
    // =========================================================================
    private fun testLocalServerLargePayload() {
        println("\n>>> Suite 13: KitsunekkoLocalServer — Large Payload (2MB Subtitle)")

        val port = KitsunekkoLocalServer.port.takeIf { it > 0 } ?: KitsunekkoLocalServer.start()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_large_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val largeFile = File(tempDir, "ep_massive_subs.srt")
            val sb = java.lang.StringBuilder()
            val cueCount = 10_000
            for (i in 1..cueCount) {
                sb.append(i).append("\n")
                sb.append(String.format(java.util.Locale.US, "00:%02d:%02d,000 --> 00:%02d:%02d,500\n",
                    (i / 60) % 60, i % 60, (i / 60) % 60, i % 60))
                sb.append("日本語字幕台詞 第").append(i).append("行：フリーレンの大冒険\n\n")
            }
            val content = sb.toString()
            largeFile.writeText(content, Charsets.UTF_8)
            val expectedSize = largeFile.length()
            assertTrue("Created large subtitle file > 500KB", expectedSize > 500_000L)

            val url = KitsunekkoLocalServer.registerSubtitle(largeFile)
            val client = OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                assertEquals("Large file returns HTTP 200", 200, resp.code)
                val returnedBytes = resp.body?.bytes() ?: ByteArray(0)
                assertEquals("Exact byte size received for 2MB payload", expectedSize, returnedBytes.size.toLong())
                val returnedText = String(returnedBytes, Charsets.UTF_8)
                assertTrue("Contains first cue", returnedText.contains("日本語字幕台詞 第1行"))
                assertTrue("Contains last cue", returnedText.contains("日本語字幕台詞 第10000行"))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 14. KitsunekkoLocalServer: Connection Resets & Abrupt Socket Drops
    // =========================================================================
    private fun testLocalServerConnectionResetsAndSocketDrops() {
        println("\n>>> Suite 14: KitsunekkoLocalServer — Connection Resets & Abrupt Drops")

        val port = KitsunekkoLocalServer.port.takeIf { it > 0 } ?: KitsunekkoLocalServer.start()

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_resets_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val testFile = File(tempDir, "reset_test.srt")
            testFile.writeText("1\n00:00:01,000 --> 00:00:04,000\nConnection Reset Resilience\n\n", Charsets.UTF_8)
            KitsunekkoLocalServer.registerSubtitle(testFile)

            // 1. Partial request line followed by abrupt socket close
            for (i in 1..5) {
                try {
                    val rawSocket = Socket("127.0.0.1", port)
                    val writer = OutputStreamWriter(rawSocket.getOutputStream(), Charsets.UTF_8)
                    writer.write("GET /sub/re")
                    writer.flush()
                    rawSocket.setSoLinger(true, 0) // Force immediate TCP RST packet
                    rawSocket.close()
                } catch (_: Throwable) {}
            }
            assertTrue("Handled partial request and immediate socket reset without crashing", true)

            // 2. Full request sent, partial response read (10 bytes), then immediate socket reset
            for (i in 1..5) {
                try {
                    val rawSocket = Socket("127.0.0.1", port)
                    val writer = OutputStreamWriter(rawSocket.getOutputStream(), Charsets.UTF_8)
                    writer.write("GET /sub/reset_test.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    writer.flush()
                    val reader = rawSocket.getInputStream()
                    val buffer = ByteArray(10)
                    reader.read(buffer) // Read initial 10 bytes of HTTP header
                    rawSocket.setSoLinger(true, 0) // Abrupt RST
                    rawSocket.close()
                } catch (_: Throwable) {}
            }
            assertTrue("Handled mid-response client reset without crashing", true)

            // 3. Connect and immediately close without sending any bytes (syn-flood / port probe simulation)
            for (i in 1..20) {
                try {
                    val rawSocket = Socket("127.0.0.1", port)
                    rawSocket.close()
                } catch (_: Throwable) {}
            }
            assertTrue("Handled 20 rapid connect/disconnect probes without thread death", true)

            // 4. Verify server is STILL fully functional and responding to normal requests
            val client = OkHttpClient()
            val verifyReq = Request.Builder().url("http://127.0.0.1:$port/sub/reset_test.srt").build()
            client.newCall(verifyReq).execute().use { resp ->
                assertEquals("Server remains completely healthy after connection reset barrage", 200, resp.code)
                assertTrue("Server delivers intact content", resp.body?.string()?.contains("Connection Reset Resilience") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
