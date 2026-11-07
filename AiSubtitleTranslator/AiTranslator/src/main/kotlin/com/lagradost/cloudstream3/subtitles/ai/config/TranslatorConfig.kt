package com.lagradost.cloudstream3.subtitles.ai.config

import android.content.Context
import java.util.Locale

/**
 * Global configuration and persistent preferences for AI Subtitle Translator.
 * Manages provider selections, API keys, custom base URLs, model overrides,
 * source language priorities, and translation parameters.
 */
object TranslatorConfig {
    private const val PREFS_NAME = "ai_subtitle_translator_preferences"

    // Supported AI Providers
    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_ANTHROPIC = "anthropic"
    const val PROVIDER_CUSTOM = "custom"

    // Source Language Priority Strategies
    const val PRIORITY_AUTO = "auto"
    const val PRIORITY_ANIME_FIRST = "anime_first"
    const val PRIORITY_ENGLISH_FIRST = "english_first"
    const val PRIORITY_CUSTOM = "custom"

    // Delivery Strategies (Strategies A & B)
    const val DELIVERY_STRATEGY_A = "strategy_a"
    const val DELIVERY_STRATEGY_B = "strategy_b"
    const val DELIVERY_STRATEGY_AUTO = "auto"
    const val STRATEGY_BATCH = "strategy_a"
    const val STRATEGY_SKELETON = "strategy_b"
    const val STRATEGY_AUTO = "auto"

    // Configuration Fields with Production Defaults
    @Volatile var isEnabled: Boolean = true
    @Volatile var targetLanguage: String = "vi"
    @Volatile var sourcePriorityMode: String = PRIORITY_ANIME_FIRST
    @Volatile var customPriorityList: String = "ja, en"
    @Volatile var provider: String = PROVIDER_GEMINI
    @Volatile var apiKey: String = ""
    @Volatile var customBaseUrl: String = ""
    @Volatile var modelName: String = ""
    @Volatile var chunkSize: Int = 30
    @Volatile var preserveSdh: Boolean = true
    @Volatile var customGlossary: String = ""
    @Volatile var deliveryStrategy: String = DELIVERY_STRATEGY_A

    /**
     * Initializes configuration by loading saved values from Android SharedPreferences.
     */
    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isEnabled = prefs.getBoolean("key_enabled", true)
            targetLanguage = prefs.getString("key_target_lang", "vi") ?: "vi"
            sourcePriorityMode = prefs.getString("key_priority_mode", PRIORITY_ANIME_FIRST) ?: PRIORITY_ANIME_FIRST
            customPriorityList = prefs.getString("key_custom_priority", "ja, en") ?: "ja, en"
            provider = prefs.getString("key_provider", PROVIDER_GEMINI) ?: PROVIDER_GEMINI
            apiKey = prefs.getString("key_api_key", "") ?: ""
            customBaseUrl = prefs.getString("key_base_url", "") ?: ""
            modelName = prefs.getString("key_model_name", "") ?: ""
            chunkSize = prefs.getInt("key_chunk_size", 30).coerceIn(10, 80)
            preserveSdh = prefs.getBoolean("key_preserve_sdh", true)
            customGlossary = prefs.getString("key_custom_glossary", "") ?: ""
            deliveryStrategy = prefs.getString("key_delivery_strategy", DELIVERY_STRATEGY_A) ?: DELIVERY_STRATEGY_A
        } catch (_: Throwable) {
            // Graceful fallback to default values if context or prefs are unavailable
        }
    }

    /**
     * Persists current configuration to Android SharedPreferences.
     */
    fun save(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("key_enabled", isEnabled)
                putString("key_target_lang", targetLanguage)
                putString("key_priority_mode", sourcePriorityMode)
                putString("key_custom_priority", customPriorityList)
                putString("key_provider", provider)
                putString("key_api_key", apiKey)
                putString("key_base_url", customBaseUrl)
                putString("key_model_name", modelName)
                putInt("key_chunk_size", chunkSize)
                putBoolean("key_preserve_sdh", preserveSdh)
                putString("key_custom_glossary", customGlossary)
                putString("key_delivery_strategy", deliveryStrategy)
                apply()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Resolves the effective model name, falling back to provider defaults if not explicitly configured.
     */
    fun getEffectiveModel(): String {
        if (modelName.isNotBlank()) return modelName.trim()
        return when (provider.lowercase(Locale.ROOT).trim()) {
            PROVIDER_GEMINI -> "gemini-1.5-flash"
            PROVIDER_OPENAI -> "gpt-4o-mini"
            PROVIDER_ANTHROPIC -> "claude-3-5-haiku-20241022"
            PROVIDER_CUSTOM -> "gpt-4o-mini"
            else -> "gemini-1.5-flash"
        }
    }

    /**
     * Parses the custom priority list string into a cleaned list of lowercase language tags.
     */
    fun getCustomPriorityLanguages(): List<String> {
        return customPriorityList
            .split(",")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
    }

    /**
     * Resets configuration fields back to factory defaults, optionally persisting them.
     */
    fun resetToDefaults(context: Context? = null) {
        isEnabled = true
        targetLanguage = "vi"
        sourcePriorityMode = PRIORITY_ANIME_FIRST
        customPriorityList = "ja, en"
        provider = PROVIDER_GEMINI
        apiKey = ""
        customBaseUrl = ""
        modelName = ""
        chunkSize = 30
        preserveSdh = true
        customGlossary = ""
        deliveryStrategy = DELIVERY_STRATEGY_A

        if (context != null) {
            save(context)
        }
    }
}
