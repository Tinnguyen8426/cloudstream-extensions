package com.lagradost.cloudstream3.subtitles.ai.session

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import java.util.Locale

/**
 * Intelligent Source Language Priority Evaluator and Target Language Bypass Engine.
 *
 * Directs subtitle selection based on content genre (e.g. Anime vs Western movies)
 * to maximize translation fidelity.
 *
 * Core Directives:
 * 1. Target Language Bypass: Returns score -1000 if an existing subtitle track matches
 *    the target language, completely bypassing AI translation to eliminate network overhead,
 *    latency, token waste, and duplicate tracks in player settings.
 * 2. Anime First: Japanese (1000) > English (500) > Others (100).
 * 3. English / Movies First: English (1000) > Japanese (500) > Others (100).
 * 4. Custom Priority: Linear ranking (1000 - index * 100) based on user-defined list.
 * 5. Smart Auto: English (800) > Japanese (750) > Others (100).
 */
object SubtitlePriorityEvaluator {

    const val SCORE_BYPASS_TARGET_LANG = -1000

    /**
     * Calculates the suitability score of a subtitle candidate track.
     *
     * @param subFile Subtitle file candidate metadata.
     * @param targetLang Target translation language code (e.g. "vi").
     * @param priorityMode Selected priority mode from TranslatorConfig.
     * @param customList User-defined prioritized language tags for PRIORITY_CUSTOM.
     * @return Calculated integer priority score, or -1000 for bypass.
     */
    fun calculateScore(
        subFile: SubtitleFile,
        targetLang: String,
        priorityMode: String = TranslatorConfig.PRIORITY_AUTO,
        customList: List<String> = emptyList()
    ): Int {
        val lang = subFile.lang.trim().lowercase(Locale.ROOT)

        // 1. Target Language Bypass Rule: 0 redundant requests if target language is already present
        if (isMatchingLanguage(lang, targetLang)) {
            return SCORE_BYPASS_TARGET_LANG
        }

        // 2. Score based on configured strategy
        return when (priorityMode) {
            TranslatorConfig.PRIORITY_ANIME_FIRST -> {
                when {
                    isJapanese(lang) -> 1000
                    isEnglish(lang) -> 500
                    else -> 100
                }
            }
            TranslatorConfig.PRIORITY_ENGLISH_FIRST -> {
                when {
                    isEnglish(lang) -> 1000
                    isJapanese(lang) -> 500
                    else -> 100
                }
            }
            TranslatorConfig.PRIORITY_CUSTOM -> {
                val index = customList.indexOfFirst { isMatchingLanguage(lang, it) }
                if (index >= 0) {
                    (1000 - (index * 100)).coerceAtLeast(150)
                } else {
                    50
                }
            }
            else -> { // PRIORITY_AUTO
                when {
                    isEnglish(lang) -> 800
                    isJapanese(lang) -> 750
                    else -> 100
                }
            }
        }
    }

    /**
     * Evaluates a candidate pool of subtitle tracks and selects the optimal source.
     * If any track is already in the target language (score -1000), it is returned immediately
     * so playback uses the native stream without triggering AI translation.
     */
    fun selectBestSubtitle(
        candidates: List<SubtitleFile>,
        targetLang: String,
        priorityMode: String = TranslatorConfig.PRIORITY_AUTO,
        customList: List<String> = emptyList()
    ): SubtitleFile? {
        if (candidates.isEmpty()) return null

        // If any candidate matches target language, bypass AI translation immediately
        val bypassCandidate = candidates.firstOrNull {
            calculateScore(it, targetLang, priorityMode, customList) == SCORE_BYPASS_TARGET_LANG
        }
        if (bypassCandidate != null) return bypassCandidate

        // Otherwise return candidate with highest priority score
        return candidates.maxByOrNull { calculateScore(it, targetLang, priorityMode, customList) }
    }

    /**
     * Checks if language label or code denotes Japanese.
     */
    fun isJapanese(lang: String): Boolean {
        val clean = lang.trim().lowercase(Locale.ROOT)
        return clean == "ja" || clean == "jp" || clean == "jpn" ||
                clean.startsWith("ja-") || clean.startsWith("jp-") || clean.startsWith("ja_") ||
                clean.contains("japan") || clean.contains("tiếng nhật") || clean.contains("ti?ng nh?t") || clean.contains("nihongo")
    }

