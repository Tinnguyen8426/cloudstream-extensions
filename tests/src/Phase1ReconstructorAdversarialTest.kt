package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleReconstructor

object Phase1ReconstructorAdversarialTest {

    fun runAllTests() {
        println("================================================================================")
        println(">>> Starting Challenger 2 Empirical Adversarial Stress Suite (Kotlin JVM)...")
        println("================================================================================")

        testTokenCasingAndSpacingVariations()
        testNonExistentTokensPreservation()
        testSevereUnclosedTagNesting()
        testAsymmetricTagCounts()
        testMixedCaseUnclosedTags()
        testPreBalancedTagsInvariance()
        testSubRipGenerationAndFallback()
        testTimestampFormattingBoundaries()
        testEndToEndParseAndReconstructRoundTrip()

        println("================================================================================")
        println(">>> ALL 9 CHALLENGER 2 EMPIRICAL ADVERSARIAL STRESS TESTS PASSED WITH 100% SUCCESS!")
        println("================================================================================")
    }

    private fun testTokenCasingAndSpacingVariations() {
        print("  [STRESS TEST 1] Token casing and spacing variations... ")
        val tagMap = mapOf(
            "[[TAG_1]]" to "<i>",
            "[[TAG_2]]" to "</i>",
            "[[TAG_12]]" to "<font color=\"#FFCC00\">",
            "[[TAG_13]]" to "</font>"
        )

        // Lowercase
        val lower = SubtitleReconstructor.unmaskTags("[[tag_1]]Hello[[tag_2]]", tagMap)
        check(lower == "<i>Hello</i>") { "Failed lowercase unmask: $lower" }

        // Uppercase
        val upper = SubtitleReconstructor.unmaskTags("[[TAG_1]]World[[TAG_2]]", tagMap)
        check(upper == "<i>World</i>") { "Failed uppercase unmask: $upper" }

        // Mixed case with single spaces
        val mixedSpaced = SubtitleReconstructor.unmaskTags("[[ Tag_1 ]]Mixed[[ tAg_2 ]]", tagMap)
        check(mixedSpaced == "<i>Mixed</i>") { "Failed mixed spaced unmask: $mixedSpaced" }

        // Multi-spaced multi-digit
        val multiSpaced = SubtitleReconstructor.unmaskTags("Prefix [[  TAG_12  ]]Gold[[   tag_13   ]] Postfix", tagMap)
        check(multiSpaced == "Prefix <font color=\"#FFCC00\">Gold</font> Postfix") { "Failed multi-spaced unmask: $multiSpaced" }

        // Tab characters
        val tabSpaced = SubtitleReconstructor.unmaskTags("[[\ttag_1\t]]Tab[[\tTAG_2\t]]", tagMap)
        check(tabSpaced == "<i>Tab</i>") { "Failed tab-spaced unmask: $tabSpaced" }

        // Adjacent tokens
        val adjacent = SubtitleReconstructor.unmaskTags("[[tag_1]][[tag_12]]Dual[[tag_13]][[tag_2]]", tagMap)
        check(adjacent == "<i><font color=\"#FFCC00\">Dual</font></i>") { "Failed adjacent tokens unmask: $adjacent" }

        // Repeated tokens
        val repeated = SubtitleReconstructor.unmaskTags("[[tag_1]]A[[TAG_2]] and [[TAG_1]]B[[tag_2]] and [[ Tag_1 ]]C[[ tag_2 ]]", tagMap)
        check(repeated == "<i>A</i> and <i>B</i> and <i>C</i>") { "Failed repeated tokens unmask: $repeated" }

        // Leading-zero tokens normalization
        val leadingZero = SubtitleReconstructor.unmaskTags("[[tag_01]]Leading zero[[TAG_002]] and [[tag_00012]]padded font[[TAG_013]]", tagMap)
        check(leadingZero == "<i>Leading zero</i> and <font color=\"#FFCC00\">padded font</font>") {
            "Failed leading zero tokens unmask: $leadingZero"
        }

        println("[PASS]")
    }

