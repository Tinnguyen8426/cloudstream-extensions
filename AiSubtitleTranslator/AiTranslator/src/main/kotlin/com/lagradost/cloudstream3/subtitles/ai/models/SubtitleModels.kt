package com.lagradost.cloudstream3.subtitles.ai.models

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents an individual parsed subtitle cue with timing, WebVTT layout settings,
 * raw dialogue text, shielded dialogue text, shielded tag mappings, and optional speaker diarization.
 */
data class SubtitleCue(
    val index: Int,
    val cueId: String? = null,
    val startMs: Long,
    val endMs: Long,
    val cueSettings: String? = null,
    val rawText: String,
    val maskedText: String = "",
    val tagMap: Map<String, String> = emptyMap(),
    val speaker: String? = null,
    val speakerGender: String? = null
)

/**
 * Represents a lightweight dialogue item for translation payload serialization
 * and polymorphic JSON deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleItem(
    val id: Int,
    val act: String? = null,
    val role: String? = null,
    val text: String
)

/**
 * Context cue in the sliding window (Lookbehind and Lookahead).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ContextCue(
    val index: Int,
    val speaker: String? = null,
    @JsonProperty("sourceText") @JsonAlias("source", "src", "source_text")
    val sourceText: String,
    @JsonProperty("translatedText") @JsonAlias("translated", "translated_text")
    val translatedText: String? = null,
    val endMs: Long = 0L
)

/**
 * Persona pronoun variations for characters with dual roles or shifting personas.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PersonaVariants(
    @JsonProperty("public_pronoun") @JsonAlias("publicPronoun")
    val public_pronoun: String? = null,
    @JsonProperty("combat_pronoun") @JsonAlias("combatPronoun")
    val combat_pronoun: String? = null
)

/**
 * Entity representation of a character within the story.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CharacterEntity(
    val name: String,
    val aliases: List<String> = emptyList(),
    val gender: String = "unknown",
    @JsonProperty("ageGroup") @JsonAlias("age_group")
    val ageGroup: String = "adult",
    val role: String = "",
    @JsonProperty("personaVariants") @JsonAlias("persona_variants")
    val personaVariants: PersonaVariants? = null,
    @JsonProperty("viPronounHint") @JsonAlias("vi_pronoun_hint", "vi_pronoun")
    val viPronounHint: String? = null,
    @JsonProperty("isEphemeral") @JsonAlias("is_ephemeral")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val isEphemeral: Boolean = false
)

/**
 * Directed or symmetrical relationship between two characters.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CharacterRelationship(
    val pair: List<String> = emptyList(),
    val type: String = "peers",
    @JsonProperty("isEphemeral") @JsonAlias("is_ephemeral")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val isEphemeral: Boolean = false,
    @JsonProperty("addressMatrix") @JsonAlias("address_matrix")
    val addressMatrix: Map<String, Any> = emptyMap()
)

/**
 * Two-tier registry maintaining persistent series character profiles and episodic dialogue states.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TwoTierCharacterRegistry(
    @JsonProperty("mediaTitle") @JsonAlias("media_title")
    val mediaTitle: String = "",
    val characters: List<CharacterEntity> = emptyList(),
    val relationships: List<CharacterRelationship> = emptyList()
)

/**
 * High-level media metadata captured from CloudStream LoadResponse.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaContext(
    val title: String = "",
    val plot: String = "",
    val actors: List<String> = emptyList(),
    val tags: List<String> = emptyList()
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
    val retryable: Boolean = false,
    val discoveredCharacters: List<CharacterEntity>? = null
) {
    // Compatibility aliases
    val items: Map<Int, String> get() = translations
    val isSuccess: Boolean get() = success
    val errorMessage: String? get() = error
}
