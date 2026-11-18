package com.lagradost.cloudstream3.subtitles.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.ContextCue
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AI subtitle translation client for Anthropic Messages API and Claude models.
 *
 * Implements:
 * 1. Base URL normalization defaulting to `https://api.anthropic.com/v1/messages`.
 * 2. Assistant prefill technique to constrain Claude to output pure JSON.
 * 3. Array prefix reconstruction for deserializing assistant continuation.
 * 4. Specific detection of HTTP 400 credit balance exhaustion ("credit balance is too low")
 *    preventing unhandled crashes and classifying as hard quota exhaustion.
 * 5. Prompt caching header (`anthropic-beta: prompt-caching-2024-07-31`).
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
    ): TranslationResult = translateChunk(
        cues = cues,
        sourceLang = sourceLang,
        targetLang = targetLang,
        customGlossary = customGlossary,
        lookbehind = emptyList(),
        lookahead = emptyList(),
        characterRegistry = null,
        mediaContext = null
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
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (cues.isEmpty()) {
            return@withContext TranslationResult(emptyMap(), null, true)
        }

        pacingGovernor?.pace()

        val endpointUrl = buildAnthropicUrl(baseUrl)
        val isBootstrap = AITranslatorPromptBuilder.isBootstrap(lookbehind, characterRegistry)
        val systemPrompt = AITranslatorPromptBuilder.buildSystemPrompt(targetLang, characterRegistry, customGlossary, mediaContext)
        val userContentJson = AITranslatorPromptBuilder.buildUserPayload(cues, lookbehind, lookahead)

        // Anthropic Messages API payload with Assistant Prefill
        val prefillContent = if (isBootstrap) "{\"translations\": [" else "["
        val requestMap = mapOf(
            "model" to modelName.trim().ifEmpty { "claude-3-5-haiku-20241022" },
            "max_tokens" to 4096,
            "temperature" to 0.3,
            "system" to systemPrompt,
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
            client.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val root = mapper.readTree(responseBody)
                    val rawContent = root.get("content")?.get(0)?.get("text")?.asText() ?: responseBody

                    // Reconstruct JSON payload from assistant prefill
                    val completeJson = when {
                        rawContent.trimStart().startsWith("{") || rawContent.trimStart().startsWith("[") -> rawContent
                        isBootstrap -> "$prefillContent$rawContent"
                        else -> "[$rawContent"
                    }

                    val translations = AITranslationParser.parseTranslations(completeJson)
                    val discovered = if (isBootstrap) AITranslationParser.parseDiscoveredCharacters(completeJson) else null

                    return@withContext TranslationResult(
                        translations = translations,
                        rawResponse = completeJson,
                        success = true,
                        discoveredCharacters = discovered
                    )
                }

                // Error Classification
                val lowerBody = responseBody.lowercase(Locale.ROOT)
                when {
                    code == 400 && lowerBody.contains("credit balance is too low") -> {
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
            }
        } catch (e: Throwable) {
            TranslationResult(
                success = false,
                error = "Anthropic Request Exception: ${e.message}",
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
         * Normalizes user-specified base URLs into fully-qualified Anthropic messages endpoint.
         */
        fun buildAnthropicUrl(baseUrl: String?): String {
            val clean = (baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://api.anthropic.com").removeSuffix("/")
            return when {
                clean.endsWith("/messages") -> clean
                clean.endsWith("/v1") -> "$clean/messages"
                !clean.contains("/v1") -> "$clean/v1/messages"
                else -> "$clean/messages"
            }
        }
    }
}
