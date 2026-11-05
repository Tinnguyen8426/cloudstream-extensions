package com.stremio.subenricher

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CloudStream Extension Entrypoint for Stremio Subtitle Enricher.
 */
@CloudstreamPlugin
class StremioSubPlugin : Plugin() {

    override fun load(context: Context) {
        // 1. Initialize persistent configurations
        StremioConfig.init(context)

        // 2. Attach dynamic hooks to external providers
        StremioSubHook.attachHooks()

        // 3. Register Settings UI dialog
        openSettings = { activityContext ->
            showSettingsDialog(activityContext)
        }
    }

    override fun beforeUnload() {
        unload()
    }

    fun unload(context: Context? = null) {
        StremioSubHook.detachHooks()
        StremioSubScraper.clearCaches()
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

        // Description
        val descView = TextView(context).apply {
            text = "Stremio Subtitle Enricher: Tự động trích xuất và nhúng phụ đề đa ngôn ngữ từ Kitsu, OpenSubtitles v3, Cinemeta và Custom Addons vào CloudStream."
            textSize = 14f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        layout.addView(descView)

        // Section 1: Language Filter
        val langLabel = TextView(context).apply {
            text = "Bộ lọc ngôn ngữ phụ đề:"
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        layout.addView(langLabel)

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }

        val rbAll = RadioButton(context).apply { text = "Tất cả ngôn ngữ (All)"; id = View.generateViewId() }
        val rbViEn = RadioButton(context).apply { text = "Ưu tiên Tiếng Việt & English (Mặc định)"; id = View.generateViewId() }
        val rbViOnly = RadioButton(context).apply { text = "Chỉ Tiếng Việt (Vietsub only)"; id = View.generateViewId() }
        val rbEnOnly = RadioButton(context).apply { text = "Chỉ English (English only)"; id = View.generateViewId() }
        val rbCustom = RadioButton(context).apply { text = "Tùy chỉnh mã ngôn ngữ (Custom)"; id = View.generateViewId() }

        radioGroup.addView(rbAll)
        radioGroup.addView(rbViEn)
        radioGroup.addView(rbViOnly)
        radioGroup.addView(rbEnOnly)
        radioGroup.addView(rbCustom)

        when (StremioConfig.langMode) {
            StremioConfig.MODE_ALL -> rbAll.isChecked = true
            StremioConfig.MODE_VI_EN -> rbViEn.isChecked = true
            StremioConfig.MODE_VI_ONLY -> rbViOnly.isChecked = true
            StremioConfig.MODE_EN_ONLY -> rbEnOnly.isChecked = true
            StremioConfig.MODE_CUSTOM -> rbCustom.isChecked = true
            else -> rbViEn.isChecked = true
        }
        layout.addView(radioGroup)

        val customLangInput = EditText(context).apply {
            hint = "Nhập mã ISO cách nhau bởi dấu phẩy (vd: vi, en, ja, fr)"
            setText(StremioConfig.customLangs)
            textSize = 13f
            visibility = if (rbCustom.isChecked) View.VISIBLE else View.GONE
        }
        layout.addView(customLangInput)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            customLangInput.visibility = if (checkedId == rbCustom.id) View.VISIBLE else View.GONE
        }

        // Section 2: Custom Addons
        val addonLabel = TextView(context).apply {
            text = "\nCustom Stremio Subtitle Addons:"
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        layout.addView(addonLabel)

        val addonHint = TextView(context).apply {
            text = "Nhập URL base của Stremio Subtitle Addons (mỗi dòng 1 URL):"
            textSize = 12f
        }
        layout.addView(addonHint)

        val addonInput = EditText(context).apply {
            hint = "https://example-stremio-addon.com\nhttps://subtitles-addon.strem.fun"
            setText(StremioConfig.customAddonsRaw)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            textSize = 13f
        }
        layout.addView(addonInput)

        // Section 3: Connection Test Button
        val testButton = Button(context).apply {
            text = "Kiểm tra kết nối Stremio (Ping Test)"
            textSize = 13f
            setOnClickListener {
                testStremioConnections(context)
            }
        }
        layout.addView(testButton)

        // Build Dialog
        AlertDialog.Builder(context)
            .setTitle("Cài đặt Stremio Subtitle Enricher")
            .setView(scrollView)
            .setPositiveButton("Lưu cấu hình") { dialog, _ ->
                val selectedMode = when (radioGroup.checkedRadioButtonId) {
                    rbAll.id -> StremioConfig.MODE_ALL
                    rbViOnly.id -> StremioConfig.MODE_VI_ONLY
                    rbEnOnly.id -> StremioConfig.MODE_EN_ONLY
                    rbCustom.id -> StremioConfig.MODE_CUSTOM
                    else -> StremioConfig.MODE_VI_EN
                }
                val customLangs = customLangInput.text.toString().trim()
                val addons = addonInput.text.toString().trim()

                StremioConfig.save(context, selectedMode, customLangs, addons)
                Toast.makeText(context, "Đã lưu cài đặt Stremio Subtitle Enricher!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNeutralButton("Khôi phục mặc định") { dialog, _ ->
                StremioConfig.resetToDefaults(context)
                Toast.makeText(context, "Đã khôi phục cài đặt mặc định.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Đóng") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun testStremioConnections(context: Context) {
        Toast.makeText(context, "Đang kiểm tra kết nối tới Stremio APIs...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val results = mutableListOf<String>()

            // 1. Test Kitsu
            val kitsuStart = System.currentTimeMillis()
            val kitsuOk = try {
                app.get("https://anime-kitsu.strem.fun/catalog/anime/kitsu-search/search=frieren.json", timeout = 5).use { it.isSuccessful }
            } catch (e: Throwable) { false }
            val kitsuTime = System.currentTimeMillis() - kitsuStart
            results.add(if (kitsuOk) "✓ Kitsu API: OK (${kitsuTime}ms)" else "✗ Kitsu API: Thất bại")

            // 2. Test Cinemeta
            val cineStart = System.currentTimeMillis()
            val cineOk = try {
                app.get("https://v3-cinemeta.strem.io/catalog/series/top/search=frieren.json", timeout = 5).use { it.isSuccessful }
            } catch (e: Throwable) { false }
            val cineTime = System.currentTimeMillis() - cineStart
            results.add(if (cineOk) "✓ Cinemeta: OK (${cineTime}ms)" else "✗ Cinemeta: Thất bại")

            // 3. Test OpenSubtitles v3
            val osStart = System.currentTimeMillis()
            val osOk = try {
                app.get("https://opensubtitles-v3.strem.io", timeout = 5).use { it.isSuccessful || it.code in 200..404 }
            } catch (e: Throwable) { false }
            val osTime = System.currentTimeMillis() - osStart
            results.add(if (osOk) "✓ OpenSubtitles v3: OK (${osTime}ms)" else "✗ OpenSubtitles v3: Thất bại")

            // 4. Test Custom Addons
            val customAddons = StremioConfig.getCustomAddonsList()
            for (addon in customAddons) {
                val aStart = System.currentTimeMillis()
                val aOk = try {
                    app.get(addon, timeout = 5).use { it.isSuccessful || it.code in 200..404 }
                } catch (e: Throwable) { false }
                val aTime = System.currentTimeMillis() - aStart
                results.add(if (aOk) "✓ Custom (${addon.take(25)}...): OK (${aTime}ms)" else "✗ Custom ($addon): Thất bại")
            }

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(context)
                    .setTitle("Kết quả kiểm tra kết nối Stremio")
                    .setMessage(results.joinToString("\n\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}

/**
 * SharedPreferences configuration manager for Stremio Subtitle Enricher.
 */
object StremioConfig {
    private const val PREFS_NAME = "stremio_enricher_prefs"
    private const val KEY_LANG_MODE = "lang_mode"
    private const val KEY_CUSTOM_LANGS = "custom_langs"
    private const val KEY_CUSTOM_ADDONS = "custom_addons"

    const val MODE_ALL = 0
    const val MODE_VI_EN = 1
    const val MODE_VI_ONLY = 2
    const val MODE_EN_ONLY = 3
    const val MODE_CUSTOM = 4

    var langMode: Int = MODE_VI_EN
        private set
    var customLangs: String = "vi, en"
        private set
    var customAddonsRaw: String = ""
        private set

    fun init(context: Context) {
        val prefs = getPrefs(context)
        langMode = prefs.getInt(KEY_LANG_MODE, MODE_VI_EN)
        customLangs = prefs.getString(KEY_CUSTOM_LANGS, "vi, en") ?: "vi, en"
        customAddonsRaw = prefs.getString(KEY_CUSTOM_ADDONS, "") ?: ""
        applyToScraper()
    }

    fun save(context: Context, mode: Int, customL: String, addons: String) {
        langMode = mode
        customLangs = customL
        customAddonsRaw = addons

        getPrefs(context).edit().apply {
            putInt(KEY_LANG_MODE, mode)
            putString(KEY_CUSTOM_LANGS, customL)
            putString(KEY_CUSTOM_ADDONS, addons)
            apply()
        }
        applyToScraper()
    }

    fun resetToDefaults(context: Context) {
        save(context, MODE_VI_EN, "vi, en", "")
    }

    private fun applyToScraper() {
        StremioSubScraper.activeLanguageFilter = when (langMode) {
            MODE_ALL -> LanguageFilterMode.ALL
            MODE_VI_EN -> LanguageFilterMode.VI_EN
            MODE_VI_ONLY -> LanguageFilterMode.VI_ONLY
            MODE_EN_ONLY -> LanguageFilterMode.EN_ONLY
            MODE_CUSTOM -> LanguageFilterMode.CUSTOM
            else -> LanguageFilterMode.VI_EN
        }
        StremioSubScraper.customLanguageCodes = customLangs
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        StremioSubScraper.customAddonUrls = getCustomAddonsList()
    }

    fun isLanguageAllowed(iso2or3: String): Boolean {
        val clean = iso2or3.lowercase().trim()
        return when (langMode) {
            MODE_ALL -> true
            MODE_VI_EN -> clean in setOf("vi", "vie", "en", "eng")
            MODE_VI_ONLY -> clean in setOf("vi", "vie")
            MODE_EN_ONLY -> clean in setOf("en", "eng")
            MODE_CUSTOM -> {
                val allowed = customLangs.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                allowed.isEmpty() || clean in allowed
            }
            else -> true
        }
    }

    fun getCustomAddonsList(): List<String> {
        return customAddonsRaw.lines()
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
