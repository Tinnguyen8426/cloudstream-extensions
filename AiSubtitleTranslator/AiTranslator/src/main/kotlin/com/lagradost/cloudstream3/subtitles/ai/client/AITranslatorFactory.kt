package com.lagradost.cloudstream3.subtitles.ai.client

import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * Factory for instantiating multi-provider AI translation clients based on configuration.
 */
object AITranslatorFactory {

    /**
     * Creates an AITranslationClient matching the specified or configured provider.
     *
     * @param provider AI provider name ("gemini", "openai", "anthropic", "custom").
     * @param apiKey API key for authentication.
     * @param baseUrl Custom API base URL (optional).
     * @param modelName Specific model identifier (optional, falls back to effective model).
     * @param httpClient Optional OkHttpClient override for testing or custom interceptors.
     */
    fun createClient(
        provider: String = TranslatorConfig.provider,
        apiKey: String = TranslatorConfig.apiKey,
        baseUrl: String = TranslatorConfig.customBaseUrl,
        modelName: String = TranslatorConfig.getEffectiveModel(),
        httpClient: OkHttpClient? = null
    ): AITranslationClient {
        return when (provider.lowercase(Locale.ROOT).trim()) {
            TranslatorConfig.PROVIDER_GEMINI -> GeminiTranslatorClient(apiKey, baseUrl, modelName, httpClient)
            TranslatorConfig.PROVIDER_ANTHROPIC -> AnthropicTranslatorClient(apiKey, baseUrl, modelName, httpClient)
            TranslatorConfig.PROVIDER_OPENAI,
            TranslatorConfig.PROVIDER_CUSTOM -> OpenAITranslatorClient(apiKey, baseUrl, modelName, httpClient)
            else -> OpenAITranslatorClient(apiKey, baseUrl, modelName, httpClient)
        }
    }
}
