package com.lagradost.cloudstream3.subtitles.ai.parser

import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue

/**
 * Unified multi-format subtitle parser supporting SubRip (.srt) and WebVTT (.vtt).
 * Handles BOM sanitization, CRLF normalization, flexible timestamp parsing (missing hours,
 * variable millisecond precision), safe blank-line delimited cue index stripping (preserving
 * numeric dialogue like "Agent 47"), and regex style tag shielding.
 */
class SubtitleParser {
    companion object {
        val BOM_PATTERN = Regex("""^(\uFEFF|\uFFFE)""")

        val TIMING_LINE_REGEX = Regex(
            """(?m)^\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,4})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,4})([^\r\n]*)"""
        )

        val TAG_SHIELD_REGEX = Regex(
            """(<\/?[a-zA-Z0-9_.\-]+(?:\s+[^>]*?)?>|\{\\[^}]+\}|<\d{2}:\d{2}(?::\d{2})?[,.]\d{3}>)"""
        )

        private val CUE_ID_REGEX = Regex("""^[a-zA-Z0-9_\-]+$""")

        /**
         * Parses hour, minute, second, and millisecond components into total milliseconds.
         * Accommodates WebVTT missing hours and 1-4 digit millisecond/fractional seconds.
         */
        fun parseTimestampToMs(hStr: String?, mStr: String, sStr: String, msStr: String): Long {
            val hours = hStr?.toLongOrNull() ?: 0L
            val minutes = mStr.toLongOrNull() ?: 0L
            val seconds = sStr.toLongOrNull() ?: 0L
            val msPadded = msStr.padEnd(3, '0').take(3)
            val millis = msPadded.toLongOrNull() ?: 0L
            return (hours * 3600_000L) + (minutes * 60_000L) + (seconds * 1000L) + millis
        }

        /**
         * Parses raw subtitle file content (.srt or .vtt) into a list of SubtitleCue objects.
         */
        fun parse(rawContent: String): List<SubtitleCue> {
            val sanitized = rawContent
                .replace(BOM_PATTERN, "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')

            val matches = TIMING_LINE_REGEX.findAll(sanitized).toList()
            if (matches.isEmpty()) return emptyList()

            val resultCues = ArrayList<SubtitleCue>(matches.size)

            for (i in matches.indices) {
                val match = matches[i]
                val g = match.groupValues

                val startMs = parseTimestampToMs(g[1].ifEmpty { null }, g[2], g[3], g[4])
                val endMs = parseTimestampToMs(g[5].ifEmpty { null }, g[6], g[7], g[8])
                val settings = g[9].trim().ifEmpty { null }

                val textStart = match.range.last + 1
                val textEnd = if (i + 1 < matches.size) {
                    val nextStart = matches[i + 1].range.first
                    val slice = sanitized.substring(textStart, nextStart)
                    val lines = slice.split("\n").toMutableList()

                    // 1. Strip trailing blank lines
                    while (lines.isNotEmpty() && lines.last().trim().isEmpty()) {
                        lines.removeAt(lines.size - 1)
                    }

                    // 2. Strip upcoming cue identifier ONLY if preceded by an empty line delimiter AND preceding dialogue
                    val hasPrecedingDialogue = lines.size >= 3 && (0 until lines.size - 2).any { lines[it].trim().isNotEmpty() }
                    if (hasPrecedingDialogue && lines[lines.size - 2].trim().isEmpty()) {
                        val candidate = lines.last().trim()
                        val isSrtIndex = candidate.isNotEmpty() && candidate.all { c -> c.isDigit() }
                        val isVttComment = candidate == "NOTE" || candidate.startsWith("NOTE ") || candidate.startsWith("NOTE\t")
                        val isVttCueId = isVttComment || (candidate.isNotEmpty() && CUE_ID_REGEX.matches(candidate))
                        if (isSrtIndex || isVttCueId) {
                            lines.removeAt(lines.size - 1)
                            while (lines.isNotEmpty() && lines.last().trim().isEmpty()) {
                                lines.removeAt(lines.size - 1)
                            }
                        }
                    }
                    textStart + lines.joinToString("\n").length
                } else {
                    sanitized.length
                }

                val dialogue = sanitized.substring(textStart, textEnd.coerceAtLeast(textStart)).trim()
                if (dialogue.isBlank()) continue

                // Shield inline tags
                val tagMap = LinkedHashMap<String, String>()
                var tagCounter = 1
                val maskedDialogue = TAG_SHIELD_REGEX.replace(dialogue) { tagMatch ->
                    val placeholder = "[[TAG_${tagCounter++}]]"
                    tagMap[placeholder] = tagMatch.value
                    placeholder
                }

                resultCues.add(
                    SubtitleCue(
                        index = resultCues.size + 1,
                        startMs = startMs,
                        endMs = endMs,
                        cueSettings = settings,
                        rawText = dialogue,
                        maskedText = maskedDialogue,
                        tagMap = tagMap
                    )
                )
            }

            return resultCues
        }
    }

    /**
     * Instance delegator for object/class polymorphism.
     */
    fun parse(rawContent: String): List<SubtitleCue> = Companion.parse(rawContent)
}
