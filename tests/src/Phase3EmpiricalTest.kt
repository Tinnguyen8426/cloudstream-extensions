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

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting Phase 3 Multi-Provider AI Clients, Parser & Reconciler Test Suite...")
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
    // SECTION 1: TranslatorConfig & Defaults
    // =========================================================================
    println("\n--- [Section 1: TranslatorConfig & Model Resolution] ---")
    TranslatorConfig.resetToDefaults()

    check("Provider constants defined",
        TranslatorConfig.PROVIDER_GEMINI == "gemini" &&
        TranslatorConfig.PROVIDER_OPENAI == "openai" &&
        TranslatorConfig.PROVIDER_ANTHROPIC == "anthropic" &&
        TranslatorConfig.PROVIDER_CUSTOM == "custom"
    )

    check("Priority constants defined",
        TranslatorConfig.PRIORITY_AUTO == "auto" &&
        TranslatorConfig.PRIORITY_ANIME_FIRST == "anime_first" &&
        TranslatorConfig.PRIORITY_ENGLISH_FIRST == "english_first" &&
        TranslatorConfig.PRIORITY_CUSTOM == "custom"
    )

    TranslatorConfig.provider = TranslatorConfig.PROVIDER_GEMINI
    TranslatorConfig.modelName = ""
    check("Effective model for Gemini defaults to gemini-1.5-flash",
        TranslatorConfig.getEffectiveModel() == "gemini-1.5-flash"
    )

    TranslatorConfig.provider = TranslatorConfig.PROVIDER_OPENAI
    check("Effective model for OpenAI defaults to gpt-4o-mini",
        TranslatorConfig.getEffectiveModel() == "gpt-4o-mini"
    )

    TranslatorConfig.provider = TranslatorConfig.PROVIDER_ANTHROPIC
    check("Effective model for Anthropic defaults to claude-3-5-haiku-20241022",
        TranslatorConfig.getEffectiveModel() == "claude-3-5-haiku-20241022"
    )

    TranslatorConfig.modelName = "deepseek-chat"
    check("Model override takes precedence over provider default",
        TranslatorConfig.getEffectiveModel() == "deepseek-chat"
    )

    TranslatorConfig.customPriorityList = "ja, en, fr, zh"
    val customLangs = TranslatorConfig.getCustomPriorityLanguages()
    check("Custom priority list parsing",
        customLangs == listOf("ja", "en", "fr", "zh")
    )

    // =========================================================================
    // SECTION 2: Universal Polymorphic Deserializer (AITranslationParser)
    // =========================================================================
    println("\n--- [Section 2: Universal Polymorphic Deserializer] ---")

    // Case 1: Bare JSON array
    val bareArray = """[{"id": 1, "text": "Hello"}]"""
    val res1 = AITranslationParser.parseTranslations(bareArray)
    check("Bare array deserialization", res1 == mapOf(1 to "Hello"))

    // Case 2: Markdown fenced JSON array
    val fencedJson = "```json\n[{\"id\": 1, \"text\": \"Hello\"}]\n```"
    val res2 = AITranslationParser.parseTranslations(fencedJson)
    check("Markdown fenced json deserialization", res2 == mapOf(1 to "Hello"))

    // Case 3: Markdown fenced without 'json'
    val fencedNoTag = "```\n[{\"id\": 1, \"text\": \"Hello\"}]\n```"
    val res3 = AITranslationParser.parseTranslations(fencedNoTag)
    check("Markdown fenced without tag deserialization", res3 == mapOf(1 to "Hello"))

    // Case 4: Conversational prefix + fence + conversational suffix
    val convFenced = "Sure, here is your translation:\n```json\n[{\"id\": 1, \"text\": \"Hello\"}]\n```\nHope this helps!"
    val res4 = AITranslationParser.parseTranslations(convFenced)
    check("Conversational fence extraction", res4 == mapOf(1 to "Hello"))

    // Case 5: Anthropic continuation without leading '['
    val anthropicCont = "{\"id\": 1, \"text\": \"Hello\"}]"
    val res5 = AITranslationParser.parseTranslations(anthropicCont)
    check("Anthropic prefill continuation recovery", res5 == mapOf(1 to "Hello"))

    // Case 6: OpenAI Structured Outputs wrapper {"translations": [...]}
    val openAiWrapper = """{"translations": [{"id": 1, "text": "Xin chao"}, {"id": 2, "text": "The gioi"}]}"""
    val res6 = AITranslationParser.parseTranslations(openAiWrapper)
    check("OpenAI object wrapper deserialization", res6 == mapOf(1 to "Xin chao", 2 to "The gioi"))

    // Case 7: Arbitrary object wrapper key {"data": [...]}
    val arbitraryWrapper = """{"data": [{"id": 10, "text": "Item 10"}]}"""
    val res7 = AITranslationParser.parseTranslations(arbitraryWrapper)
    check("Arbitrary wrapper key deserialization", res7 == mapOf(10 to "Item 10"))

    // Case 8: Delimiter fallback <<<ID>>>\nText
    val delimiterText = "<<<1>>>\nHello\n<<<2>>>\nWorld"
    val res8 = AITranslationParser.parseTranslations(delimiterText)
    check("Line delimiter fallback deserialization", res8 == mapOf(1 to "Hello", 2 to "World"))

    // Case 9: Delimiter fallback with multiline text
    val delimiterMulti = "<<<1>>>\nLine 1\nLine 2\n<<<2>>>\nLine 3"
    val res9 = AITranslationParser.parseTranslations(delimiterMulti)
    check("Multiline line delimiter deserialization", res9 == mapOf(1 to "Line 1\nLine 2", 2 to "Line 3"))

    // =========================================================================
    // SECTION 3: Auto-Healing Dialogue Reconciler (SubtitleReconciler)
    // =========================================================================
    println("\n--- [Section 3: Auto-Healing Dialogue Reconciler] ---")

    fun makeCue(index: Int, text: String) = SubtitleCue(
        index = index,
        startMs = index * 1000L,
        endMs = index * 1000L + 900L,
        rawText = text,
        maskedText = text
    )

    // Scenario 1: Chunk 2 (cues 31..60), LLM outputs relative 1..30 (Defect Healing)
    val cuesChunk2 = (31..60).map { makeCue(it, "English $it") }
    val llmRelative = (1..30).associateWith { "Vietnamese ${it + 30}" }
    val healed1 = SubtitleReconciler.autoHealingReconcile(cuesChunk2, llmRelative)
    val recoveredCount = (31..60).count { healed1[it] == "Vietnamese $it" }
    check("Auto-healing recovers 30/30 cues from relative 1..30 re-indexing",
        recoveredCount == 30 && healed1.size == 30,
        "Recovered: $recoveredCount/30"
    )

    // Scenario 2: Normal Chunk 1 (cues 1..30), LLM outputs 1..30
    val cuesChunk1 = (1..30).map { makeCue(it, "English $it") }
    val llmNormal1 = (1..30).associateWith { "Vietnamese $it" }
    val healed2 = SubtitleReconciler.autoHealingReconcile(cuesChunk1, llmNormal1)
    val normalCount1 = (1..30).count { healed2[it] == "Vietnamese $it" }
    check("Normal chunk 1 matches 1..30 directly", normalCount1 == 30)

    // Scenario 3: Normal Chunk 2 (cues 31..60), LLM outputs global 31..60
    val llmNormal2 = (31..60).associateWith { "Vietnamese $it" }
    val healed3 = SubtitleReconciler.autoHealingReconcile(cuesChunk2, llmNormal2)
    val normalCount2 = (31..60).count { healed3[it] == "Vietnamese $it" }
    check("Normal chunk 2 matches 31..60 directly", normalCount2 == 30)

    // Scenario 4: Partial drop fallback: Cue 45 missing from translation
    val llmDropped45 = (31..60).filter { it != 45 }.associateWith { "Vietnamese $it" }
    val healed4 = SubtitleReconciler.autoHealingReconcile(cuesChunk2, llmDropped45)
    check("Dropped cue falls back to source text while others remain translated",
        healed4[45] == "English 45" && healed4[46] == "Vietnamese 46" && healed4.size == 30
    )

    // Scenario 5: Relative re-indexing with omitted trailing cues (LLM emits 1..28 of 30)
    val llmRelativeDrop = (1..28).associateWith { "Vietnamese ${it + 30}" }
    val healed5 = SubtitleReconciler.autoHealingReconcile(cuesChunk2, llmRelativeDrop)
    val recoveredRel28 = (31..58).count { healed5[it] == "Vietnamese $it" }
    check("Relative re-indexing with drop recovers 28/28 and falls back 29-30 to rawText",
        recoveredRel28 == 28 && healed5[59] == "English 59" && healed5[60] == "English 60"
    )

    // Scenario 6: Hallucinated keys (0, 999) are filtered out
    val llmHallucinated = (31..60).associateWith { "Vietnamese $it" }.toMutableMap()
    llmHallucinated[0] = "Hallucinated 0"
    llmHallucinated[999] = "Hallucinated 999"
    val healed6 = SubtitleReconciler.autoHealingReconcile(cuesChunk2, llmHallucinated)
    check("Hallucinated cue keys outside chunk range are safely excluded",
        healed6.size == 30 && !healed6.containsKey(0) && !healed6.containsKey(999)
    )

    // =========================================================================
    // SECTION 4: SubtitlePriorityEvaluator & Target Bypass
    // =========================================================================
    println("\n--- [Section 4: SubtitlePriorityEvaluator & Target Bypass] ---")

    val subVi = SubtitleFile("Tiếng Việt (Anime47)", "https://example.com/vi.srt")
    val subJa = SubtitleFile("Tiếng Nhật (Kitsunekko)", "https://example.com/ja.ass")
    val subEn = SubtitleFile("English (Stremio)", "https://example.com/en.srt")
    val subEs = SubtitleFile("Spanish", "https://example.com/es.srt")
    val subFrench = SubtitleFile("French (OpenSubtitles)", "https://example.com/fr.srt")

    // Target Language Bypass
    check("Target Language Bypass triggers -1000 for matching Vietnamese track",
        SubtitlePriorityEvaluator.calculateScore(subVi, "vi") == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
    )
    check("Target Language Bypass triggers -1000 for matching English track",
        SubtitlePriorityEvaluator.calculateScore(subEn, "en") == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG
    )

    // Substring collision prevention
    check("French does NOT match target language English (no collision)",
        SubtitlePriorityEvaluator.calculateScore(subFrench, "en") != SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG &&
        !SubtitlePriorityEvaluator.isMatchingLanguage("french", "en")
    )
    check("Swedish does NOT match English",
        !SubtitlePriorityEvaluator.isMatchingLanguage("swedish", "en")
    )

    // Anime First Mode
    val scoreJaAnime = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    val scoreEnAnime = SubtitlePriorityEvaluator.calculateScore(subEn, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    val scoreEsAnime = SubtitlePriorityEvaluator.calculateScore(subEs, "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    check("Anime First scores Japanese (1000) > English (500) > Spanish (100)",
        scoreJaAnime == 1000 && scoreEnAnime == 500 && scoreEsAnime == 100
    )

    // English First Mode
    val scoreEnMovie = SubtitlePriorityEvaluator.calculateScore(subEn, "vi", TranslatorConfig.PRIORITY_ENGLISH_FIRST)
    val scoreJaMovie = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_ENGLISH_FIRST)
    check("English First scores English (1000) > Japanese (500)",
        scoreEnMovie == 1000 && scoreJaMovie == 500
    )

    // Custom Mode
    val customList = listOf("ja", "en", "es")
    val scoreJaCustom = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val scoreEnCustom = SubtitlePriorityEvaluator.calculateScore(subEn, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val scoreEsCustom = SubtitlePriorityEvaluator.calculateScore(subEs, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    val scoreFrCustom = SubtitlePriorityEvaluator.calculateScore(subFrench, "vi", TranslatorConfig.PRIORITY_CUSTOM, customList)
    check("Custom Mode applies linear ranking",
        scoreJaCustom == 1000 && scoreEnCustom == 900 && scoreEsCustom == 800 && scoreFrCustom == 50
    )

    // Smart Auto Mode
    val scoreEnAuto = SubtitlePriorityEvaluator.calculateScore(subEn, "vi", TranslatorConfig.PRIORITY_AUTO)
    val scoreJaAuto = SubtitlePriorityEvaluator.calculateScore(subJa, "vi", TranslatorConfig.PRIORITY_AUTO)
    check("Smart Auto scores English (800) > Japanese (750)",
        scoreEnAuto == 800 && scoreJaAuto == 750
    )

    // selectBestSubtitle
    val bestWithVi = SubtitlePriorityEvaluator.selectBestSubtitle(listOf(subEn, subVi, subJa), "vi")
    check("selectBestSubtitle selects target-matching track immediately for bypass",
        bestWithVi == subVi
    )

    val bestWithoutVi = SubtitlePriorityEvaluator.selectBestSubtitle(listOf(subEn, subJa, subEs), "vi", TranslatorConfig.PRIORITY_ANIME_FIRST)
    check("selectBestSubtitle selects highest priority candidate when target is not present",
        bestWithoutVi == subJa
    )

    // =========================================================================
    // SECTION 5: OpenAI Client URL Normalization & Mock Execution
    // =========================================================================
    println("\n--- [Section 5: OpenAI Client Normalization & Translation] ---")

    check("OpenAI URL normalizer: bare domain",
        OpenAITranslatorClient.normalizeOpenAiUrl("https://api.openai.com") == "https://api.openai.com/v1/chat/completions"
    )
    check("OpenAI URL normalizer: /v1 base",
        OpenAITranslatorClient.normalizeOpenAiUrl("https://api.openai.com/v1") == "https://api.openai.com/v1/chat/completions"
    )
    check("OpenAI URL normalizer: OpenRouter",
        OpenAITranslatorClient.normalizeOpenAiUrl("https://openrouter.ai/api/v1") == "https://openrouter.ai/api/v1/chat/completions"
    )
    check("OpenAI URL normalizer: DeepSeek",
        OpenAITranslatorClient.normalizeOpenAiUrl("https://api.deepseek.com") == "https://api.deepseek.com/chat/completions"
    )
    check("OpenAI URL normalizer: Groq /openai",
        OpenAITranslatorClient.normalizeOpenAiUrl("https://api.groq.com/openai") == "https://api.groq.com/openai/v1/chat/completions"
    )

    // Mock HTTP Interceptor for OpenAI
    val mockOpenAiClient = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        val auth = req.header("Authorization")
        if (auth == null || !auth.startsWith("Bearer test-key")) {
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{\"error\": {\"message\": \"Invalid API key\"}}".toResponseBody("application/json".toMediaType()))
                .build()
        } else {
            val responseJson = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\"translations\": [{\"id\": 1, \"text\": \"Xin chào thế giới\"}]}"
                      }
                    }
                  ]
                }
            """.trimIndent()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseJson.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }.build()

    val openAiClient = OpenAITranslatorClient(apiKey = "test-key", httpClient = mockOpenAiClient)
    val openAiResult = openAiClient.translateChunk(listOf(makeCue(1, "Hello world")), targetLang = "vi")
    check("OpenAI client executes translation and parses Structured Output",
        openAiResult.isSuccess && openAiResult.translations[1] == "Xin chào thế giới"
    )

    // Test Quota Exhaustion handling
    val mockOpenAiQuotaClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .body("{\"error\": {\"code\": \"insufficient_quota\", \"message\": \"You exceeded your current quota.\"}}".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val openAiQuotaResult = OpenAITranslatorClient(apiKey = "test-key", httpClient = mockOpenAiQuotaClient)
        .translateChunk(listOf(makeCue(1, "Hello world")), targetLang = "vi")
    check("OpenAI client detects insufficient_quota as hard quota exhaustion",
        !openAiQuotaResult.isSuccess && openAiQuotaResult.isQuotaExhausted && !openAiQuotaResult.retryable
    )

    // =========================================================================
    // SECTION 6: Gemini Client URL Builder, Prefix Stripping & Decoupling
    // =========================================================================
    println("\n--- [Section 6: Gemini Client URL Builder & Decoupling] ---")

    check("Gemini URL builder standard",
        GeminiTranslatorClient.buildGeminiUrl(null, "gemini-1.5-flash") == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    )
    check("Gemini URL builder strips 'models/' prefix",
        GeminiTranslatorClient.buildGeminiUrl(null, "models/gemini-1.5-flash") == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    )
    check("Gemini URL builder custom base ending with /v1beta",
        GeminiTranslatorClient.buildGeminiUrl("https://custom.gemini/v1beta", "gemini-1.5-pro") == "https://custom.gemini/v1beta/models/gemini-1.5-pro:generateContent"
    )

    // 15 RPM Rate Limit vs Daily Quota Decoupling
    val dailyPayload = """{"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "details": [{"violations": [{"description": "Quota exceeded for GenerateRequestsPerDay"}]}]}}"""
    val rpmPayload = """{"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "details": [{"violations": [{"description": "Quota exceeded for GenerateRequestsPerMinute"}]}]}}"""
    val genericPayload = """{"error": {"code": 429, "message": "Resource has been exhausted."}}"""

    check("Gemini detects GenerateRequestsPerDay as daily quota error",
        GeminiTranslatorClient.isDailyQuotaError(429, dailyPayload) && !GeminiTranslatorClient.isTransientRpmError(429, dailyPayload)
    )
    check("Gemini detects GenerateRequestsPerMinute as transient RPM error",
        !GeminiTranslatorClient.isDailyQuotaError(429, rpmPayload) && GeminiTranslatorClient.isTransientRpmError(429, rpmPayload)
    )
    check("Gemini treats generic 429 without PerDay as transient RPM error",
        !GeminiTranslatorClient.isDailyQuotaError(429, genericPayload) && GeminiTranslatorClient.isTransientRpmError(429, genericPayload)
    )

    // Mock Gemini Client
    val mockGeminiClient = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        check("Gemini client transmits API key via x-goog-api-key header",
            req.header("x-goog-api-key") == "gemini-key"
        )
        val geminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "[{\"id\": 1, \"text\": \"Chào bạn\"}]"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(geminiResponse.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val geminiClient = GeminiTranslatorClient(apiKey = "gemini-key", httpClient = mockGeminiClient)
    val geminiResult = geminiClient.translateChunk(listOf(makeCue(1, "Hi")), targetLang = "vi")
    check("Gemini client parses candidate content parts text",
        geminiResult.isSuccess && geminiResult.translations[1] == "Chào bạn"
    )

    // =========================================================================
    // SECTION 7: Anthropic Client URL Builder, Prefill & Credit Exhaustion
    // =========================================================================
    println("\n--- [Section 7: Anthropic Client URL Builder & Prefill] ---")

    check("Anthropic URL builder default",
        AnthropicTranslatorClient.buildAnthropicUrl(null) == "https://api.anthropic.com/v1/messages"
    )
    check("Anthropic URL builder appends /messages",
        AnthropicTranslatorClient.buildAnthropicUrl("https://api.anthropic.com/v1") == "https://api.anthropic.com/v1/messages"
    )

    // Credit Exhaustion detection
    val creditExhaustedBody = """{"type": "error", "error": {"type": "invalid_request_error", "message": "Your credit balance is too low to access the Claude API."}}"""
    check("Anthropic detects HTTP 400 credit balance is too low",
        AnthropicTranslatorClient.isCreditExhaustion(400, creditExhaustedBody)
    )
    check("Anthropic normal 400 is not credit exhaustion",
        !AnthropicTranslatorClient.isCreditExhaustion(400, "{\"type\": \"error\", \"error\": {\"message\": \"Bad prompt\"}}")
    )

    // Mock Anthropic Client with assistant prefill continuation (no leading '[')
    val mockAnthropicClient = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        check("Anthropic client sends x-api-key header", req.header("x-api-key") == "anthropic-key")
        check("Anthropic client sends anthropic-version header", req.header("anthropic-version") == "2023-06-01")
        check("Anthropic client sends prompt-caching header", req.header("anthropic-beta") == "prompt-caching-2024-07-31")

        // Claude continuation text starts after '['
        val anthropicResponse = """
            {
              "content": [
                {
                  "type": "text",
                  "text": "{\"id\": 1, \"text\": \"Xin chào từ Claude\"}]"
                }
              ]
            }
        """.trimIndent()

        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(anthropicResponse.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val anthropicClient = AnthropicTranslatorClient(apiKey = "anthropic-key", httpClient = mockAnthropicClient)
    val anthropicResult = anthropicClient.translateChunk(listOf(makeCue(1, "Hello from Claude")), targetLang = "vi")
    check("Anthropic client reconstructs assistant prefill continuation and parses JSON",
        anthropicResult.isSuccess && anthropicResult.translations[1] == "Xin chào từ Claude"
    )

    // =========================================================================
    // SECTION 8: AITranslatorFactory Dispatch
    // =========================================================================
    println("\n--- [Section 8: AITranslatorFactory Dispatch] ---")

    val c1 = AITranslatorFactory.createClient("openai", "k1")
    val c2 = AITranslatorFactory.createClient("gemini", "k2")
    val c3 = AITranslatorFactory.createClient("anthropic", "k3")
    val c4 = AITranslatorFactory.createClient("custom", "k4")

    check("Factory creates OpenAITranslatorClient for 'openai'", c1 is OpenAITranslatorClient)
    check("Factory creates GeminiTranslatorClient for 'gemini'", c2 is GeminiTranslatorClient)
    check("Factory creates AnthropicTranslatorClient for 'anthropic'", c3 is AnthropicTranslatorClient)
    check("Factory creates OpenAITranslatorClient for 'custom'", c4 is OpenAITranslatorClient)

    // =========================================================================
    // SUMMARY
    // =========================================================================
    println("\n================================================================================")
    println(">>> Phase 3 Test Results: $passed PASSED, $failed FAILED (Total ${passed + failed})")
    println("================================================================================")

    if (failed > 0) {
        throw AssertionError("Phase 3 Empirical Test Suite failed with $failed errors!")
    }
}
