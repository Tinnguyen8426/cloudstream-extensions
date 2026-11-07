package com.lagradost.cloudstream3.subtitles.ai.parser

import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import java.util.Locale

/**
 * Reconstructs translated dialogue by unmasking shielded tag tokens case-insensitively,
 * automatically balancing unclosed HTML styling tags (e.g. <i>, <b>, <u>, <s>, <font>),
 * and formatting valid SubRip (.srt) documents with fallback to source text.
 */
class SubtitleReconstructor {
    companion object {
        val TAG_TOKEN_REGEX = Regex("""\[\[\s*TAG_(\d+)\s*\]\]""", RegexOption.IGNORE_CASE)

        private val SIMPLE_TAGS = listOf("i", "b", "u", "s")
        private val OPEN_FONT_REGEX = Regex("""<font(?:\s+[^>]*?)?>""", RegexOption.IGNORE_CASE)
        private val CLOSE_FONT_REGEX = Regex("""</font>""", RegexOption.IGNORE_CASE)

        /**
         * Formats a millisecond timestamp to SubRip standard format (HH:MM:SS,mmm).
         */
        fun formatSrtTimestamp(ms: Long): String {
            val clamped = ms.coerceAtLeast(0L)
            val hours = clamped / 3600_000L
            val minutes = (clamped % 3600_000L) / 60_000L
            val seconds = (clamped % 60_000L) / 1000L
            val millis = clamped % 1000L
            return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
        }

        /**
         * Unmasks shielded tag tokens in translated text case-insensitively and whitespace-tolerantly.
         * Automatically balances unclosed HTML tags to prevent styling bleed in player renderers.
         */
        fun unmaskTags(text: String, tagMap: Map<String, String>): String {
            val unmasked = TAG_TOKEN_REGEX.replace(text) { match ->
                val rawTagId = match.groupValues[1]
                val canonicalTagId = rawTagId.toLongOrNull()?.toString() ?: rawTagId
                val canonicalKey = "[[TAG_$canonicalTagId]]"
                val fallbackKey = "[[TAG_$rawTagId]]"
                tagMap[canonicalKey] ?: tagMap[fallbackKey] ?: match.value
            }
            return balanceHtmlTags(unmasked)
        }

        /**
         * Balances unclosed HTML tags (i, b, u, s, and font) by appending missing closing tags.
         * Idempotent for already-balanced strings.
         */
        fun balanceHtmlTags(input: String): String {
            var balanced = input
            for (tag in SIMPLE_TAGS) {
                val openCount = Regex("""<$tag(?:\s+[^>]*?)?>""", RegexOption.IGNORE_CASE).findAll(balanced).count()
                val closeCount = Regex("""</$tag>""", RegexOption.IGNORE_CASE).findAll(balanced).count()
                if (openCount > closeCount) {
                    repeat(openCount - closeCount) {
                        balanced += "</$tag>"
                    }
                }
            }

            // Balance font tags (e.g. <font color="...">)
            val openFontCount = OPEN_FONT_REGEX.findAll(balanced).count()
            val closeFontCount = CLOSE_FONT_REGEX.findAll(balanced).count()
            if (openFontCount > closeFontCount) {
                balanced += "</font>".repeat(openFontCount - closeFontCount)
            }

            return balanced
        }

        /**
         * Builds a standard SubRip (.srt) document string from cues and translation map.
         * When a cue translation is null, empty, or blank, falls back to cue.rawText
         * ensuring the player timeline remains fully indexed.
         */
        fun buildSubRip(cues: List<SubtitleCue>, translatedTexts: Map<Int, String>): String {
            if (cues.isEmpty()) return ""
            val sb = StringBuilder()
            cues.forEachIndexed { idx, cue ->
                val translation = translatedTexts[cue.index]?.trim()
                val content = if (!translation.isNullOrBlank()) {
                    unmaskTags(translation, cue.tagMap)
                } else {
                    cue.rawText
                }
                sb.append(idx + 1).append("\n")
                sb.append(formatSrtTimestamp(cue.startMs))
                    .append(" --> ")
                    .append(formatSrtTimestamp(cue.endMs))
                    .append("\n")
                sb.append(content).append("\n\n")
            }
            return sb.toString().trimEnd() + "\n"
        }
    }

    /**
     * Instance delegators for object/class polymorphism.
     */
    fun formatSrtTimestamp(ms: Long): String = Companion.formatSrtTimestamp(ms)
    fun unmaskTags(text: String, tagMap: Map<String, String>): String = Companion.unmaskTags(text, tagMap)
    fun balanceHtmlTags(input: String): String = Companion.balanceHtmlTags(input)
    fun buildSubRip(cues: List<SubtitleCue>, translatedTexts: Map<Int, String>): String = Companion.buildSubRip(cues, translatedTexts)
}
