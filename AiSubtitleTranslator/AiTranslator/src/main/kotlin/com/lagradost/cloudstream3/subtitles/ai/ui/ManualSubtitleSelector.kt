package com.lagradost.cloudstream3.subtitles.ai.ui

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitlePriorityEvaluator

/**
 * Pure-Kotlin decision logic for the manual "Dịch" button.
 * No Android dependencies so it stays unit-testable.
 */
object ManualSubtitleSelector {

    sealed class Result {
        object NotSelected : Result()
        object Embedded : Result()
        object NonHttp : Result()
        object AlreadyTarget : Result()
        object AlreadyAi : Result()
        data class Ready(val subtitleFile: SubtitleFile) : Result()
    }

    fun evaluate(
        name: String?,
        url: String?,
        languageCode: String?,
        originName: String?,
        targetLang: String,
        headers: Map<String, String> = emptyMap()
    ): Result {
        val cleanUrl = url?.trim().orEmpty()
        if (cleanUrl.isEmpty()) return Result.NotSelected

        if (originName?.equals("EMBEDDED_IN_VIDEO", ignoreCase = true) == true) return Result.Embedded
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return Result.NonHttp
        }

        val label = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: languageCode?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""

        if (label.contains("[AI", ignoreCase = true) || AITranslatorLocalServer.isOwnSubtitleUrl(cleanUrl)) {
            return Result.AlreadyAi
        }

        val subFile = SubtitleFile(lang = label.ifBlank { "unknown" }, url = cleanUrl)
        subFile.headers = headers

        val score = SubtitlePriorityEvaluator.calculateScore(
            subFile = subFile,
            targetLang = targetLang,
            priorityMode = TranslatorConfig.sourcePriorityMode,
            customList = TranslatorConfig.getCustomPriorityLanguages()
        )
        if (score == SubtitlePriorityEvaluator.SCORE_BYPASS_TARGET_LANG) return Result.AlreadyTarget

        return Result.Ready(subFile)
    }
}