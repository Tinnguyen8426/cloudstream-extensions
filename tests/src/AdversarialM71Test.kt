package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.client.*
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitleReconciler
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

fun main() = runBlocking {
    println("================================================================================")
    println(">>> RUNNING ADVERSARIAL M7-1 STRESS HARNESS: MULTI-PROVIDER AI CLIENTS")
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

    fun makeCue(index: Int, text: String) = SubtitleCue(
        index = index,
        startMs = index * 1000L,
        endMs = index * 1000L + 900L,
        rawText = text,
        maskedText = text
    )

    // =========================================================================
    // 1. OPENAI URL NORMALIZER ADVERSARIAL STRESS
    // =========================================================================
    println("\n--- [1. OpenAI URL Normalizer Stress] ---")

    val openAiCases = mapOf(
        null to "https://api.openai.com/v1/chat/completions",
        "" to "https://api.openai.com/v1/chat/completions",
        "   " to "https://api.openai.com/v1/chat/completions",
        "https://api.openai.com" to "https://api.openai.com/v1/chat/completions",
        "https://api.openai.com/" to "https://api.openai.com/v1/chat/completions",
        "https://api.openai.com/v1" to "https://api.openai.com/v1/chat/completions",
        "https://api.openai.com/v1/" to "https://api.openai.com/v1/chat/completions",
        "https://api.openai.com/v1/chat/completions" to "https://api.openai.com/v1/chat/completions",
        "https://openrouter.ai/api/v1" to "https://openrouter.ai/api/v1/chat/completions",
        "https://openrouter.ai/api/v1/" to "https://openrouter.ai/api/v1/chat/completions",
        "https://api.deepseek.com" to "https://api.deepseek.com/chat/completions",
        "https://api.deepseek.com/" to "https://api.deepseek.com/chat/completions",
        "https://api.deepseek.com/v1" to "https://api.deepseek.com/v1/chat/completions",
        "https://api.groq.com/openai" to "https://api.groq.com/openai/v1/chat/completions",
        "https://api.groq.com/openai/" to "https://api.groq.com/openai/v1/chat/completions",
        "https://api.groq.com/openai/v1" to "https://api.groq.com/openai/v1/chat/completions",
        "https://api.groq.com/openai/v1/" to "https://api.groq.com/openai/v1/chat/completions",
        "http://127.0.0.1:11434" to "http://127.0.0.1:11434/v1/chat/completions",
        "http://127.0.0.1:11434/v1" to "http://127.0.0.1:11434/v1/chat/completions"
    )

    for ((input, expected) in openAiCases) {
        val actual = OpenAITranslatorClient.normalizeOpenAiUrl(input)
        check("OpenAI URL: '$input'", actual == expected, "Expected: $expected, got: $actual")
    }

    // =========================================================================
    // 2. GEMINI URL BUILDER ADVERSARIAL STRESS
    // =========================================================================
    println("\n--- [2. Gemini URL Builder Stress] ---")

    val geminiCases = listOf(
        Triple(null, "gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple("", "gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple("   ", "gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple(null, "models/gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple(null, "  models/gemini-1.5-flash  ", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple(null, "models/gemini-1.5-pro", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent"),
        Triple(null, "", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple("https://generativelanguage.googleapis.com", "gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple("https://generativelanguage.googleapis.com/", "gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple("https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple("https://generativelanguage.googleapis.com/v1beta/", "gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple("https://generativelanguage.googleapis.com/v1beta/", "models/gemini-1.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"),
        Triple("https://custom.gemini.proxy/v1beta", "gemini-exp", "https://custom.gemini.proxy/v1beta/models/gemini-exp:generateContent")
    )

    for ((base, model, expected) in geminiCases) {
        val actual = GeminiTranslatorClient.buildGeminiUrl(base, model)
        check("Gemini URL: base='$base', model='$model'", actual == expected, "Expected: $expected, got: $actual")
    }

    // =========================================================================
    // 3. ANTHROPIC URL BUILDER ADVERSARIAL STRESS
    // =========================================================================
    println("\n--- [3. Anthropic URL Builder Stress] ---")

    val anthropicCases = mapOf(
        null to "https://api.anthropic.com/v1/messages",
        "" to "https://api.anthropic.com/v1/messages",
        "   " to "https://api.anthropic.com/v1/messages",
        "https://api.anthropic.com/v1" to "https://api.anthropic.com/v1/messages",
        "https://api.anthropic.com/v1/" to "https://api.anthropic.com/v1/messages",
        "https://api.anthropic.com/v1/messages" to "https://api.anthropic.com/v1/messages",
        "https://custom.anthropic.proxy" to "https://custom.anthropic.proxy/messages",
        "https://custom.anthropic.proxy/" to "https://custom.anthropic.proxy/messages"
    )

    for ((input, expected) in anthropicCases) {
        val actual = AnthropicTranslatorClient.buildAnthropicUrl(input)
        check("Anthropic URL: '$input'", actual == expected, "Expected: $expected, got: $actual")
    }

    // =========================================================================
    // 4. GEMINI 15 RPM DECOUPLING LIVE RETRY HARNESS
    // =========================================================================
    println("\n--- [4. Gemini 15 RPM vs Daily Quota Decoupling Live Harness] ---")

    // Test 4A: Hard Daily Quota Exhaustion -> Immediate Circuit Breaker (Zero retry delays)
    val dailyCallCount = AtomicInteger(0)
    val mockDailyClient = OkHttpClient.Builder().addInterceptor { chain ->
        dailyCallCount.incrementAndGet()
        val dailyJson = """
            {
              "error": {
                "code": 429,
                "status": "RESOURCE_EXHAUSTED",
                "message": "Resource has been exhausted (e.g. check quota).",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                      {
                        "subject": "project:12345",
                        "description": "Quota exceeded for quota metric 'GenerateRequestsPerDay' of service 'generativelanguage.googleapis.com'"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .body(dailyJson.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val t0 = System.currentTimeMillis()
    val geminiDailyClient = GeminiTranslatorClient("fake-key", httpClient = mockDailyClient)
    val dailyResult = geminiDailyClient.translateChunk(listOf(makeCue(1, "Test")), targetLang = "vi")
    val elapsedDaily = System.currentTimeMillis() - t0

    check("Daily quota immediately aborts with success=false", !dailyResult.isSuccess)
    check("Daily quota flagged as isQuotaExhausted=true", dailyResult.isQuotaExhausted)
    check("Daily quota marked retryable=false", !dailyResult.retryable)
    check("Daily quota stops at exactly 1 attempt (no retry waste)", dailyCallCount.get() == 1, "Calls: ${dailyCallCount.get()}")
    check("Daily quota returns immediately (< 1000ms)", elapsedDaily < 1000, "Elapsed: ${elapsedDaily}ms")

    // Test 4B: Transient 15 RPM error detection helper
    val rpmViolationJson = """{"error": {"code": 429, "details": [{"violations": [{"description": "Quota exceeded for GenerateRequestsPerMinute"}]}]}}"""
    check("Transient RPM violation detected as NOT daily quota",
        !GeminiTranslatorClient.isDailyQuotaError(429, rpmViolationJson)
    )
    check("Transient RPM violation detected as transient RPM error",
        GeminiTranslatorClient.isTransientRpmError(429, rpmViolationJson)
    )

    // =========================================================================
    // 5. ANTHROPIC HTTP 400 CREDIT EXHAUSTION DETECTION
    // =========================================================================
    println("\n--- [5. Anthropic HTTP 400 Credit Exhaustion Stress] ---")

    val anthropicCreditCases = listOf(
        Pair(400, "{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", \"message\": \"Your credit balance is too low to access the Claude API. Please go to Plans & Billing to upgrade or purchase credits.\"}}") to true,
        Pair(400, "{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", \"message\": \"credit balance is too low\"}}") to true,
        Pair(400, "{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", \"message\": \"Invalid model name claude-99\"}}") to false,
        Pair(400, "{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", \"message\": \"max_tokens: must be > 0\"}}") to false,
        Pair(429, "{\"type\": \"error\", \"error\": {\"message\": \"Rate limit exceeded\"}}") to false,
        Pair(500, "{\"type\": \"error\", \"error\": {\"message\": \"Internal Server Error\"}}") to false
    )

    for (case in anthropicCreditCases) {
        val code = case.first.first
        val body = case.first.second
        val expected = case.second
        val actual = AnthropicTranslatorClient.isCreditExhaustion(code, body)
        check("Anthropic credit exhaustion (code $code, snippet '${body.take(45)}...')", actual == expected)
    }

    // Live Anthropic client test with HTTP 400 credit exhaustion
    val mockAnthropicCreditClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body("{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", \"message\": \"Your credit balance is too low to access the Claude API.\"}}".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val anthropicCreditClient = AnthropicTranslatorClient("fake-key", httpClient = mockAnthropicCreditClient)
    val anthropicCreditResult = anthropicCreditClient.translateChunk(listOf(makeCue(1, "Test")), targetLang = "vi")
    check("Anthropic client classifies credit exhaustion as isQuotaExhausted=true", anthropicCreditResult.isQuotaExhausted)
    check("Anthropic client marks credit exhaustion as retryable=false", !anthropicCreditResult.retryable)
    check("Anthropic client returns success=false", !anthropicCreditResult.isSuccess)

    // =========================================================================
    // 6. POLYMORPHIC PARSER ADVERSARIAL CASES
    // =========================================================================
    println("\n--- [6. Polymorphic Deserializer Adversarial Stress] ---")

    // Case A: Missing leading bracket but valid JSON content
    val brokenBracket = "{\"id\": 1, \"text\": \"Dòng 1\"}, {\"id\": 2, \"text\": \"Dòng 2\"}]"
    val parseA = AITranslationParser.parseTranslations(brokenBracket)
    check("Anthropic continuation missing bracket parses correctly",
        parseA == mapOf(1 to "Dòng 1", 2 to "Dòng 2")
    )

    // Case B: Embedded JSON in surrounding explanation
    val chatter = "Here are the translated subtitle items you requested:\n```json\n[{\"id\": 1, \"text\": \"Chào\"}]\n```\nLet me know if you need anything else!"
    val parseB = AITranslationParser.parseTranslations(chatter)
    check("Markdown fenced array inside chatter parses correctly",
        parseB == mapOf(1 to "Chào")
    )

    // Case C: OpenAI format inside conversational text
    val openAiChatter = "Done!\n{\"translations\": [{\"id\": 1, \"text\": \"Chào bạn\"}]}\nEnjoy the movie!"
    val parseC = AITranslationParser.parseTranslations(openAiChatter)
    check("OpenAI object wrapper inside text parses correctly",
        parseC == mapOf(1 to "Chào bạn")
    )

    // Case D: Delimiter format with whitespace variations
    val delim = "<<<1>>>   \n   Xin chao   \n<<<2>>>\n   Tam biet   "
    val parseD = AITranslationParser.parseTranslations(delim)
    check("Delimiter format with padded whitespace parses correctly",
        parseD == mapOf(1 to "Xin chao", 2 to "Tam biet")
    )

    // =========================================================================
    // 7. AUTO-HEALING RECONCILER STRESS
    // =========================================================================
    println("\n--- [7. Auto-Healing Reconciler Adversarial Stress] ---")

    // Test 7A: Global cues 101..130, model reset to 1..30
    val chunk101 = (101..130).map { makeCue(it, "Raw English $it") }
    val modelOutput101 = (1..30).associateWith { "Vietnamese $it" }
    val reconciled101 = SubtitleReconciler.autoHealingReconcile(chunk101, modelOutput101)
    check("Chunk 101..130 healed from 1..30 re-indexing",
        reconciled101.size == 30 && (101..130).all { reconciled101[it] == "Vietnamese ${it - 100}" }
    )

    // Test 7B: Empty inputs
    check("Empty input cues return empty map",
        SubtitleReconciler.autoHealingReconcile(emptyList(), modelOutput101).isEmpty()
    )

    // =========================================================================
    // SUMMARY
    // =========================================================================
    println("\n================================================================================")
    println(">>> ADVERSARIAL STRESS TEST COMPLETE: $passed PASSED, $failed FAILED (Total ${passed + failed})")
    println("================================================================================")

    if (failed > 0) {
        throw AssertionError("Adversarial Stress Test failed with $failed errors!")
    }
}
