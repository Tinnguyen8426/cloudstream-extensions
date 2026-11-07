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
         * Detects whether the content represents Advanced SubStation Alpha (.ass / .ssa) subtitle format.
         */
        fun isAssContent(content: String): Boolean {
            return content.contains("[Events]", ignoreCase = true) ||
                   content.contains("[Script Info]", ignoreCase = true) ||
                   content.contains("Dialogue:", ignoreCase = true)
        }

        /**
         * Parses raw subtitle file content (.srt, .vtt, or .ass/.ssa) into a list of SubtitleCue objects.
         */
        fun parse(rawContent: String): List<SubtitleCue> {
            val sanitized = rawContent
                .replace(BOM_PATTERN, "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')

            if (isAssContent(sanitized)) {
                val assResult = parseAss(sanitized)
                if (assResult.isNotEmpty()) return assResult
            }

            val matches = TIMING_LINE_REGEX.findAll(sanitized).toList()
            if (matches.isEmpty()) {
                return emptyList()
            }

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

        /**
         * Parses ASS/SSA timestamp format H:MM:SS.CC or MM:SS.CC into milliseconds.
         */
        fun parseAssTimestampToMs(timeStr: String): Long {
            val parts = timeStr.trim().split(":")
            if (parts.size < 2) return 0L
            val (h, m, sWithMs) = if (parts.size >= 3) {
                Triple(parts[0].toLongOrNull() ?: 0L, parts[1].toLongOrNull() ?: 0L, parts[2])
            } else {
                Triple(0L, parts[0].toLongOrNull() ?: 0L, parts[1])
            }
            val sParts = sWithMs.split(".")
            val sec = sParts[0].toLongOrNull() ?: 0L
            val ms = if (sParts.size > 1) {
                sParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else 0L
            return (h * 3600_000L) + (m * 60_000L) + (sec * 1000L) + ms
        }

        /**
         * Parses Advanced SubStation Alpha (.ass / .ssa) subtitle content into SubtitleCue objects.
         */
        fun parseAss(rawContent: String): List<SubtitleCue> {
            val lines = rawContent.lines()
            var inEvents = false
            var formatColumns: List<String>? = null
            var startIndex = 1
            var endIndex = 2
            var textIndex = 9

            val resultCues = ArrayList<SubtitleCue>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[Events]", ignoreCase = true)) {
                    inEvents = true
                    continue
                } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inEvents = false
                    continue
                }

                if (inEvents || trimmed.startsWith("Dialogue:", ignoreCase = true) || trimmed.startsWith("Format:", ignoreCase = true)) {
                    if (trimmed.startsWith("Format:", ignoreCase = true)) {
                        val header = trimmed.substringAfter(":").trim()
                        formatColumns = header.split(",").map { it.trim().lowercase() }
                        startIndex = formatColumns.indexOf("start").takeIf { it >= 0 } ?: 1
                        endIndex = formatColumns.indexOf("end").takeIf { it >= 0 } ?: 2
                        textIndex = formatColumns.indexOf("text").takeIf { it >= 0 } ?: (formatColumns.size - 1).coerceAtLeast(3)
                        continue
                    }

                    if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                        val content = trimmed.substringAfter(":").trim()
                        val limit = formatColumns?.size ?: 10
                        val parts = content.split(",", limit = limit)
                        if (parts.size > textIndex) {
                            val startMs = parseAssTimestampToMs(parts.getOrElse(startIndex) { "" })
                            val endMs = parseAssTimestampToMs(parts.getOrElse(endIndex) { "" })
                            val rawDialogue = parts[textIndex]
                                .replace("\\N", "\n")
                                .replace("\\n", "\n")
                                .replace("\\h", " ")
                                .trim()
                            if (rawDialogue.isBlank()) continue

                            val tagMap = LinkedHashMap<String, String>()
                            var tagCounter = 1
                            val maskedDialogue = TAG_SHIELD_REGEX.replace(rawDialogue) { tagMatch ->
                                val placeholder = "[[TAG_${tagCounter++}]]"
                                tagMap[placeholder] = tagMatch.value
                                placeholder
                            }

                            resultCues.add(
                                SubtitleCue(
                                    index = resultCues.size + 1,
                                    startMs = startMs,
                                    endMs = endMs,
                                    cueSettings = null,
                                    rawText = rawDialogue,
                                    maskedText = maskedDialogue,
                                    tagMap = tagMap
                                )
                            )
                        }
                    }
                }
            }
            return resultCues
        }
    }

    /**
     * Instance delegator for object/class polymorphism.
     */
    fun parse(rawContent: String): List<SubtitleCue> = Companion.parse(rawContent)
}
