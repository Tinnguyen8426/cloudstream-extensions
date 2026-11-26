package com.lagradost.cloudstream3.subtitles.ai.client

import com.fasterxml.jackson.databind.JsonNode
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
 * AI subtitle translation client for Google AI Studio (Gemini REST API v1beta).
 *
 * Implements:
 * 1. Endpoint construction with prefix stripping to eliminate `models/models/...` 404 defects.
 * 2. Header-based authentication via `x-goog-api-key`.
 * 3. Permissive safety settings (`BLOCK_NONE`) across all 4 categories to prevent movie subtitle drops.
 * 4. Unified lean structured output schema `{"translations":[{"id":<int>,"text":"<string>"}]}` with
 *    default `thinkingConfig` preserved and `"thought": true` parts filtered out.
 * 5. Single-attempt execution with 0ms cancellable OkHttp calls and 15 RPM vs daily quota decoupling.
 * 6. Pass 0 lightweight JSON prompt execution via `generateJsonPrompt`.
 */
class GeminiTranslatorClient(
    val apiKey: String,
    val baseUrl: String? = null,
    val modelName: String = "gemini-1.5-flash",
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

        val endpointUrl = buildGeminiUrl(baseUrl, modelName)
        val sampleText = cues.firstOrNull()?.rawText ?: ""
        val systemInstruction = AITranslatorPromptBuilder.buildSystemPrompt(
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

        val responseSchema = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "translations" to mapOf(
                    "type" to "ARRAY",
                    "items" to mapOf(
                        "type" to "OBJECT",
                        "properties" to linkedMapOf(
                            "id" to mapOf("type" to "INTEGER"),
                            "text" to mapOf("type" to "STRING")
                        ),
                        "required" to listOf("id", "text")
                    )
                )
            ),
            "required" to listOf("translations")
        )

        // Native Gemini REST v1beta payload (thinkingConfig omitted intentionally to preserve model defaults)
        val requestMap = mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemInstruction))
            ),
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to userContentJson))
                )
            ),
            "safetySettings" to listOf(
                mapOf("category" to "HARM_CATEGORY_HARASSMENT", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_HATE_SPEECH", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold" to "BLOCK_NONE")
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.3,
                "topP" to 0.95,
                "maxOutputTokens" to 8192,
                "responseMimeType" to "application/json",
                "responseSchema" to responseSchema
            )
        )

        val jsonBody = mapper.writeValueAsString(requestMap)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .post(requestBody)
            .header("Content-Type", "application/json; charset=utf-8")

        if (apiKey.isNotBlank()) {
            reqBuilder.header("x-goog-api-key", apiKey.trim())
        }

        try {
            val response = executeCancellableCall(client, reqBuilder.build())
            val code = response.code
            val responseBody = response.body

            if (response.isSuccessful) {
                val root = mapper.readTree(responseBody)
                val text = extractNonThoughtText(root, responseBody)
                val translations = AITranslationParser.parseTranslations(text)
                return@withContext TranslationResult(
                    translations = translations,
                    rawResponse = text,
                    success = true,
                    discoveredCharacters = null
                )
            }

            if (code == 429) {
                if (isDailyQuotaError(code, responseBody)) {
                    return@withContext TranslationResult(
                        success = false,
                        error = "Gemini Daily Quota Exhausted: $responseBody",
                        isQuotaExhausted = true,
                        retryable = false
                    )
                }
                return@withContext TranslationResult(
                    success = false,
                    error = "Gemini 15 RPM rate limit exceeded: $responseBody",
                    isQuotaExhausted = false,
                    retryable = true
                )
            } else if (code == 400 && responseBody.contains("API_KEY_INVALID", ignoreCase = true)) {
                return@withContext TranslationResult(
                    success = false,
                    error = "Gemini Invalid API Key: $responseBody",
                    isQuotaExhausted = true,
                    retryable = false
                )
            } else if (code in 500..599) {
                return@withContext TranslationResult(
                    success = false,
                    error = "Gemini Server Error ($code): $responseBody",
                    isQuotaExhausted = false,
                    retryable = true
                )
            } else {
                return@withContext TranslationResult(
                    success = false,
                    error = "Gemini API Error ($code): $responseBody",
                    isQuotaExhausted = false,
                    retryable = false
                )
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            TranslationResult(
                success = false,
                error = "Gemini Connection Exception: ${e.message}",
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

        val endpointUrl = buildGeminiUrl(baseUrl, modelName)
        val requestMap = mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemPrompt))
            ),
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to userPrompt))
                )
            ),
            "safetySettings" to listOf(
                mapOf("category" to "HARM_CATEGORY_HARASSMENT", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_HATE_SPEECH", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold" to "BLOCK_NONE")
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.2,
                "maxOutputTokens" to 2048,
                "responseMimeType" to "application/json"
            )
        )

        val jsonBody = mapper.writeValueAsString(requestMap)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .post(requestBody)
            .header("Content-Type", "application/json; charset=utf-8")

        if (apiKey.isNotBlank()) {
            reqBuilder.header("x-goog-api-key", apiKey.trim())
        }

        try {
            val response = executeCancellableCall(client.withShortTimeout(timeoutSeconds), reqBuilder.build())
            if (!response.isSuccessful) return@withContext null
            val root = mapper.readTree(response.body)
            extractNonThoughtText(root, response.body).takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun extractNonThoughtText(root: JsonNode, fallback: String): String {
        val partsNode = root.get("candidates")?.get(0)?.get("content")?.get("parts")
        if (partsNode == null || !partsNode.isArray) {
            return if (root.has("candidates")) "" else fallback
        }
        val sb = StringBuilder()
        for (i in 0 until partsNode.size()) {
            val part = partsNode.get(i) ?: continue
            if (part.get("thought")?.asBoolean(false) == true) continue
            val partText = part.get("text")?.takeIf { it.isTextual }?.asText()
            if (!partText.isNullOrEmpty()) sb.append(partText)
        }
        return sb.toString()
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
         * Builds the Gemini generateContent REST URL, stripping any erroneous 'models/' prefix.
         */
        fun buildGeminiUrl(baseUrl: String?, model: String): String {
            val cleanBase = (baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://generativelanguage.googleapis.com").removeSuffix("/")
            val cleanModel = model.trim().removePrefix("models/").ifEmpty { "gemini-1.5-flash" }
            return if (cleanBase.endsWith("/v1beta")) {
                "$cleanBase/models/$cleanModel:generateContent"
            } else {
                "$cleanBase/v1beta/models/$cleanModel:generateContent"
            }
        }

        /**
         * Identifies whether a 429 response signifies hard daily quota exhaustion.
         */
        fun isDailyQuotaError(code: Int, body: String): Boolean {
            if (code != 429) return false
            val lower = body.lowercase(Locale.ROOT)
            return lower.contains("perday") || lower.contains("generaterequestsperday") || lower.contains("daily")
        }

        /**
         * Identifies whether a 429 response signifies transient 15 RPM rate limiting.
         */
        fun isTransientRpmError(code: Int, body: String): Boolean {
            if (code != 429) return false
            return !isDailyQuotaError(code, body)
        }

        /**
         * Computes exponential backoff delay with full jitter for transient 15 RPM throttling.
         */
        fun calculateBackoffMs(attempt: Int): Long {
            return (1000L * (1 shl attempt.coerceAtMost(2))) + (0..500).random()
        }
    }
}