    private fun testNonExistentTokensPreservation() {
        print("  [STRESS TEST 2] Non-existent / hallucinated tokens preservation... ")
        val tagMap = mapOf(
            "[[TAG_1]]" to "<b>",
            "[[TAG_2]]" to "</b>"
        )

        // Non-existent uppercase token [[TAG_999]]
        val nonExistentUpper = SubtitleReconstructor.unmaskTags("[[TAG_1]]Hello [[TAG_999]][[TAG_2]]", tagMap)
        check(nonExistentUpper == "<b>Hello [[TAG_999]]</b>") { "Non-existent upper token failed: $nonExistentUpper" }

        // Non-existent lowercase token [[tag_888]]
        val nonExistentLower = SubtitleReconstructor.unmaskTags("[[TAG_1]]World [[tag_888]][[TAG_2]]", tagMap)
        check(nonExistentLower == "<b>World [[tag_888]]</b>") { "Non-existent lower token failed: $nonExistentLower" }

        // Spaced non-existent token [[   TAG_777   ]]
        val nonExistentSpaced = SubtitleReconstructor.unmaskTags("Text [[   tag_777   ]] end", tagMap)
        check(nonExistentSpaced == "Text [[   tag_777   ]] end") { "Non-existent spaced token failed: $nonExistentSpaced" }

        println("[PASS]")
    }

    private fun testSevereUnclosedTagNesting() {
        print("  [STRESS TEST 3] Severe unclosed tag nesting (all 5 HTML tag types)... ")
        val severelyNested = "<i><b><u><s><font color=\"red\">Deeply nested unclosed"
        val balanced = SubtitleReconstructor.balanceHtmlTags(severelyNested)

        check(balanced.contains("</i>")) { "Missing </i>: $balanced" }
        check(balanced.contains("</b>")) { "Missing </b>: $balanced" }
        check(balanced.contains("</u>")) { "Missing </u>: $balanced" }
        check(balanced.contains("</s>")) { "Missing </s>: $balanced" }
        check(balanced.contains("</font>")) { "Missing </font>: $balanced" }

        // Confirm counts match exactly 1 open, 1 close
        check(Regex("<i>").findAll(balanced).count() == Regex("</i>").findAll(balanced).count())
        check(Regex("<b>").findAll(balanced).count() == Regex("</b>").findAll(balanced).count())
        check(Regex("<u>").findAll(balanced).count() == Regex("</u>").findAll(balanced).count())
        check(Regex("<s>").findAll(balanced).count() == Regex("</s>").findAll(balanced).count())
        check(Regex("<font[^>]*>").findAll(balanced).count() == Regex("</font>").findAll(balanced).count())

        // Tags with attributes balancing
        val withAttrs = "<i class=\"custom\"><b style=\"color:red\">Styled unclosed"
        val balAttrs = SubtitleReconstructor.balanceHtmlTags(withAttrs)
        check(balAttrs.contains("</i>") && balAttrs.contains("</b>")) {
            "Failed attributes tag balancing: $balAttrs"
        }
        check(Regex("<i(?:\\s+[^>]*?)?>").findAll(balAttrs).count() == Regex("</i>").findAll(balAttrs).count())
        check(Regex("<b(?:\\s+[^>]*?)?>").findAll(balAttrs).count() == Regex("</b>").findAll(balAttrs).count())

        // Non-target tags invariance
        val nonTarget = "<input type=\"text\"><button>Click</button><span>Span</span>"
        val balNonTarget = SubtitleReconstructor.balanceHtmlTags(nonTarget)
        check(balNonTarget == nonTarget) { "Non-target tags modified: $balNonTarget" }

        println("[PASS]")
    }

    private fun testAsymmetricTagCounts() {
        print("  [STRESS TEST 4] Asymmetric tag counts & multiple unclosed instances... ")
        // 3 <i> open, 1 </i> closed
        val threeItalic = "<i>First <i>Second <i>Third</i>"
        val balItalic = SubtitleReconstructor.balanceHtmlTags(threeItalic)
        check(balItalic == "<i>First <i>Second <i>Third</i></i></i>") { "Asymmetric italic failed: $balItalic" }

        // 2 <font> open, 0 </font> closed
        val twoFont = "<font color=\"blue\"><font color=\"green\">Double font"
        val balFont = SubtitleReconstructor.balanceHtmlTags(twoFont)
        check(balFont == "<font color=\"blue\"><font color=\"green\">Double font</font></font>") { "Asymmetric font failed: $balFont" }

        // Orphan close tags should not cause negative repeat or errors
        val orphan = "Dialogue</i></b></font>"
        val balOrphan = SubtitleReconstructor.balanceHtmlTags(orphan)
        check(balOrphan == orphan) { "Orphan close tags modified: $balOrphan" }

        println("[PASS]")
    }

    private fun testMixedCaseUnclosedTags() {
        print("  [STRESS TEST 5] Mixed-case unclosed tags balancing... ")
        val mixedCase = "<I><B><U><S><FONT COLOR=\"#AABBCC\">Uppercase unclosed"
        val balanced = SubtitleReconstructor.balanceHtmlTags(mixedCase)

        check(balanced.endsWith("</i></b></u></s></font>")) { "Mixed case failed to close: $balanced" }
        println("[PASS]")
    }

