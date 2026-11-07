package tests.adversarial

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.client.*
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitlePriorityEvaluator
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitleReconciler
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicBoolean

fun main() = runBlocking {
    println("================================================================================")
    println(">>> AUDITOR INDEPENDENT FORENSIC INTEGRITY & ADVERSARIAL STRESS TEST (PHASE 3)")
    println("================================================================================")

    var testsCount = 0
    var passedCount = 0

    fun assertAuditor(name: String, condition: Boolean, message: String = "") {
        testsCount++
        if (condition) {
            println("  [PASS] $name")
            passedCount++
        } else {
            System.err.println("  [FAIL] $name: $message")
            throw AssertionError("Forensic audit assertion failed: $name - $message")
        }
    }

    // -------------------------------------------------------------------------
    // TEST SUITE 1: TranslatorConfig Forensic State Verification
    // -------------------------------------------------------------------------
    println("\n[Auditor Suite 1: TranslatorConfig & State Transitions]")
    TranslatorConfig.resetToDefaults()
    assertAuditor("Default provider is Gemini", TranslatorConfig.provider == TranslatorConfig.PROVIDER_GEMINI)
    assertAuditor("Default priority mode is Anime First", TranslatorConfig.sourcePriorityMode == TranslatorConfig.PRIORITY_ANIME_FIRST)
    assertAuditor("Default target language is vi", TranslatorConfig.targetLanguage == "vi")
    assertAuditor("Default effective model resolves to gemini-1.5-flash", TranslatorConfig.getEffectiveModel() == "gemini-1.5-flash")

    // Dirty priority list parsing
    TranslatorConfig.customPriorityList = "  ja , , EN ,,, fr ,  "
    val parsedLangs = TranslatorConfig.getCustomPriorityLanguages()
    assertAuditor("Custom priority handles dirty whitespace and empty tokens", parsedLangs == listOf("ja", "en", "fr"))

    TranslatorConfig.modelName = "claude-3-opus-20240229"
    assertAuditor("Explicit modelName override takes strict precedence", TranslatorConfig.getEffectiveModel() == "claude-3-opus-20240229")
    TranslatorConfig.resetToDefaults()

    // -------------------------------------------------------------------------
    // TEST SUITE 2: AITranslationParser Adversarial Payloads
    // -------------------------------------------------------------------------
    println("\n[Auditor Suite 2: Polymorphic Deserializer Adversarial Stress]")

    // 2.1 Unicode and Special Character Preservations
    val unicodePayload = """[{"id": 1, "text": "Tiếng Việt có dấu: Ứ, Ự, Đ, Ơ! 特殊文字: 🍣🍱"}]"""
    val parsedUnicode = AITranslationParser.parseTranslations(unicodePayload)
    assertAuditor("Unicode & CJK / Emoji preservation in JSON parsing",
        parsedUnicode[1] == "Tiếng Việt có dấu: Ứ, Ự, Đ, Ơ! 特殊文字: 🍣🍱")

    // 2.2 Deeply nested / noisy conversational preambles and postambles
    val noisyPayload = """
        Here is the translation you requested. Please notice:
        1. We preserved the tone.
        
        ```json
        {
          "translations": [
            {"id": 101, "text": "First line"},
            {"id": 102, "text": "Second line"}
          ]
        }
        ```
        
        Please let me know if you need any further assistance!
    """.trimIndent()
    val parsedNoisy = AITranslationParser.parseTranslations(noisyPayload)
    assertAuditor("Noisy conversational fence extraction with OpenAI wrapper",
        parsedNoisy == mapOf(101 to "First line", 102 to "Second line"))

    // 2.3 Bare Anthropic prefill continuation without brackets
    val anthropicCont = """{"id": 50, "text": "Continuation text"}]"""
    val parsedAnthropic = AITranslationParser.parseTranslations(anthropicCont)
    assertAuditor("Anthropic continuation without opening '[' recovered cleanly",
        parsedAnthropic == mapOf(50 to "Continuation text"))

    // 2.4 Delimiter Fallback Parser stress
    val rawDelimiter = """
        <<<1>>>
        First line dialogue
        <<<2>>>
        Second line with special <font color="#FF0000">tags</font>
        <<<3>>>
        
        <<<4>>>
        Final line after blank cue
    """.trimIndent()
    val parsedDelimiter = AITranslationParser.parseTranslations(rawDelimiter)
    assertAuditor("Delimiter fallback parses non-empty cues and skips empty cue 3",
        parsedDelimiter[1] == "First line dialogue" &&
        parsedDelimiter[2] == "Second line with special <font color=\"#FF0000\">tags</font>" &&
        !parsedDelimiter.containsKey(3) &&
        parsedDelimiter[4] == "Final line after blank cue")

    // 2.5 Empty or garbage input handling
    val parsedGarbage = AITranslationParser.parseTranslations("This contains absolutely no valid JSON or delimiters.")
    assertAuditor("Garbage input returns safe empty map without throwing exception", parsedGarbage.isEmpty())

    // -------------------------------------------------------------------------
    // TEST SUITE 3: SubtitleReconciler Adversarial Boundary Tests
    // -------------------------------------------------------------------------
    println("\n[Auditor Suite 3: Auto-Healing Reconciler Adversarial Stress]")

    fun cue(id: Int, raw: String) = SubtitleCue(index = id, startMs = 0L, endMs = 1000L, rawText = raw, maskedText = raw)

    // 3.1 Single cue chunk with relative re-indexing (Cue 42 -> LLM outputs 1)
    val singleChunk = listOf(cue(42, "Original text 42"))
    val singleLlm = mapOf(1 to "Translated text 42")
    val singleHealed = SubtitleReconciler.autoHealingReconcile(singleChunk, singleLlm)
    assertAuditor("Single cue offset chunk (42 -> 1) heals accurately",
        singleHealed[42] == "Translated text 42" && singleHealed.size == 1)

    // 3.2 50 Cues with relative re-indexing and non-contiguous drops
    val cues50 = (51..100).map { cue(it, "Source $it") }
    // LLM outputs 1..50 but drops items 5, 25, 50
    val llm50 = (1..50).filter { it !in listOf(5, 25, 50) }.associateWith { "Translated ${it + 50}" }
    val healed50 = SubtitleReconciler.autoHealingReconcile(cues50, llm50)
    assertAuditor("Reconciler heals 47/50 cues and preserves source text for dropped cues 55, 75, 100",
        healed50.size == 50 &&
        healed50[51] == "Translated 51" &&
        healed50[54] == "Translated 54" &&
        healed50[55] == "Source 55" && // dropped item 5 -> cue 55
        healed50[75] == "Source 75" && // dropped item 25 -> cue 75
        healed50[100] == "Source 100" && // dropped item 50 -> cue 100
        healed50[99] == "Translated 99")

    // 3.3 Blank / whitespace translations fallback to rawText
    val cuesBlank = listOf(cue(1, "Original 1"), cue(2, "Original 2"))
    val llmBlank = mapOf(1 to "   ", 2 to "Valid")
    val healedBlank = SubtitleReconciler.autoHealingReconcile(cuesBlank, llmBlank)
    assertAuditor("Blank whitespace translations safely fallback to rawText",
        healedBlank[1] == "Original 1" && healedBlank[2] == "Valid")

    // -------------------------------------------------------------------------
    // TEST SUITE 4: SubtitlePriorityEvaluator Language Matching & Target Bypass
    // -------------------------------------------------------------------------
    println("\n[Auditor Suite 4: Priority Evaluator & Target Bypass Verification]")

    // 4.1 Tokenized matching edge cases
    assertAuditor("Vietnamese variations matched accurately",
        SubtitlePriorityEvaluator.isVietnamese("vi") &&
        SubtitlePriorityEvaluator.isVietnamese("vie") &&
        SubtitlePriorityEvaluator.isVietnamese("vietnamese") &&
        SubtitlePriorityEvaluator.isVietnamese("Tiếng Việt (Anime47)") &&
        SubtitlePriorityEvaluator.isVietnamese("vi-VN"))

    assertAuditor("English variations matched accurately",
        SubtitlePriorityEvaluator.isEnglish("en") &&
        SubtitlePriorityEvaluator.isEnglish("eng") &&
        SubtitlePriorityEvaluator.isEnglish("english") &&
        SubtitlePriorityEvaluator.isEnglish("en-US") &&
        SubtitlePriorityEvaluator.isEnglish("English [CC]"))

    // Ensure no false positives
    assertAuditor("French does NOT match English or Vietnamese",
        !SubtitlePriorityEvaluator.isEnglish("french") &&
        !SubtitlePriorityEvaluator.isVietnamese("french"))

    assertAuditor("Spanish does NOT match English or Japanese",
        !SubtitlePriorityEvaluator.isEnglish("spanish") &&
        !SubtitlePriorityEvaluator.isJapanese("spanish"))

    // 4.2 Target Language Bypass on exact matches
    val viTrack = SubtitleFile("Tiếng Việt", "https://anime47.com/sub.srt")
    val enTrack = SubtitleFile("English", "https://stremio.com/sub.srt")
    val jaTrack = SubtitleFile("Japanese", "https://kitsunekko.net/sub.ass")

    assertAuditor("Target language bypass triggers SCORE_BYPASS_TARGET_LANG (-1000) for target vi",
        SubtitlePriorityEvaluator.calculateScore(viTrack, "vi") == -1000)

    assertAuditor("Target language bypass triggers SCORE_BYPASS_TARGET_LANG (-1000) for target en",
        SubtitlePriorityEvaluator.calculateScore(enTrack, "en") == -1000)

    val bestVi = SubtitlePriorityEvaluator.selectBestSubtitle(listOf(enTrack, jaTrack, viTrack), "vi")
    assertAuditor("selectBestSubtitle returns target language stream directly for native playback",
        bestVi == viTrack)

    // -------------------------------------------------------------------------
    // TEST SUITE 5: AI Provider Endpoints & Error Classifications
    // -------------------------------------------------------------------------
    println("\n[Auditor Suite 5: AI Provider Normalization & Diagnostics]")

    // 5.1 OpenAI URL Normalization
    assertAuditor("OpenAI bare localhost endpoint normalized",
        OpenAITranslatorClient.normalizeOpenAiUrl("http://localhost:11434") == "http://localhost:11434/v1/chat/completions")
    assertAuditor("OpenAI endpoint with trailing slash normalized",
        OpenAITranslatorClient.normalizeOpenAiUrl("https://api.openai.com/v1/") == "https://api.openai.com/v1/chat/completions")

    // 5.2 Gemini URL Construction & Error Decoupling
    assertAuditor("Gemini strips 'models/' prefix when present",
        GeminiTranslatorClient.buildGeminiUrl(null, "models/gemini-2.0-flash") ==
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent")

    val dailyQuotaBody = """{"error": {"code": 429, "message": "Quota exceeded for GenerateRequestsPerDay"}}"""
    val rpmQuotaBody = """{"error": {"code": 429, "message": "Quota exceeded for GenerateRequestsPerMinute"}}"""
    assertAuditor("Gemini distinguishes Daily Quota from transient RPM",
        GeminiTranslatorClient.isDailyQuotaError(429, dailyQuotaBody) &&
        !GeminiTranslatorClient.isDailyQuotaError(429, rpmQuotaBody) &&
        GeminiTranslatorClient.isTransientRpmError(429, rpmQuotaBody))

    // 5.3 Anthropic URL & Credit Exhaustion
    assertAuditor("Anthropic URL appends /messages correctly",
        AnthropicTranslatorClient.buildAnthropicUrl("https://api.anthropic.com/v1") == "https://api.anthropic.com/v1/messages")

    val anthropicLowCredit = """{"type": "error", "error": {"type": "invalid_request_error", "message": "credit balance is too low"}}"""
    assertAuditor("Anthropic detects credit balance exhaustion as hard quota failure",
        AnthropicTranslatorClient.isCreditExhaustion(400, anthropicLowCredit))

    // 5.4 Factory Case Insensitivity
    val fOpenAi = AITranslatorFactory.createClient("OPENAI", "k1")
    val fGemini = AITranslatorFactory.createClient("GeMiNi", "k2")
    val fAnthropic = AITranslatorFactory.createClient("ANTHROPIC", "k3")
    val fCustom = AITranslatorFactory.createClient("CuStOm", "k4")
    assertAuditor("AITranslatorFactory is case-insensitive for all provider names",
        fOpenAi is OpenAITranslatorClient &&
        fGemini is GeminiTranslatorClient &&
        fAnthropic is AnthropicTranslatorClient &&
        fCustom is OpenAITranslatorClient)

    // 5.5 Authentic HTTP Interceptor Test
    val calledOpenAi = AtomicBoolean(false)
    val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
        calledOpenAi.set(true)
        val request = chain.request()
        if (request.header("Authorization") != "Bearer test-key-123") {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        } else {
            val responseBody = """{"choices": [{"message": {"content": "{\"translations\": [{\"id\": 1, \"text\": \"Dịch thành công\"}]}"}}]}"""
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }.build()

    val clientTest = OpenAITranslatorClient(apiKey = "test-key-123", httpClient = mockClient)
    val resTest = clientTest.translateChunk(listOf(cue(1, "Original Hello")), targetLang = "vi")
    assertAuditor("OpenAITranslatorClient performs authentic HTTP invocation and parses translations",
        calledOpenAi.get() && resTest.isSuccess && resTest.translations[1] == "Dịch thành công")

    println("\n================================================================================")
    println(">>> AUDITOR INDEPENDENT TEST RESULTS: $passedCount / $testsCount PASSED (100%)")
    println("================================================================================")
}
