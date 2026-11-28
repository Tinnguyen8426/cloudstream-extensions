package com.lagradost.cloudstream3.subtitles.ai.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import java.lang.ref.WeakReference

/**
 * TranslationProgressHUD
 *
 * Real-time, non-intrusive floating progress HUD for CloudStream AI Subtitle Translator.
 * Built 100% programmatically in Kotlin (zero XML inflation) with pure integer color literals.
 *
 * Designed to seamlessly blend into CloudStream player aesthetics (dark obsidian card,
 * translucent border, cyan progress bar, smooth green completion state) without intercepting
 * Android TV D-Pad remote navigation focus or causing memory leaks.
 */
object TranslationProgressHUD {

    // Pure Kotlin Hex Color Literals (0 Android framework Color.parseColor calls for JVM test safety)
    private const val COLOR_BG = 0xE6181825.toInt()           // Dark obsidian card (#181825 @ 90% opacity)
    private const val COLOR_BORDER = 0x33FFFFFF.toInt()       // Subtle border (20% translucent white)
    private const val COLOR_CYAN = 0xFF4DD0E1.toInt()         // CloudStream Cyan/Teal accent
    private const val COLOR_GREEN = 0xFF81C784.toInt()        // Completion green
    private const val COLOR_RED = 0xFFE57373.toInt()          // Error red
    private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt() // White primary text
    private const val COLOR_TEXT_MUTED = 0xB3FFFFFF.toInt()   // 70% translucent white secondary text
    private const val COLOR_PROGRESS_TRACK = 0x26FFFFFF.toInt() // 15% translucent track

    // WeakReferences to guard against activity/view memory leaks
    private var activityRef: WeakReference<Activity>? = null
    private var hudViewRef: WeakReference<View>? = null
    private var progressBarRef: WeakReference<ProgressBar>? = null
    private var percentTextRef: WeakReference<TextView>? = null
    private var statusTextRef: WeakReference<TextView>? = null
    private var cancelButtonRef: WeakReference<TextView>? = null
    private var dismissRunnable: Runnable? = null
    @Volatile
    private var onCancelCallback: (() -> Unit)? = null

    /**
     * Safely executes an action on the Android main UI thread with headless JVM fallback.
     */
    private fun runOnMainThread(activity: Activity?, action: () -> Unit) {
        try {
            if (activity != null && !activity.isFinishing) {
                activity.runOnUiThread {
                    try { action() } catch (_: Throwable) {}
                }
                return
            }
        } catch (_: Throwable) {}

        try {
            val looper = Looper.getMainLooper()
            if (looper != null) {
                Handler(looper).post {
                    try { action() } catch (_: Throwable) {}
                }
            } else {
                // Headless test environment fallback
                action()
            }
        } catch (_: Throwable) {
            // Safe no-op in headless test runners
        }
    }

