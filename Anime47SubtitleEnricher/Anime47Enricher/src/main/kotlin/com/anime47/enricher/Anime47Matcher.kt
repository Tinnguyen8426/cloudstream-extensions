package com.anime47.enricher

import com.anime47.enricher.models.EpisodeItem
import com.anime47.enricher.models.ParsedMediaInfo
import com.anime47.enricher.models.SearchResult
import java.util.Locale
import kotlin.math.max

/**
 * Normalization and fuzzy matching utility for aligning Torrentio/Media stream titles
 * with Anime47 anime titles and episode numbers.
 */
object Anime47Matcher {

    private val RELEASE_GROUP_REGEX = Regex("""^(\[[^\]]+\]|\([^)]+\))\s*""")
    private val CHECKSUM_REGEX = Regex("""\[[0-9A-Fa-f]{8}\]""")
    private val EXTENSION_REGEX = Regex("""\.(mkv|mp4|avi|webm|ts|m4v)$""", RegexOption.IGNORE_CASE)
    
    private val TECHNICAL_TAGS_REGEX = Regex(
        """\b(1080p|720p|480p|2160p|4k|x264|x265|hevc|avc|h264|h265|10bit|8bit|web-dl|bluray|bdrip|hdtv|aac2?\.?0?|ac3|dts|flac|dual-audio|multi-audio|multi-sub|eng-sub|vostfr|remux|subita|ita)\b""",
        RegexOption.IGNORE_CASE
    )

    private val SEASON_EPISODE_REGEX = Regex("""[Ss](\d+)[Ee](\d+)""", RegexOption.IGNORE_CASE)
    private val EPISODE_TAG_REGEX = Regex("""\b(?:EP|Episode|Tập|Ep)\.?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val DASH_EPISODE_REGEX = Regex("""\s+-\s+(\d{1,4})(?:v\d)?(?=[\s_.\-\[(]|$)""", RegexOption.IGNORE_CASE)
    private val STANDALONE_EPISODE_REGEX = Regex("""(?:\s|\.)[Ee](\d{1,4})(?:v\d)?(?=[\s_.\-\[(]|$)""")

    /**
     * Parses a raw torrent filename or CloudStream episode title into a clean anime title and episode number.
     * Example input: "[SubsPlease] Sousou no Frieren - 05 (1080p) [ABCD1234].mkv"
     * Output: cleanTitle="Sousou no Frieren", episodeNumber=5
     */
    fun parseMediaInfo(rawInput: String): ParsedMediaInfo {
        var text = rawInput.trim()

        // 1. Remove file extension
        text = EXTENSION_REGEX.replace(text, "")

        // 2. Strip leading release group: [SubsPlease] or (HorribleSubs)
        text = RELEASE_GROUP_REGEX.replace(text, "")

        // 3. Strip CRC32 checksum tags like [7BAA1C90]
        text = CHECKSUM_REGEX.replace(text, "")

        // 4. Strip technical tags (resolutions, codecs)
        text = TECHNICAL_TAGS_REGEX.replace(text, "")

        // 5. Detect Season & Episode
        var season: Int? = null
        var episode: Int? = null
        var titlePart = text

        val sAndEMatch = SEASON_EPISODE_REGEX.find(text)
        if (sAndEMatch != null) {
            season = sAndEMatch.groupValues[1].toIntOrNull()
            episode = sAndEMatch.groupValues[2].toIntOrNull()
            titlePart = text.substring(0, sAndEMatch.range.first)
        } else {
            val epTagMatch = EPISODE_TAG_REGEX.find(text)
            if (epTagMatch != null) {
                episode = epTagMatch.groupValues[1].toIntOrNull()
                titlePart = text.substring(0, epTagMatch.range.first)
            } else {
                val dashEpMatch = DASH_EPISODE_REGEX.find(text)
                if (dashEpMatch != null) {
                    episode = dashEpMatch.groupValues[1].toIntOrNull()
                    titlePart = text.substring(0, dashEpMatch.range.first)
                } else {
                    val standaloneMatch = STANDALONE_EPISODE_REGEX.find(text)
                    if (standaloneMatch != null) {
                        episode = standaloneMatch.groupValues[1].toIntOrNull()
                        titlePart = text.substring(0, standaloneMatch.range.first)
                    }
                }
            }
        }

        // Clean up title part: replace dots and underscores with spaces, remove bracket leftovers
        var cleanTitle = titlePart
            .replace(".", " ")
            .replace("_", " ")
            .replace(Regex("""\[.*?\]"""), " ")
            .replace(Regex("""\(.*?\)"""), " ")
            .replace(Regex("""\s+-\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        return ParsedMediaInfo(
            cleanTitle = cleanTitle,
            episodeNumber = episode,
            seasonNumber = season,
            rawTitle = rawInput
        )
    }

    /**
     * Finds the best matching SearchResult from Anime47 for a given title.
     * Evaluates primary title and alternative titles (English, Romanized, Vietnamese).
     */
    fun findBestAnimeMatch(cleanQuery: String, candidates: List<SearchResult>): SearchResult? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        val normalizedQuery = normalizeString(cleanQuery)
        var bestScore = 0.0
        var bestMatch: SearchResult? = null

        for (candidate in candidates) {
            // Check primary title
            var score = calculateSimilarity(normalizedQuery, normalizeString(candidate.title))

            // Check alternative titles
            candidate.titles?.forEach { alt ->
                alt.title?.let { altTitle ->
                    val altScore = calculateSimilarity(normalizedQuery, normalizeString(altTitle))
                    if (altScore > score) {
                        score = altScore
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestMatch = candidate
            }
        }

        // Return best match if similarity passes reasonable confidence threshold (0.45+)
        return if (bestScore >= 0.45) bestMatch else candidates.firstOrNull()
    }

    /**
     * Locates the exact EpisodeItem matching the requested episode number.
     */
    fun findMatchingEpisode(targetEpisode: Int, episodes: List<EpisodeItem>): EpisodeItem? {
        if (episodes.isEmpty()) return null

        // 1. Direct number match
        val directMatch = episodes.firstOrNull { it.parsedNumber == targetEpisode }
        if (directMatch != null) return directMatch

        // 2. Formatted title match (e.g. "05" or "5")
        val formattedTarget = String.format(Locale.ROOT, "%02d", targetEpisode)
        val titleMatch = episodes.firstOrNull {
            it.title?.trim() == targetEpisode.toString() || it.title?.trim() == formattedTarget
        }
        if (titleMatch != null) return titleMatch

        // 3. Substring match in title
        val epRegex = Regex("""\b0*${targetEpisode}\b""")
        return episodes.firstOrNull { ep ->
            ep.title?.let { epRegex.containsMatchIn(it) } == true
        }
    }

    /**
     * Normalizes text for comparison by lowercasing and stripping punctuation.
     */
    fun normalizeString(input: String): String {
        return input.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Calculates bigram-based Dice similarity coefficient between two strings (0.0 to 1.0).
     */
    fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        if (s1.contains(s2) || s2.contains(s1)) {
            val lengthRatio = s1.length.toDouble() / s2.length.toDouble()
            val score = if (lengthRatio > 1.0) 1.0 / lengthRatio else lengthRatio
            return max(score, 0.8)
        }

        val bigrams1 = s1.windowed(2, 1, false)
        val bigrams2 = s2.windowed(2, 1, false)

        if (bigrams1.isEmpty() || bigrams2.isEmpty()) return 0.0

        val intersection = bigrams1.intersect(bigrams2.toSet()).size
        return (2.0 * intersection) / (bigrams1.size + bigrams2.size)
    }
}
