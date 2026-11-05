package com.stremio.subenricher

import java.text.Normalizer
import java.util.Locale

/**
 * Normalization, noise stripping, season/episode extraction, and Bigram Dice
 * similarity scoring engine for Stremio Subtitle Enricher.
 */
object StremioSubMatcher {

    private val RELEASE_GROUP_REGEX = Regex("""^(?:(\[[^\]]+\]|\([^)]+\))\s*)+""")
    private val CHECKSUM_REGEX = Regex("""\[[0-9A-Fa-f]{8}\]|\[CRC(?:-?32)?\]""", RegexOption.IGNORE_CASE)
    private val EXTENSION_REGEX = Regex("""\.(mkv|mp4|avi|webm|ts|m4v)$""", RegexOption.IGNORE_CASE)

    private val TECHNICAL_TAGS_REGEX = Regex(
        """(?<![a-zA-Z0-9])(?:""" +
        """2160p|1080p|1080i|720p|576p|480p|360p|4k|2k|uhd|fhd|hd|""" +
        """x\.?264|x\.?265|hevc|avc|h\.?264|h\.?265|10bit|8bit|av1|xvid|divx|remux|bdremux|""" +
        """web-?dl|web-?rip|bluray|blu-ray|bdrip|brrip|hdtv|dvdrip|telesync|camrip|""" +
        """aac2?\.?0?|aac5\.1|ac3|dts(?:-hd)?|flac(?:\s*2\.0)?|truehd|atmos|eac3|mp3|""" +
        """ddp?(?:[\s.]*5\.1)?|5\.1|7\.1|""" +
        """hdr(?:10(?:\+|plus)?)?|sdr|dovi|dv|dolby[-.\s]?vision|""" +
        """\d+kbps|""" +
        """dual-audio|multi-audio|multi-sub|multiple\s+subtitles?|eng-sub|vostfr|subita|ita|raw""" +
        """)(?![a-zA-Z0-9])""",
        RegexOption.IGNORE_CASE
    )

