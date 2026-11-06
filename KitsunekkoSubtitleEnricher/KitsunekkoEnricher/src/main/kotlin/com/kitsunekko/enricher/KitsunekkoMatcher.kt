package com.kitsunekko.enricher

import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/**
 * Normalization, season penalty scoring, Bigram Dice fuzzy matching,
 * and episode extraction engine for Kitsunekko Japanese Subtitle Enricher.
 */
object KitsunekkoMatcher {

    private val NORM_REGEX = Regex("""[^\p{L}\p{N}]+""")

    private val RELEASE_GROUP_REGEX = Regex("""^(?:(\[[^\]]+\]|\([^)]+\))\s*)+""")
    private val CHECKSUM_REGEX = Regex("""\[[0-9A-Fa-f]{8}\]|\[CRC(?:-?32)?\]""", RegexOption.IGNORE_CASE)
    private val EXTENSION_REGEX = Regex(
        """\.(mkv|mp4|avi|webm|ts|m4v|ass|srt|ssa|zip)$""",
        RegexOption.IGNORE_CASE
    )

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

    private val SEASON_EPISODE_REGEX = Regex("""[Ss](\d{1,2})\s*[._-]?\s*[Ee](\d{1,4})""", RegexOption.IGNORE_CASE)
    private val EPISODE_TAG_REGEX = Regex(
        """(?:^|[^0-9a-z])(?:ep?|episode|tap|tập|第)[\s._-]?(\d{1,4})(?:\s*話)?(?![0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val DASH_EPISODE_REGEX = Regex(
        """(?:\s+-\s*|(?<=[a-zA-Z0-9])-\s*)(\d{1,4})(?:v\d)?(?=[\s_.\-\[(]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val STANDALONE_EPISODE_REGEX = Regex("""(?:^|[^a-z0-9])[Ee](\d{1,4})(?:v\d)?(?![0-9])""")

    private val FALLBACK_EPISODE_REGEX = Regex(
        """(?<!\d\.)(?<![0-9a-z])(\d{1,4})(?![0-9]|\.\d|\s*(?:k|bit|kbps|fps|hz)\b)""",
        RegexOption.IGNORE_CASE
    )

    private val RESOLUTION_NUMBERS = setOf(360, 480, 576, 720, 1080, 2160)

    private val STANDALONE_NUMBERED_TITLES = setOf(
        "district 9", "super 8", "apollo 13", "ocean s 11", "oceans 11", "big hero 6"
    )

    private val ROMAN_MAP = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5,
        "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9
    )

    // ==========================================
    // 1. Unicode Title Normalization
    // ==========================================

    /**
     * Normalizes title using Unicode NFC composition, lowercase conversion,
     * stripping all characters outside \p{L} (letters across all alphabets,
     * including Japanese Kanji/Kana) and \p{N} (numbers), and collapsing spaces.
     */
    fun normTitle(s: String): String {
        if (s.isEmpty()) return ""
        return Normalizer.normalize(s, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .replace(NORM_REGEX, " ")
            .trim()
    }

    // ==========================================
    // 2. Bigram Dice Title Similarity (0..100)
    // ==========================================

    /**
     * Calculates Bigram Dice similarity coefficient (0 to 100) between two titles.
     * Contains 4+ char substring containment boost (75 pts) and length guards.
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
     * Highest similarity score between candidate and any title in [titles].
     */
    fun bestMatchScore(candidate: String, titles: List<String>): Int =
        titles.filter { it.isNotBlank() }.maxOfOrNull { titleSimilarity(candidate, it) } ?: 0

    // ==========================================
    // 3. Season Detection & Season Hint
    // ==========================================

    /**
     * Detects season number from a title string using keywords, ordinals,
     * Roman numerals (I..IX), single-letter S notation, or trailing numbers.
     */
    fun seasonOf(title: String): Int? {
        val t = normTitle(title)
        if (t.isEmpty()) return null

        // 1. Standalone movie defense
        if (STANDALONE_NUMBERED_TITLES.contains(t)) return null

        // 2. Pure numeric title defense (e.g. anime "86")
        if (!t.any { it.isLetter() }) return null

        // 3. Explicit SxxExx pattern
        SEASON_EPISODE_REGEX.find(title)?.let {
            it.groupValues[1].toIntOrNull()?.let { s -> return s }
        }

        // 4. Keyword markers (part, season, ss, cour, act, arc) or single-letter S followed by digits
        Regex("""\b(?:(?:part|season|ss|cour|act|arc)\s*|s)(\d{1,2})\b""").find(t)?.let {
            it.groupValues[1].toIntOrNull()?.let { s -> return s }
        }

        // 5. Ordinal seasons: "1st Season", "2nd Season", "3rd", "4th", "2nd Cour", etc.
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)(?:\s+(?:season|cour|part)\b|\s*$)""").find(t)?.let {
            it.groupValues[1].toIntOrNull()?.let { s -> return s }
        }

        // 6. Roman numeral I: "Part I", "Season I", or trailing " I"
        if (Regex("""\b(?:part|season|ss|cour|arc)\s+i\b""").containsMatchIn(t) ||
            Regex("""[\s_]i$""").containsMatchIn(t)) {
            return 1
        }

        // 7. Roman numerals II to IX
        for ((roman, num) in ROMAN_MAP) {
            if (Regex("""\b$roman\b""").containsMatchIn(t)) return num
        }

        // 8. Trailing number (e.g. "One Punch Man 2", "Jujutsu Kaisen 0")
        Regex("""[\s_](\d{1,2})$""").find(t.trim())?.let {
            it.groupValues[1].toIntOrNull()?.let { s -> return s }
        }

        return null
    }

    /**
     * Lowest season number across all candidate titles.
     */
    fun seasonHintOf(titles: List<String>): Int? =
        titles.filter { it.isNotBlank() }.mapNotNull { seasonOf(it) }.minOrNull()

    // ==========================================
    // 4. Critical Season Penalty Rule
    // ==========================================

    /**
     * Computes similarity score of a Kitsunekko folder with critical season penalty.
     * Deducts 200 points for mismatched season (e.g. query Season 1 vs cand Season 2).
     */
    fun scoreFolder(
        folderTitle: String,
        queryTitles: List<String>,
        seasonHint: Int? = seasonHintOf(queryTitles)
    ): Int {
        var score = bestMatchScore(folderTitle, queryTitles)
        val candSeason = seasonOf(folderTitle)

        // Critical rule: Mismatched season deduction
        if (seasonHint != null && candSeason != null && candSeason != seasonHint) {
            score -= 200
        }
        // Query specifies season but candidate folder does not
        if (seasonHint != null && candSeason == null) {
            score -= 10
        }
        // Query does not specify season but candidate folder does
        if (seasonHint == null && candSeason != null) {
            score -= 15
        }
        return score
    }

    /**
     * Filters and ranks Kitsunekko folders by match score.
     * Only returns candidates with score >= [threshold] (default 40), up to [limit] (default 2).
     */
    fun rankFolders(
        folders: List<KitsunekkoFolder>,
        queryTitles: List<String>,
        seasonHint: Int? = seasonHintOf(queryTitles),
        threshold: Int = 40,
        limit: Int = 2
    ): List<Pair<KitsunekkoFolder, Int>> {
        return folders
            .map { folder -> folder to scoreFolder(folder.title, queryTitles, seasonHint) }
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .take(limit)
    }

    // ==========================================
    // 5. Episode Number Extraction
    // ==========================================

    private fun validEpisode(n: Int?): Int? = n?.takeIf { it in 0..3000 }

    /**
     * Robust episode number extraction with cascading regex priority.
     * Supports Episode 0..3000; filters release years and video resolutions.
     */
    fun extractEpisodeNumber(text: String): Int? {
        if (text.isBlank()) return null

        // 1. SxxExx pattern
        SEASON_EPISODE_REGEX.findAll(text).lastOrNull()?.let {
            validEpisode(it.groupValues[2].toIntOrNull())?.let { ep -> return ep }
        }

        // 2. Keyword tag: EP, Episode, Tập
        EPISODE_TAG_REGEX.findAll(text).lastOrNull()?.let {
            validEpisode(it.groupValues[1].toIntOrNull())?.let { ep -> return ep }
        }

        // 3. Anime dash notation: " - 05" or "Show - 14v2"
        DASH_EPISODE_REGEX.find(text)?.let {
            val num = it.groupValues[1].toIntOrNull()
            if (num != null && num !in 1900..2100 && num !in RESOLUTION_NUMBERS) {
                validEpisode(num)?.let { ep -> return ep }
            }
        }

        // 4. Standalone E notation: "E05"
        STANDALONE_EPISODE_REGEX.find(text)?.let {
            validEpisode(it.groupValues[1].toIntOrNull())?.let { ep -> return ep }
        }

        // 5. Standalone numbers with technical tags pre-filtered
        val cleaned = TECHNICAL_TAGS_REGEX.replace(text, " ")
        val numbers = FALLBACK_EPISODE_REGEX.findAll(cleaned)
            .mapNotNull { validEpisode(it.groupValues[1].toIntOrNull()) }
            .filter { it !in RESOLUTION_NUMBERS && it !in 1900..2100 }
            .toList()

        return numbers.firstOrNull()
    }

    // ==========================================
    // 6. Media Info Parser
    // ==========================================

    /**
     * Parses raw video filename, magnet link name, or stream title into clean metadata.
     */
    fun parseMediaInfo(rawInput: String): ParsedMediaInfo {
        var text = rawInput.trim()
        if (text.isEmpty()) {
            return ParsedMediaInfo(title = "", episodeNumber = null, seasonNumber = null, rawTitle = rawInput)
        }

        // Handle URL/magnet encoding
        if (text.contains("dn=")) {
            text = text.substringAfter("dn=").substringBefore("&")
            try {
                text = URLDecoder.decode(text, "UTF-8")
            } catch (_: Throwable) {}
        } else if (text.contains("%20") || text.contains("%5B")) {
            try {
                text = URLDecoder.decode(text, "UTF-8")
            } catch (_: Throwable) {}
        }

        // 1. Strip file extension
        text = EXTENSION_REGEX.replace(text, "")

        // 2. Strip release groups: [SubsPlease], (HorribleSubs)
        text = RELEASE_GROUP_REGEX.replace(text, "")

        // 3. Strip checksums: [ABCD1234]
        text = CHECKSUM_REGEX.replace(text, "")

        // 4. Detect Season & Episode
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
                if (dashEpMatch != null && dashNum != null && dashNum !in 1900..2100 && dashNum !in RESOLUTION_NUMBERS) {
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

        if (episode == null) {
            episode = extractEpisodeNumber(text)
        }
        if (season == null) {
            season = seasonOf(titlePart) ?: seasonOf(text)
        }

        // 5. Strip technical tags from titlePart
        titlePart = TECHNICAL_TAGS_REGEX.replace(titlePart, " ")

        // 6. Clean punctuation
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
            title = cleanTitle,
            episodeNumber = episode,
            seasonNumber = season,
            rawTitle = rawInput
        )
    }
}
