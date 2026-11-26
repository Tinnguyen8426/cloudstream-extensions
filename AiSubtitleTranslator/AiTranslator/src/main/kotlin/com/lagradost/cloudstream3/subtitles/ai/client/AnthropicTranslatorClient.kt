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
import java.util.Locale

/**
 * AI subtitle translation client for Anthropic Messages API and Claude models.
 *
 * Implements:
 * 1. Base URL normalization defaulting to `https://api.anthropic.com/v1/messages`.
 * 2. Unified assistant prefill (`{"translations": [`) with backwards-compatible continuation reconstruction.
 * 3. Conditional `cache_control` ephemeral prompt caching on official `api.anthropic.com`.
 * 4. Specific detection of HTTP 400 credit balance exhaustion (`isCreditExhaustion`).
 * 5. 0ms cancellable OkHttp calls and Pass 0 `generateJsonPrompt`.
 */
class AnthropicTranslatorClient(
    val apiKey: String,
    val baseUrl: String? = null,
    val modelName: String = "claude-3-5-haiku-20241022",
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

        val endpointUrl = buildAnthropicUrl(baseUrl)
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

        val systemField: Any = if (endpointUrl.contains("api.anthropic.com", ignoreCase = true)) {
            listOf(
                mapOf(
                    "type" to "text",
                    "text" to systemPrompt,
                    "cache_control" to mapOf("type" to "ephemeral")
                )
            )
        } else {
            systemPrompt
        }

        // Unified Anthropic Messages API payload with Assistant Prefill
        val prefillContent = "{\"translations\": ["
        val requestMap = mapOf(
            "model" to modelName.trim().ifEmpty { "claude-3-5-haiku-20241022" },
            "max_tokens" to 8192,
            "temperature" to 0.3,
            "system" to systemField,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to "Translate the following subtitle dialogue cues into $targetLang:\n$userContentJson"
                ),
                mapOf(
                    "role" to "assistant",
                    "content" to prefillContent
                )
            )
        )

        val jsonBody = mapper.writeValueAsString(requestMap)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .post(requestBody)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "prompt-caching-2024-07-31")

        if (apiKey.isNotBlank()) {
            reqBuilder.header("x-api-key", apiKey.trim())
        }

        try {
            val response = executeCancellableCall(client, reqBuilder.build())
            val code = response.code
            val responseBody = response.body

            if (response.isSuccessful) {
                val root = mapper.readTree(responseBody)
                val rawContent = root.get("content")?.get(0)?.get("text")?.takeIf { it.isTextual }?.asText() ?: responseBody

                // Reconstruct JSON payload from assistant prefill (backwards-compatible with ']' and ']}')
                val trimmed = rawContent.trim()
                val completeJson = when {
                    trimmed.startsWith("{\"translations\"") || trimmed.startsWith("[") -> trimmed
                    trimmed.startsWith("{") && trimmed.endsWith("]}") -> "$prefillContent$trimmed"
                    trimmed.startsWith("{") && trimmed.endsWith("]") -> trimmed
                    else -> "$prefillContent$trimmed"
                }

                val translations = AITranslationParser.parseTranslations(completeJson)

                return@withContext TranslationResult(
                    translations = translations,
                    rawResponse = completeJson,
                    success = true,
                    discoveredCharacters = null
                )
            }

            // Error Classification
            when {
                isCreditExhaustion(code, responseBody) -> {
                    TranslationResult(
                        success = false,
                        error = "Anthropic Credit Exhausted: $responseBody",
                        isQuotaExhausted = true,
                        retryable = false
                    )
                }
                code == 429 -> {
                    TranslationResult(
                        success = false,
                        error = "Anthropic Rate Limit Exceeded: $responseBody",
                        isQuotaExhausted = false,
                        retryable = true
                    )
                }
                code == 401 -> {
                    TranslationResult(
                        success = false,
                        error = "Anthropic Unauthorized (Invalid API Key): $responseBody",
                        isQuotaExhausted = true,
                        retryable = false
                    )
                }
                code in 500..599 -> {
                    TranslationResult(
                        success = false,
                        error = "Anthropic Server Error ($code): $responseBody",
                        isQuotaExhausted = false,
                        retryable = true
                    )
                }
                else -> {
                    TranslationResult(
                        success = false,
                        error = "Anthropic API Error ($code): $responseBody",
                        isQuotaExhausted = false,
                        retryable = false
                    )
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            TranslationResult(
                success = false,
                error = "Anthropic Request Exception: ${e.message}",
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

        val endpointUrl = buildAnthropicUrl(baseUrl)
        val systemField: Any = if (endpointUrl.contains("api.anthropic.com", ignoreCase = true)) {
            listOf(
                mapOf(
                    "type" to "text",
                    "text" to systemPrompt,
                    "cache_control" to mapOf("type" to "ephemeral")
                )
            )
        } else {
            systemPrompt
        }

        val requestMap = mapOf(
            "model" to modelName.trim().ifEmpty { "claude-3-5-haiku-20241022" },
            "max_tokens" to 2048,
            "temperature" to 0.2,
            "system" to systemField,
            "messages" to listOf(
                mapOf("role" to "user", "content" to userPrompt),
                mapOf("role" to "assistant", "content" to "{")
            )
        )

        val jsonBody = mapper.writeValueAsString(requestMap)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .post(requestBody)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "prompt-caching-2024-07-31")

        if (apiKey.isNotBlank()) {
            reqBuilder.header("x-api-key", apiKey.trim())
        }

        try {
            val response = executeCancellableCall(client.withShortTimeout(timeoutSeconds), reqBuilder.build())
            if (!response.isSuccessful) return@withContext null
            val root = mapper.readTree(response.body)
            val rawContent = if (root.has("content")) {
                root.get("content")?.get(0)?.get("text")?.takeIf { it.isTextual }?.asText()
                    ?: return@withContext null
            } else {
                response.body
            }
            val trimmed = rawContent.trim()
            if (trimmed.isEmpty()) return@withContext null
            if (trimmed.startsWith("{")) trimmed else "{$trimmed"
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
         * Normalizes user-specified base URLs into fully-qualified Anthropic messages endpoint.
         */
        fun buildAnthropicUrl(baseUrl: String?): String {
            val raw = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return "https://api.anthropic.com/v1/messages"
            val clean = raw.removeSuffix("/")
            return when {
                clean.endsWith("/messages") -> clean
                clean.equals("https://api.anthropic.com", ignoreCase = true) -> "$clean/v1/messages"
                else -> "$clean/messages"
            }
        }

        /**
         * Identifies whether an HTTP 400 response indicates Anthropic credit exhaustion.
         */
        fun isCreditExhaustion(code: Int, body: String): Boolean {
            return code == 400 && body.lowercase(Locale.ROOT).contains("credit balance is too low")
        }
    }
}
