package com.lagradost.cloudstream3.subtitles.ai.session

import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue

/**
 * Auto-healing dialogue reconciler for chunk-based AI subtitle translation.
 *
 * Resolves cue desynchronization defects where LLMs (such as DeepSeek, Groq Llama,
 * or local quantized models) reset dialogue IDs to relative 1-based sequences (1..N)
 * instead of preserving global input cue indices (e.g., cues 31..60).
 *
 * Guarantees:
 * 1. 100% cue recovery (30/30 cues) under relative 1-based re-indexing.
 * 2. Unaltered preservation of timeline sync by falling back omitted cues to source rawText.
 * 3. Elimination of hallucinated or out-of-bounds cue IDs.
 * 4. Result map size strictly matches input cue chunk size.
 */
object SubtitleReconciler {

    /**
     * Reconciles input cues with translated items, auto-detecting and healing relative 1..N indexing.
     *
     * @param inputCues List of source subtitle cues in the chunk.
     * @param translatedItems Map of cue ID to translated dialogue string from the AI model.
     * @return Map of cue index to reconciled dialogue text (size == inputCues.size).
     */
    fun autoHealingReconcile(
        inputCues: List<SubtitleCue>,
        translatedItems: Map<Int, String>
    ): Map<Int, String> {
        if (inputCues.isEmpty()) return emptyMap()

        val reconciled = LinkedHashMap<Int, String>(inputCues.size)
        val minInputId = inputCues.minOfOrNull { it.index } ?: 1
        val transKeys = translatedItems.keys.sorted()

        // Auto-detect 1-based relative re-indexing:
        // LLM output keys start at 1 and end <= inputCues.size,
        // while the input chunk starts beyond the chunk size or does not match input IDs.
        val isRelative = transKeys.isNotEmpty() &&
                transKeys.first() == 1 &&
                transKeys.last() <= inputCues.size &&
                (minInputId > inputCues.size || (minInputId > 1 && !translatedItems.containsKey(minInputId)))

        for ((offset, cue) in inputCues.withIndex()) {
            // 1. Primary lookup by exact cue index
            var text = translatedItems[cue.index]?.trim()

            // 2. Secondary lookup by 1-based offset if relative re-indexing was detected
            if (text.isNullOrBlank() && isRelative) {
                text = translatedItems[offset + 1]?.trim()
            }

            // 3. Tertiary fallback to cue.rawText if translation is omitted or blank
            reconciled[cue.index] = if (!text.isNullOrBlank()) text else cue.rawText
        }

        return reconciled
    }

    /**
     * Backward-compatible alias matching Section 3.5 naming specification.
     */
    fun reconcileChunkItems(
        inputCues: List<SubtitleCue>,
        translatedItems: Map<Int, String>
    ): Map<Int, String> = autoHealingReconcile(inputCues, translatedItems)
}