    private val SEASON_EPISODE_REGEX = Regex("""[Ss](\d{1,2})[._-]?[Ee](\d{1,4})""", RegexOption.IGNORE_CASE)
    private val EPISODE_TAG_REGEX = Regex("""(?:^|[^0-9a-z])(?:ep?|episode|tap|tập)[\s._-]?(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    private val DASH_EPISODE_REGEX = Regex("""(?:\s+-\s*|(?<=[a-zA-Z0-9])-\s*)(\d{1,4})(?:v\d)?(?=[\s_.\-\[(]|$)""", RegexOption.IGNORE_CASE)
    private val STANDALONE_EPISODE_REGEX = Regex("""(?:^|[^a-z0-9])[Ee](\d{1,4})(?:v\d)?(?![0-9])""")
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")

    private val FALLBACK_EPISODE_REGEX = Regex(
        """(?<!\d\.)(?<![0-9a-z])(\d{1,4})(?![0-9]|\.\d|\s*(?:k|bit|kbps|fps|hz)\b)""",
        RegexOption.IGNORE_CASE
    )

    private val RESOLUTION_NUMBERS = setOf(360, 480, 576, 720, 1080, 2160)

    private val STANDALONE_NUMBERED_TITLES = setOf(
        "district 9",
        "super 8",
        "apollo 13",
        "ocean s 11",
        "oceans 11",
        "big hero 6"
    )

    private val ROMAN_MAP = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5,
        "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9
    )

    private val NORM_REGEX = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Normalizes a title by applying Unicode NFC normalization (merging NFD combining accents),
     * converting to lowercase, retaining all Unicode letters and numbers across global scripts
     * (Latin, Vietnamese, CJK, Cyrillic, etc.), and collapsing whitespace.
     */
    fun normTitle(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .replace(NORM_REGEX, " ")
            .trim()

    /**
     * Calculates Bigram Dice similarity coefficient between two titles (0 to 100).
     */
    fun titleSimilarity(a: String, b: String): Int {
        val x = normTitle(a)
        val y = normTitle(b)
        if (x.isEmpty() || y.isEmpty()) return 0
        if (x == y) return 100
        if (x.length >= 4 && y.length >= 4 && (x.contains(y) || y.contains(x))) return 75
        if (x.length == 1 && y.length == 1) return if (x == y) 100 else 0

        val bx = x.replace(" ", "").windowed(2).toSet()
        val by = y.replace(" ", "").windowed(2).toSet()
        if (bx.isEmpty() || by.isEmpty()) return 0
        return 200 * bx.intersect(by).size / (bx.size + by.size)
    }

    /**
     * Returns the highest similarity score between candidate and any title in [titles].
     */
    fun bestMatchScore(candidate: String, titles: List<String>): Int =
        titles.maxOfOrNull { titleSimilarity(candidate, it) } ?: 0

    /**
     * Extracts release year (4 digits, 1900-2099) from string if present.
     */
    fun extractYear(text: String): Int? =
        YEAR_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Scores a candidate title against query titles with a +15 bonus if releaseInfo year
     * matches [queryYear] within 1 year.
     */
    fun scoreCandidate(
        candidateTitle: String,
        candidateReleaseInfo: String?,
        queryTitles: List<String>,
        queryYear: Int?
    ): Int {
        var score = bestMatchScore(candidateTitle, queryTitles)
        if (score <= 15) return 0
        if (queryYear != null && candidateReleaseInfo != null) {
            val startYear = extractYear(candidateReleaseInfo)
            if (startYear != null && kotlin.math.abs(startYear - queryYear) <= 1) {
                score += 15
            }
        }
        return score
    }

    /**
     * Detects season number from a title string using keywords, ordinals, Roman numerals,
     * or TV indicator-preceded trailing numbers.
     * Standalone numbered movies (e.g. District 9, Super 8) safely return null.
     */
    fun seasonOf(title: String): Int? {
        val t = normTitle(title)
        if (t.isEmpty()) return null

        // 1. Standalone movie defense-in-depth protection
        if (STANDALONE_NUMBERED_TITLES.contains(t)) return null

        // 2. Explicit SxxExx pattern (e.g. "Show S02E05" -> 2)
        SEASON_EPISODE_REGEX.find(title)?.let {
            it.groupValues[1].toIntOrNull()?.let { s -> return s }
        }

        // 3. Keyword marker: season, part, ss, cour, act, arc, or compact single-letter S followed by digits
        // Note: single 's' requires digits immediately adjacent (no whitespace) to prevent possessive 's collision
        Regex("""\b(?:(?:part|season|ss|cour|act|arc)\s*|s)(\d{1,2})\b""").find(t)?.let {
            it.groupValues[1].toIntOrNull()?.let { s -> return s }
        }

        // 4. Ordinal seasons: "1st Season", "2nd Season", "3rd", "2nd Cour", etc.
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)(?:\s+(?:season|cour|part)\b|\s*$)""").find(t)?.let {
            it.groupValues[1].toIntOrNull()?.let { s -> return s }
        }

        // 5. Roman numeral I: "Part I", "Season I", or trailing " I" at title end
        if (Regex("""\b(?:part|season|ss|cour|arc)\s+i\b""").containsMatchIn(t) ||
            Regex("""[\s_]i$""").containsMatchIn(t)) {
            return 1
        }

        // 6. Roman numerals II to IX: "Show II" .. "Show IX"
        for ((roman, num) in ROMAN_MAP) {
            if (Regex("""\b$roman\b""").containsMatchIn(t)) return num
        }

        // 7. Trailing number with TV indicator (e.g. "Show 2", "Show 5", "Series 3", "TV 2")
        Regex("""\b(?:show|series|tv|cour|act|arc)\s*(\d{1,2})$""").find(t)?.let {
            it.groupValues[1].toIntOrNull()?.let { s -> return s }
        }

        return null
    }

    /**
     * Returns the lowest season number found across all titles, or null if none have season markers.
     */
    fun seasonHintOf(titles: List<String>): Int? =
        titles.filter { it.isNotBlank() }.mapNotNull { seasonOf(it) }.minOrNull()

    /**
     * Extracts episode number from arbitrary text or filename.
     */
    fun extractEpisodeNumber(text: String): Int? {
        fun valid(n: Int?): Int? = n?.takeIf { it in 0..3000 }

        // 1. SxxExx
        SEASON_EPISODE_REGEX.findAll(text).lastOrNull()?.let {
            valid(it.groupValues[2].toIntOrNull())?.let { ep -> return ep }
        }

        // 2. Keyword marker (EP, Episode, Tập)
        EPISODE_TAG_REGEX.findAll(text).lastOrNull()?.let {
            valid(it.groupValues[1].toIntOrNull())?.let { ep -> return ep }
        }

        // 3. Dash anime episode (excluding release years)
        DASH_EPISODE_REGEX.find(text)?.let {
            val num = it.groupValues[1].toIntOrNull()
            if (num != null && num !in 1900..2100) {
                valid(num)?.let { ep -> return ep }
            }
        }

        // 4. Standalone Exx
        STANDALONE_EPISODE_REGEX.find(text)?.let {
            valid(it.groupValues[1].toIntOrNull())?.let { ep -> return ep }
        }

        // 5. Standalone numbers with technical tags pre-filtered and guarded
        val cleaned = TECHNICAL_TAGS_REGEX.replace(text, " ")
        val numbers = FALLBACK_EPISODE_REGEX.findAll(cleaned)
            .mapNotNull { valid(it.groupValues[1].toIntOrNull()) }
            .filter { it !in RESOLUTION_NUMBERS && it !in 1900..2100 }
            .toList()

        return numbers.firstOrNull()
    }

    /**
     * Full media parser converting raw filename or stream description into clean metadata.
     */
    fun parseMediaInfo(rawInput: String): ParsedMediaInfo {
        var text = rawInput.trim()
        if (text.isEmpty()) {
            return ParsedMediaInfo(cleanTitle = "", rawTitle = rawInput)
        }

        // 1. Strip container extensions
        text = EXTENSION_REGEX.replace(text, "")

        // 2. Strip leading release group
        text = RELEASE_GROUP_REGEX.replace(text, "")

        // 3. Strip CRC32 tags
        text = CHECKSUM_REGEX.replace(text, "")

        // 4. Extract release year
        val year = extractYear(text)

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
            val epTagMatch = EPISODE_TAG_REGEX.findAll(text).lastOrNull()
            if (epTagMatch != null) {
                episode = epTagMatch.groupValues[1].toIntOrNull()
                titlePart = text.substring(0, epTagMatch.range.first)
            } else {
                val dashEpMatch = DASH_EPISODE_REGEX.find(text)
                val dashNum = dashEpMatch?.groupValues?.get(1)?.toIntOrNull()
                if (dashEpMatch != null && dashNum != null && dashNum !in 1900..2100) {
                    episode = dashNum
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

        if (season == null) {
            season = seasonOf(text)
        }

        // 6. Strip technical tags from title
        titlePart = TECHNICAL_TAGS_REGEX.replace(titlePart, " ")
        if (year != null) {
            val withoutYear = YEAR_REGEX.replace(titlePart, " ").trim()
            if (withoutYear.any { it.isLetter() } && withoutYear.length >= 3) {
                titlePart = withoutYear
            }
        }

        // 7. Clean punctuation and delimiters (preserving arc words)
        var cleanTitle = titlePart
            .replace(".", " ")
            .replace("_", " ")
            .replace("[", " ")
            .replace("]", " ")
            .replace("(", " ")
            .replace(")", " ")
            .replace(Regex("""\s+-\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (cleanTitle.isEmpty()) {
            cleanTitle = rawInput.trim()
        }

        return ParsedMediaInfo(
            cleanTitle = cleanTitle,
            seasonNumber = season,
            episodeNumber = episode,
            year = year,
            rawTitle = rawInput
        )
    }
}
