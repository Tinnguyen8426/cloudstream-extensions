package com.lagradost.cloudstream3.subtitles.ai.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer

/**
 * Snapshot of the subtitle track currently selected in the CloudStream player.
 */
data class PreferredSubtitleTrack(
    val name: String,
    val url: String,
    val languageCode: String,
    val originName: String,
    val headers: Map<String, String>
)

/**
 * Reflective bridge to the CloudStream player internals (4.7 - 4.9 tolerant).
 * All reflection is try/catch guarded; failures degrade to "do nothing" so the
 * emitted track remains selectable manually.
 */
object PlayerSubtitleBridge {
    private const val TAG = "PlayerSubtitleBridge"
    private const val PLAYER_VM_CLASS = "com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel"

    fun runOnMain(action: () -> Unit) {
        try {
            Handler(Looper.getMainLooper()).post {
                try { action() } catch (t: Throwable) { logW("main action failed: ${t.message}") }
            }
        } catch (_: Throwable) {}
    }

    private fun currentActivity(): Activity? = try { CommonActivity.activity } catch (_: Throwable) { null }

    private fun collectFragments(fm: Any?, depth: Int, out: MutableList<Any>) {
        if (fm == null || depth > 4) return
        val fragments = try {
            fm.javaClass.getMethod("getFragments").invoke(fm) as? List<*>
        } catch (_: Throwable) {
            null
        } ?: return
        for (fragment in fragments) {
            if (fragment == null) continue
            out.add(fragment)
            val childFm = try {
                fragment.javaClass.getMethod("getChildFragmentManager").invoke(fragment)
            } catch (_: Throwable) {
                null
            }
            collectFragments(childFm, depth + 1, out)
        }
    }

    // Result pages also expose a `getPlayer()` (mini player), so method presence alone
    // is not enough: only fragments of the actual player hierarchy qualify.
    private val playerFragmentBaseClass: Class<*>? = try {
        Class.forName("com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment")
    } catch (_: Throwable) {
        null
    }

    private fun findPlayerFragment(): Any? {
        val activity = currentActivity() ?: return null
        val fm = try {
            activity.javaClass.getMethod("getSupportFragmentManager").invoke(activity)
        } catch (_: Throwable) {
            null
        } ?: return null

        val fragments = mutableListOf<Any>()
        collectFragments(fm, 0, fragments)
        for (fragment in fragments) {
            val base = playerFragmentBaseClass
            if (base != null && !base.isInstance(fragment)) continue
            var cls: Class<*>? = fragment.javaClass
            while (cls != null) {
                try {
                    val method = cls.getMethod("getPlayer")
                    val player = method.invoke(fragment)
                    if (player != null) return fragment
                    break
                } catch (_: NoSuchMethodException) {
                    cls = cls.superclass
                } catch (_: Throwable) {
                    break
                }
            }
        }
        return null
    }

    private fun playerOf(fragment: Any?): Any? {
        if (fragment == null) return null
        return try { fragment.javaClass.getMethod("getPlayer").invoke(fragment) } catch (_: Throwable) { null }
    }