    private fun testPreBalancedTagsInvariance() {
        print("  [STRESS TEST 6] Pre-balanced tags invariance (strict idempotence)... ")
        val inputs = listOf(
            "<i>Italic</i> <b>Bold</b> <font color=\"blue\">Color</font>",
            "<i><b><u><s><font color=\"cyan\">All five pre-balanced</font></s></u></b></i>",
            "Plain text with no tags at all 12345.",
            "",
            "Already balanced: <font size=\"3\" color=\"red\">Red text</font> and <b>bold text</b>."
        )

        for (input in inputs) {
            val result = SubtitleReconstructor.balanceHtmlTags(input)
            check(result == input) { "Pre-balanced string was modified! Input: '$input' vs Result: '$result'" }
        }

        println("[PASS]")
    }

    private fun testSubRipGenerationAndFallback() {
        print("  [STRESS TEST 7] SubRip output formatting & dialogue fallback... ")
        val cues = listOf(
            SubtitleCue(
                index = 1,
                startMs = 1000L,
                endMs = 2500L,
                rawText = "Original cue 1",
                maskedText = "Original cue 1"
            ),
            SubtitleCue(
                index = 2,
                startMs = 3000L,
                endMs = 4500L,
                rawText = "Original cue 2 with [[TAG_1]]tag[[TAG_2]]",
                maskedText = "Original cue 2 with [[TAG_1]]tag[[TAG_2]]",
                tagMap = mapOf("[[TAG_1]]" to "<b>", "[[TAG_2]]" to "</b>")
            ),
            SubtitleCue(
                index = 3,
                startMs = 5000L,
                endMs = 6000L,
                rawText = "Agent 47 Fallback",
                maskedText = "Agent 47 Fallback"
            ),
            SubtitleCue(
                index = 4,
                startMs = 7000L,
                endMs = 8000L,
                rawText = "Room 101 Empty Fallback",
                maskedText = "Room 101 Empty Fallback"
            ),
            SubtitleCue(
                index = 5,
                startMs = 9000L,
                endMs = 10000L,
                rawText = "Whitespace Fallback",
                maskedText = "Whitespace Fallback"
            ),
            SubtitleCue(
                index = 6,
                startMs = 11000L,
                endMs = 12000L,
                rawText = "Unclosed Tag Translation",
                maskedText = "Unclosed Tag Translation",
                tagMap = mapOf("[[TAG_1]]" to "<font color=\"red\">")
            )
        )

        val translations = mapOf(
            1 to "Translated cue 1",
            2 to "Dịch cue 2 với [[tag_1]]in đậm[[tag_2]]",
            // Cue 3 is missing from map -> fallback
            4 to "", // Empty string -> fallback
            5 to "   \n\t  ", // Whitespace only -> fallback
            6 to "[[tag_1]]Cảnh báo" // Unclosed font tag -> should unmask and balance
        )

        val srtOutput = SubtitleReconstructor.buildSubRip(cues, translations)

        // 1. Validate Cue 1
        check(srtOutput.contains("1\n00:00:01,000 --> 00:00:02,500\nTranslated cue 1\n\n")) {
            "Cue 1 incorrect in srt:\n$srtOutput"
        }

        // 2. Validate Cue 2 (case-insensitive unmasking)
        check(srtOutput.contains("2\n00:00:03,000 --> 00:00:04,500\nDịch cue 2 với <b>in đậm</b>\n\n")) {
            "Cue 2 incorrect in srt:\n$srtOutput"
        }

        // 3. Validate Cue 3 (missing key fallback)
        check(srtOutput.contains("3\n00:00:05,000 --> 00:00:06,000\nAgent 47 Fallback\n\n")) {
            "Cue 3 fallback failed in srt:\n$srtOutput"
        }

        // 4. Validate Cue 4 (empty string fallback)
        check(srtOutput.contains("4\n00:00:07,000 --> 00:00:08,000\nRoom 101 Empty Fallback\n\n")) {
            "Cue 4 empty fallback failed in srt:\n$srtOutput"
        }

        // 5. Validate Cue 5 (whitespace fallback)
        check(srtOutput.contains("5\n00:00:09,000 --> 00:00:10,000\nWhitespace Fallback\n\n")) {
            "Cue 5 whitespace fallback failed in srt:\n$srtOutput"
        }

        // 6. Validate Cue 6 (unclosed tag balanced in buildSubRip)
        check(srtOutput.contains("6\n00:00:11,000 --> 00:00:12,000\n<font color=\"red\">Cảnh báo</font>\n")) {
            "Cue 6 unclosed tag balancing failed in srt:\n$srtOutput"
        }

        // Empty cues list check
        check(SubtitleReconstructor.buildSubRip(emptyList(), emptyMap()) == "") {
            "Empty cues list must yield empty string"
        }

        println("[PASS]")
    }

