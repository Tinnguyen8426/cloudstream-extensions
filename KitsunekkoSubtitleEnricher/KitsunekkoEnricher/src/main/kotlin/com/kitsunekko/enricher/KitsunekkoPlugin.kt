package com.kitsunekko.enricher

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CloudStream Extension Entrypoint for Kitsunekko Japanese Subtitle Enricher.
 * Handles lifecycle callbacks and presents programmatic settings UI with real-time cache metrics.
 */
@CloudstreamPlugin
class KitsunekkoPlugin : Plugin() {

    override fun load(context: Context) {
        // 1. Initialize context references and persistent cache
        KitsunekkoHook.init(context)
        KitsunekkoScraper.init(context)

        // 2. Attach dynamic interceptor hooks to external providers
        KitsunekkoHook.attachHooks()

        // 3. Register Settings UI dialog
        openSettings = { activityContext ->
            showSettingsDialog(activityContext)
        }
    }

    override fun beforeUnload() {
        unload()
    }

    fun unload(context: Context? = null) {
        KitsunekkoHook.detachHooks()
        KitsunekkoScraper.clearMemoryCache()
    }

    private fun showSettingsDialog(context: Context) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scrollView.addView(layout)

        // Title / Description
        val descView = TextView(context).apply {
            text = "Kitsunekko Japanese Subtitle Enricher: Tự động trích xuất phụ đề tiếng Nhật (.ass, .srt, .ssa, .zip) từ kitsunekko.net và nhúng trực tiếp vào trình phát CloudStream."
            textSize = 14f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        layout.addView(descView)

        // Metrics Section Header
        val metricsHeader = TextView(context).apply {
            text = "Thông tin bộ nhớ đệm (Cache Metrics):"
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        layout.addView(metricsHeader)

        // Helper to format MB
        val formatMb = { bytes: Long -> String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024)) }

        val formatSummary = { summary: KitsunekkoCacheSummary ->
            val dateStr = if (summary.directoryLastUpdated > 0L) {
                SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date(summary.directoryLastUpdated))
            } else {
                "Chưa tải"
            }
            "• Phụ đề giải nén (kitsunekko_subs): ${summary.cachedSubsCount} file (${formatMb(summary.subsCacheBytes)})\n" +
            "• File nén đã tải (kitsunekko_cache): ${formatMb(summary.zipCacheBytes)}\n" +
            "• Tổng dung lượng đệm: ${formatMb(summary.cacheSizeBytes)} / 100 MB\n" +
            "• Danh mục Kitsunekko: ${summary.directoryItemCount} anime\n" +
            "• Cập nhật danh mục lần cuối: $dateStr"
        }

        val initialSummary = KitsunekkoScraper.getCacheSummary(context)
        val metricsText = TextView(context).apply {
            text = formatSummary(initialSummary)
            textSize = 13f
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        layout.addView(metricsText)

        // Action 1: Clear Cache Button
        val clearCacheButton = Button(context).apply {
            text = "Xóa bộ nhớ đệm phụ đề (Clear Cache)"
            textSize = 13f
            setOnClickListener {
                ZipSubtitleExtractor.clearAllCache(context)
                Toast.makeText(context, "Đã dọn dẹp toàn bộ cache phụ đề Kitsunekko.", Toast.LENGTH_SHORT).show()
                val updated = KitsunekkoScraper.getCacheSummary(context)
                metricsText.text = formatSummary(updated)
            }
        }
        layout.addView(clearCacheButton)

        // Action 2: Refresh Root Directory Cache Button
        val refreshDirButton = Button(context).apply {
            text = "Làm mới danh mục Anime (Refresh Categories)"
            textSize = 13f
            setOnClickListener {
                Toast.makeText(context, "Đang tải danh mục anime mới nhất từ Kitsunekko...", Toast.LENGTH_SHORT).show()
                isEnabled = false
                CoroutineScope(Dispatchers.IO).launch {
                    val count = KitsunekkoScraper.refreshRootDirectory(context)
                    withContext(Dispatchers.Main) {
                        isEnabled = true
                        if (count > 0) {
                            Toast.makeText(context, "Đã làm mới danh mục: $count anime.", Toast.LENGTH_LONG).show()
                            val updated = KitsunekkoScraper.getCacheSummary(context)
                            metricsText.text = formatSummary(updated)
                        } else {
                            Toast.makeText(context, "Lỗi khi tải danh mục từ Kitsunekko. Vui lòng thử lại sau.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        layout.addView(refreshDirButton)

        // Build AlertDialog
        AlertDialog.Builder(context)
            .setTitle("Cài đặt Kitsunekko Japanese Subtitles")
            .setView(scrollView)
            .setNegativeButton("Đóng") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
