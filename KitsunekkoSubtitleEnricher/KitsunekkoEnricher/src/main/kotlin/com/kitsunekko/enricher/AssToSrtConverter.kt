package com.kitsunekko.enricher

import android.util.Log
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

/**
 * High-performance ASS/SSA to SubRip (.srt) converter and multi-charset normalizer.
 *
 * CloudStream's PlayerSubtitleHelper defaults all non-vtt/xml/ttml subtitle extensions (.ass, .ssa)
 * to "application/x-subrip". ExoPlayer's CustomSubripParser cannot parse raw ASS dialogue lines,
 * producing 0 cues and triggering the "No subtitles loaded" offset warning.
 *
 * This converter extracts dialogue events, converts ASS timestamps (H:MM:SS.cs) to SubRip (HH:MM:SS,mmm),
 * strips style override tags, handles line breaks, normalizes UTF-16/Shift-JIS/UTF-8 encodings,
 * and sorts cues chronologically.
 */
object AssToSrtConverter {

    private const val TAG = "AssToSrtConverter"

    data class SrtCue(
        val startMs: Long,
        val endMs: Long,
        val startSrt: String,
        val endSrt: String,
        val text: String
    )

    private val CHARSET_SHIFT_JIS by lazy {
        try { Charset.forName("Shift_JIS") } catch (_: Throwable) { Charsets.UTF_8 }
    }
    private val CHARSET_CP1252 by lazy {
        try { Charset.forName("windows-1252") } catch (_: Throwable) { Charsets.ISO_8859_1 }
    }

