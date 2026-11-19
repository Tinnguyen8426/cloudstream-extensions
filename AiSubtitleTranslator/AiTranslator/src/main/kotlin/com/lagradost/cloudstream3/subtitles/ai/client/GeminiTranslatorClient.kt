package com.lagradost.cloudstream3.subtitles.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.ContextCue
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AI subtitle translation client for Google AI Studio (Gemini REST API v1beta).
 *
 * Implements:
 * 1. Endpoint construction with prefix stripping to eliminate `models/models/...` 404 defects.
 * 2. Header-based authentication via `x-goog-api-key`.
 * 3. Permissive safety settings (`BLOCK_NONE`) across all 4 categories to prevent movie subtitle drops.
 * 4. Dual structured schemas: Bootstrap schema (Chunk 0) vs TranslationsOnly schema (Chunks 1..N).
 * 5. 15 RPM rate limit vs 24-hour daily quota decoupling with exponential backoff and jitter.
 * 6. Null-safe candidate extraction from `candidates[0].content.parts[0].text`.
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
        httpClient ?: try {
            com.lagradost.cloudstream3.app.baseClient
        } catch (_: Throwable) {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
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

        val endpointUrl = buildGeminiUrl(baseUrl, modelName)
        val isBootstrap = AITranslatorPromptBuilder.isBootstrap(lookbehind, characterRegistry)
        val includeDiscoveredCharacters = isBootstrap && TranslatorConfig.enableCharacterProfiling
        val sampleText = cues.firstOrNull()?.rawText ?: ""
        val systemInstruction = AITranslatorPromptBuilder.buildSystemPrompt(
            targetLang = targetLang,
            characterRegistry = characterRegistry,
            customGlossary = customGlossary,
            mediaContext = mediaContext,
            sourceLang = sourceLang,
            sampleText = sampleText
        )
        val userContentJson = AITranslatorPromptBuilder.buildUserPayload(cues, lookbehind, lookahead)

        val responseSchema = if (includeDiscoveredCharacters) {
            mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "translations" to mapOf(
                        "type" to "ARRAY",
                        "items" to mapOf(
                            "type" to "OBJECT",
                            "properties" to linkedMapOf(
                                "id" to mapOf("type" to "INTEGER"),
                                "act" to mapOf("type" to "STRING", "enum" to listOf("dialogue", "monologue")),
                                "role" to mapOf("type" to "STRING", "enum" to listOf("senior", "junior", "peer", "solo")),
                                "text" to mapOf("type" to "STRING")
                            ),
                            "required" to listOf("id", "act", "role", "text")
                        )
                    ),
                    "discovered_characters" to mapOf(
                        "type" to "ARRAY",
                        "items" to mapOf(
                            "type" to "OBJECT",
                            "properties" to mapOf(
                                "name" to mapOf("type" to "STRING"),
                                "gender" to mapOf("type" to "STRING"),
                                "role" to mapOf("type" to "STRING")
                            ),
                            "required" to listOf("name", "gender", "role")
                        )
                    )
                ),
                "required" to listOf("translations")
            )
        } else {
            mapOf(
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
        }

        // Native Gemini REST v1beta payload
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
                "maxOutputTokens" to 4096,
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

        val maxAttempts = 2
        var lastError = "Unknown Gemini error"

        for (attempt in 0 until maxAttempts) {
            try {
                client.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val root = mapper.readTree(responseBody)
                        val text = root.get("candidates")?.get(0)?.get("content")?.get("parts")?.get(0)?.get("text")?.asText() ?: responseBody
                        val translations = AITranslationParser.parseTranslations(text)
                        val discovered = if (includeDiscoveredCharacters) AITranslationParser.parseDiscoveredCharacters(text) else null
                        return@withContext TranslationResult(
                            translations = translations,
                            rawResponse = text,
                            success = true,
                            discoveredCharacters = discovered
                        )
                    }

                    // Handle 429 Rate Limiting vs Daily Quota Decoupling
                    if (code == 429) {
                        if (isDailyQuotaError(code, responseBody)) {
                            // Hard daily quota ban: circuit breaker
                            return@withContext TranslationResult(
                                success = false,
                                error = "Gemini Daily Quota Exhausted: $responseBody",
                                isQuotaExhausted = true,
                                retryable = false
                            )
                        }

                        // Transient 15 RPM rate limit: exponential backoff with full jitter
                        lastError = "Gemini 15 RPM rate limit exceeded: $responseBody"
                        if (attempt < maxAttempts - 1) {
                            val backoff = calculateBackoffMs(attempt)
                            delay(backoff)
                            // Continue loop to retry
                        }
                    } else if (code == 400 && responseBody.contains("API_KEY_INVALID", ignoreCase = true)) {
                        return@withContext TranslationResult(
                            success = false,
                            error = "Gemini Invalid API Key: $responseBody",
                            isQuotaExhausted = true,
                            retryable = false
                        )
                    } else if (code in 500..599) {
                        lastError = "Gemini Server Error ($code): $responseBody"
                        if (attempt < maxAttempts - 1) {
                            delay(2000L * (attempt + 1))
                        }
                    } else {
                        return@withContext TranslationResult(
                            success = false,
                            error = "Gemini API Error ($code): $responseBody",
                            isQuotaExhausted = false,
                            retryable = false
                        )
                    }
                }
            } catch (e: Throwable) {
                lastError = "Gemini Connection Exception: ${e.message}"
                if (attempt < maxAttempts - 1) {
                    delay(2000L * (attempt + 1))
                }
            }
        }

        TranslationResult(
            success = false,
            error = lastError,
            isQuotaExhausted = false,
            retryable = true
        )
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
