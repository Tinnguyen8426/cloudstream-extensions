package tests.adversarial

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitlePriorityEvaluator
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitleReconciler

fun main() {
    println("================================================================================")
    println(">>> RUNNING CHALLENGER M7-2 ADVERSARIAL STRESS TEST HARNESS")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun check(testName: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $testName")
            passed++
        } else {
            println("  [FAIL] $testName -> $details")
            failed++
        }
    }

    // =========================================================================
    // SUITE 1: Polymorphic JSON Deserializer Adversarial Stress
    // =========================================================================
    println("\n--- [SUITE 1: Polymorphic JSON Deserializer Adversarial Stress] ---")

    // 1.1 Bare JSON array with whitespace, formatting, escaped quotes, unicode emojis
    val bareArrayAdv = """
    [
        { "id": 1, "text": "Line with escaped \"quotes\" and \n newline" },
        { "id": 2, "text": "Unicode: Xin chào thế giới! 🌸 日本語 and UTF-8" },
        { "id": 3, "text": "Special symbols: <font color=\"#ff0000\">&amp; [[TAG_1]]</font>" }
    ]
    """.trimIndent()
    val resBare = AITranslationParser.parseTranslations(bareArrayAdv)
    check("1.1 Bare array with special characters, unicode, and escaped quotes",
        resBare.size == 3 &&
        resBare[1] == "Line with escaped \"quotes\" and \n newline" &&
        resBare[2] == "Unicode: Xin chào thế giới! 🌸 日本語 and UTF-8" &&
        resBare[3] == "Special symbols: <font color=\"#ff0000\">&amp; [[TAG_1]]</font>",
        "Got: $resBare"
    )

    // 1.2 OpenAI Structured Outputs wrapper with extraneous root metadata
    val openAiExtraMetadata = """
    {
        "id": "chatcmpl-9xyz123",
        "object": "chat.completion",
        "created": 1718000000,
        "model": "gpt-4o-mini-2024-07-18",
        "usage": { "prompt_tokens": 500, "completion_tokens": 120, "total_tokens": 620 },
        "translations": [
            { "id": 1, "text": "First translated sentence", "confidence": 0.99 },
            { "id": 2, "text": "Second translated sentence", "tokens": ["Second", "translated"] }
        ]
    }
    """.trimIndent()
    val resOpenAi = AITranslationParser.parseTranslations(openAiExtraMetadata)
    check("1.2 OpenAI wrapper with extra root & item metadata",
        resOpenAi == mapOf(1 to "First translated sentence", 2 to "Second translated sentence"),
        "Got: $resOpenAi"
    )

    // 1.3 Arbitrary wrapper keys
    val wrapperKeys = listOf("data", "subtitles", "items", "cues", "results")
    var allWrappersPassed = true
    for (k in wrapperKeys) {
        val payload = """{ "$k": [ { "id": 100, "text": "Value under $k" } ] }"""
        val parsed = AITranslationParser.parseTranslations(payload)
        if (parsed != mapOf(100 to "Value under $k")) {
            allWrappersPassed = false
            println("    Failed wrapper key: $k, got: $parsed")
        }
    }
    check("1.3 Standard arbitrary wrapper keys (data, subtitles, items, cues, results)", allWrappersPassed)

    // 1.3b Unknown wrapper key falling back to first child array
    val unknownWrapper = """{ "custom_ai_payload": [ { "id": 50, "text": "Found via first array child" } ] }"""
    val resUnknown = AITranslationParser.parseTranslations(unknownWrapper)
    check("1.3b Unknown wrapper key extracts child array",
        resUnknown == mapOf(50 to "Found via first array child"),
        "Got: $resUnknown"
    )

    // 1.4 Markdown code fences with heavy conversational preamble & postamble
    val markdownAdv = """
    I'd be glad to translate that for you! Here is the JSON subtitle structure you requested:

    ```json
    [
        {"id": 10, "text": "Dialogue ten"},
        {"id": 11, "text": "Dialogue eleven"}
    ]
    ```

    Note: I preserved all original dialogue tone. Please let me know if you need any other languages!
    """.trimIndent()
    val resMarkdown = AITranslationParser.parseTranslations(markdownAdv)
    check("1.4 Markdown code fence surrounded by conversational preamble/postamble",
        resMarkdown == mapOf(10 to "Dialogue ten", 11 to "Dialogue eleven"),
        "Got: $resMarkdown"
    )

    // 1.4b Markdown code fence with JSON object wrapper
    val markdownObjectWrapper = """
    ```json
    {
      "translations": [
        {"id": 20, "text": "Object inside fence"}
      ]
    }
    ```
    """.trimIndent()
    val resMarkdownObj = AITranslationParser.parseTranslations(markdownObjectWrapper)
    check("1.4b Markdown code fence containing object wrapper",
        resMarkdownObj == mapOf(20 to "Object inside fence"),
        "Got: $resMarkdownObj"
    )

    // 1.4c Conversational text containing brackets before the fence
    val bracketsInPreamble = """
    Looking at lines [1] to [10] from the previous scene {chunk 1}, here is the translation:
    ```
    [
        {"id": 1, "text": "Preamble bracket immunity"}
    ]
    ```
    """.trimIndent()
    val resBrackets = AITranslationParser.parseTranslations(bracketsInPreamble)
    check("1.4c Conversational text with brackets before markdown fence",
        resBrackets == mapOf(1 to "Preamble bracket immunity"),
        "Got: $resBrackets"
    )

    // 1.5 Anthropic continuation (assistant prefill without leading bracket)
    val anthropicAdv = """{"id": 1, "text": "First"}, {"id": 2, "text": "Second"}]"""
    val resAnthropic = AITranslationParser.parseTranslations(anthropicAdv)
    check("1.5 Anthropic continuation without leading '['",
        resAnthropic == mapOf(1 to "First", 2 to "Second"),
        "Got: $resAnthropic"
    )

    // 1.5b Anthropic continuation inside markdown fence
    val anthropicFenced = "```json\n{\"id\": 5, \"text\": \"Anthropic in fence\"}]\n```"
    val resAnthropicFenced = AITranslationParser.parseTranslations(anthropicFenced)
    check("1.5b Anthropic continuation inside markdown fence",
        resAnthropicFenced == mapOf(5 to "Anthropic in fence"),
        "Got: $resAnthropicFenced"
    )

    // 1.6 Tagged Line Delimiters fallback
    val delimiterAdv = """
    <<<1>>>
    First line text
    <<<2>>>
    Second line with multiple
    sub-lines of spoken dialogue
    <<<3>>>
    Third line text
    """.trimIndent()
    val resDelim = AITranslationParser.parseTranslations(delimiterAdv)
    check("1.6 Tagged line delimiter with multiline text",
        resDelim == mapOf(
            1 to "First line text",
            2 to "Second line with multiple\nsub-lines of spoken dialogue",
            3 to "Third line text"
        ),
        "Got: $resDelim"
    )

    // 1.7 Poisoned & Edge Case Inputs
    check("1.7a Empty input string returns empty map",
        AITranslationParser.parseTranslations("").isEmpty()
    )
    check("1.7b Whitespace only returns empty map",
        AITranslationParser.parseTranslations("   \n\t  \r\n").isEmpty()
    )
    val refusal = "I'm sorry, but as an AI language model, I cannot translate content that violates safety policies."
    check("1.7c AI refusal returns empty map gracefully without throwing",
        AITranslationParser.parseTranslations(refusal).isEmpty()
    )
    val truncated = """[{"id": 1, "text": "Incomplete"""
    check("1.7d Truncated invalid JSON returns empty map without crashing",
        AITranslationParser.parseTranslations(truncated).isEmpty()
    )
    val stringIds = """[{"id": "42", "text": "String ID coerced"}]"""
    check("1.7e String IDs in JSON coerced properly",
        AITranslationParser.parseTranslations(stringIds) == mapOf(42 to "String ID coerced")
    )
    val emptyTextFiltered = """[{"id": 1, "text": "  "}, {"id": 2, "text": "Valid"}]"""
    check("1.7f Blank text items filtered out",
        AITranslationParser.parseTranslations(emptyTextFiltered) == mapOf(2 to "Valid")
    )

    // 1.8 Deeply nested JSON array extracted via regex fallback
    val deeplyNested = """{ "response": { "nested_level_2": { "items_array": [ { "id": 1, "text": "Deep extraction" } ] } } }"""
    val resDeep = AITranslationParser.parseTranslations(deeplyNested)
    check("1.8 Deeply nested JSON array extracted",
        resDeep == mapOf(1 to "Deep extraction"),
        "Got: $resDeep"
    )

    // 1.9 Large array stress (100 items)
    val largeArray = (1..100).joinToString(prefix = "[", postfix = "]", separator = ",") {
        """{"id": $it, "text": "Sentence number $it"}"""
    }
    val resLarge = AITranslationParser.parseTranslations(largeArray)
    check("1.9 Large array stress: 100/100 items parsed",
        resLarge.size == 100 && resLarge[100] == "Sentence number 100"
    )


    // =========================================================================
    // SUITE 2: Auto-Healing Dialogue Reconciler Stress
    // =========================================================================
    println("\n--- [SUITE 2: Auto-Healing Dialogue Reconciler Stress] ---")

    fun makeCue(index: Int, text: String = "English $index") = SubtitleCue(
        index = index,
        startMs = index * 1000L,
        endMs = index * 1000L + 900L,
        rawText = text,
        maskedText = text
    )

    // 2.1 Chunk 2 relative re-indexing: 30 cues (31..60) translated as 1..30
    val chunk2Cues = (31..60).map { makeCue(it) }
    val relTrans30 = (1..30).associateWith { "Bản dịch $it" }
    val healedChunk2 = SubtitleReconciler.autoHealingReconcile(chunk2Cues, relTrans30)

    val chunk2Count = (31..60).count { healedChunk2[it] == "Bản dịch ${it - 30}" }
    check("2.1 Chunk 2 re-indexing: 100% recovery (30/30 cues mapped 31..60 -> 1..30)",
        chunk2Count == 30 && healedChunk2.size == 30,
        "Recovered: $chunk2Count/30"
    )

    // 2.2 Chunk 10 relative re-indexing: 30 cues (271..300) translated as 1..30
    val chunk10Cues = (271..300).map { makeCue(it) }
    val healedChunk10 = SubtitleReconciler.autoHealingReconcile(chunk10Cues, relTrans30)
    val chunk10Count = (271..300).count { healedChunk10[it] == "Bản dịch ${it - 270}" }
    check("2.2 High-index Chunk 10 re-indexing: 100% recovery (30/30 cues mapped 271..300 -> 1..30)",
        chunk10Count == 30 && healedChunk10.size == 30,
        "Recovered: $chunk10Count/30"
    )

    // 2.3 Non-contiguous cue indices in chunk: e.g. cues [100, 105, 110, 115, 120]
    val nonContigCues = listOf(100, 105, 110, 115, 120).map { makeCue(it) }
    val relNonContig = (1..5).associateWith { "NonContig $it" }
    val healedNonContig = SubtitleReconciler.autoHealingReconcile(nonContigCues, relNonContig)
    check("2.3 Non-contiguous cues auto-healed via offset",
        healedNonContig[100] == "NonContig 1" &&
        healedNonContig[105] == "NonContig 2" &&
        healedNonContig[110] == "NonContig 3" &&
        healedNonContig[115] == "NonContig 4" &&
        healedNonContig[120] == "NonContig 5" &&
        healedNonContig.size == 5
    )

    // 2.4 Dropped lines in relative mode falling back to source rawText
    // LLM returned 1..30 EXCEPT cue 15 (offset 14, corresponding to cue 45)
    val relWithDrop = (1..30).filter { it != 15 }.associateWith { "Bản dịch $it" }
    val healedRelDrop = SubtitleReconciler.autoHealingReconcile(chunk2Cues, relWithDrop)
    check("2.4 Dropped line in relative mode falls back to rawText while preserving rest",
        healedRelDrop[45] == "English 45" &&
        healedRelDrop[44] == "Bản dịch 14" &&
        healedRelDrop[46] == "Bản dịch 16" &&
        healedRelDrop.size == 30
    )

    // 2.5 Truncated relative response (LLM stopped after 10 of 30 cues)
    val relTruncated = (1..10).associateWith { "Bản dịch $it" }
    val healedTrunc = SubtitleReconciler.autoHealingReconcile(chunk2Cues, relTruncated)
    val translatedCount10 = (31..40).count { healedTrunc[it] == "Bản dịch ${it - 30}" }
    val fallbackCount20 = (41..60).count { healedTrunc[it] == "English $it" }
    check("2.5 Truncated relative response: cues 1..10 translated, 11..30 fallback to rawText",
        translatedCount10 == 10 && fallbackCount20 == 20 && healedTrunc.size == 30
    )

    // 2.6 Hallucinated keys filtered out strictly
    val hallucinatedTrans = (1..30).associateWith { "Bản dịch $it" }.toMutableMap()
    hallucinatedTrans[-10] = "Negative ID"
    hallucinatedTrans[0] = "Zero ID"
    hallucinatedTrans[31] = "Accidental 31" // in relative mode this could be ambiguous
    hallucinatedTrans[999] = "Huge ID"
    val healedHalluc = SubtitleReconciler.autoHealingReconcile(chunk2Cues, hallucinatedTrans)
    check("2.6 Hallucinated keys strictly filtered out, size strictly matches chunk size",
        healedHalluc.size == 30 &&
        !healedHalluc.containsKey(-10) &&
        !healedHalluc.containsKey(0) &&
        !healedHalluc.containsKey(999)
    )

    // 2.7 Blank translations fall back to rawText
    val blankTrans = mapOf(
        31 to "Translated 31",
        32 to "",
        33 to "   ",
        34 to "\t\n  "
    )
    val smallCues = (31..34).map { makeCue(it) }
    val healedBlank = SubtitleReconciler.autoHealingReconcile(smallCues, blankTrans)
    check("2.7 Blank/whitespace translations fall back to source rawText",
        healedBlank[31] == "Translated 31" &&
        healedBlank[32] == "English 32" &&
        healedBlank[33] == "English 33" &&
        healedBlank[34] == "English 34" &&
        healedBlank.size == 4
    )

    // 2.8 Empty cue list returns emptyMap
    check("2.8 Empty cues input returns emptyMap",
        SubtitleReconciler.autoHealingReconcile(emptyList(), relTrans30).isEmpty()
    )

    // 2.9 Single cue chunk with relative re-indexing
    val singleCue = listOf(makeCue(42))
    val singleRel = mapOf(1 to "Single translated")
    val healedSingle = SubtitleReconciler.autoHealingReconcile(singleCue, singleRel)
    check("2.9 Single cue chunk healed from relative key 1 to cue 42",
        healedSingle == mapOf(42 to "Single translated")
    )

    // 2.10 Backward-compatible alias reconcileChunkItems
    val healedAlias = SubtitleReconciler.reconcileChunkItems(chunk2Cues, relTrans30)
    check("2.10 reconcileChunkItems alias works identically",
        healedAlias.size == 30 && healedAlias[31] == "Bản dịch 1"
    )


    // =========================================================================
    // SUITE 3: Subtitle Priority Evaluator Adversarial Stress
    // =========================================================================
    println("\n--- [SUITE 3: Subtitle Priority Evaluator Adversarial Stress] ---")

    fun sub(lang: String) = SubtitleFile(lang, "https://example.com/sub.srt")

    // 3.1 Target Language Bypass (-1000 score) across languages and tag variants
    val viVariants = listOf("vi", "vie", "vietnamese", "VI", "vi-VN", "vi_VN", "tiếng việt", "ti?ng vi?t", "Tiếng Việt (Anime47)")
    var allViBypass = true
    for (v in viVariants) {
        val score = SubtitlePriorityEvaluator.calculateScore(sub(v), "vi")
        if (score != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            allViBypass = false
            println("    Failed vi bypass for variant: '$v', got score: $score")
        }
    }
    check("3.1a Target Language Bypass (-1000) for all Vietnamese variants against target 'vi'", allViBypass)

    val enVariants = listOf("en", "eng", "english", "EN", "en-US", "en_GB", "tiếng anh", "English [SDH]")
    var allEnBypass = true
    for (e in enVariants) {
        val score = SubtitlePriorityEvaluator.calculateScore(sub(e), "en")
        if (score != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            allEnBypass = false
            println("    Failed en bypass for variant: '$e', got score: $score")
        }
    }
    check("3.1b Target Language Bypass (-1000) for all English variants against target 'en'", allEnBypass)

    val jaVariants = listOf("ja", "jp", "jpn", "japanese", "JA", "ja-JP", "tiếng nhật", "nihongo")
    var allJaBypass = true
    for (j in jaVariants) {
        val score = SubtitlePriorityEvaluator.calculateScore(sub(j), "ja")
        if (score != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            allJaBypass = false
            println("    Failed ja bypass for variant: '$j', got score: $score")
        }
    }
    check("3.1c Target Language Bypass (-1000) for all Japanese variants against target 'ja'", allJaBypass)

    // 3.2 Substring Collision Safety (crucial adversarial challenge!)
    val englishSubstringCollisions = listOf(
        "french",
        "swedish",
        "slovenian",
        "greenlandic",
        "armenian",
        "bengali",
        "valencian",
        "chechen",
        "turkmen",
        "yemen",
        "kannada",
        "rundi"
    )
    var zeroEnCollisions = true
    for (c in englishSubstringCollisions) {
        val matches = SubtitlePriorityEvaluator.isMatchingLanguage(c, "en")
        val score = SubtitlePriorityEvaluator.calculateScore(sub(c), "en")
        if (matches || score == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            zeroEnCollisions = false
            println("    COLLISION DETECTED: '$c' matched English! (matches=$matches, score=$score)")
        }
    }
    check("3.2a Zero substring collisions with 'en' for languages containing 'en' as substring", zeroEnCollisions)

    val vietnameseSubstringCollisions = listOf(
        "latvian",
        "bolivian",
        "moldavian",
        "herzegovinian"
    )
    var zeroViCollisions = true
    for (c in vietnameseSubstringCollisions) {
        val matches = SubtitlePriorityEvaluator.isMatchingLanguage(c, "vi")
        val score = SubtitlePriorityEvaluator.calculateScore(sub(c), "vi")
        if (matches || score == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            zeroViCollisions = false
            println("    COLLISION DETECTED: '$c' matched Vietnamese! (matches=$matches, score=$score)")
        }
    }
    check("3.2b Zero substring collisions with 'vi' for languages containing 'vi' as substring", zeroViCollisions)

    // 3.3 Priority Modes Ranking
    val subJa = sub("ja")
    val subEn = sub("en")
    val subFr = sub("fr")
    val subEs = sub("es")
    val subDe = sub("de")

    // Anime First: ja (1000) > en (500) > fr (100)
    val sJaAnime = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    val sEnAnime = SubtitlePriorityEvaluator.calculateScore(subEn, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    val sFrAnime = SubtitlePriorityEvaluator.calculateScore(subFr, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    check("3.3a Anime First rankings: ja (1000) > en (500) > others (100)",
        sJaAnime == 1000 && sEnAnime == 500 && sFrAnime == 100
    )

    // English First: en (1000) > ja (500) > fr (100)
    val sEnEng = SubtitlePriorityEvaluator.calculateScore(subEn, "vi", TranslatorConfig.PRIORITY_ENGLISH_FIRST)
    val sJaEng = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_ENGLISH_FIRST)
    val sFrEng = SubtitlePriorityEvaluator.calculateScore(subFr, "vi", TranslatorConfig.PRIORITY_ENGLISH_FIRST)
    check("3.3b English First rankings: en (1000) > ja (500) > others (100)",
        sEnEng == 1000 && sJaEng == 500 && sFrEng == 100
    )

    // Smart Auto: en (800) > ja (750) > fr (100)
    val sEnAuto = SubtitlePriorityEvaluator.calculateScore(subEn, "vi", TranslatorConfig.PRIORITY_AUTO)
    val sJaAuto = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_AUTO)
    val sFrAuto = SubtitlePriorityEvaluator.calculateScore(subFr, "vi", TranslatorConfig.PRIORITY_AUTO)
    check("3.3c Smart Auto rankings: en (800) > ja (750) > others (100)",
        sEnAuto == 800 && sJaAuto == 750 && sFrAuto == 100
    )

    // Custom Mode: custom list ["fr", "es", "ja", "en"]
    val customList = listOf("fr", "es", "ja", "en")
    val sFrCustom = SubtitlePriorityEvaluator.calculateScore(subFr, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val sEsCustom = SubtitlePriorityEvaluator.calculateScore(subEs, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val sJaCustom = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val sEnCustom = SubtitlePriorityEvaluator.calculateScore(subEn, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val sDeCustom = SubtitlePriorityEvaluator.calculateScore(subDe, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    check("3.3d Custom Mode rankings: fr (1000) > es (900) > ja (800) > en (700) > de (50)",
        sFrCustom == 1000 && sEsCustom == 900 && sJaCustom == 800 && sEnCustom == 700 && sDeCustom == 50,
        "Got: fr=$sFrCustom, es=$sEsCustom, ja=$sJaCustom, en=$sEnCustom, de=$sDeCustom"
    )

    // 3.4 selectBestSubtitle Decision Engine
    val subVi = sub("Tiếng Việt")
    val poolWithVi = listOf(subEn, subVi, subJa, subFr)
    val selectedBypass = SubtitlePriorityEvaluator.selectBestSubtitle(poolWithVi, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    check("3.4a selectBestSubtitle immediately chooses target language track for bypass regardless of mode",
        selectedBypass == subVi
    )

    val poolWithoutVi = listOf(subFr, subEn, subJa, subEs)
    val selectedAnime = SubtitlePriorityEvaluator.selectBestSubtitle(poolWithoutVi, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    check("3.4b selectBestSubtitle chooses Japanese for Anime First when target is absent",
        selectedAnime == subJa
    )

    val selectedEnglish = SubtitlePriorityEvaluator.selectBestSubtitle(poolWithoutVi, "vi", TranslatorConfig.PRIORITY_ENGLISH_FIRST)
    check("3.4c selectBestSubtitle chooses English for English First when target is absent",
        selectedEnglish == subEn
    )

    val selectedCustom = SubtitlePriorityEvaluator.selectBestSubtitle(poolWithoutVi, "vi", TranslatorConfig.PRIORITY_CUSTOM, listOf("es", "fr"))
    check("3.4d selectBestSubtitle chooses Spanish for Custom mode when 'es' is first in customList",
        selectedCustom == subEs
    )

    val selectedEmpty = SubtitlePriorityEvaluator.selectBestSubtitle(emptyList(), "vi")
    check("3.4e selectBestSubtitle returns null for empty candidates",
        selectedEmpty == null
    )

    // 3.5 Real-world complex track names
    val complexTracks = listOf(
        sub("[Official] English (US)") to "en",
        sub("English - English [CC]") to "en",
        sub("Subtitles: Japanese (Audio commentary)") to "ja",
        sub("Vietnamese (Thuyết minh)") to "vi",
        sub("Spanish (Latin America)") to "es",
        sub("French [Forced Narrator]") to "fr",
        sub("Chinese (Simplified) [zh-Hans]") to "zh",
        sub("Korean (한국어)") to "ko"
    )
    var allComplexMatched = true
    for ((file, target) in complexTracks) {
        val score = SubtitlePriorityEvaluator.calculateScore(file, target)
        if (score != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) {
            allComplexMatched = false
            println("    Failed complex track '${file.lang}' for target '$target', got score: $score")
        }
    }
    check("3.5 Real-world complex & bracketed track names correctly trigger target bypass", allComplexMatched)

    // =========================================================================
    // FINAL VERDICT TALLY
    // =========================================================================
    println("\n================================================================================")
    println(">>> CHALLENGER M7-2 ADVERSARIAL STRESS RESULTS: $passed PASSED, $failed FAILED (Total ${passed + failed})")
    println("================================================================================")

    if (failed > 0) {
        println("[!] STRESS TEST SUITE REJECTED WITH $failed FAILURES.")
        System.exit(1)
    } else {
        println("[+] ALL ADVERSARIAL STRESS TESTS CONFIRMED CLEANLY!")
        System.exit(0)
    }
}