    private fun testTimestampFormattingBoundaries() {
        print("  [STRESS TEST 8] Timestamp formatting boundaries & edge cases... ")
        // Clamping negative to 0
        check(SubtitleReconstructor.formatSrtTimestamp(-1000L) == "00:00:00,000") {
            "Negative ms clamp failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(0L) == "00:00:00,000") {
            "Zero ms failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(5L) == "00:00:00,005") {
            "5 ms padding failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(45L) == "00:00:00,045") {
            "45 ms padding failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(999L) == "00:00:00,999") {
            "999 ms failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(1000L) == "00:00:01,000") {
            "1s failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(60000L) == "00:01:00,000") {
            "1m failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(3600000L) == "01:00:00,000") {
            "1h failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(86400000L) == "24:00:00,000") {
            "24h failed"
        }
        check(SubtitleReconstructor.formatSrtTimestamp(360000000L) == "100:00:00,000") {
            "100h failed"
        }

        println("[PASS]")
    }

    private fun testEndToEndParseAndReconstructRoundTrip() {
        print("  [STRESS TEST 9] Full end-to-end parse and reconstruct round-trip... ")
        val rawSrt = """
            1
            00:00:01,000 --> 00:00:03,000
            {\an8}<i>Agent</i>
            47

            2
            00:00:04,500 --> 00:00:06,000
            Meet at <font color="yellow">Room 101

            3
            00:00:07,000 --> 00:00:09,000
            47
        """.trimIndent()

        // 1. Parse raw content
        val cues = SubtitleParser.parse(rawSrt)
        check(cues.size == 3) { "Expected 3 cues, got ${cues.size}" }
        check(cues[0].rawText == "{\\an8}<i>Agent</i>\n47") { "Cue 0 text mismatch: ${cues[0].rawText}" }
        check(cues[1].rawText == "Meet at <font color=\"yellow\">Room 101") { "Cue 1 text mismatch: ${cues[1].rawText}" }
        check(cues[2].rawText == "47") { "Cue 2 text mismatch: ${cues[2].rawText}" }

        // 2. Simulate translations with token variations and fallback
        val translations = mapOf(
            1 to "[[tag_1]][[tag_2]]Điệp viên[[tag_3]]\n47", // Lowercase unmasking
            2 to "Gặp tại [[  TAG_1  ]]Phòng 101", // Spaced unmasking with unclosed font tag
            3 to "" // Empty -> fallback to "47"
        )

        // 3. Build SubRip
        val srtOutput = SubtitleReconstructor.buildSubRip(cues, translations)

        // 4. Re-parse the generated SubRip with SubtitleParser
        val roundTripCues = SubtitleParser.parse(srtOutput)
        check(roundTripCues.size == 3) { "Round trip cues count failed: ${roundTripCues.size}" }
        check(roundTripCues[0].startMs == 1000L && roundTripCues[0].endMs == 3000L) { "Cue 0 timing corrupted" }
        check(roundTripCues[1].startMs == 4500L && roundTripCues[1].endMs == 6000L) { "Cue 1 timing corrupted" }
        check(roundTripCues[2].startMs == 7000L && roundTripCues[2].endMs == 9000L) { "Cue 2 timing corrupted" }

        // Confirm Cue 1 has restored tags
        check(roundTripCues[0].rawText == "{\\an8}<i>Điệp viên</i>\n47") {
            "Round trip cue 0 text mismatch: ${roundTripCues[0].rawText}"
        }

        // Confirm Cue 2 has closed font tag
        check(roundTripCues[1].rawText == "Gặp tại <font color=\"yellow\">Phòng 101</font>") {
            "Round trip cue 1 font tag not closed: ${roundTripCues[1].rawText}"
        }

        // Confirm Cue 3 preserved fallback "47"
        check(roundTripCues[2].rawText == "47") {
            "Round trip cue 2 fallback failed: ${roundTripCues[2].rawText}"
        }

        println("[PASS]")
    }
}

fun main() {
    Phase1ReconstructorAdversarialTest.runAllTests()
}
