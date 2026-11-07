package com.lagradost.cloudstream3.subtitles.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
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
 * 4. Native `responseSchema` with root `ARRAY` of `OBJECT` (id: INTEGER, text: STRING).
 * 5. 15 RPM rate limit vs 24-hour daily quota decoupling with exponential backoff and jitter.
 * 6. Null-safe candidate extraction from `candidates[0].content.parts[0].text`.
 */
class GeminiTranslatorClient(
    val apiKey: String,
    val baseUrl: String? = null,
    val modelName: String = "gemini-1.5-flash",
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

        val endpointUrl = buildGeminiUrl(baseUrl, modelName)
        val glossaryPrompt = if (!customGlossary.isNullOrBlank()) {
            " Strictly adhere to the following glossary terms:\n$customGlossary\n"
        } else ""

        val systemInstruction = "You are a professional subtitle translator. Translate each dialogue entry accurately into the target language ($targetLang). " +
                "Preserve all special tag shields (e.g. [[TAG_0]], [[TAG_1]]) exactly as formatted without alteration or removal. " +
                "Output only a JSON array of objects with integer 'id' and string 'text'.$glossaryPrompt"

        val dialogueList = cues.map { cue ->
            mapOf(
                "id" to cue.index,
                "text" to if (cue.maskedText.isNotBlank()) cue.maskedText else cue.rawText
            )
        }
        val userContentJson = mapper.writeValueAsString(dialogueList)

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
                "responseSchema" to mapOf(
                    "type" to "ARRAY",
                    "items" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "id" to mapOf("type" to "INTEGER"),
                            "text" to mapOf("type" to "STRING")
                        ),
                        "required" to listOf("id", "text")
                    )
                )
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

        val maxAttempts = 5
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
                        return@withContext TranslationResult(
                            translations = translations,
                            rawResponse = text,
                            success = true
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
            return (5000L * (1 shl attempt.coerceAtMost(3))) + (0..2000).random()
        }
    }
}
