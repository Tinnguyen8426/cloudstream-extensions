package com.lagradost.cloudstream3.subtitles.ai.client

import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult

/**
 * Universal interface for multi-provider AI subtitle translation clients.
 * Defines contractual methods for batch chunk translation and endpoint health verification.
 */
interface AITranslationClient {

    /**
     * Translates a chunk of subtitle cues into the target language.
     *
     * @param cues List of subtitle cues containing cue indices, timing, and shielded/masked dialogue text.
     * @param sourceLang Optional source language identifier (e.g. "ja", "en").
     * @param targetLang ISO 639-1 target language code (defaults to "vi").
     * @param customGlossary Optional user-defined terminology glossary.
     * @return TranslationResult containing translated items map, raw response, and status flags.
     */
    suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String? = null,
        targetLang: String = "vi",
        customGlossary: String? = null
    ): TranslationResult

    /**
     * Sends a minimal diagnostic ping request to verify endpoint reachability,
     * API key validity, and model responsiveness.
     *
     * @return TranslationResult indicating connection success or specific diagnostic failure.
     */
    suspend fun ping(): TranslationResult
}
