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
 * 4. Micro-healing recovery of short Japanese interjections (うん, はい, えっ, 嘘) omitted by LLMs.
 * 5. Result map size strictly matches input cue chunk size.
 */
object SubtitleReconciler {

    val JAPANESE_INTERJECTIONS: Map<String, String> = mapOf(
        "うん" to "Ừ",
        "はい" to "Vâng",
        "えっ" to "Hả?",
        "えっ？" to "Hả?",
        "え？" to "Hả?",
        "え" to "Hả...",
        "あ" to "A...",
        "ああ" to "A...",
        "うそ" to "Không thể nào...",
        "嘘" to "Không thể nào...",
        "嘘でしょ" to "Không thể nào...",
        "何" to "Cái gì?",
        "なに" to "Cái gì?",
        "おい" to "Này!",
        "待って" to "Đợi đã!",
        "やめて" to "Dừng lại!",
        "やめろ" to "Dừng lại!",
        "助けて" to "Cứu với!",
        "ありがとう" to "Cảm ơn.",
        "ごめん" to "Xin lỗi.",
        "わかった" to "Tôi hiểu rồi.",
        "そうか" to "Ra là vậy.",
        "なるほど" to "Ra là thế."
    )

    private val PUNCT_CLEAN_REGEX = Regex("""[！？!?…・\.\s\u3002\u3001]+""")

    /**
     * Auto-heals dropped Japanese interjections from dictionary mapping.
     * Strips quotation marks, brackets, and Japanese ideographic punctuation (。\u3002, 、\u3001).
     */
    fun healJapaneseInterjection(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val cleaned = rawText.trim().trim('「', '」', '"', '\'', '『', '』', '(', ')', '（', '）', '\u3002', '\u3001')
        if (cleaned.isEmpty()) return null
        if (JAPANESE_INTERJECTIONS.containsKey(cleaned)) {
            return JAPANESE_INTERJECTIONS[cleaned]
        }
        val strippedPunct = PUNCT_CLEAN_REGEX.replace(cleaned, "")
        if (strippedPunct.isNotEmpty() && JAPANESE_INTERJECTIONS.containsKey(strippedPunct)) {
            return JAPANESE_INTERJECTIONS[strippedPunct]
        }
        return null
    }

    /**
     * Reconciles input cues with translated items, auto-detecting and healing relative 1..N indexing,
     * and micro-healing omitted Japanese interjections.
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

            // 3. Micro-healing: if translation is omitted or blank, attempt Japanese interjection recovery
            if (text.isNullOrBlank()) {
                val healed = healJapaneseInterjection(cue.rawText)
                if (!healed.isNullOrBlank()) {
                    text = healed
                }
            }

            // 4. Tertiary fallback to cue.rawText if translation is still omitted or blank
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
