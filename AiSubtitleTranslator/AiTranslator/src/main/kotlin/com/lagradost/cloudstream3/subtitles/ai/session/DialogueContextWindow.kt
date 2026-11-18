package com.lagradost.cloudstream3.subtitles.ai.session

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.ContextCue
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Manages 3-zone contextual sliding window (Lookbehind, Target Cues, Lookahead)
 * with temporal continuity guards, seek invalidation, and active-speaker pruning.
 */
class DialogueContextWindow(
    val lookbehindSize: Int = 6,
    val lookaheadSize: Int = 4,
    val maxLookbehindGapMs: Long = 5000L
) {
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    // Bounded history sorted strictly monotonically by cue index
    private val translatedHistory = ConcurrentSkipListMap<Int, ContextCue>()
    private var lastCueEndMs: Long = -1L

    @Volatile
    var sessionGenerationId: Long = 0L
        private set

    @Synchronized
    fun reset(newGenerationId: Long? = null) {
        translatedHistory.clear()
        lastCueEndMs = -1L
        if (newGenerationId != null) {
            sessionGenerationId = newGenerationId
        } else {
            sessionGenerationId++
        }
    }

    @Synchronized
    fun recordFinalizedCues(cues: List<SubtitleCue>, translations: Map<Int, String>) {
        for (cue in cues) {
            val transText = translations[cue.index] ?: cue.rawText
            val srcText = if (cue.maskedText.isNotBlank()) cue.maskedText else cue.rawText
            translatedHistory[cue.index] = ContextCue(
                index = cue.index,
                speaker = cue.speaker,
                sourceText = srcText,
                translatedText = transText,
                endMs = cue.endMs
            )
            if (cue.endMs > lastCueEndMs) {
                lastCueEndMs = cue.endMs
            }
        }

        // Bounded RAM history retention (< 10 KB)
        while (translatedHistory.size > 50) {
            translatedHistory.pollFirstEntry()
        }
    }

    @Synchronized
    fun getLookbehind(targetCues: List<SubtitleCue>): List<ContextCue> {
        if (targetCues.isEmpty() || translatedHistory.isEmpty()) return emptyList()

        val firstTarget = targetCues.first()
        val lastHistory = translatedHistory.lastEntry()?.value ?: return emptyList()

        // Temporal Continuity Guard
        val gapMs = firstTarget.startMs - lastHistory.endMs
        val indexGap = firstTarget.index - lastHistory.index

        if (gapMs > maxLookbehindGapMs || gapMs < -5000L || indexGap > 5 || indexGap < 1) {
            reset()
            return emptyList()
        }

        return translatedHistory.values.toList().takeLast(lookbehindSize)
    }

    fun extractLookahead(allCues: List<SubtitleCue>, currentTargetEndIndex: Int): List<ContextCue> {
        return allCues
            .filter { it.index > currentTargetEndIndex }
            .take(lookaheadSize)
            .map { cue ->
                ContextCue(
                    index = cue.index,
                    speaker = cue.speaker,
                    sourceText = if (cue.maskedText.isNotBlank()) cue.maskedText else cue.rawText,
                    translatedText = null,
                    endMs = cue.endMs
                )
            }
    }

    fun pruneRegistry(
        fullRegistry: TwoTierCharacterRegistry?,
        targetCues: List<SubtitleCue>,
        lookbehind: List<ContextCue> = emptyList(),
        lookahead: List<ContextCue> = emptyList()
    ): TwoTierCharacterRegistry {
        if (fullRegistry == null || fullRegistry.characters.isEmpty()) {
            return fullRegistry ?: TwoTierCharacterRegistry()
        }

        val activeNames = mutableSetOf<String>()

        // 1. Direct active speakers in sliding window
        targetCues.mapNotNull { it.speaker }.forEach { spk ->
            if (spk.isNotBlank()) activeNames.add(spk.trim().lowercase())
        }
        (lookbehind + lookahead).mapNotNull { it.speaker }.forEach { spk ->
            if (spk.isNotBlank()) activeNames.add(spk.trim().lowercase())
        }

        // 2. Mentions in dialogue text
        val combinedText = (targetCues.map { it.rawText } +
                lookbehind.map { it.sourceText } +
                lookahead.map { it.sourceText })
            .joinToString(" ")
            .lowercase()

        for (char in fullRegistry.characters) {
            val cName = char.name.trim().lowercase()
            if (cName.isNotEmpty() && combinedText.contains(cName)) {
                activeNames.add(cName)
            }
            for (alias in char.aliases) {
                val aStr = alias.trim().lowercase()
                if (aStr.isNotEmpty() && combinedText.contains(aStr)) {
                    activeNames.add(cName)
                }
            }
        }

        // 3. Anchor top 3 lead characters
        for (char in fullRegistry.characters.take(3)) {
            val leadName = char.name.trim().lowercase()
            if (leadName.isNotEmpty()) {
                activeNames.add(leadName)
            }
        }

        // 4. Filter characters and relationships (eliminating orphan relationships)
        var filteredChars = fullRegistry.characters.filter { char ->
            val cName = char.name.trim().lowercase()
            cName in activeNames || char.aliases.any { it.trim().lowercase() in activeNames }
        }.toMutableList()

        if (filteredChars.size > 6) {
            filteredChars = filteredChars.take(6).toMutableList()
        }

        val activeCharNames = filteredChars.map { it.name.trim().lowercase() }.toSet()

        var filteredRels = fullRegistry.relationships.filter { rel ->
            rel.pair.isNotEmpty() && rel.pair.all { it.trim().lowercase() in activeCharNames }
        }.toMutableList()

        var pruned = fullRegistry.copy(characters = filteredChars, relationships = filteredRels)

        // 5. Hard token budget enforcement (<= 250.0 tokens)
        while (estimateTokens(pruned) > 250.0) {
            if (filteredRels.isNotEmpty()) {
                filteredRels.removeAt(filteredRels.size - 1)
            } else if (filteredChars.size > 3) {
                filteredChars.removeAt(filteredChars.size - 1)
            } else {
                var modified = false
                filteredChars = filteredChars.map { char ->
                    var updated = char
                    if (char.role.length > 60) {
                        updated = updated.copy(role = updated.role.take(60))
                        modified = true
                    }
                    if (updated.aliases.isNotEmpty()) {
                        updated = updated.copy(aliases = emptyList())
                        modified = true
                    }
                    updated
                }.toMutableList()
                pruned = pruned.copy(characters = filteredChars, relationships = filteredRels)
                if (!modified || estimateTokens(pruned) > 250.0) {
                    if (filteredChars.size > 1) {
                        filteredChars.removeAt(filteredChars.size - 1)
                    } else {
                        break
                    }
                }
            }
            pruned = pruned.copy(characters = filteredChars, relationships = filteredRels)
        }

        return pruned
    }

    private fun estimateTokens(registry: TwoTierCharacterRegistry): Double {
        return try {
            mapper.writeValueAsString(registry).length / 4.0
        } catch (_: Throwable) {
            0.0
        }
    }
}
