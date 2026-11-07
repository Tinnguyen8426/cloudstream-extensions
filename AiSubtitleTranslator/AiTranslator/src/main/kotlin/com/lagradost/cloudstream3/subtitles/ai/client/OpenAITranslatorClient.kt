package com.lagradost.cloudstream3.subtitles.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI subtitle translation client for OpenAI and OpenAI-compatible REST endpoints.
 *
 * Implements:
 * 1. OpenAI Structured Outputs via `json_schema` response_format (`name: "subtitle_translations"`).
 * 2. Universal base URL normalization supporting OpenAI (`/v1`), OpenRouter, DeepSeek, Groq (`/openai/v1`),
 *    and custom local endpoints (Ollama, LocalAI).
 * 3. Quota exhaustion detection (HTTP 429 + `insufficient_quota`) vs transient rate limits.
 * 4. Header-based Bearer authentication.
 */
class OpenAITranslatorClient(
    val apiKey: String,
    val baseUrl: String? = null,
    val modelName: String = "gpt-4o-mini",
    val httpClient: OkHttpClient? = null
) : AITranslationClient {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val client: OkHttpClient by lazy {
        httpClient ?: try {
            com.lagradost.cloudstream3.app.baseClient
        } catch (_: Throwable) {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
        customGlossary: String?
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (cues.isEmpty()) {
            return@withContext TranslationResult(emptyMap(), null, true)
        }

        val endpointUrl = normalizeOpenAiUrl(baseUrl)
        val glossaryPrompt = if (!customGlossary.isNullOrBlank()) {
            " Strictly adhere to the following glossary terms:\n$customGlossary\n"
        } else ""

        val systemPrompt = "You are an expert subtitle translator. Translate each dialogue entry accurately into the target language ($targetLang). " +
                "Preserve all special tag shields (e.g. [[TAG_0]], [[TAG_1]]) exactly as formatted without modification, reordering, or omission. " +
                "Output a valid JSON object with key 'translations' containing an array of objects, each having integer 'id' and string 'text'.$glossaryPrompt"

        val dialogueList = cues.map { cue ->
            mapOf(
                "id" to cue.index,
                "text" to if (cue.maskedText.isNotBlank()) cue.maskedText else cue.rawText
            )
        }
        val userContentJson = mapper.writeValueAsString(dialogueList)

        // Construct OpenAI Structured Outputs JSON schema payload
        val requestMap = mapOf(
            "model" to modelName.trim().ifEmpty { "gpt-4o-mini" },
            "temperature" to 0.3,
            "top_p" to 0.95,
            "max_tokens" to 4096,
            "response_format" to mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "subtitle_translations",
                    "strict" to true,
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "translations" to mapOf(
                                "type" to "array",
                                "items" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "id" to mapOf("type" to "integer"),
                                        "text" to mapOf("type" to "string")
                                    ),
                                    "required" to listOf("id", "text"),
                                    "additionalProperties" to false
                                )
                            )
                        ),
                        "required" to listOf("translations"),
                        "additionalProperties" to false
                    )
                )
            ),
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userContentJson)
            )
        )

        val jsonBody = mapper.writeValueAsString(requestMap)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .post(requestBody)
            .header("Content-Type", "application/json; charset=utf-8")

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val root = mapper.readTree(responseBody)
                    val rawContent = root.get("choices")?.get(0)?.get("message")?.get("content")?.asText() ?: responseBody
                    val translations = AITranslationParser.parseTranslations(rawContent)
                    return@withContext TranslationResult(
                        translations = translations,
                        rawResponse = rawContent,
                        success = true
                    )
                }

                // Error Classification
                val lowerBody = responseBody.lowercase()
                when {
                    code == 429 && (lowerBody.contains("insufficient_quota") || lowerBody.contains("quota_exceeded")) -> {
                        TranslationResult(
                            success = false,
                            error = "OpenAI Quota Exhausted: $responseBody",
                            isQuotaExhausted = true,
                            retryable = false
                        )
                    }
                    code == 429 -> {
                        TranslationResult(
                            success = false,
                            error = "OpenAI Rate Limit Exceeded: $responseBody",
                            isQuotaExhausted = false,
                            retryable = true
                        )
                    }
                    code == 401 -> {
                        TranslationResult(
                            success = false,
                            error = "OpenAI Unauthorized (Invalid API Key): $responseBody",
                            isQuotaExhausted = true,
                            retryable = false
                        )
                    }
                    code in 500..599 -> {
                        TranslationResult(
                            success = false,
                            error = "OpenAI Server Error ($code): $responseBody",
                            isQuotaExhausted = false,
                            retryable = true
                        )
                    }
                    else -> {
                        TranslationResult(
                            success = false,
                            error = "OpenAI API Error ($code): $responseBody",
                            isQuotaExhausted = false,
                            retryable = false
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            TranslationResult(
                success = false,
                error = "OpenAI Request Exception: ${e.message}",
                isQuotaExhausted = false,
                retryable = true
            )
        }
    }

    override suspend fun ping(): TranslationResult {
        val testCue = SubtitleCue(
            index = 1,
            startMs = 0,
            endMs = 1000,
            rawText = "Hello",
            maskedText = "Hello"
        )
        return translateChunk(listOf(testCue), targetLang = "vi")
    }

    companion object {
        /**
         * Normalizes user-specified base URLs into fully-qualified chat completion endpoints.
         */
        fun normalizeOpenAiUrl(baseUrl: String?): String {
            val clean = (baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1").removeSuffix("/")
            return when {
                clean.endsWith("/chat/completions") -> clean
                clean.endsWith("/v1") -> "$clean/chat/completions"
                clean.contains("deepseek.com") && !clean.endsWith("/v1") -> "$clean/chat/completions"
                clean.contains("/openai") && !clean.endsWith("/v1") -> "$clean/v1/chat/completions"
                !clean.contains("/v1") -> "$clean/v1/chat/completions"
                else -> "$clean/chat/completions"
            }
        }
    }
}
