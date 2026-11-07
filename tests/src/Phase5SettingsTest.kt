@file:Suppress("DEPRECATION")
package tests.adversarial

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.ui.AITranslatorSettingsDialog
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

// =============================================================================
// Mock In-Memory SharedPreferences Implementation
// =============================================================================

class MockSharedPreferences(
    private val store: MutableMap<String, Any?> = ConcurrentHashMap()
) : SharedPreferences {

    override fun getAll(): Map<String, *> = HashMap(store)

    override fun getString(key: String?, defValue: String?): String? {
        val v = store[key]
        return if (v != null) v.toString() else defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val v = store[key]
        return (v as? MutableSet<String>) ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        val v = store[key]
        return when (v) {
            is Int -> v
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getLong(key: String?, defValue: Long): Long {
        val v = store[key]
        return when (v) {
            is Long -> v
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        val v = store[key]
        return when (v) {
            is Float -> v
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val v = store[key]
        return when (v) {
            is Boolean -> v
            is String -> v.toBoolean()
            else -> defValue
        }
    }

    override fun contains(key: String?): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MockEditor(this, store)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class MockEditor(
        private val prefs: MockSharedPreferences,
        private val targetMap: MutableMap<String, Any?>
    ) : SharedPreferences.Editor {
        private val uncommitted = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removed.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            synchronized(targetMap) {
                if (clearRequested) {
                    targetMap.clear()
                    clearRequested = false
                }
                for (r in removed) {
                    targetMap.remove(r)
                }
                removed.clear()
                for ((k, v) in uncommitted) {
                    if (v == null) {
                        targetMap.remove(k)
                    } else {
                        targetMap[k] = v
                    }
                }
                uncommitted.clear()
            }
        }
    }
}

// =============================================================================
// Mock Context Implementation (Unsafe Allocated for Android Stub Safety)
// =============================================================================

open class MockContext : ContextWrapper(null) {
    private val prefsRegistry = ConcurrentHashMap<String, MockSharedPreferences>()
    var mockFilesDir: File = File(System.getProperty("java.io.tmpdir", "."), "mock_p5_files")

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val key = name ?: "default_prefs"
        return prefsRegistry.getOrPut(key) { MockSharedPreferences() }
    }

    override fun getFilesDir(): File {
        if (!mockFilesDir.exists()) mockFilesDir.mkdirs()
        return mockFilesDir
    }

    companion object {
        fun create(): MockContext {
            return try {
                // Try direct constructor if stub is not throwing
                MockContext()
            } catch (_: Throwable) {
                // If android.jar constructor throws RuntimeException: Stub!, allocate via Unsafe
                val f: Field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
                f.isAccessible = true
                val unsafe = f.get(null)
                val method = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
                val instance = method.invoke(unsafe, MockContext::class.java) as MockContext
                // Initialize internal state
                val prefsField = MockContext::class.java.getDeclaredField("prefsRegistry")
                prefsField.isAccessible = true
                prefsField.set(instance, ConcurrentHashMap<String, MockSharedPreferences>())
                val filesField = MockContext::class.java.getDeclaredField("mockFilesDir")
                filesField.isAccessible = true
                val tempDir = File(System.getProperty("java.io.tmpdir", "."), "mock_p5_files_" + System.currentTimeMillis())
                tempDir.mkdirs()
                filesField.set(instance, tempDir)
                instance
            }
        }
    }
}

// =============================================================================
// Main Phase 5 Test Suite Runner
// =============================================================================

fun main() {
    println("================================================================================")
    println(">>> Starting Phase 5 Settings UI & SharedPreferences Test Suite...")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun check(testName: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $testName")
            passed++
        } else {
            System.err.println("  [FAIL] $testName -> $details")
            failed++
        }
    }

    val mockContext = MockContext.create()

    // -------------------------------------------------------------------------
    // Section 1: Factory Defaults Verification
    // -------------------------------------------------------------------------
    println("\n--- [Section 1: Factory Defaults Verification] ---")
    TranslatorConfig.resetToDefaults()

    check("1.1 Default isEnabled is true", TranslatorConfig.isEnabled)
    check("1.2 Default targetLanguage is 'vi'", TranslatorConfig.targetLanguage == "vi")
    check(
        "1.3 Default deliveryStrategy is Strategy A (batch)",
        TranslatorConfig.deliveryStrategy in listOf(TranslatorConfig.DELIVERY_STRATEGY_A, TranslatorConfig.STRATEGY_BATCH, "batch")
    )
    check(
        "1.4 Default sourcePriorityMode is anime_first",
        TranslatorConfig.sourcePriorityMode == TranslatorConfig.PRIORITY_ANIME_FIRST
    )
    check(
        "1.5 Default customPriorityList is 'ja, en'",
        TranslatorConfig.customPriorityList == "ja, en"
    )
    check(
        "1.6 Default provider is gemini",
        TranslatorConfig.provider == TranslatorConfig.PROVIDER_GEMINI
    )
    check("1.7 Default apiKey is empty", TranslatorConfig.apiKey.isEmpty())
    check("1.8 Default customBaseUrl is empty", TranslatorConfig.customBaseUrl.isEmpty())
    check("1.9 Default modelName is empty", TranslatorConfig.modelName.isEmpty())
    check("1.10 Default chunkSize is 30", TranslatorConfig.chunkSize == 30)
    check("1.11 Default preserveSdh is true", TranslatorConfig.preserveSdh)
    check("1.12 Default customGlossary is empty", TranslatorConfig.customGlossary.isEmpty())

    // -------------------------------------------------------------------------
    // Section 2: SharedPreferences Save & Restore 100% Fidelity
    // -------------------------------------------------------------------------
    println("\n--- [Section 2: SharedPreferences 100% Save & Restore Fidelity] ---")

    // Configure distinct non-default values across ALL 12 keys
    TranslatorConfig.isEnabled = false
    TranslatorConfig.targetLanguage = "ja"
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B
    TranslatorConfig.sourcePriorityMode = TranslatorConfig.PRIORITY_CUSTOM
    TranslatorConfig.customPriorityList = "fr, de, es, zh"
    TranslatorConfig.provider = TranslatorConfig.PROVIDER_ANTHROPIC
    TranslatorConfig.apiKey = "sk-ant-api03-test-token-xyz"
    TranslatorConfig.customBaseUrl = "https://custom.anthropic.internal/v1"
    TranslatorConfig.modelName = "claude-3-5-sonnet-20241022"
    TranslatorConfig.chunkSize = 45
    TranslatorConfig.preserveSdh = false
    TranslatorConfig.customGlossary = "Jutsu = Nhẫn thuật\nShinigami = Thần chết"

    // Save configuration to SharedPreferences
    TranslatorConfig.save(mockContext)

    // Inspect the underlying raw SharedPreferences store
    val rawPrefs = mockContext.getSharedPreferences("ai_subtitle_translator_preferences", Context.MODE_PRIVATE)
    check("2.1 Raw key_enabled stored", rawPrefs.getBoolean("key_enabled", true) == false)
    check("2.2 Raw key_target_lang stored", rawPrefs.getString("key_target_lang", null) == "ja")
    check("2.3 Raw key_delivery_strategy stored", rawPrefs.getString("key_delivery_strategy", null) == TranslatorConfig.DELIVERY_STRATEGY_B)
    check("2.4 Raw key_priority_mode stored", rawPrefs.getString("key_priority_mode", null) == TranslatorConfig.PRIORITY_CUSTOM)
    check("2.5 Raw key_custom_priority stored", rawPrefs.getString("key_custom_priority", null) == "fr, de, es, zh")
    check("2.6 Raw key_provider stored", rawPrefs.getString("key_provider", null) == TranslatorConfig.PROVIDER_ANTHROPIC)
    check("2.7 Raw key_api_key stored", rawPrefs.getString("key_api_key", null) == "sk-ant-api03-test-token-xyz")
    check("2.8 Raw key_base_url stored", rawPrefs.getString("key_base_url", null) == "https://custom.anthropic.internal/v1")
    check("2.9 Raw key_model_name stored", rawPrefs.getString("key_model_name", null) == "claude-3-5-sonnet-20241022")
    check("2.10 Raw key_chunk_size stored", rawPrefs.getInt("key_chunk_size", 0) == 45)
    check("2.11 Raw key_preserve_sdh stored", rawPrefs.getBoolean("key_preserve_sdh", true) == false)
    check("2.12 Raw key_custom_glossary stored", rawPrefs.getString("key_custom_glossary", null) == "Jutsu = Nhẫn thuật\nShinigami = Thần chết")

    // Reset in-memory fields back to factory defaults
    TranslatorConfig.resetToDefaults()
    check("2.13 Memory reset confirmed: isEnabled", TranslatorConfig.isEnabled == true)
    check("2.14 Memory reset confirmed: targetLanguage", TranslatorConfig.targetLanguage == "vi")
    check("2.15 Memory reset confirmed: apiKey", TranslatorConfig.apiKey.isEmpty())
    check("2.16 Memory reset confirmed: chunkSize", TranslatorConfig.chunkSize == 30)

    // Reload from SharedPreferences via TranslatorConfig.init(mockContext)
    TranslatorConfig.init(mockContext)

    // Assert 100% restore fidelity
    check("2.17 Restored isEnabled", TranslatorConfig.isEnabled == false)
    check("2.18 Restored targetLanguage", TranslatorConfig.targetLanguage == "ja")
    check("2.19 Restored deliveryStrategy", TranslatorConfig.deliveryStrategy == TranslatorConfig.DELIVERY_STRATEGY_B)
    check("2.20 Restored sourcePriorityMode", TranslatorConfig.sourcePriorityMode == TranslatorConfig.PRIORITY_CUSTOM)
    check("2.21 Restored customPriorityList", TranslatorConfig.customPriorityList == "fr, de, es, zh")
    check("2.22 Restored provider", TranslatorConfig.provider == TranslatorConfig.PROVIDER_ANTHROPIC)
    check("2.23 Restored apiKey", TranslatorConfig.apiKey == "sk-ant-api03-test-token-xyz")
    check("2.24 Restored customBaseUrl", TranslatorConfig.customBaseUrl == "https://custom.anthropic.internal/v1")
    check("2.25 Restored modelName", TranslatorConfig.modelName == "claude-3-5-sonnet-20241022")
    check("2.26 Restored chunkSize", TranslatorConfig.chunkSize == 45)
    check("2.27 Restored preserveSdh", TranslatorConfig.preserveSdh == false)
    check("2.28 Restored customGlossary", TranslatorConfig.customGlossary == "Jutsu = Nhẫn thuật\nShinigami = Thần chết")

    // -------------------------------------------------------------------------
    // Section 3: Value Coercion and Boundary Handling
    // -------------------------------------------------------------------------
    println("\n--- [Section 3: Value Coercion & Boundary Handling] ---")

    // Under-boundary chunk size (< 10) coerced to 10
    TranslatorConfig.chunkSize = 3
    TranslatorConfig.save(mockContext)
    TranslatorConfig.chunkSize = 50
    TranslatorConfig.init(mockContext)
    check("3.1 Underflow chunkSize (3) coerced to 10", TranslatorConfig.chunkSize == 10)

    // Over-boundary chunk size (> 80) coerced to 80
    TranslatorConfig.chunkSize = 150
    TranslatorConfig.save(mockContext)
    TranslatorConfig.chunkSize = 50
    TranslatorConfig.init(mockContext)
    check("3.2 Overflow chunkSize (150) coerced to 80", TranslatorConfig.chunkSize == 80)

    // Strategy B persistence
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B
    TranslatorConfig.save(mockContext)
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_A
    TranslatorConfig.init(mockContext)
    check("3.3 Strategy B persisted and restored", TranslatorConfig.deliveryStrategy == TranslatorConfig.DELIVERY_STRATEGY_B)

    // Strategy A persistence
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_A
    TranslatorConfig.save(mockContext)
    TranslatorConfig.deliveryStrategy = TranslatorConfig.DELIVERY_STRATEGY_B
    TranslatorConfig.init(mockContext)
    check("3.4 Strategy A persisted and restored", TranslatorConfig.deliveryStrategy == TranslatorConfig.DELIVERY_STRATEGY_A)

    // Reset to defaults with context overload
    TranslatorConfig.resetToDefaults(mockContext)
    check("3.5 resetToDefaults(context) resets isEnabled", TranslatorConfig.isEnabled == true)
    check("3.6 resetToDefaults(context) resets targetLanguage", TranslatorConfig.targetLanguage == "vi")
    check("3.7 resetToDefaults(context) resets deliveryStrategy", TranslatorConfig.deliveryStrategy == TranslatorConfig.DELIVERY_STRATEGY_A)
    val rawAfterReset = mockContext.getSharedPreferences("ai_subtitle_translator_preferences", Context.MODE_PRIVATE)
    check("3.8 resetToDefaults(context) persisted reset to prefs", rawAfterReset.getString("key_target_lang", "") == "vi")

    // -------------------------------------------------------------------------
    // Section 4: Model Resolution and Custom Priority Parsing
    // -------------------------------------------------------------------------
    println("\n--- [Section 4: Model Resolution & Custom Priority Parsing] ---")

    TranslatorConfig.modelName = ""
    TranslatorConfig.provider = TranslatorConfig.PROVIDER_GEMINI
    check("4.1 Gemini effective model default", TranslatorConfig.getEffectiveModel() == "gemini-1.5-flash")

    TranslatorConfig.provider = TranslatorConfig.PROVIDER_OPENAI
    check("4.2 OpenAI effective model default", TranslatorConfig.getEffectiveModel() == "gpt-4o-mini")

    TranslatorConfig.provider = TranslatorConfig.PROVIDER_ANTHROPIC
    check("4.3 Anthropic effective model default", TranslatorConfig.getEffectiveModel() == "claude-3-5-haiku-20241022")

    TranslatorConfig.provider = TranslatorConfig.PROVIDER_CUSTOM
    check("4.4 Custom effective model default", TranslatorConfig.getEffectiveModel() == "gpt-4o-mini")

    TranslatorConfig.modelName = "custom-deepseek-v3"
    check("4.5 Explicit modelName override takes precedence", TranslatorConfig.getEffectiveModel() == "custom-deepseek-v3")

    TranslatorConfig.customPriorityList = " JA , EN ,  vi, , ZH "
    val parsedPriority = TranslatorConfig.getCustomPriorityLanguages()
    check("4.6 Custom priority parsing count", parsedPriority.size == 4)
    check("4.7 Custom priority parsed order", parsedPriority == listOf("ja", "en", "vi", "zh"))

    // -------------------------------------------------------------------------
    // Section 5: Cache Metrics and Disk Maintenance
    // -------------------------------------------------------------------------
    println("\n--- [Section 5: Cache Metrics & Disk Maintenance] ---")

    val initialMetrics = SubtitleCacheManager.getMetrics(mockContext)
    check("5.1 getMetrics returns non-negative file count", initialMetrics.first >= 0)
    check("5.2 getMetrics returns non-negative byte count", initialMetrics.second >= 0)

    // Clear all cache through manager
    SubtitleCacheManager.clearAll(mockContext)
    val afterClearMetrics = SubtitleCacheManager.getMetrics(mockContext)
    check("5.3 clearAll sets file count to 0", afterClearMetrics.first == 0)
    check("5.4 clearAll sets byte count to 0", afterClearMetrics.second == 0L)

    // -------------------------------------------------------------------------
    // Section 6: UI Component Integrity & Programmatic Construction
    // -------------------------------------------------------------------------
    println("\n--- [Section 6: UI Component Integrity & Programmatic Construction] ---")

    val dialogClass = AITranslatorSettingsDialog::class.java
    val showMethod = dialogClass.methods.firstOrNull { it.name == "show" && it.parameterTypes.size == 1 }
    check("6.1 AITranslatorSettingsDialog exposes public show(Context)", showMethod != null)

    val createDialogMethod = dialogClass.methods.firstOrNull { it.name == "createDialog" && it.parameterTypes.size == 1 }
    check("6.2 AITranslatorSettingsDialog exposes public createDialog(Context)", createDialogMethod != null)

    val buildDialogMethod = dialogClass.methods.firstOrNull { it.name == "buildDialog" && it.parameterTypes.size == 1 }
    check("6.3 AITranslatorSettingsDialog exposes public buildDialog(Context)", buildDialogMethod != null)

    println("\n================================================================================")
    println(">>> Phase 5 Test Results: $passed PASSED, $failed FAILED (Total ${passed + failed})")
    println("================================================================================")

    if (failed > 0) {
        System.exit(1)
    }
}
