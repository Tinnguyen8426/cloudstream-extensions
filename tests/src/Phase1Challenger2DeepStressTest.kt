package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleReconstructor

fun main() {
    println("=== Running Empirical Deep Adversarial Stress Suite for SubtitleReconstructor ===")

    // TEST 1: Leading Zero Tokens of various lengths and spacing
    println("[TEST 1] Leading-zero token unmasking...")
    val tagMap = mapOf(
        "[[TAG_1]]" to "<i>",
        "[[TAG_2]]" to "</i>",
        "[[TAG_7]]" to "<b>",
        "[[TAG_8]]" to "</b>",
        "[[TAG_42]]" to "<u>",
        "[[TAG_43]]" to "</u>",
        "[[TAG_999]]" to "<font color=\"#123456\">",
        "[[TAG_1000]]" to "</font>"
    )

    val inputTokens = listOf(
        "[[TAG_01]]" to "<i>",
        "[[TAG_001]]" to "<i>",
        "[[tag_00000001]]" to "<i>",
        "[[   TAG_07   ]]" to "<b>",
        "[[ tag_042 ]]" to "<u>",
        "[[TAG_000999]]" to "<font color=\"#123456\">",
        "[[tag_001000]]" to "</font>"
    )

    for ((token, expectedTag) in inputTokens) {
        val result = SubtitleReconstructor.unmaskTags(token, tagMap)
        val expected = SubtitleReconstructor.balanceHtmlTags(expectedTag)
        check(result == expected) {
            "Mismatch for token '$token': got '$result', expected '$expected'"
        }
    }
    println("  -> Leading zero tokens passed.")

    // TEST 2: Leading-zero token inside complete sentence
    println("[TEST 2] Sentence with multiple leading-zero tokens and spacing...")
    val sentence = "[[tag_01]][[  TAG_007  ]]Điệp viên 007[[TAG_08]][[ tag_02 ]]"
    val resSentence = SubtitleReconstructor.unmaskTags(sentence, tagMap)
    val expectedSentence = "<i><b>Điệp viên 007</b></i>"
    check(resSentence == expectedSentence) {
        "Sentence unmask failed: got '$resSentence', expected '$expectedSentence'"
    }
    println("  -> Sentence unmasking passed.")

    // TEST 3: Styling tags with various attributes
    println("[TEST 3] HTML tag balancing with attributes...")
    val attrCases = listOf(
        "<i class=\"italic-text\">Text" to "<i class=\"italic-text\">Text</i>",
        "<b style=\"color: red; font-size: 14px;\">Bold" to "<b style=\"color: red; font-size: 14px;\">Bold</b>",
        "<u data-id=\"underline_123\">Underline" to "<u data-id=\"underline_123\">Underline</u>",
        "<s class='strike' id='s1'>Strike" to "<s class='strike' id='s1'>Strike</s>",
        "<font face=\"Helvetica\" color=\"#aabbcc\" size=\"4\">Font text" to "<font face=\"Helvetica\" color=\"#aabbcc\" size=\"4\">Font text</font>",
        // Multiline attributes
        "<i\n  class=\"foo\"\n  id=\"bar\">Multiline" to "<i\n  class=\"foo\"\n  id=\"bar\">Multiline</i>"
    )

    for ((inp, exp) in attrCases) {
        val bal = SubtitleReconstructor.balanceHtmlTags(inp)
        check(bal == exp) {
            "Attribute balancing failed for '$inp': got '$bal', expected '$exp'"
        }
    }
    println("  -> Tag balancing with attributes passed.")

    // TEST 4: Non-target HTML tags must NOT be touched or falsely closed
    println("[TEST 4] Non-target tags collision test (input, button, ul, span, small)...")
    val nonTargetCases = listOf(
        "<input type=\"text\" value=\"test\">",
        "<button class=\"btn\">Click</button>",
        "<ul><li>Item</li></ul>",
        "<span style=\"color:blue\">Span text</span>",
        "<small>Small text</small>",
        "<h1>Heading</h1>",
        "<p>Paragraph</p>"
    )

    for (caseText in nonTargetCases) {
        val bal = SubtitleReconstructor.balanceHtmlTags(caseText)
        check(bal == caseText) {
            "Non-target tag modified! In: '$caseText', Out: '$bal'"
        }
    }
    println("  -> Non-target tags left intact.")

    // TEST 5: Pre-balanced tags with attributes must remain unchanged (Idempotence)
    println("[TEST 5] Idempotence on pre-balanced tags with attributes...")
    val balancedAttrCases = listOf(
        "<i class=\"test\">Already balanced</i>",
        "<b style=\"color:red\">Balanced bold</b> and <u class=\"u\">Underline</u>",
        "<font color=\"#fff\" face=\"sans\">Prebalanced font</font>",
        "<i id=\"1\"><b id=\"2\"><u id=\"3\"><s id=\"4\"><font color=\"red\">Deep</font></s></u></b></i>"
    )

    for (caseText in balancedAttrCases) {
        val bal = SubtitleReconstructor.balanceHtmlTags(caseText)
        check(bal == caseText) {
            "Idempotence failed on '$caseText': got '$bal'"
        }
    }
    println("  -> Idempotence on tags with attributes passed.")

    // TEST 6: SubRip Builder fallback and format
    println("[TEST 6] SubRip builder comprehensive fallback testing...")
    val cues = listOf(
        SubtitleCue(index = 1, startMs = 1000L, endMs = 2000L, rawText = "Raw 1", maskedText = "Raw 1"),
        SubtitleCue(index = 2, startMs = 3000L, endMs = 4000L, rawText = "Raw 2", maskedText = "Raw 2"),
        SubtitleCue(index = 3, startMs = 5000L, endMs = 6000L, rawText = "Agent 47", maskedText = "Agent 47"),
        SubtitleCue(index = 4, startMs = 7000L, endMs = 8000L, rawText = "Room 101", maskedText = "Room 101"),
        SubtitleCue(index = 5, startMs = 9000L, endMs = 10000L, rawText = "Line 5", maskedText = "Line 5", tagMap = mapOf("[[TAG_1]]" to "<i class=\"custom\">"))
    )

    val translations = mapOf(
        1 to "Translated 1",
        // 2 missing
        3 to "   ", // whitespace only
        4 to "",    // empty string
        5 to "[[tag_01]]Dịch dòng 5" // leading zero + unclosed tag with attribute
    )

    val subRip = SubtitleReconstructor.buildSubRip(cues, translations)
    check(subRip.contains("1\n00:00:01,000 --> 00:00:02,000\nTranslated 1\n\n")) { "Cue 1 failed" }
    check(subRip.contains("2\n00:00:03,000 --> 00:00:04,000\nRaw 2\n\n")) { "Cue 2 fallback failed" }
    check(subRip.contains("3\n00:00:05,000 --> 00:00:06,000\nAgent 47\n\n")) { "Cue 3 fallback failed" }
    check(subRip.contains("4\n00:00:07,000 --> 00:00:08,000\nRoom 101\n\n")) { "Cue 4 fallback failed" }
    check(subRip.contains("5\n00:00:09,000 --> 00:00:10,000\n<i class=\"custom\">Dịch dòng 5</i>\n")) { "Cue 5 unmask+balance failed:\n$subRip" }
    println("  -> SubRip builder passed.")

    println("=== ALL EMPIRICAL DEEP ADVERSARIAL STRESS TESTS PASSED SUCCESSFULLY! ===")
}
