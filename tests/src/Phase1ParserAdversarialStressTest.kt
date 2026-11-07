package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleReconstructor

fun main() {
    println("================================================================================")
    println(">>> Starting Challenger 1 Kotlin JVM Adversarial Stress Test Suite...")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun check(testName: String, condition: Boolean, details: String) {
        if (condition) {
            println("  [PASS] $testName")
            passed++
        } else {
            println("  [FAIL] $testName -> $details")
            failed++
        }
    }

    // 1. Extreme timecodes
    val srtTimecodes = """
        1
        00:00:00,000 --> 00:00:01,000
        Start
        
        2
        99:59:59,999 --> 99:59:59,999
        End of century
        
        3
        00:05.100 --> 00:06.20
        Missing hours
        
        4
        00:00:01.1234 --> 00:00:02.5
        Variable ms
    """.trimIndent()
    val timecodeCues = SubtitleParser.parse(srtTimecodes)
    check(
        "Extreme timecodes parsed",
        timecodeCues.size == 4 && timecodeCues[0].startMs == 0L && timecodeCues[1].startMs == ((99L * 3600L + 59L * 60L + 59L) * 1000L + 999L),
        "Expected 4 cues with exact ms, got ${timecodeCues.size}"
    )

    // 2. Tag shielding and unmasking
    val dialogue = """{\an8\pos(100,200)}<v Roger>Hello <font color="#ff0000">World</font> <00:19.500>♪"""
    val singleDoc = "1\n00:00:01,000 --> 00:00:04,000\n$dialogue\n"
    val singleCues = SubtitleParser.parse(singleDoc)
    val masked = singleCues.firstOrNull()?.maskedText ?: ""
    val tagMap = singleCues.firstOrNull()?.tagMap ?: emptyMap()
    val unmasked = SubtitleReconstructor.unmaskTags(
        "[[tag_1]][[tag_2]]Hello [[  TAG_3   ]]World[[tag_4]] [[TAG_5]]♪",
        tagMap
    )
    check(
        "Tag shielding and unmasking",
        unmasked.contains("""{\an8\pos(100,200)}""") && unmasked.contains("<font color=\"#ff0000\">World</font>"),
        "Unmasked text mismatch: $unmasked"
    )

    // 3. WebVTT without cue IDs with single-word dialogue lines (Adversarial Defect)
    val vttSingleWords = """
        WEBVTT
        
        00:00:01.000 --> 00:00:02.000
        Yes
        
        00:00:03.000 --> 00:00:04.000
        No
        
        00:00:05.000 --> 00:00:06.000
        Wait
        
        00:00:07.000 --> 00:00:08.000
        Done
    """.trimIndent()
    val vttCues = SubtitleParser.parse(vttSingleWords)
    check(
        "WebVTT single-word dialogue cues preservation",
        vttCues.size == 4,
        "Expected 4 cues, but Kotlin SubtitleParser returned ${vttCues.size} cues! Deleted cues: ${4 - vttCues.size}"
    )

    // 4. WebVTT dialogue starting with NOTE
    val vttNote = """
        WEBVTT
        
        00:00:01.000 --> 00:00:02.000
        NOTE: Please be quiet
        
        00:00:03.000 --> 00:00:04.000
        Thank you
    """.trimIndent()
    val noteCues = SubtitleParser.parse(vttNote)
    check(
        "WebVTT dialogue starting with NOTE preservation",
        noteCues.size == 2,
        "Expected 2 cues, but Kotlin SubtitleParser returned ${noteCues.size} cues!"
    )

    // 5. Unnumbered SRT with numeric dialogue "47"
    val unnumberedSrt = """
        00:00:01,000 --> 00:00:02,000
        47
        
        00:00:03,000 --> 00:00:04,000
        Next cue
    """.trimIndent()
    val unnumberedCues = SubtitleParser.parse(unnumberedSrt)
    check(
        "Unnumbered SRT single numeric dialogue '47' preservation",
        unnumberedCues.size == 2,
        "Expected 2 cues, but Kotlin SubtitleParser returned ${unnumberedCues.size} cues!"
    )

    println("================================================================================")
    println(">>> CHALLENGER 1 SUITE SUMMARY: $passed PASSED, $failed FAILED")
    println("================================================================================")
}
