package com.lagradost.cloudstream3.subtitles.ai.client

import com.lagradost.cloudstream3.subtitles.ai.models.ContextCue
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry

/**
 * Universal interface for multi-provider AI subtitle translation clients.
 * Defines contractual methods for batch chunk translation, sliding dialogue context windows,
 * character roster addressing, and endpoint health verification.
 */
interface AITranslationClient {

    /**
     * Translates a chunk of subtitle cues into the target language (legacy 4-parameter overload).
     */
    suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String? = null,
        targetLang: String = "vi",
        customGlossary: String? = null
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

    /**
     * Translates a chunk of subtitle cues into the target language with full 3-zone sliding context
     * (Lookbehind, Target Cues, Lookahead) and character registry addressing.
     */
    suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String? = null,
        targetLang: String = "vi",
        customGlossary: String? = null,
        lookbehind: List<ContextCue> = emptyList(),
        lookahead: List<ContextCue> = emptyList(),
        characterRegistry: TwoTierCharacterRegistry? = null,
        mediaContext: MediaContext? = null
    ): TranslationResult = translateChunk(
        cues = cues,
        sourceLang = sourceLang,
        targetLang = targetLang,
        customGlossary = customGlossary
    )

    /**
     * Sends a minimal diagnostic ping request to verify endpoint reachability,
     * API key validity, and model responsiveness.
     *
     * @return TranslationResult indicating connection success or specific diagnostic failure.
     */
    suspend fun ping(): TranslationResult
}
