package com.lagradost.cloudstream3.subtitles.ai.hook

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Dynamic Provider Hook Engine for CloudStream AI Subtitle Translator.
 * Maintains the Outermost Decorator Invariant in APIHolder.allProviders
 * and synchronizes APIHolder.addPluginMapping for Search, Bookmarks, and Deep Links.
 */
object AITranslatorHook {
    private const val TAG = "AITranslatorHook"
    @Volatile var appContext: Context? = null
    private val DELEGATE_GETTERS = listOf("getBaseApi", "getOriginal", "getDelegate", "getBase", "getInner")
    private val PREFERRED_FIELDS = listOf("baseApi", "original", "delegate", "base", "inner")

    private fun isAITranslatorWrapper(api: MainAPI): Boolean {
        return api is AITranslatorEnrichedMainAPI || api.javaClass.name.contains("AITranslatorEnrichedMainAPI")
    }

    /**
     * Resolves the immediate inner delegate of a wrapped provider.
     * Supports direct references, getter inspection, and declared field traversal.
     */
    fun getInnerDelegate(provider: MainAPI): MainAPI? {
        if (provider is AITranslatorEnrichedMainAPI) {
            val b = provider.baseApi
            return if (b !== provider) b else null
        }

        val clazz = provider.javaClass

        // 1. Cross-classloader direct field check if class name matches
        if (clazz.name.contains("AITranslatorEnrichedMainAPI")) {
            try {
                val field = clazz.getDeclaredField("baseApi")
                field.isAccessible = true
                val res = field.get(provider) as? MainAPI
                if (res != null && res !== provider) return res
            } catch (_: Throwable) {}
        }

        // 2. Check standard getter methods
        for (getterName in DELEGATE_GETTERS) {
            try {
                val method = clazz.getMethod(getterName)
                if (MainAPI::class.java.isAssignableFrom(method.returnType)) {
                    val candidate = method.invoke(provider) as? MainAPI
                    if (candidate != null && candidate !== provider) return candidate
                }
            } catch (_: NoSuchMethodException) {
            } catch (_: Throwable) {}
        }

        // 3. Traverse declared fields in class hierarchy
        var currentClass: Class<*>? = clazz
        while (currentClass != null && currentClass != Any::class.java && currentClass != MainAPI::class.java) {
            // 3a. Preferred field names first
            for (fieldName in PREFERRED_FIELDS) {
                try {
                    val field = currentClass.getDeclaredField(fieldName)
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (MainAPI::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        val candidate = field.get(provider) as? MainAPI
                        if (candidate != null && candidate !== provider) return candidate
                    }
                } catch (_: NoSuchFieldException) {
                } catch (_: Throwable) {}
            }

            // 3b. Any MainAPI field
            for (field in currentClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (MainAPI::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val candidate = field.get(provider) as? MainAPI
                        if (candidate != null && candidate !== provider) return candidate
                    } catch (_: Throwable) {}
                }
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    /**
     * Traverses downward to the innermost leaf provider in the decorator chain.
     * Guaranteed cycle-safe via IdentityHashMap.
     */
    fun getBaseProvider(provider: MainAPI): MainAPI {
        val visited = Collections.newSetFromMap(IdentityHashMap<MainAPI, Boolean>())
        var current = provider
        visited.add(current)
        while (true) {
            val inner = getInnerDelegate(current) ?: break
            if (!visited.add(inner)) {
                logW(TAG, "Cycle detected in provider chain at ${inner.name}")
                break
            }
            current = inner
        }
        return current
    }

    /**
     * Safely updates an inner delegate reference on a parent wrapper node.
     * Enforces strict identity check (currentVal === oldChild) to prevent clobbering unrelated fields.
     */
    fun setInnerDelegate(parent: MainAPI, oldChild: MainAPI, newDelegate: MainAPI): Boolean {
        if (parent is AITranslatorEnrichedMainAPI) {
            parent.baseApi = newDelegate
            return true
        }

        val clazz = parent.javaClass
        var currentClass: Class<*>? = clazz
        while (currentClass != null && currentClass != Any::class.java) {
            for (field in currentClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (MainAPI::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val currentVal = field.get(parent)
                        if (currentVal === oldChild) {
                            field.set(parent, newDelegate)
                            return true
                        }
                    } catch (_: Throwable) {}
                }
            }
            currentClass = currentClass.superclass
        }
        return false
    }

    /**
     * Slices out any existing AITranslatorEnrichedMainAPI instances from anywhere in the chain.
     * Prevents duplicate wrappers, decorator inflation, and recursive forwarding loops.
     * Guaranteed cycle-safe via shared IdentityHashMap across leading unpeeling and mid-chain slicing.
     */
    @JvmOverloads
    fun unwrapAITranslatorChain(
        root: MainAPI,
        visited: MutableSet<MainAPI> = Collections.newSetFromMap(IdentityHashMap())
    ): MainAPI {
        var current: MainAPI = root

        // 1. Iteratively unpeel leading AI translator wrappers with cycle tracking
        while (isAITranslatorWrapper(current)) {
            if (!visited.add(current)) {
                logW(TAG, "Cycle detected in leading AI wrapper chain at ${current.name}")
                return current
            }
            val inner = if (current is AITranslatorEnrichedMainAPI) {
                if (current.baseApi !== current) current.baseApi else null
            } else getInnerDelegate(current)
            if (inner == null || inner === current) {
                return current
            }
            current = inner
        }

        // 2. Track the unpeeled non-AI root in the shared visited set
        if (!visited.add(current)) {
            logW(TAG, "Cycle detected in provider unwrap chain at ${current.name}")
            return current
        }

        // 3. Traverse non-AI wrappers and splice out any buried AI wrappers mid-chain
        var parent: MainAPI? = current
        while (parent != null) {
            val child = getInnerDelegate(parent) ?: break
            if (!visited.add(child)) break

            if (isAITranslatorWrapper(child)) {
                val childInner = if (child is AITranslatorEnrichedMainAPI) child.baseApi else getInnerDelegate(child) ?: child
                val unwrappedChild = unwrapAITranslatorChain(childInner, visited)
                setInnerDelegate(parent, child, unwrappedChild)
                break
            }
            parent = child
        }
        return current
    }

    /**
     * Safely synchronizes provider mapping in APIHolder.apis:
     * 1. Purges any pre-existing entries (base provider or prior wrappers) with matching name.
     * 2. Appends the new canonical wrapper via APIHolder.addPluginMapping.
     * Ensures exactly ONE entry per provider exists in APIHolder.apis and UI source list.
     */
    fun syncPluginMapping(provider: MainAPI) {
        try {
            APIHolder.apis.withLock {
                APIHolder.apis = APIHolder.apis.filter { it.name != provider.name }
            }
            APIHolder.addPluginMapping(provider)
        } catch (_: Throwable) {
            try {
                APIHolder.addPluginMapping(provider)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Atomically deduplicates APIHolder.apis by provider name to eliminate duplicate source listings.
     */
    fun deduplicateApis() {
        try {
            APIHolder.apis.withLock {
                APIHolder.apis = APIHolder.apis.distinctBy { it.name }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Scans APIHolder.allProviders, un-buries any enclosed AI translator instances,
     * and hoists AITranslatorEnrichedMainAPI to the outermost position (Head).
     * Synchronizes APIHolder.addPluginMapping for Search, Bookmarks, and Deep Links.
     */
    @Synchronized
    fun scanAndHoistHooks() {
        val allProviders = APIHolder.allProviders
        allProviders.withLock {
            // 1. Prune duplicate provider names in allProviders
            val seenNames = mutableSetOf<String>()
            var idx = 0
            while (idx < allProviders.size) {
                val p = allProviders.getOrNull(idx) ?: break
                if (!seenNames.add(p.name)) {
                    allProviders.removeAt(idx)
                } else {
                    idx++
                }
            }

            for (i in 0 until allProviders.size) {
                val current = allProviders.getOrNull(i) ?: continue
                val base = getBaseProvider(current)

                // Never wrap native AI translator provider
                if (base.name.equals("AITranslatorProvider", ignoreCase = true) ||
                    base.javaClass.name.contains("AITranslatorProvider")
                ) {
                    continue
                }

                // If current head is already our wrapper, ensure inner chain is clean
                if (current.javaClass.name.contains("AITranslatorEnrichedMainAPI")) {
                    val aiWrapper = current as? AITranslatorEnrichedMainAPI
                    if (aiWrapper != null) {
                        val cleanInner = unwrapAITranslatorChain(aiWrapper.baseApi)
                        if (cleanInner !== aiWrapper.baseApi) {
                            aiWrapper.baseApi = cleanInner
                        }
                    } else {
                        val inner = getInnerDelegate(current)
                        if (inner != null) {
                            val cleanInner = unwrapAITranslatorChain(inner)
                            if (cleanInner !== inner) {
                                setInnerDelegate(current, inner, cleanInner)
                            }
                        }
                    }
                    syncPluginMapping(current)
                    continue
                }

                // Provider is wrapped by peer enricher or is unwrapped:
                // 1. Splice out any existing AI translator instance buried inside
                val cleanChain = unwrapAITranslatorChain(current)

                // 2. Wrap at outermost position
                val hoisted = AITranslatorEnrichedMainAPI(cleanChain)
                allProviders[i] = hoisted

                // 3. Synchronize CloudStream Plugin Mapping for Search & Bookmarks without duplicates
                syncPluginMapping(hoisted)
                logI(TAG, "Successfully attached AI Subtitle Translator to provider: ${base.name}")
            }
        }
        deduplicateApis()
    }

    /**
     * Detaches all hooks cleanly, restoring the original provider chain and plugin mappings.
     * Invoked during plugin unloading.
     */
    fun detachHooks() {
        val allProviders = APIHolder.allProviders
        allProviders.withLock {
            for (i in 0 until allProviders.size) {
                val current = allProviders.getOrNull(i) ?: continue
                val unwrapped = unwrapAITranslatorChain(current)
                if (unwrapped !== current) {
                    allProviders[i] = unwrapped
                    syncPluginMapping(unwrapped)
                }
            }
        }
        deduplicateApis()
    }

    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] [INFO] $msg") }
    }

    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) { System.err.println("[$tag] [WARN] $msg") }
    }
}
