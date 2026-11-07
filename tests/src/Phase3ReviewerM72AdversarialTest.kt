package tests.adversarial

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitlePriorityEvaluator
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitleReconciler

fun main() {
    println("================================================================================")
    println(">>> Starting Reviewer M7-2 Adversarial Stress Suite (Parser, Reconciler, Priority)...")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun assertTest(name: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            println("  [FAIL] $name -> $details")
            failed++
        }
    }

    // =========================================================================
    // 1. ADVERSARIAL STRESS: AITranslationParser
    // =========================================================================
    println("\n--- [Adversarial Parser Tests] ---")

    // Edge 1.1: Complex markdown fence with leading/trailing chatter and backticks
    val chatterFence = """
        Here is the JSON translation you requested:
        ```json
        [
          {"id": 1, "text": "Line 1 with `code` backtick"},
          {"id": 2, "text": "Line 2 with \"quotes\" and \n newline"}
        ]
        ```
        Let me know if you need anything else!
    """.trimIndent()
    val p1 = AITranslationParser.parse(chatterFence)
    assertTest("Parser handles conversational chatter with backticks in text",
        p1.size == 2 && p1[1]?.contains("`code`") == true && p1[2]?.contains("\"quotes\"") == true
    )

    // Edge 1.2: Arbitrary wrapper keys ("results", "subtitles", "cues", "custom_key")
    val wrapperResults = """{"results": [{"id": 5, "text": "Result 5"}]}"""
    val p2a = AITranslationParser.parse(wrapperResults)
    assertTest("Parser handles 'results' wrapper key", p2a == mapOf(5 to "Result 5"))

    val wrapperCustomKey = """{"arbitrary_payload": [{"id": 99, "text": "Custom Key 99"}]}"""
    val p2b = AITranslationParser.parse(wrapperCustomKey)
    assertTest("Parser handles arbitrary wrapper key via child array fallback", p2b == mapOf(99 to "Custom Key 99"))

    // Edge 1.3: String IDs ("id": "12")
    val stringIds = """[{"id": "12", "text": "String ID 12"}, {"id": "13", "text": "String ID 13"}]"""
    val p3 = AITranslationParser.parse(stringIds)
    assertTest("Parser coerces string IDs to integers", p3 == mapOf(12 to "String ID 12", 13 to "String ID 13"))

    // Edge 1.4: Anthropic continuation with leading whitespace and newlines
    val anthropicWhitespace = "\n   \t {\"id\": 10, \"text\": \"Continuation 10\"}, {\"id\": 11, \"text\": \"Continuation 11\"}]"
    val p4 = AITranslationParser.parse(anthropicWhitespace)
    assertTest("Parser recovers Anthropic continuation with leading whitespace",
        p4 == mapOf(10 to "Continuation 10", 11 to "Continuation 11")
    )

    // Edge 1.5: Regex array recovery when JSON is surrounded by conversational text without fences
    val noFenceConversational = "Here is the data: [{\"id\": 7, \"text\": \"Recovered 7\"}] that's all."
    val p5 = AITranslationParser.parse(noFenceConversational)
    assertTest("Parser recovers embedded array from conversational text without fences",
        p5 == mapOf(7 to "Recovered 7")
    )

    // Edge 1.6: Delimiter fallback with multiline text and unicode
    val delimiterComplex = "<<<101>>>\nTiếng Việt có dấu 🌟\nDòng thứ hai\n<<<102>>>\nSecond cue"
    val p6 = AITranslationParser.parse(delimiterComplex)
    assertTest("Parser extracts multiline unicode delimiter cues",
        p6[101] == "Tiếng Việt có dấu 🌟\nDòng thứ hai" && p6[102] == "Second cue"
    )

    // Edge 1.7: Empty string and non-JSON garbage returns empty map without throwing exception
    val emptyResult = AITranslationParser.parse("")
    val garbageResult = AITranslationParser.parse("Sorry, I cannot translate this.")
    assertTest("Parser returns empty map on empty or conversational-only input without crashing",
        emptyResult.isEmpty() && garbageResult.isEmpty()
    )

    // Edge 1.8: Array containing mixed non-object elements
    val mixedArray = """[123, "random", {"id": 1, "text": "Valid"}, null]"""
    val p8 = AITranslationParser.parse(mixedArray)
    assertTest("Parser extracts valid objects from array with mixed junk elements",
        p8 == mapOf(1 to "Valid")
    )

    // =========================================================================
    // 2. ADVERSARIAL STRESS: SubtitleReconciler
    // =========================================================================
    println("\n--- [Adversarial Reconciler Tests] ---")

    fun makeCue(index: Int, text: String) = SubtitleCue(
        index = index,
        startMs = index * 1000L,
        endMs = index * 1000L + 900L,
        rawText = text,
        maskedText = text
    )

    // Edge 2.1: Chunk 3 (cues 61..90), LLM resets to 1..30
    val cuesChunk3 = (61..90).map { makeCue(it, "English $it") }
    val llmRelChunk3 = (1..30).associateWith { "Vietnamese ${it + 60}" }
    val healedChunk3 = SubtitleReconciler.autoHealingReconcile(cuesChunk3, llmRelChunk3)
    val passChunk3Count = (61..90).count { healedChunk3[it] == "Vietnamese $it" }
    assertTest("Reconciler recovers 100% (30/30) cues for Chunk 3 (61..90)",
        passChunk3Count == 30 && healedChunk3.size == 30
    )

    // Edge 2.2: Single cue chunk at index 50, LLM resets to 1
    val singleCueChunk = listOf(makeCue(50, "Only cue 50"))
    val singleLlm = mapOf(1 to "Translated cue 50")
    val singleHealed = SubtitleReconciler.autoHealingReconcile(singleCueChunk, singleLlm)
    assertTest("Reconciler auto-heals single-cue chunk from relative 1 to 50",
        singleHealed[50] == "Translated cue 50" && singleHealed.size == 1
    )

    // Edge 2.3: Non-contiguous cue indices (gaps in subtitle timestamps)
    val gapCues = listOf(makeCue(10, "Cue 10"), makeCue(20, "Cue 20"), makeCue(30, "Cue 30"))
    val gapLlmRelative = mapOf(1 to "Trans 10", 2 to "Trans 20", 3 to "Trans 30")
    val gapHealed = SubtitleReconciler.autoHealingReconcile(gapCues, gapLlmRelative)
    assertTest("Reconciler handles non-contiguous cue indices under relative 1..N re-indexing",
        gapHealed[10] == "Trans 10" && gapHealed[20] == "Trans 20" && gapHealed[30] == "Trans 30"
    )

    // Edge 2.4: Blank or whitespace translation falls back to source text
    val cuesWithBlank = listOf(makeCue(1, "Original 1"), makeCue(2, "Original 2"))
    val transWithBlank = mapOf(1 to "   ", 2 to "Valid 2")
    val healedBlank = SubtitleReconciler.autoHealingReconcile(cuesWithBlank, transWithBlank)
    assertTest("Blank or whitespace translation falls back to cue.rawText",
        healedBlank[1] == "Original 1" && healedBlank[2] == "Valid 2"
    )

    // Edge 2.5: Empty input cues returns empty map
    val emptyHealed = SubtitleReconciler.autoHealingReconcile(emptyList(), mapOf(1 to "orphan"))
    assertTest("Empty input cues returns empty map safely", emptyHealed.isEmpty())

    // Edge 2.6: Massive hallucination of out-of-range keys
    val cuesNormal = (1..5).map { makeCue(it, "Src $it") }
    val hallucinatedKeys = mapOf(
        -5 to "Neg", 0 to "Zero", 1 to "T1", 2 to "T2", 3 to "T3", 4 to "T4", 5 to "T5",
        6 to "Extra", 999 to "Hallucinated"
    )
    val healedHallucination = SubtitleReconciler.autoHealingReconcile(cuesNormal, hallucinatedKeys)
    assertTest("Hallucinated keys are excluded, strictly preserving input size (5)",
        healedHallucination.size == 5 &&
        healedHallucination.keys == setOf(1, 2, 3, 4, 5) &&
        !healedHallucination.containsKey(0) && !healedHallucination.containsKey(999)
    )

    // =========================================================================
    // 3. ADVERSARIAL STRESS: SubtitlePriorityEvaluator
    // =========================================================================
    println("\n--- [Adversarial Priority Evaluator Tests] ---")

    val subVi1 = SubtitleFile("Tiếng Việt (Anime47)", "https://example.com/vi1.srt")
    val subVi2 = SubtitleFile("vi-VN", "https://example.com/vi2.srt")
    val subVi3 = SubtitleFile("vi_VN", "https://example.com/vi3.srt")
    val subViCorrupted = SubtitleFile("Ti?ng Vi?t", "https://example.com/vi_corrupt.srt")
    val subEn = SubtitleFile("English [CC]", "https://example.com/en.srt")
    val subJa = SubtitleFile("Japanese (Original)", "https://example.com/ja.ass")
    val subFr = SubtitleFile("French", "https://example.com/fr.srt")
    val subSv = SubtitleFile("Swedish", "https://example.com/sv.srt")
    val subSl = SubtitleFile("Slovenian", "https://example.com/sl.srt")

    // Edge 3.1: Target language bypass across all Vietnamese dialect / encoding tags
    assertTest("Target Bypass matches 'Tiếng Việt (Anime47)' for 'vi'",
        SubtitlePriorityEvaluator.calculateScore(subVi1, "vi") == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
    )
    assertTest("Target Bypass matches 'vi-VN' for 'vi'",
        SubtitlePriorityEvaluator.calculateScore(subVi2, "vi") == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
    )
    assertTest("Target Bypass matches 'vi_VN' for 'vi'",
        SubtitlePriorityEvaluator.calculateScore(subVi3, "vi") == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
    )
    assertTest("Target Bypass matches ASCII corrupted 'Ti?ng Vi?t' for 'vi'",
        SubtitlePriorityEvaluator.calculateScore(subViCorrupted, "vi") == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
    )

    // Edge 3.2: False positive substring collision checks
    assertTest("French does not collide with English target",
        SubtitlePriorityEvaluator.calculateScore(subFr, "en") != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG &&
        !SubtitlePriorityEvaluator.isMatchingLanguage("french", "en")
    )
    assertTest("Swedish does not collide with English target",
        SubtitlePriorityEvaluator.calculateScore(subSv, "en") != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG &&
        !SubtitlePriorityEvaluator.isMatchingLanguage("swedish", "en")
    )
    assertTest("Slovenian does not collide with English target",
        SubtitlePriorityEvaluator.calculateScore(subSl, "en") != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG &&
        !SubtitlePriorityEvaluator.isMatchingLanguage("slovenian", "en")
    )

    // Edge 3.3: Case insensitivity and whitespace trimming on targetLang
    assertTest("Target Language matching is case-insensitive and trims whitespace",
        SubtitlePriorityEvaluator.calculateScore(subVi1, "  VI  ") == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG &&
        SubtitlePriorityEvaluator.calculateScore(subEn, "  EN  ") == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
    )

    // Edge 3.4: Custom Mode edge cases
    val customList = listOf("ko", "zh", "ja")
    val scoreKo = SubtitlePriorityEvaluator.calculateScore(SubtitleFile("Korean", "url"), "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val scoreZh = SubtitlePriorityEvaluator.calculateScore(SubtitleFile("Chinese", "url"), "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val scoreJa = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val scoreUnlisted = SubtitlePriorityEvaluator.calculateScore(subFr, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)

    assertTest("Custom mode ranks items in order (ko=1000, zh=900, ja=800, unlisted=50)",
        scoreKo == 1000 && scoreZh == 900 && scoreJa == 800 && scoreUnlisted == 50
    )

    // Edge 3.5: selectBestSubtitle with multiple candidates including bypass
    val candidatePool = listOf(subFr, subEn, subVi1, subJa)
    val selectedBypass = SubtitlePriorityEvaluator.selectBestSubtitle(candidatePool, "vi")
    assertTest("selectBestSubtitle selects bypass track over higher priority anime tracks",
        selectedBypass == subVi1
    )

    // Edge 3.6: selectBestSubtitle with no bypass track
    val candidatePoolNoTarget = listOf(subFr, subEn, subJa)
    val selectedAnime = SubtitlePriorityEvaluator.selectBestSubtitle(candidatePoolNoTarget, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    assertTest("selectBestSubtitle selects highest anime priority track (Japanese) when no target track present",
        selectedAnime == subJa
    )

    // Edge 3.7: selectBestSubtitle on empty candidate list returns null
    val selectedEmpty = SubtitlePriorityEvaluator.selectBestSubtitle(emptyList(), "vi")
    assertTest("selectBestSubtitle on empty list returns null safely", selectedEmpty == null)

    println("\n================================================================================")
    println(">>> Reviewer M7-2 Adversarial Results: $passed PASSED, $failed FAILED (Total ${passed + failed})")
    println("================================================================================")

    if (failed > 0) {
        throw AssertionError("Reviewer M7-2 Adversarial Suite failed with $failed errors!")
    }
}
