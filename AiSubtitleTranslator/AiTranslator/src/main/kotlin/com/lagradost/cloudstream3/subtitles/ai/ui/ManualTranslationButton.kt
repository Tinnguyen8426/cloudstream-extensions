package com.lagradost.cloudstream3.subtitles.ai.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import java.lang.ref.WeakReference

/**
 * Floating "Dịch" button for manual translation mode.
 * Programmatic overlay (no XML), top-left of the player, non-focusable for TV D-Pad,
 * with WeakReferences and main-thread-only view mutations.
 */
object ManualTranslationButton {
    private const val TAG = "ManualTranslationButton"
    private const val VIEW_TAG = "AITranslatorManualTranslateButton"
    private const val COLOR_BG = 0xE626A69A.toInt()
    private const val COLOR_BG_BUSY = 0xE6555566.toInt()
    private const val COLOR_BORDER = 0x66FFFFFF
    private const val COLOR_TEXT = 0xFFFFFFFF.toInt()

    private var activityRef: WeakReference<Activity>? = null
    private var viewRef: WeakReference<TextView>? = null
    private var sessionRef: WeakReference<AITranslationSession>? = null
    private var lastSyncState: String? = null

    fun attach(session: AITranslationSession) {
        sessionRef = WeakReference(session)
        sync()
    }

    fun syncSoon() = sync()

    /**
     * Idempotent tick (called every ~1s by the plugin while manual mode is active).
     */
    fun sync() {
        postMain {
            try {
                if (!TranslatorConfig.isEnabled || TranslatorConfig.translationMode != TranslatorConfig.MODE_MANUAL) {
                    logStateTransition("disabled-or-auto", null, null, null)
                    detachInternal()
                    return@postMain
                }
                val session = sessionRef?.get()
                if (session == null) {
                    logStateTransition("no-session", null, null, null)
                    detachInternal()
                    return@postMain
                }
                val act = currentActivity()
                val playerActive = act != null && PlayerSubtitleBridge.isPlayerActive()
                logStateTransition(
                    "manual",
                    act != null,
                    playerActive,
                    if (playerActive) PlayerSubtitleBridge.activePlayerFragmentClass() else null
                )
                if (act == null || !playerActive) {
                    detachInternal()
                    return@postMain
                }
                val view = ensureView(act) ?: return@postMain
                updateState(view, session)
            } catch (t: Throwable) {
                logW("sync failed: ${t.message}")
            }
        }
    }

    private fun logStateTransition(reason: String, activity: Boolean?, playerActive: Boolean?, playerClass: String?) {
        val state = "reason=$reason, activity=$activity, playerActive=$playerActive, playerClass=$playerClass, view=${viewRef?.get() != null}"
        if (state != lastSyncState) {
            lastSyncState = state
            logI("sync state -> $state")
        }
    }

    /**
     * Fully removes the button and releases the session reference (plugin unload).
     */
    fun detach() {
        postMain {
            detachInternal()
            sessionRef = null
            activityRef = null
        }
    }

