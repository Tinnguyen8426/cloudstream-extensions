package com.lagradost.cloudstream3.subtitles.ai.session

import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue

/**
 * Detects partial subtitle tracks (e.g. signs/songs or a truncated file)
 * before spending API tokens on translation.
 */
object SubtitleCoverageValidator {

    fun isLikelyPartial(cues: List<SubtitleCue>, durationMs: Long?): Boolean {
        if (durationMs == null || durationMs <= 0L) return false
        if (cues.isEmpty()) return false

        val durationMinutes = durationMs / 60_000.0
        if (durationMinutes < 5.0) return false

        val lastEnd = cues.maxOfOrNull { it.endMs } ?: 0L
        if (lastEnd < (durationMs * 0.35)) return true

        val minCueCount = maxOf(8, (durationMinutes * 1.5).toInt())
        return cues.size < minCueCount
    }
}