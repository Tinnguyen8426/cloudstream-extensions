package com.lagradost.cloudstream3.subtitles.ai.models

/**
 * Represents an individual parsed subtitle cue with timing, WebVTT layout settings,
 * raw dialogue text, shielded dialogue text, and shielded tag mappings.
 */
data class SubtitleCue(
    val index: Int,
    val cueId: String? = null,
    val startMs: Long,
    val endMs: Long,
    val cueSettings: String? = null,
    val rawText: String,
    val maskedText: String,
    val tagMap: Map<String, String> = emptyMap()
)

/**
 * Represents a lightweight dialogue item for translation payload serialization
 * and polymorphic JSON deserialization.
 */
data class SubtitleItem(
    val id: Int,
    val text: String
)

/**
 * Encapsulates the results, translated items, and status of an AI subtitle translation operation.
 */
data class TranslationResult(
    val translations: Map<Int, String> = emptyMap(),
    val rawResponse: String? = null,
    val success: Boolean = true,
    val error: String? = null,
    val isQuotaExhausted: Boolean = false,
    val retryable: Boolean = false
) {
    // Compatibility aliases
    val items: Map<Int, String> get() = translations
    val isSuccess: Boolean get() = success
    val errorMessage: String? get() = error
}