    private fun detachInternal() {
        try {
            val view = viewRef?.get()
            if (view != null) {
                try {
                    (view.parent as? ViewGroup)?.removeView(view)
                } catch (_: Throwable) {}
            } else {
                // A stale copy can outlive a plugin reload; remove it by tag so it cannot stick.
                val stray = try {
                    val decor = currentActivity()?.window?.decorView as? ViewGroup
                    decor?.findViewWithTag<View>(VIEW_TAG)
                } catch (_: Throwable) {
                    null
                }
                if (stray != null) {
                    try {
                        (stray.parent as? ViewGroup)?.removeView(stray)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
        } finally {
            viewRef = null
            activityRef = null
        }
    }

    private fun currentActivity(): Activity? = try { CommonActivity.activity } catch (_: Throwable) { null }

    private fun ensureView(activity: Activity): TextView? {
        val decor = try {
            activity.window?.decorView as? ViewGroup
        } catch (_: Throwable) {
            null
        } ?: return null

        val existing = try {
            decor.findViewWithTag<View>(VIEW_TAG) as? TextView
        } catch (_: Throwable) {
            null
        }
        if (existing != null) {
            activityRef = WeakReference(activity)
            viewRef = WeakReference(existing)
            return existing
        }

        val density = try { activity.resources.displayMetrics.density } catch (_: Throwable) { 1.0f }
        val padH = (14 * density).toInt().coerceAtLeast(8)
        val padV = (7 * density).toInt().coerceAtLeast(4)

        val button = TextView(activity).apply {
            tag = VIEW_TAG
            text = "Dịch"
            textSize = 13f
            paint.isFakeBoldText = true
            setTextColor(COLOR_TEXT)
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            background = buildBackground(activity, busy = false)
            try { elevation = 6 * density } catch (_: Throwable) {}
            setOnClickListener { onTranslateClicked() }
        }

        return try {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins((16 * density).toInt(), (72 * density).toInt(), 0, 0)
            }
            decor.addView(button, lp)
            activityRef = WeakReference(activity)
            viewRef = WeakReference(button)
            logI("Manual translate button attached to player overlay.")
            button
        } catch (t: Throwable) {
            logW("Failed attaching button: ${t.message}")
            null
        }
    }

    private fun buildBackground(activity: Activity, busy: Boolean): GradientDrawable {
        val density = try { activity.resources.displayMetrics.density } catch (_: Throwable) { 1.0f }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * density
            setColor(if (busy) COLOR_BG_BUSY else COLOR_BG)
            setStroke((1 * density).toInt().coerceAtLeast(1), COLOR_BORDER)
        }
    }

    private fun updateState(view: TextView, session: AITranslationSession) {
        val busy = session.isTranslationInFlight
        if (view.text.toString() != if (busy) "Đang dịch…" else "Dịch") {
            view.text = if (busy) "Đang dịch…" else "Dịch"
            view.isEnabled = !busy
            view.alpha = if (busy) 0.8f else 1.0f
            try {
                val act = activityRef?.get()
                if (act != null) view.background = buildBackground(act, busy)
            } catch (_: Throwable) {}
        }
    }

    private fun onTranslateClicked() {
        try {
            val act = currentActivity()
            val session = sessionRef?.get()
            if (act == null || session == null) {
                toast(act, "AI Translator chưa sẵn sàng cho phim này.")
                return
            }
            if (!TranslatorConfig.isEnabled) {
                toast(act, "AI Translator đang tắt trong Cài đặt.")
                return
            }
            if (session.isTranslationInFlight) return

            val preferred = PlayerSubtitleBridge.currentPreferredTrack()
            val result = ManualSubtitleSelector.evaluate(
                name = preferred?.name,
                url = preferred?.url,
                languageCode = preferred?.languageCode,
                originName = preferred?.originName,
                targetLang = TranslatorConfig.targetLanguage,
                headers = preferred?.headers ?: emptyMap()
            )

            when (result) {
                ManualSubtitleSelector.Result.NotSelected -> {
                    toast(act, "Hãy chọn phụ đề trước khi bấm Dịch.")
                }
                ManualSubtitleSelector.Result.Embedded,
                ManualSubtitleSelector.Result.NonHttp -> {
                    toast(act, "Phụ đề này nằm trong video. Hãy chọn phụ đề dạng URL (softsub).")
                }
                ManualSubtitleSelector.Result.AlreadyTarget -> {
                    val langName = AITranslationSession.getTargetLanguageDisplayName(TranslatorConfig.targetLanguage)
                    toast(act, "Phụ đề đang chọn đã là $langName, không cần dịch.")
                }
                ManualSubtitleSelector.Result.AlreadyAi -> {
                    toast(act, "Đây là bản dịch AI, không cần dịch lại.")
                }
                is ManualSubtitleSelector.Result.Ready -> {
                    logI("Manual translation requested for '${result.subtitleFile.lang}' -> ${result.subtitleFile.url}")
                    session.startSession(result.subtitleFile)
                    viewRef?.get()?.let { updateState(it, session) }
                }
            }
        } catch (t: Throwable) {
            logW("Translate click failed: ${t.message}")
        }
    }

    private fun toast(activity: Activity?, message: String) {
        try {
            val ctx = activity ?: TranslatorConfig.appContext
            if (ctx != null) {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            } else {
                logI(message)
            }
        } catch (_: Throwable) {
            logI(message)
        }
    }

    private fun postMain(block: () -> Unit) {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    block()
                } catch (t: Throwable) {
                    logW("main action failed: ${t.message}")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun logI(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) { println("[$TAG] [INFO] $msg") }
    }

    private fun logW(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) { System.err.println("[$TAG] [WARN] $msg") }
    }
}