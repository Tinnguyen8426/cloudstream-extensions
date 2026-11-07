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
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AI subtitle translation client for Anthropic Messages API and Claude models.
 *
 * Implements:
 * 1. Base URL normalization defaulting to `https://api.anthropic.com/v1/messages`.
 * 2. Assistant prefill `[` technique to constrain Claude to output pure JSON array.
 * 3. Array prefix reconstruction (`[` prepending) for deserializing assistant continuation.
 * 4. Specific detection of HTTP 400 credit balance exhaustion ("credit balance is too low")
 *    preventing unhandled crashes and classifying as hard quota exhaustion.
 * 5. Prompt caching header (`anthropic-beta: prompt-caching-2024-07-31`).
 */
class AnthropicTranslatorClient(
    val apiKey: String,
    val baseUrl: String? = null,
    val modelName: String = "claude-3-5-haiku-20241022",
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

        val endpointUrl = buildAnthropicUrl(baseUrl)
        val glossaryPrompt = if (!customGlossary.isNullOrBlank()) {
            " Strictly adhere to the following glossary terms:\n$customGlossary\n"
        } else ""

        val systemPrompt = "You are a professional subtitle translator. Translate each dialogue entry accurately into the target language ($targetLang). " +
                "Preserve all special tag shields (e.g. [[TAG_0]], [[TAG_1]]) exactly as formatted without alteration or removal. " +
                "Output only a JSON array of objects with integer 'id' and string 'text'.$glossaryPrompt"

        val dialogueList = cues.map { cue ->
            mapOf(
                "id" to cue.index,
                "text" to if (cue.maskedText.isNotBlank()) cue.maskedText else cue.rawText
            )
        }
        val userContentJson = mapper.writeValueAsString(dialogueList)

        // Anthropic Messages API payload with Assistant Prefill
        val requestMap = mapOf(
            "model" to modelName.trim().ifEmpty { "claude-3-5-haiku-20241022" },
            "max_tokens" to 4096,
            "temperature" to 0.3,
            "system" to systemPrompt,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to "Translate the following subtitle cues into $targetLang:\n$userContentJson"
                ),
                mapOf(
                    "role" to "assistant",
                    "content" to "["
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

                    // Reconstruct JSON array since assistant prefill '[' was already emitted
                    val completeJson = if (rawContent.trimStart().startsWith("[")) {
                        rawContent
                    } else {
                        "[$rawContent"
                    }

                    val translations = AITranslationParser.parseTranslations(completeJson)
                    return@withContext TranslationResult(
                        translations = translations,
                        rawResponse = completeJson,
                        success = true
                    )
                }

                // Error Classification
                when {
                    isCreditExhaustion(code, responseBody) -> {
                        TranslationResult(
                            success = false,
                            error = "Anthropic Credit Balance Exhausted: $responseBody",
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
         * Builds the Anthropic messages API endpoint URL.
         */
        fun buildAnthropicUrl(baseUrl: String?): String {
            val clean = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://api.anthropic.com/v1/messages"
            return if (clean.endsWith("/messages")) clean else "$clean/messages".replace("//messages", "/messages")
        }

        /**
         * Detects whether an Anthropic response indicates credit exhaustion (HTTP 400 + credit balance error).
         */
        fun isCreditExhaustion(code: Int, body: String): Boolean {
            if (code == 400) {
                val lower = body.lowercase(Locale.ROOT)
                return lower.contains("credit balance is too low") ||
                        (lower.contains("invalid_request_error") && lower.contains("credit"))
            }
            return false
        }
    }
}