    /**
     * Checks if language label or code denotes English, avoiding false substring collisions.
     */
    fun isEnglish(lang: String): Boolean {
        val clean = lang.trim().lowercase(Locale.ROOT)
        val tokens = clean.split(Regex("[^a-zA-Z0-9]+"))
        return tokens.contains("en") || tokens.contains("eng") || tokens.contains("english") ||
                clean.contains("tiếng anh") || clean.contains("ti?ng anh") || clean.startsWith("en-") || clean.startsWith("en_")
    }

    /**
     * Checks if language label or code denotes Vietnamese.
     */
    fun isVietnamese(lang: String): Boolean {
        val clean = lang.trim().lowercase(Locale.ROOT)
        val tokens = clean.split(Regex("[^a-zA-Z0-9]+"))
        return tokens.contains("vi") || tokens.contains("vie") || tokens.contains("vietnamese") ||
                clean.contains("tiếng việt") || clean.contains("ti?ng vi?t") || clean.startsWith("vi-") || clean.startsWith("vi_")
    }

    /**
     * Checks if language label or code denotes Spanish.
     */
    fun isSpanish(lang: String): Boolean {
        val clean = lang.trim().lowercase(Locale.ROOT)
        val tokens = clean.split(Regex("[^a-zA-Z0-9]+"))
        return tokens.contains("es") || tokens.contains("spa") || tokens.contains("spanish") ||
                clean.contains("tiếng tây ban nha") || clean.startsWith("es-") || clean.startsWith("es_")
    }

    /**
     * Checks if language label or code denotes French.
     */
    fun isFrench(lang: String): Boolean {
        val clean = lang.trim().lowercase(Locale.ROOT)
        val tokens = clean.split(Regex("[^a-zA-Z0-9]+"))
        return tokens.contains("fr") || tokens.contains("fra") || tokens.contains("fre") || tokens.contains("french") ||
                clean.contains("tiếng pháp") || clean.startsWith("fr-") || clean.startsWith("fr_")
    }

    /**
     * Checks if language label or code denotes Chinese.
     */
    fun isChinese(lang: String): Boolean {
        val clean = lang.trim().lowercase(Locale.ROOT)
        val tokens = clean.split(Regex("[^a-zA-Z0-9]+"))
        return tokens.contains("zh") || tokens.contains("chi") || tokens.contains("zho") || tokens.contains("chinese") ||
                clean.contains("tiếng trung") || clean.startsWith("zh-") || clean.startsWith("zh_")
    }

    /**
     * Checks if language label or code denotes Korean.
     */
    fun isKorean(lang: String): Boolean {
        val clean = lang.trim().lowercase(Locale.ROOT)
        val tokens = clean.split(Regex("[^a-zA-Z0-9]+"))
        return tokens.contains("ko") || tokens.contains("kor") || tokens.contains("korean") ||
                clean.contains("tiếng hàn") || clean.startsWith("ko-") || clean.startsWith("ko_")
    }

    /**
     * Verifies whether a candidate language string corresponds to a target language code.
     */
    fun isMatchingLanguage(lang: String, target: String): Boolean {
        val cleanLang = lang.trim().lowercase(Locale.ROOT)
        val cleanTarget = target.trim().lowercase(Locale.ROOT)

        if (cleanLang == cleanTarget) return true
        if (cleanLang.startsWith("$cleanTarget-") || cleanLang.startsWith("${cleanTarget}_")) return true

        if (cleanTarget == "vi" || cleanTarget == "vie" || cleanTarget == "vietnamese") {
            return isVietnamese(cleanLang)
        }
        if (cleanTarget == "ja" || cleanTarget == "jpn" || cleanTarget == "japanese") {
            return isJapanese(cleanLang)
        }
        if (cleanTarget == "en" || cleanTarget == "eng" || cleanTarget == "english") {
            return isEnglish(cleanLang)
        }
        if (cleanTarget == "es" || cleanTarget == "spa" || cleanTarget == "spanish") {
            return isSpanish(cleanLang)
        }
        if (cleanTarget == "fr" || cleanTarget == "fra" || cleanTarget == "french") {
            return isFrench(cleanLang)
        }
        if (cleanTarget == "zh" || cleanTarget == "chi" || cleanTarget == "chinese") {
            return isChinese(cleanLang)
        }
        if (cleanTarget == "ko" || cleanTarget == "kor" || cleanTarget == "korean") {
            return isKorean(cleanLang)
        }

        val tokens = cleanLang.split(Regex("[^a-zA-Z0-9]+"))
        return tokens.contains(cleanTarget)
    }
}
