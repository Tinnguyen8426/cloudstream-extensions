package com.lagradost.cloudstream3.subtitles.ai.client

import com.lagradost.cloudstream3.subtitles.ai.models.ContextCue
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CancellableHttpResponse(
    val code: Int,
    val isSuccessful: Boolean,
    val body: String
)

internal fun buildAiOkHttpClient(httpClientOverride: OkHttpClient?): OkHttpClient {
    if (httpClientOverride != null) return httpClientOverride
    return try {
        com.lagradost.cloudstream3.app.baseClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    } catch (_: Throwable) {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }
}

internal fun OkHttpClient.withShortTimeout(timeoutSeconds: Long): OkHttpClient {
    val clamped = timeoutSeconds.coerceAtLeast(1L)
    return newBuilder()
        .connectTimeout(clamped.coerceAtMost(10L), TimeUnit.SECONDS)
        .readTimeout(clamped, TimeUnit.SECONDS)
        .writeTimeout(clamped.coerceAtMost(10L), TimeUnit.SECONDS)
        .callTimeout(clamped, TimeUnit.SECONDS)
        .build()
}

internal suspend fun executeCancellableCall(
    client: OkHttpClient,
    request: Request
): CancellableHttpResponse = suspendCancellableCoroutine { cont ->
    val call = client.newCall(request)
    val completed = AtomicBoolean(false)
    val cancelledByCoroutine = AtomicBoolean(false)

    cont.invokeOnCancellation {
        cancelledByCoroutine.set(true)
        try {
            call.cancel()
        } catch (_: Throwable) {}
    }

    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (completed.compareAndSet(false, true) && cont.isActive) {
                if (cancelledByCoroutine.get()) {
                    cont.resumeWithException(CancellationException("HTTP call cancelled", e))
                } else {
                    cont.resumeWithException(e)
                }
            }
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { resp ->
                    CancellableHttpResponse(
                        code = resp.code,
                        isSuccessful = resp.isSuccessful,
                        body = resp.body?.string() ?: ""
                    )
                }
                if (completed.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(result)
                }
            } catch (t: Throwable) {
                if (completed.compareAndSet(false, true) && cont.isActive) {
                    if (cancelledByCoroutine.get()) {
                        cont.resumeWithException(CancellationException("HTTP response read cancelled", t))
                    } else {
                        cont.resumeWithException(t)
                    }
                }
            }
        }
    })
}

/**
 * Universal interface for multi-provider AI subtitle translation clients.
 * Defines contractual methods for batch chunk translation, sliding dialogue context windows,
 * character roster addressing, Pass 0 Film Bible prompts, and endpoint health verification.
 */
interface AITranslationClient {

    /**
     * Translates a chunk of subtitle cues into the target language (legacy 4-parameter overload).
     */
    suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String? = null,
        targetLang: String = "vi",
        customGlossary: String? = null
    ): TranslationResult = translateChunk(
        cues = cues,
        sourceLang = sourceLang,
        targetLang = targetLang,
        customGlossary = customGlossary,
        lookbehind = emptyList(),
        lookahead = emptyList(),
        characterRegistry = null,
        mediaContext = null
    )

    /**
     * Translates a chunk of subtitle cues into the target language with full 3-zone sliding context
     * (Lookbehind, Target Cues, Lookahead) and character registry addressing.
     */
    suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String? = null,
        targetLang: String = "vi",
        customGlossary: String? = null,
        lookbehind: List<ContextCue> = emptyList(),
        lookahead: List<ContextCue> = emptyList(),
        characterRegistry: TwoTierCharacterRegistry? = null,
        mediaContext: MediaContext? = null
    ): TranslationResult = translateChunk(
        cues = cues,
        sourceLang = sourceLang,
        targetLang = targetLang,
        customGlossary = customGlossary
    )

    /**
     * Translates a chunk with an optional rolling scene state digest appended to the user payload.
     */
    suspend fun translateChunkWithSceneDigest(
        cues: List<SubtitleCue>,
        sourceLang: String? = null,
        targetLang: String = "vi",
        customGlossary: String? = null,
        lookbehind: List<ContextCue> = emptyList(),
        lookahead: List<ContextCue> = emptyList(),
        characterRegistry: TwoTierCharacterRegistry? = null,
        mediaContext: MediaContext? = null,
        rollingSceneDigest: String? = null
    ): TranslationResult = translateChunk(
        cues = cues,
        sourceLang = sourceLang,
        targetLang = targetLang,
        customGlossary = customGlossary,
        lookbehind = lookbehind,
        lookahead = lookahead,
        characterRegistry = characterRegistry,
        mediaContext = mediaContext
    )

    /**
     * Executes a lightweight JSON prompt call with bounded timeout (default 12s) for Pass 0
     * Global Film Bible Pre-Scan. Returns raw JSON string or null on non-fatal failure.
     */
    suspend fun generateJsonPrompt(
        systemPrompt: String,
        userPrompt: String,
        timeoutSeconds: Long = 12L
    ): String? = null

    /**
     * Sends a minimal diagnostic ping request to verify endpoint reachability,
     * API key validity, and model responsiveness.
     *
     * @return TranslationResult indicating connection success or specific diagnostic failure.
     */
    suspend fun ping(): TranslationResult
}