    private fun resolveViewModel(fragment: Any?): Any? {
        if (fragment != null) {
            var cls: Class<*>? = fragment.javaClass
            while (cls != null) {
                try {
                    val field = cls.getDeclaredField("viewModel")
                    field.isAccessible = true
                    val vm = field.get(fragment)
                    if (vm != null && vm.javaClass.name.contains("PlayerGeneratorViewModel")) return vm
                } catch (_: NoSuchFieldException) {
                    // keep walking up
                } catch (_: Throwable) {
                    break
                }
                cls = cls.superclass
            }
        }

        val owner = fragment ?: currentActivity() ?: return null
        return try {
            val providerCls = Class.forName("androidx.lifecycle.ViewModelProvider")
            val ownerCls = Class.forName("androidx.lifecycle.ViewModelStoreOwner")
            val vmCls = Class.forName(PLAYER_VM_CLASS)
            val provider = providerCls.getConstructor(ownerCls).newInstance(owner)
            providerCls.getMethod("get", Class::class.java).invoke(provider, vmCls)
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeMethod(target: Any?, methodName: String, arg: Any?): Any? {
        if (target == null) return null
        return try {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1
            } ?: return null
            method.invoke(target, arg)
        } catch (_: Throwable) {
            null
        }
    }

    private fun createSubtitleData(subFile: SubtitleFile): Any? {
        return try {
            val helperCls = Class.forName("com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper")
            val companion = helperCls.getField("Companion").get(null)
            val method = companion.javaClass.getMethod("getSubtitleData", SubtitleFile::class.java)
            method.invoke(companion, subFile)
        } catch (t: Throwable) {
            logW("getSubtitleData unavailable: ${t.message}")
            null
        }
    }

    fun isPlayerActive(): Boolean = try { findPlayerFragment() != null } catch (_: Throwable) { false }

    fun activePlayerFragmentClass(): String? = try { findPlayerFragment()?.javaClass?.name } catch (_: Throwable) { null }

    /**
     * Reads the currently preferred subtitle track from the player (main thread recommended).
     */
    fun currentPreferredTrack(): PreferredSubtitleTrack? {
        val player = playerOf(findPlayerFragment()) ?: return null
        val sd = try {
            player.javaClass.getMethod("getCurrentPreferredSubtitle").invoke(player)
        } catch (_: Throwable) {
            null
        } ?: return null

        val cls = sd.javaClass
        fun str(methodName: String): String = try {
            cls.getMethod(methodName).invoke(sd) as? String ?: ""
        } catch (_: Throwable) {
            ""
        }

        val originName = try {
            val origin = cls.getMethod("getOrigin").invoke(sd)
            origin?.javaClass?.getMethod("name")?.invoke(origin) as? String ?: ""
        } catch (_: Throwable) {
            ""
        }

        val headers: Map<String, String> = try {
            val raw = cls.getMethod("getHeaders").invoke(sd) as? Map<*, *>
            raw?.entries?.mapNotNull { entry ->
                val key = entry.key as? String
                val value = entry.value as? String
                if (key != null && value != null) key to value else null
            }?.toMap() ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }

        return PreferredSubtitleTrack(
            name = str("getName"),
            url = str("getUrl"),
            languageCode = str("getLanguageCode"),
            originName = originName,
            headers = headers
        )
    }

    /**
     * True when the currently preferred track is an AI loopback track served by this extension.
     */
    fun isPreferredOwnAiTrack(): Boolean {
        val url = try { currentPreferredTrack()?.url } catch (_: Throwable) { null } ?: return false
        return AITranslatorLocalServer.isOwnSubtitleUrl(url)
    }

    fun duration(): Long? {
        val player = playerOf(findPlayerFragment()) ?: return null
        return try {
            player.javaClass.getMethod("getDuration").invoke(player) as? Long
        } catch (_: Throwable) {
            null
        }
    }

    private val PREFER_ATTEMPTS_MS = longArrayOf(0L, 120L, 300L, 700L, 1200L, 2000L, 3500L, 6000L, 10000L)
    private val reloadRequestedUrls = mutableSetOf<String>()

    /**
     * Adds the track to the player's subtitle list and selects it as preferred.
     * addSubtitles propagates asynchronously through the VM LiveData, so an immediate
     * setPreferredSubtitles can return NOT_FOUND and the player re-applies its own
     * default track — hence the delayed retry/verify loop.
     */
    fun addAndPrefer(subFile: SubtitleFile) {
        runOnMain {
            try {
                reloadRequestedUrls.clear()
                val fragment = findPlayerFragment()
                val vm = resolveViewModel(fragment)
                val player = playerOf(fragment)
                val subtitleData = createSubtitleData(subFile)
                if (subtitleData == null) {
                    logW("Cannot build SubtitleData for '${subFile.lang}'; track stays manually selectable.")
                    return@runOnMain
                }

                if (vm != null) {
                    invokeMethod(vm, "addSubtitles", setOf(subtitleData))
                }
                logI("addAndPrefer '${subFile.lang}': added=${vm != null}, url=${subFile.url}")
                schedulePrefer(player, subFile, subtitleData, 0)
            } catch (t: Throwable) {
                logW("addAndPrefer failed for '${subFile.lang}': ${t.message}")
            }
        }
    }

    /**
     * The player only registers a SubtitleData as active when it was included in the last
     * loadExo() source set; added tracks stay "REQUIRES_RELOAD" and setPreferredSubtitles
     * returns true without applying anything. The app's own remedy is saveData() +
     * reloadPlayer(ctx); onRenderFirst() then re-applies the stored currentSubtitles.
     */
    private fun subtitleStatusOf(player: Any?, subtitleData: Any): String? {
        if (player == null) return null
        return try {
            val field = player.javaClass.getDeclaredField("subtitleHelper")
            field.isAccessible = true
            val helper = field.get(player) ?: return null
            val method = helper.javaClass.methods.firstOrNull {
                it.name == "subtitleStatus" && it.parameterTypes.size == 1
            } ?: return null
            (method.invoke(helper, subtitleData) as? Enum<*>)?.name
        } catch (_: Throwable) {
            null
        }
    }

    private fun requestPlayerReload(player: Any?): Boolean {
        if (player == null) return false
        return try {
            player.javaClass.getMethod("saveData").invoke(player)
            val candidates = player.javaClass.methods.filter { it.name == "reloadPlayer" }
            val method = candidates.firstOrNull {
                it.parameterTypes.size == 1 &&
                    android.content.Context::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (method != null) {
                val activity = currentActivity() ?: return false
                method.invoke(player, activity)
            } else {
                val noArg = candidates.firstOrNull { it.parameterTypes.isEmpty() } ?: return false
                noArg.invoke(player)
            }
            true
        } catch (t: Throwable) {
            logW("requestPlayerReload failed: ${t.message}")
            false
        }
    }

    private fun schedulePrefer(player: Any?, subFile: SubtitleFile, subtitleData: Any, attempt: Int) {
        if (attempt >= PREFER_ATTEMPTS_MS.size) {
            logW("Could not auto-switch to '${subFile.lang}' after ${PREFER_ATTEMPTS_MS.size} attempts; track remains selectable.")
            return
        }
        val delayMs = PREFER_ATTEMPTS_MS[attempt]
        val action = Runnable {
            try {
                if (currentPreferredTrack()?.url == subFile.url) {
                    logI("Preferred track verified: '${subFile.lang}' (attempt #${attempt + 1})")
                    return@Runnable
                }
                val result = invokeMethod(player, "setPreferredSubtitles", subtitleData) as? Boolean
                val status = subtitleStatusOf(player, subtitleData)
                logI("Prefer attempt #${attempt + 1} for '${subFile.lang}': setPreferred=${result ?: "unavailable"}, status=${status ?: "unknown"}")
                if (currentPreferredTrack()?.url == subFile.url) {
                    logI("Preferred track verified after attempt #${attempt + 1}: '${subFile.lang}'")
                } else {
                    val needsReload = status == "REQUIRES_RELOAD" || (status == null && attempt >= 2)
                    if (needsReload && reloadRequestedUrls.add(subFile.url)) {
                        logI("Reloading player to activate '${subFile.lang}'")
                        if (!requestPlayerReload(player)) {
                            reloadRequestedUrls.remove(subFile.url)
                        }
                    }
                    schedulePrefer(player, subFile, subtitleData, attempt + 1)
                }
            } catch (t: Throwable) {
                logW("Prefer attempt #${attempt + 1} failed: ${t.message}")
                schedulePrefer(player, subFile, subtitleData, attempt + 1)
            }
        }
        if (delayMs <= 0L) {
            action.run()
        } else {
            try {
                Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
            } catch (_: Throwable) {
                action.run()
            }
        }
    }

    private fun logI(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) { println("[$TAG] [INFO] $msg") }
    }

    private fun logW(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) { System.err.println("[$TAG] [WARN] $msg") }
    }
}