    private val SUBRIP_TIMING_REGEX = Regex(
        """(?m)^\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})"""
    )
    private val ASS_OVERRIDE_TAGS_REGEX = Regex("""\{[^}]*\}""")
    private val ASS_DRAWING_REGEX = Regex("""\{\\p[1-9]\}.*?(\{\\p0\}|$)""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Converts an ASS/SSA timestamp string (e.g. "0:01:23.45" or "1:02:03.450") to milliseconds.
     */
    fun assTimeToMs(timeStr: String): Long? {
        val trimmed = timeStr.trim()
        val parts = trimmed.split(":")
        if (parts.size != 3) return null

        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null

        val secParts = parts[2].split('.', ',')
        val s = secParts[0].toLongOrNull() ?: return null
        val ms = if (secParts.size > 1) {
            val frac = secParts[1]
            when {
                frac.length == 1 -> (frac.toLongOrNull() ?: 0L) * 100L
                frac.length == 2 -> (frac.toLongOrNull() ?: 0L) * 10L
                frac.length >= 3 -> frac.take(3).toLongOrNull() ?: 0L
                else -> 0L
            }
        } else 0L

        return (h * 3600_000L) + (m * 60_000L) + (s * 1000L) + ms
    }

    /**
     * Formats milliseconds into SubRip timestamp format: HH:MM:SS,mmm.
     */
    fun msToSrtTime(totalMs: Long): String {
        val safeMs = totalMs.coerceAtLeast(0L)
        val ms = safeMs % 1000L
        val totalSec = safeMs / 1000L
        val s = totalSec % 60L
        val totalMin = totalSec / 60L
        val m = totalMin % 60L
        val h = totalMin / 60L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    /**
     * Converts ASS timestamp directly to SubRip timestamp string.
     */
    fun assTimeToSrtTime(timeStr: String): String? {
        val ms = assTimeToMs(timeStr) ?: return null
        return msToSrtTime(ms)
    }

    /**
     * Cleans ASS dialogue text:
     * - Replaces \N and \n with standard LF
     * - Replaces \h with standard space
     * - Strips vector drawings
     * - Strips override tags {\...}
     * - Trims and normalizes whitespace
     */
    fun stripAssTags(rawText: String): String {
        // 1. Remove vector drawing commands
        var text = ASS_DRAWING_REGEX.replace(rawText, "")

        // 2. Replace ASS line breaks and spacing
        text = text
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")

        // 3. Remove style tags
        text = ASS_OVERRIDE_TAGS_REGEX.replace(text, "")

        // 4. Clean leading/trailing spaces per line and remove empty lines
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return lines.joinToString("\n")
    }

    /**
     * Detects if content contains ASS or SSA structure.
     */
    fun isAssOrSsa(content: String): Boolean {
        return content.contains("[Events]", ignoreCase = true) ||
                content.contains("[Script Info]", ignoreCase = true) ||
                content.contains("Dialogue:", ignoreCase = true)
    }

    /**
     * Detects if content is already in SubRip (.srt) format.
     */
    fun isSubRip(content: String): Boolean {
        return SUBRIP_TIMING_REGEX.containsMatchIn(content)
    }

    /**
     * Converts ASS/SSA formatted text to standard SubRip (.srt).
     */
    fun convertAssToSrt(assContent: String): String {
        val lines = assContent.lines()
        var inEvents = false
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9

        val cues = mutableListOf<SrtCue>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("!")) {
                continue
            }

            if (line.startsWith("[Events]", ignoreCase = true)) {
                inEvents = true
                continue
            } else if (line.startsWith("[") && line.endsWith("]")) {
                // Another section encountered (e.g. [V4+ Styles], [Fonts])
                if (inEvents) {
                    inEvents = false
                }
                continue
            }

            if (inEvents && line.startsWith("Format:", ignoreCase = true)) {
                val formatCols = line.substringAfter("Format:").split(",").map { it.trim().lowercase() }
                startIdx = formatCols.indexOf("start").takeIf { it >= 0 } ?: 1
                endIdx = formatCols.indexOf("end").takeIf { it >= 0 } ?: 2
                textIdx = formatCols.indexOf("text").takeIf { it >= 0 } ?: (formatCols.size - 1)
                continue
            }

            // Also support Dialogue lines outside explicit [Events] tag if file is truncated
            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val dialoguePayload = line.substringAfter("Dialogue:").trimStart()
                val maxNeededIdx = maxOf(startIdx, endIdx, textIdx)
                val parts = dialoguePayload.split(",", limit = maxNeededIdx + 1)
                if (parts.size > maxNeededIdx) {
                    val rawStart = parts[startIdx].trim()
                    val rawEnd = parts[endIdx].trim()
                    val rawText = parts[textIdx]

                    val startMs = assTimeToMs(rawStart) ?: continue
                    val endMs = assTimeToMs(rawEnd) ?: continue
                    if (endMs < startMs) continue

                    val cleanedText = stripAssTags(rawText)
                    if (cleanedText.isNotBlank()) {
                        cues.add(
                            SrtCue(
                                startMs = startMs,
                                endMs = endMs,
                                startSrt = msToSrtTime(startMs),
                                endSrt = msToSrtTime(endMs),
                                text = cleanedText
                            )
                        )
                    }
                }
            }
        }

        if (cues.isEmpty()) {
            return ""
        }

        // Sort cues chronologically by start time
        cues.sortBy { it.startMs }

        val sb = StringBuilder()
        cues.forEachIndexed { index, cue ->
            sb.append(index + 1).append("\n")
            sb.append(cue.startSrt).append(" --> ").append(cue.endSrt).append("\n")
            sb.append(cue.text).append("\n\n")
        }

        return sb.toString().trimEnd() + "\n"
    }

    /**
     * Normalizes an existing SubRip text:
     * - Standardizes time formats (dots to commas, 3-digit ms)
     * - Renumbers cues sequentially starting at 1
     * - Strips leftover tags and normalizes newlines
     */
    fun normalizeSrt(srtContent: String): String {
        val normalized = srtContent.replace("\r\n", "\n").replace('\r', '\n')
        val matches = SUBRIP_TIMING_REGEX.findAll(normalized).toList()
        if (matches.isEmpty()) return srtContent

        val sb = StringBuilder()
        var cueIndex = 1

        for (i in matches.indices) {
            val match = matches[i]
            val rawStart = match.groupValues[1].replace('.', ',')
            val rawEnd = match.groupValues[2].replace('.', ',')

            // Extract text block between current timing line and next cue index/timing
            val startPos = match.range.last + 1
            val endPos = if (i + 1 < matches.size) {
                // Find start of previous line before next timing match
                val nextStart = matches[i + 1].range.first
                val textSegment = normalized.substring(startPos, nextStart)
                val lastNumIdx = textSegment.lastIndexOfAny(charArrayOf('\n', '\r'))
                if (lastNumIdx != -1) startPos + lastNumIdx else nextStart
            } else {
                normalized.length
            }

            val textBlock = normalized.substring(startPos, endPos)
                .lines()
                .filter { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() && !trimmed.all { it.isDigit() }
                }
                .joinToString("\n")
                .trim()

            if (textBlock.isNotEmpty()) {
                val startMs = assTimeToMs(rawStart) ?: 0L
                val endMs = assTimeToMs(rawEnd) ?: (startMs + 1000L)
                sb.append(cueIndex++).append("\n")
                sb.append(msToSrtTime(startMs)).append(" --> ").append(msToSrtTime(endMs)).append("\n")
                sb.append(textBlock).append("\n\n")
            }
        }

        return sb.toString().trimEnd() + "\n"
    }

    /**
     * Multi-charset auto-detection and decoding:
     * 1. Checks BOM: UTF-8, UTF-16 LE, UTF-16 BE
     * 2. Checks UTF-16 LE without BOM (alternating zero bytes)
     * 3. Tries UTF-8
     * 4. Tries Shift-JIS (standard Japanese subtitle encoding)
     * 5. Tries Windows-1252
     * 6. Lossy UTF-8 fallback
     */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. UTF-8 BOM: 0xEF, 0xBB, 0xBF
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
        }

        // 2. UTF-16 Little Endian BOM: 0xFF, 0xFE
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                .replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
        }

        // 3. UTF-16 Big Endian BOM: 0xFE, 0xFF
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                .replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
        }

        // 4. UTF-16 LE without BOM heuristic (e.g. ASCII tag characters like '[' '\0' 'S' '\0')
        if (bytes.size >= 4 && bytes[1] == 0.toByte() && bytes[3] == 0.toByte()) {
            try {
                val candidate = String(bytes, Charsets.UTF_16LE)
                if (candidate.contains("[Script Info]", ignoreCase = true) ||
                    candidate.contains("[Events]", ignoreCase = true) ||
                    candidate.contains("-->")
                ) {
                    return candidate.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
                }
            } catch (_: Throwable) {}
        }

        // 5. Try clean UTF-8
        try {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (!utf8.contains('\uFFFD')) {
                return utf8.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
            }
        } catch (_: Throwable) {}

        // 6. Try Shift-JIS (critical for legacy Kitsunekko Japanese subtitles)
        try {
            val sjis = String(bytes, CHARSET_SHIFT_JIS)
            if (!sjis.contains('\uFFFD')) {
                return sjis.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
            }
        } catch (_: Throwable) {}

        // 7. Fallback to Windows-1252
        return try {
            val cp1252 = String(bytes, CHARSET_CP1252)
            cp1252.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
        } catch (_: Throwable) {
            String(bytes, Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
        }
    }

    /**
     * Decodes raw bytes, detects format (ASS/SSA vs SRT), converts to SubRip,
     * and returns normalized UTF-8 SubRip text.
     */
    fun decodeAndNormalizeToSrt(bytes: ByteArray): String {
        val decoded = decodeText(bytes)
        if (decoded.isBlank()) return ""

        return when {
            isAssOrSsa(decoded) -> convertAssToSrt(decoded)
            isSubRip(decoded) -> normalizeSrt(decoded)
            else -> decoded
        }
    }

    /**
     * Converts a given subtitle file (.ass, .ssa, .srt) into a UTF-8 .srt file in the destination directory.
     * Guaranteed ZipSlip path protection and atomic file writing.
     */
    fun normalizeFileToSrt(
        inputFile: File,
        destinationDir: File,
        outputBaseName: String? = null
    ): File {
        if (!destinationDir.exists()) destinationDir.mkdirs()

        val baseName = outputBaseName ?: inputFile.nameWithoutExtension
        val srtFileName = if (baseName.endsWith(".srt", ignoreCase = true)) baseName else "$baseName.srt"
        val outFile = File(destinationDir, srtFileName)
        ZipSubtitleExtractor.validateZipSlip(outFile, destinationDir)

        // Read and convert
        val rawBytes = inputFile.readBytes()
        val srtContent = decodeAndNormalizeToSrt(rawBytes)

        val tmpFile = File(destinationDir, "tmp_${System.currentTimeMillis()}_$srtFileName")
        ZipSubtitleExtractor.validateZipSlip(tmpFile, destinationDir)
        try {
            tmpFile.writeText(srtContent, Charsets.UTF_8)
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
            Log.i(TAG, "Normalized subtitle to SRT: ${inputFile.name} -> ${outFile.name} (${outFile.length()} bytes)")
            return outFile
        } catch (e: Throwable) {
            if (tmpFile.exists()) tmpFile.delete()
            throw e
        }
    }
}
