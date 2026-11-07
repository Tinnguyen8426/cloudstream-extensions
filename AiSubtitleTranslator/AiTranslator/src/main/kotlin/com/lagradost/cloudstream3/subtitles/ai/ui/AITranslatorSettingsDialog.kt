package com.lagradost.cloudstream3.subtitles.ai.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslatorFactory
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure Kotlin Programmatic Settings UI for AI Subtitle Translator.
 *
 * Implements 100% programmatic Android View hierarchy using AlertDialog.Builder.
 * ABSOLUTELY ZERO XML layout inflation calls (no LayoutInflater.inflate, no R.layout,
 * no setContentView) to prevent Dalvik Resources$NotFoundException when loaded dynamically
 * via DexClassLoader in CloudStream.
 */
object AITranslatorSettingsDialog {

    private val viewIdGenerator = AtomicInteger(10000)

    /**
     * Safely generates unique View IDs without relying on framework View.generateViewId()
     * which throws RuntimeException: Stub! on desktop JVM headless test environments.
     */
    private fun generateSafeId(): Int {
        return try {
            View.generateViewId()
        } catch (_: Throwable) {
            viewIdGenerator.incrementAndGet()
        }
    }

    /**
     * Safely dispatches Toast alerts on the Android Main thread, falling back cleanly in non-Android environments.
     */
    private fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        try {
            Toast.makeText(context, message, duration).show()
        } catch (_: Throwable) {
            println("[AITranslatorToast] $message")
        }
    }

    /**
     * Pure programmatic Spinner adapter to eliminate any dependency on android.R.layout.simple_spinner_item
     * or LayoutInflater.inflate.
     */
    private class PureKotlinSpinnerAdapter(
        private val context: Context,
        private val items: List<String>
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                val pad = try { (12 * context.resources.displayMetrics.density).toInt() } catch (_: Throwable) { 12 }
                setPadding(pad, pad / 2, pad, pad / 2)
                textSize = 14f
            }
            tv.text = items.getOrElse(position) { "" }
            return tv
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                val pad = try { (16 * context.resources.displayMetrics.density).toInt() } catch (_: Throwable) { 16 }
                setPadding(pad, pad / 2, pad, pad / 2)
                textSize = 14f
            }
            tv.text = items.getOrElse(position) { "" }
            return tv
        }
    }

    /**
     * Constructs the pure Kotlin AlertDialog.Builder without XML layout inflation.
     */
    @JvmStatic
    fun buildDialog(context: Context): AlertDialog.Builder {
        val density = try { context.resources.displayMetrics.density } catch (_: Throwable) { 1.0f }
        val pad16 = (16 * density).toInt().coerceAtLeast(8)
        val pad12 = (12 * density).toInt().coerceAtLeast(6)
        val pad8 = (8 * density).toInt().coerceAtLeast(4)
        val pad4 = (4 * density).toInt().coerceAtLeast(2)

        val scrollView = ScrollView(context)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad16, pad16, pad16, pad16)
        }
        scrollView.addView(rootLayout)

        fun addSectionHeader(title: String) {
            val header = TextView(context).apply {
                text = title
                textSize = 15f
                paint.isFakeBoldText = true
                setPadding(0, pad16, 0, pad4)
            }
            rootLayout.addView(header)
        }

        fun addLabel(text: String): TextView {
            val label = TextView(context).apply {
                this.text = text
                textSize = 13f
                setPadding(0, pad8, 0, pad4)
            }
            rootLayout.addView(label)
            return label
        }

        // --- Header Description ---
        val descView = TextView(context).apply {
            text = "AI Subtitle Translator: Tự động dịch phụ đề phim đa ngôn ngữ sang tiếng Việt bằng các mô hình AI tiên tiến (Gemini, OpenAI, Claude) với độ trễ 0ms."
            textSize = 13f
            setPadding(0, 0, 0, pad12)
        }
        rootLayout.addView(descView)

        // --- Master Enable CheckBox ---
        val enableCheckBox = CheckBox(context).apply {
            text = "Bật dịch phụ đề AI (Enable AI Subtitle Translation)"
            isChecked = TranslatorConfig.isEnabled
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, pad8)
        }
        rootLayout.addView(enableCheckBox)

        // =====================================================================
        // SECTION 1: Cấu hình Dịch thuật (Translation Settings)
        // =====================================================================
        addSectionHeader("1. Cấu hình Dịch thuật (Translation Settings)")

        // Target Language Spinner & Input
        addLabel("Ngôn ngữ đích (Target Language):")
        val langOptions = listOf(
            "vi - Tiếng Việt (Vietnamese)",
            "en - English",
            "ja - 日本語 (Japanese)",
            "ko - 한국어 (Korean)",
            "zh - 中文 (Chinese)",
            "es - Español (Spanish)",
            "fr - Français (French)",
            "de - Deutsch (German)",
            "Tùy chỉnh (Custom...)"
        )
        val langCodes = listOf("vi", "en", "ja", "ko", "zh", "es", "fr", "de")

        val targetLangSpinner = Spinner(context).apply {
            adapter = PureKotlinSpinnerAdapter(context, langOptions)
        }
        rootLayout.addView(targetLangSpinner)

        val targetLangInput = EditText(context).apply {
            hint = "Mã ngôn ngữ ISO 639-1 (mặc định: vi)"
            setText(TranslatorConfig.targetLanguage)
            textSize = 14f
        }
        rootLayout.addView(targetLangInput)

        // Sync initial selection
        val initialLangCode = TranslatorConfig.targetLanguage.lowercase(Locale.ROOT).trim()
        val foundLangIndex = langCodes.indexOf(initialLangCode)
        if (foundLangIndex >= 0) {
            targetLangSpinner.setSelection(foundLangIndex)
        } else {
            targetLangSpinner.setSelection(langOptions.size - 1)
        }

        targetLangSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in langCodes.indices) {
                    targetLangInput.setText(langCodes[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Delivery Strategy RadioGroup (Batch vs Skeleton)
        addLabel("Chiến lược phân phối phụ đề (Delivery Strategy):")
        val deliveryRadioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val rbStrategyBatch = RadioButton(context).apply {
            id = generateSafeId()
            text = "Strategy A: Phân phối Batch (Nhanh & đồng bộ qua 4 coroutine workers)"
            textSize = 13f
        }
        val rbStrategySkeleton = RadioButton(context).apply {
            id = generateSafeId()
            text = "Strategy B: Phân phối Skeleton (Phát khung fallback ngay, cập nhật full sau)"
            textSize = 13f
        }
        deliveryRadioGroup.addView(rbStrategyBatch)
        deliveryRadioGroup.addView(rbStrategySkeleton)

        val currentStrategy = TranslatorConfig.deliveryStrategy.lowercase(Locale.ROOT).trim()
        if (currentStrategy in listOf(TranslatorConfig.DELIVERY_STRATEGY_B, TranslatorConfig.STRATEGY_SKELETON, "skeleton")) {
            rbStrategySkeleton.isChecked = true
        } else {
            rbStrategyBatch.isChecked = true
        }
        rootLayout.addView(deliveryRadioGroup)

        // Source Language Priority Mode
        addLabel("Chế độ ưu tiên nguồn phụ đề (Source Priority Mode):")
        val priorityRadioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val rbPriorityAuto = RadioButton(context).apply {
            id = generateSafeId()
            text = "Tự động nhận diện (Smart Auto)"
            textSize = 13f
        }
        val rbPriorityAnime = RadioButton(context).apply {
            id = generateSafeId()
            text = "Ưu tiên Anime / Tiếng Nhật (Anime First: ja, en)"
            textSize = 13f
        }
        val rbPriorityWestern = RadioButton(context).apply {
            id = generateSafeId()
            text = "Ưu tiên Phim Âu Mỹ (Western First: en, ja)"
            textSize = 13f
        }
        val rbPriorityCustom = RadioButton(context).apply {
            id = generateSafeId()
            text = "Tùy chỉnh danh sách ngôn ngữ (Custom Priority)"
            textSize = 13f
        }
        priorityRadioGroup.addView(rbPriorityAuto)
        priorityRadioGroup.addView(rbPriorityAnime)
        priorityRadioGroup.addView(rbPriorityWestern)
        priorityRadioGroup.addView(rbPriorityCustom)

        when (TranslatorConfig.sourcePriorityMode.lowercase(Locale.ROOT).trim()) {
            TranslatorConfig.PRIORITY_AUTO -> rbPriorityAuto.isChecked = true
            TranslatorConfig.PRIORITY_ENGLISH_FIRST -> rbPriorityWestern.isChecked = true
            TranslatorConfig.PRIORITY_CUSTOM -> rbPriorityCustom.isChecked = true
            else -> rbPriorityAnime.isChecked = true
        }
        rootLayout.addView(priorityRadioGroup)

        val customPriorityInput = EditText(context).apply {
            hint = "Nhập mã ngôn ngữ cách nhau bởi dấu phẩy (vd: ja, en, zh)"
            setText(TranslatorConfig.customPriorityList)
            textSize = 13f
            visibility = if (rbPriorityCustom.isChecked) View.VISIBLE else View.GONE
        }
        rootLayout.addView(customPriorityInput)

        priorityRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            customPriorityInput.visibility = if (checkedId == rbPriorityCustom.id) View.VISIBLE else View.GONE
        }

        // =====================================================================
        // SECTION 2: Nhà cung cấp AI (AI Provider & Credentials)
        // =====================================================================
        addSectionHeader("2. Nhà cung cấp AI (AI Provider & Credentials)")

        val providerRadioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val rbProviderGemini = RadioButton(context).apply {
            id = generateSafeId()
            text = "Google Gemini (gemini-1.5-flash) - Khuyên dùng (Nhanh & Ổn định)"
            textSize = 13f
        }
        val rbProviderOpenAI = RadioButton(context).apply {
            id = generateSafeId()
            text = "OpenAI (gpt-4o-mini)"
            textSize = 13f
        }
        val rbProviderAnthropic = RadioButton(context).apply {
            id = generateSafeId()
            text = "Anthropic Claude (claude-3-5-haiku)"
            textSize = 13f
        }
        val rbProviderCustom = RadioButton(context).apply {
            id = generateSafeId()
            text = "Tùy chỉnh / Tương thích OpenAI (Custom Endpoint / Proxy)"
            textSize = 13f
        }
        providerRadioGroup.addView(rbProviderGemini)
        providerRadioGroup.addView(rbProviderOpenAI)
        providerRadioGroup.addView(rbProviderAnthropic)
        providerRadioGroup.addView(rbProviderCustom)

        when (TranslatorConfig.provider.lowercase(Locale.ROOT).trim()) {
            TranslatorConfig.PROVIDER_OPENAI -> rbProviderOpenAI.isChecked = true
            TranslatorConfig.PROVIDER_ANTHROPIC -> rbProviderAnthropic.isChecked = true
            TranslatorConfig.PROVIDER_CUSTOM -> rbProviderCustom.isChecked = true
            else -> rbProviderGemini.isChecked = true
        }
        rootLayout.addView(providerRadioGroup)

        // API Key with Show / Hide Toggle
        addLabel("API Key (Bảo mật):")
        val apiKeyLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val apiKeyInput = EditText(context).apply {
            hint = "Nhập API Key của nhà cung cấp"
            setText(TranslatorConfig.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        var isKeyVisible = false
        val toggleKeyButton = Button(context).apply {
            text = "Hiện"
            textSize = 12f
            setOnClickListener {
                isKeyVisible = !isKeyVisible
                if (isKeyVisible) {
                    apiKeyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    text = "Ẩn"
                } else {
                    apiKeyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    text = "Hiện"
                }
                apiKeyInput.setSelection(apiKeyInput.text?.length ?: 0)
            }
        }
        apiKeyLayout.addView(apiKeyInput)
        apiKeyLayout.addView(toggleKeyButton)
        rootLayout.addView(apiKeyLayout)

        // Custom Base URL & Custom Model Name
        addLabel("Custom Base URL (Tùy chọn, để trống dùng mặc định):")
        val customBaseUrlInput = EditText(context).apply {
            hint = "https://generativelanguage.googleapis.com hoặc proxy"
            setText(TranslatorConfig.customBaseUrl)
            textSize = 13f
        }
        rootLayout.addView(customBaseUrlInput)

        addLabel("Custom Model Name (Tùy chọn, để trống dùng mặc định):")
        val customModelInput = EditText(context).apply {
            hint = "vd: gemini-1.5-flash, gpt-4o-mini, claude-3-5-haiku-20241022"
            setText(TranslatorConfig.modelName)
            textSize = 13f
        }
        rootLayout.addView(customModelInput)

        // Diagnostic Ping Test Button
        val pingButton = Button(context).apply {
            text = "[ Kiểm tra kết nối API (Ping Test) ]"
            textSize = 13f
        }
        rootLayout.addView(pingButton)

        pingButton.setOnClickListener {
            val selectedProvider = when (providerRadioGroup.checkedRadioButtonId) {
                rbProviderOpenAI.id -> TranslatorConfig.PROVIDER_OPENAI
                rbProviderAnthropic.id -> TranslatorConfig.PROVIDER_ANTHROPIC
                rbProviderCustom.id -> TranslatorConfig.PROVIDER_CUSTOM
                else -> TranslatorConfig.PROVIDER_GEMINI
            }
            val key = apiKeyInput.text?.toString()?.trim() ?: ""
            val baseUrl = customBaseUrlInput.text?.toString()?.trim() ?: ""
            val model = customModelInput.text?.toString()?.trim() ?: ""

            showToast(context, "Đang thực hiện Ping Test tới $selectedProvider...")
            pingButton.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                val start = System.currentTimeMillis()
                val effectiveModel = if (model.isNotBlank()) model else when (selectedProvider) {
                    TranslatorConfig.PROVIDER_GEMINI -> "gemini-1.5-flash"
                    TranslatorConfig.PROVIDER_OPENAI -> "gpt-4o-mini"
                    TranslatorConfig.PROVIDER_ANTHROPIC -> "claude-3-5-haiku-20241022"
                    TranslatorConfig.PROVIDER_CUSTOM -> "gpt-4o-mini"
                    else -> "gemini-1.5-flash"
                }

                val result = try {
                    val client = AITranslatorFactory.createClient(
                        provider = selectedProvider,
                        apiKey = key,
                        baseUrl = baseUrl,
                        modelName = effectiveModel
                    )
                    client.ping()
                } catch (t: Throwable) {
                    TranslationResult(
                        translations = emptyMap(),
                        rawResponse = null,
                        success = false,
                        error = t.message ?: "Unknown error"
                    )
                }
                val elapsed = System.currentTimeMillis() - start

                withContext(Dispatchers.Main) {
                    pingButton.isEnabled = true
                    val title: String
                    val message: String
                    if (result.success) {
                        title = "✓ Ping Test Thành Công"
                        val sample = result.translations.values.firstOrNull() ?: "OK"
                        message = "Kết nối thành công tới $selectedProvider (${elapsed}ms)!\nModel: $effectiveModel\nPhản hồi dịch thử: '$sample'"
                        showToast(context, "Ping thành công (${elapsed}ms)")
                    } else if (result.isQuotaExhausted) {
                        title = "✗ Lỗi Hạn Mức Quota"
                        message = "Tài khoản API đã hết hạn mức (Resource Exhausted):\n${result.error}"
                        showToast(context, "Lỗi Quota: Hết hạn mức API")
                    } else {
                        title = "✗ Kết Nối Thất Bại"
                        message = "Không thể kết nối tới $selectedProvider (${elapsed}ms):\n${result.error}"
                        showToast(context, "Kết nối thất bại: ${result.error}")
                    }

                    try {
                        AlertDialog.Builder(context)
                            .setTitle(title)
                            .setMessage(message)
                            .setPositiveButton("Đóng", null)
                            .show()
                    } catch (_: Throwable) {
                        println("[$title] $message")
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 3: Tùy chọn Nâng cao & Bộ nhớ đệm (Advanced & Cache)
        // =====================================================================
        addSectionHeader("3. Tùy chọn Nâng cao & Bộ nhớ đệm (Advanced & Cache)")

        addLabel("Kích thước gói dịch (Chunk Size: 10 - 80 câu, mặc định 30):")
        val chunkSizeInput = EditText(context).apply {
            hint = "30"
            setText(TranslatorConfig.chunkSize.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
        }
        rootLayout.addView(chunkSizeInput)

        val sdhCheckBox = CheckBox(context).apply {
            text = "Giữ & Dịch âm thanh mô tả SDH ([Music], (Laughs))"
            isChecked = TranslatorConfig.preserveSdh
            textSize = 13f
            setPadding(0, pad4, 0, pad4)
        }
        rootLayout.addView(sdhCheckBox)

        addLabel("Thuật ngữ / Bảng từ vựng tùy chỉnh (Custom Glossary):")
        val glossaryInput = EditText(context).apply {
            hint = "Nhập hướng dẫn hoặc cặp thuật ngữ tùy chỉnh (vd: Jutsu = Nhẫn thuật)\nMỗi dòng một hướng dẫn hoặc thuật ngữ."
            setText(TranslatorConfig.customGlossary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            textSize = 13f
        }
        rootLayout.addView(glossaryInput)

        // Cache Metrics & Clear Cache
        val formatMetrics = {
            val (count, bytes) = SubtitleCacheManager.getMetrics(context)
            val mb = bytes.toDouble() / (1024 * 1024)
            "• Bộ nhớ đệm phụ đề hiện tại: $count file (${String.format(Locale.US, "%.2f MB", mb)} / 50 MB)"
        }

        val metricsText = TextView(context).apply {
            text = formatMetrics()
            textSize = 13f
            setPadding(0, pad8, 0, pad8)
        }
        rootLayout.addView(metricsText)

        val clearCacheButton = Button(context).apply {
            text = "[ Xóa bộ nhớ đệm phụ đề (Clear Cache) ]"
            textSize = 13f
            setOnClickListener {
                SubtitleCacheManager.clearAll(context)
                showToast(context, "Đã xóa toàn bộ bộ nhớ đệm phụ đề.")
                metricsText.text = formatMetrics()
            }
        }
        rootLayout.addView(clearCacheButton)

        // =====================================================================
        // Dialog Action Buttons (Save, Reset, Close)
        // =====================================================================
        val builder = AlertDialog.Builder(context)
            .setTitle("Cài đặt AI Subtitle Translator")
            .setView(scrollView)
            .setPositiveButton("Lưu cấu hình") { dialog, _ ->
                // 1. Extract values
                TranslatorConfig.isEnabled = enableCheckBox.isChecked
                val rawTarget = targetLangInput.text?.toString()?.trim() ?: "vi"
                TranslatorConfig.targetLanguage = if (rawTarget.isNotBlank()) rawTarget else "vi"

                TranslatorConfig.deliveryStrategy = if (deliveryRadioGroup.checkedRadioButtonId == rbStrategySkeleton.id) {
                    TranslatorConfig.DELIVERY_STRATEGY_B
                } else {
                    TranslatorConfig.DELIVERY_STRATEGY_A
                }

                TranslatorConfig.sourcePriorityMode = when (priorityRadioGroup.checkedRadioButtonId) {
                    rbPriorityAuto.id -> TranslatorConfig.PRIORITY_AUTO
                    rbPriorityWestern.id -> TranslatorConfig.PRIORITY_ENGLISH_FIRST
                    rbPriorityCustom.id -> TranslatorConfig.PRIORITY_CUSTOM
                    else -> TranslatorConfig.PRIORITY_ANIME_FIRST
                }
                TranslatorConfig.customPriorityList = customPriorityInput.text?.toString()?.trim() ?: "ja, en"

                TranslatorConfig.provider = when (providerRadioGroup.checkedRadioButtonId) {
                    rbProviderOpenAI.id -> TranslatorConfig.PROVIDER_OPENAI
                    rbProviderAnthropic.id -> TranslatorConfig.PROVIDER_ANTHROPIC
                    rbProviderCustom.id -> TranslatorConfig.PROVIDER_CUSTOM
                    else -> TranslatorConfig.PROVIDER_GEMINI
                }

                TranslatorConfig.apiKey = apiKeyInput.text?.toString()?.trim() ?: ""
                TranslatorConfig.customBaseUrl = customBaseUrlInput.text?.toString()?.trim() ?: ""
                TranslatorConfig.modelName = customModelInput.text?.toString()?.trim() ?: ""

                val rawChunk = chunkSizeInput.text?.toString()?.toIntOrNull() ?: 30
                TranslatorConfig.chunkSize = rawChunk.coerceIn(10, 80)

                TranslatorConfig.preserveSdh = sdhCheckBox.isChecked
                TranslatorConfig.customGlossary = glossaryInput.text?.toString()?.trim() ?: ""

                // 2. Persist to SharedPreferences
                TranslatorConfig.save(context)
                showToast(context, "Đã lưu cài đặt AI Subtitle Translator!")
                dialog.dismiss()
            }
            .setNeutralButton("Khôi phục mặc định") { dialog, _ ->
                TranslatorConfig.resetToDefaults()
                TranslatorConfig.save(context)
                showToast(context, "Đã khôi phục cài đặt mặc định.")
                dialog.dismiss()
            }
            .setNegativeButton("Đóng") { dialog, _ ->
                dialog.dismiss()
            }

        return builder
    }

    /**
     * Instantiates an AlertDialog instance without showing it (safe for headless testing).
     */
    @JvmStatic
    fun createDialog(context: Context): AlertDialog {
        return buildDialog(context).create()
    }

    /**
     * Constructs and displays the settings dialog on screen.
     */
    @JvmStatic
    fun show(context: Context): AlertDialog {
        val dialog = createDialog(context)
        try {
            dialog.show()
        } catch (_: Throwable) {
            // Graceful fallback when running in headless desktop test environments
        }
        return dialog
    }
}