    /**
     * Displays or re-initializes the Translation Progress HUD.
     */
    fun show(
        context: Context? = null,
        activity: Activity? = null,
        modelName: String = "",
        targetLang: String = "",
        totalChunks: Int = 0,
        totalCues: Int = 0,
        initialStatus: String? = null,
        onCancel: (() -> Unit)? = null,
        force: Boolean = false
    ) {
        try {
            if (!TranslatorConfig.showProgressHUD && !force) return
            onCancelCallback = onCancel

            val act = activity
                ?: (context as? Activity)
                ?: try { CommonActivity.activity } catch (_: Throwable) { null }
                ?: return

            try {
                if (act.isFinishing) return
            } catch (_: Throwable) {}

            runOnMainThread(act) {
                try {
                    dismissInternal()
                    onCancelCallback = onCancel

                    val decor = act.window?.decorView as? ViewGroup ?: return@runOnMainThread
                    val view = buildHudView(act, modelName, totalChunks, totalCues)
                    if (initialStatus != null) {
                        statusTextRef?.get()?.text = initialStatus
                    }

                    val density = try { act.resources.displayMetrics.density } catch (_: Throwable) { 1.0f }
                    val margin = (20 * density).toInt()

                    val lp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, margin, margin, 0)
                        try {
                            marginEnd = margin
                        } catch (_: Throwable) {}
                    }

                    decor.addView(view, lp)
                    hudViewRef = WeakReference(view)
                    activityRef = WeakReference(act)
                } catch (_: Throwable) {
                    // Safe headless JVM fallback
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Updates progress percentage, cue counts, and status description.
     */
    fun updateProgress(
        completedChunks: Int,
        totalChunks: Int,
        completedCues: Int,
        totalCues: Int,
        percent: Int = -1,
        modelName: String? = null,
        status: String? = null,
        message: String? = null
    ) {
        try {
            if (!TranslatorConfig.showProgressHUD && hudViewRef?.get() == null) return
            val act = activityRef?.get() ?: return

            runOnMainThread(act) {
                try {
                    val calcPercent = if (percent in 0..100) {
                        percent
                    } else if (totalChunks > 0) {
                        ((completedChunks.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }

                    progressBarRef?.get()?.progress = calcPercent
                    percentTextRef?.get()?.text = "$calcPercent%"

                    val textToDisplay = status ?: message
                        ?: "Đang dịch: Chunk $completedChunks/$totalChunks ($calcPercent%) • $completedCues/$totalCues câu"
                    statusTextRef?.get()?.text = textToDisplay
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Updates the status line message.
     */
    fun updateStatus(message: String) {
        try {
            if (!TranslatorConfig.showProgressHUD && hudViewRef?.get() == null) return
            val act = activityRef?.get() ?: return

            runOnMainThread(act) {
                try {
                    statusTextRef?.get()?.text = message
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Updates HUD notification when player seeks to a new timestamp.
     */
    fun onSeek(posSec: Long) {
        try {
            if (!TranslatorConfig.showProgressHUD && hudViewRef?.get() == null) return
            val act = activityRef?.get() ?: return

            runOnMainThread(act) {
                try {
                    statusTextRef?.get()?.text = "Đang ưu tiên phân cảnh (${posSec}s)..."
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Displays successful completion (100% green bar) and schedules auto-dismissal after 3 seconds.
     */
    fun showSuccess(message: String = "Hoàn tất 100%! Phụ đề đã sẵn sàng") {
        try {
            if (!TranslatorConfig.showProgressHUD && hudViewRef?.get() == null) return
            val act = activityRef?.get() ?: return

            runOnMainThread(act) {
                try {
                    progressBarRef?.get()?.apply {
                        progress = 100
                        try {
                            progressTintList = ColorStateList.valueOf(COLOR_GREEN)
                        } catch (_: Throwable) {}
                    }
                    percentTextRef?.get()?.apply {
                        text = "100%"
                        setTextColor(COLOR_GREEN)
                    }
                    statusTextRef?.get()?.apply {
                        text = message
                        setTextColor(COLOR_GREEN)
                    }
                    cancelButtonRef?.get()?.visibility = View.GONE
                    scheduleAutoDismiss(3000L)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Alias for showSuccess.
     */
    fun onComplete(message: String = "Hoàn tất 100%! Phụ đề đã sẵn sàng") {
        showSuccess(message)
    }

    /**
     * Displays an error message and schedules dismissal after 5 seconds.
     */
    fun showError(errorMessage: String) {
        try {
            if (!TranslatorConfig.showProgressHUD && hudViewRef?.get() == null) return
            val act = activityRef?.get() ?: return

            runOnMainThread(act) {
                try {
                    percentTextRef?.get()?.apply {
                        text = "Lỗi"
                        setTextColor(COLOR_RED)
                    }
                    statusTextRef?.get()?.apply {
                        text = errorMessage
                        setTextColor(COLOR_RED)
                    }
                    progressBarRef?.get()?.apply {
                        try {
                            progressTintList = ColorStateList.valueOf(COLOR_RED)
                        } catch (_: Throwable) {}
                    }
                    cancelButtonRef?.get()?.visibility = View.GONE
                    scheduleAutoDismiss(5000L)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Immediately dismisses and removes HUD view from hierarchy.
     */
    fun dismiss() {
        try {
            val act = activityRef?.get()
            runOnMainThread(act) {
                dismissInternal()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Internal removal logic with smooth alpha fade-out and resource cleanup.
     */
    private fun dismissInternal() {
        try {
            cancelScheduledDismiss()
            val view = hudViewRef?.get()
            val act = activityRef?.get()

            if (view != null && act != null) {
                val decor = act.window?.decorView as? ViewGroup
                if (decor != null) {
                    try {
                        view.animate()
                            .alpha(0f)
                            .setDuration(250L)
                            .withEndAction {
                                try { decor.removeView(view) } catch (_: Throwable) {}
                            }
                            .start()
                    } catch (_: Throwable) {
                        decor.removeView(view)
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            hudViewRef = null
            progressBarRef = null
            percentTextRef = null
            statusTextRef = null
            cancelButtonRef = null
            activityRef = null
            onCancelCallback = null
        }
    }

    private fun scheduleAutoDismiss(delayMs: Long) {
        try {
            cancelScheduledDismiss()
            val runnable = Runnable {
                dismissInternal()
            }
            dismissRunnable = runnable
            val looper = Looper.getMainLooper()
            if (looper != null) {
                Handler(looper).postDelayed(runnable, delayMs)
            }
        } catch (_: Throwable) {}
    }

    private fun cancelScheduledDismiss() {
        try {
            dismissRunnable?.let { r ->
                val looper = Looper.getMainLooper()
                if (looper != null) {
                    Handler(looper).removeCallbacks(r)
                }
            }
            dismissRunnable = null
        } catch (_: Throwable) {}
    }

    /**
     * Builds the pure programmatic Android View hierarchy without XML layouts.
     * Enforces non-intrusive focusability so Android TV D-Pad navigation never gets trapped.
     */
    private fun buildHudView(activity: Activity, modelName: String, totalChunks: Int, totalCues: Int): View {
        val density = try { activity.resources.displayMetrics.density } catch (_: Throwable) { 1.0f }
        val pad14 = (14 * density).toInt().coerceAtLeast(8)
        val pad10 = (10 * density).toInt().coerceAtLeast(6)
        val pad8 = (8 * density).toInt().coerceAtLeast(4)
        val pad6 = (6 * density).toInt().coerceAtLeast(3)
        val pad4 = (4 * density).toInt().coerceAtLeast(2)
        val minWidth = (240 * density).toInt()

        // Card Container: Dark obsidian translucent card with subtle border
        val cardLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            minimumWidth = minWidth
            setPadding(pad14, pad10, pad14, pad10)

            try {
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(COLOR_BG)
                    setStroke((1 * density).toInt().coerceAtLeast(1), COLOR_BORDER)
                }
                background = bgDrawable
            } catch (_: Throwable) {}
        }

        // Header Row: Model name badge + percentage text + dismiss button
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        val displayModel = if (modelName.isNotBlank()) modelName.trim() else "AI Translator"
        val modelBadge = TextView(activity).apply {
            text = "🤖 $displayModel"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(COLOR_CYAN)
            isFocusable = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            layoutParams = lp
        }
        headerRow.addView(modelBadge)

        val percentView = TextView(activity).apply {
            text = "0%"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(pad8, 0, pad8, 0)
            isFocusable = false
        }
        headerRow.addView(percentView)
        percentTextRef = WeakReference(percentView)

        val cancelButton = TextView(activity).apply {
            text = "✕ Hủy dịch"
            textSize = 11f
            paint.isFakeBoldText = true
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(pad8, pad4, pad8, pad4)
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            try {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(0x66E57373)
                    setStroke((1 * density).toInt().coerceAtLeast(1), COLOR_RED)
                }
            } catch (_: Throwable) {}
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = pad6
            }
            setOnClickListener {
                isEnabled = false
                statusTextRef?.get()?.text = "Đang hủy dịch..."
                val cb = onCancelCallback
                if (cb != null) {
                    cb.invoke()
                } else {
                    dismiss()
                }
            }
        }
        headerRow.addView(cancelButton)
        cancelButtonRef = WeakReference(cancelButton)

        val closeButton = TextView(activity).apply {
            text = "✕"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(pad4, 0, pad4, 0)
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { dismiss() }
        }
        headerRow.addView(closeButton)

        cardLayout.addView(headerRow)

        // Progress Bar: Sleek 4dp horizontal bar
        val barHeight = (4 * density).toInt().coerceAtLeast(4)
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isFocusable = false
            isIndeterminate = false
            max = 100
            progress = 0
            try {
                progressTintList = ColorStateList.valueOf(COLOR_CYAN)
                progressBackgroundTintList = ColorStateList.valueOf(COLOR_PROGRESS_TRACK)
            } catch (_: Throwable) {}
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barHeight).apply {
                topMargin = pad6
                bottomMargin = pad6
            }
            layoutParams = lp
        }
        cardLayout.addView(progressBar)
        progressBarRef = WeakReference(progressBar)

        // Status Detail Label: Real-time chunk & cue progress
        val statusView = TextView(activity).apply {
            text = if (totalChunks > 0) "Đang khởi tạo: 0/$totalChunks chunks • 0/$totalCues câu" else "Đang chuẩn bị dịch..."
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            isFocusable = false
        }
        cardLayout.addView(statusView)
        statusTextRef = WeakReference(statusView)

        return cardLayout
    }
}
