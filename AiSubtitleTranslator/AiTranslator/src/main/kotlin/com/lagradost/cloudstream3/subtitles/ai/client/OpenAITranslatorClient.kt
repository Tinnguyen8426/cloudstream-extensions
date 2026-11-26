package com.lagradost.cloudstream3.subtitles.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.ContextCue
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI subtitle translation client for OpenAI and OpenAI-compatible REST endpoints.
 *
 * Implements:
 * 1. OpenAI Structured Outputs via unified lean `json_schema` `{"translations":[{"id":<int>,"text":"<string>"}]}`.
 * 2. Universal base URL normalization supporting OpenAI (`/v1`), OpenRouter, DeepSeek, Groq (`/openai/v1`),
 *    and custom local endpoints (Ollama, LocalAI).
 * 3. Quota exhaustion detection (HTTP 429 + `insufficient_quota`) vs transient rate limits.
 * 4. Header-based Bearer authentication, 0ms cancellable OkHttp calls, and Pass 0 `generateJsonPrompt`.
 */
class OpenAITranslatorClient(
    val apiKey: String,
    val baseUrl: String? = null,
    val modelName: String = "gpt-4o-mini",
    val httpClient: OkHttpClient? = null,
    val pacingGovernor: PacingGovernor? = null
) : AITranslationClient {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val client: OkHttpClient by lazy {
        buildAiOkHttpClient(httpClient)
    }

    override suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
        customGlossary: String?
    ): TranslationResult = translateChunkWithSceneDigest(
        cues = cues,
        sourceLang = sourceLang,
        targetLang = targetLang,
        customGlossary = customGlossary,
        lookbehind = emptyList(),
        lookahead = emptyList(),
        characterRegistry = null,
        mediaContext = null,
        rollingSceneDigest = null
    )

    override suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
        customGlossary: String?,
        lookbehind: List<ContextCue>,
        lookahead: List<ContextCue>,
        characterRegistry: TwoTierCharacterRegistry?,
        mediaContext: MediaContext?
    ): TranslationResult = translateChunkWithSceneDigest(
        cues = cues,
        sourceLang = sourceLang,
        targetLang = targetLang,
        customGlossary = customGlossary,
        lookbehind = lookbehind,
        lookahead = lookahead,
        characterRegistry = characterRegistry,
        mediaContext = mediaContext,
        rollingSceneDigest = null
    )

    override suspend fun translateChunkWithSceneDigest(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
        customGlossary: String?,
        lookbehind: List<ContextCue>,
        lookahead: List<ContextCue>,
        characterRegistry: TwoTierCharacterRegistry?,
        mediaContext: MediaContext?,
        rollingSceneDigest: String?
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (cues.isEmpty()) {
            return@withContext TranslationResult(emptyMap(), null, true)
        }

        pacingGovernor?.pace()

        val endpointUrl = normalizeOpenAiUrl(baseUrl)
        val sampleText = cues.firstOrNull()?.rawText ?: ""
        val systemPrompt = AITranslatorPromptBuilder.buildSystemPrompt(
            targetLang = targetLang,
            characterRegistry = characterRegistry,
            customGlossary = customGlossary,
            mediaContext = mediaContext,
            sourceLang = sourceLang,
            sampleText = sampleText
        )
        val userContentJson = AITranslatorPromptBuilder.buildUserPayload(
            cues = cues,
            lookbehind = lookbehind,
            lookahead = lookahead,
            rollingSceneDigest = rollingSceneDigest
        )

        // Unified lean OpenAI Structured Outputs JSON schema payload
        val responseFormat = mapOf(
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
                                "properties" to linkedMapOf(
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
        )

        val requestMap = mapOf(
            "model" to modelName.trim().ifEmpty { "gpt-4o-mini" },
            "temperature" to 0.3,
            "top_p" to 0.95,
            "max_tokens" to 8192,
            "response_format" to responseFormat,
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
            val response = executeCancellableCall(client, reqBuilder.build())
            val code = response.code
            val responseBody = response.body

            if (response.isSuccessful) {
                val root = mapper.readTree(responseBody)
                val rawContent = root.get("choices")?.get(0)?.get("message")?.get("content")?.takeIf { it.isTextual }?.asText() ?: responseBody
                val translations = AITranslationParser.parseTranslations(rawContent)
                return@withContext TranslationResult(
                    translations = translations,
                    rawResponse = rawContent,
                    success = true,
                    discoveredCharacters = null
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
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            TranslationResult(
                success = false,
                error = "OpenAI Request Exception: ${e.message}",
                isQuotaExhausted = false,
                retryable = true
            )
        }
    }

    override suspend fun generateJsonPrompt(
        systemPrompt: String,
        userPrompt: String,
        timeoutSeconds: Long
    ): String? = withContext(Dispatchers.IO) {
        pacingGovernor?.pace()

        val endpointUrl = normalizeOpenAiUrl(baseUrl)
        val requestMap = mapOf(
            "model" to modelName.trim().ifEmpty { "gpt-4o-mini" },
            "temperature" to 0.2,
            "max_tokens" to 2048,
            "response_format" to mapOf("type" to "json_object"),
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
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
            val response = executeCancellableCall(client.withShortTimeout(timeoutSeconds), reqBuilder.build())
            if (!response.isSuccessful) return@withContext null
            val root = mapper.readTree(response.body)
            val rawContent = if (root.has("choices")) {
                root.get("choices")?.get(0)?.get("message")?.get("content")?.takeIf { it.isTextual }?.asText()
                    ?: return@withContext null
            } else {
                response.body
            }
            rawContent.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
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
