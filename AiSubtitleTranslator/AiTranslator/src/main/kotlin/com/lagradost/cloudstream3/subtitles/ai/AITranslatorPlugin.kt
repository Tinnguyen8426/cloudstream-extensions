package com.lagradost.cloudstream3.subtitles.ai

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import kotlinx.coroutines.*

/**
 * CloudStream Extension Entrypoint for AI Subtitle Translator.
 *
 * Coordinates extension lifecycle:
 * 1. Initializing persistent configuration from SharedPreferences.
 * 2. Starting embedded loopback HTTP server (127.0.0.1:<ephemeral-port>) for ExoPlayer streaming.
 * 3. Registering pure Kotlin AlertDialog Settings UI dialog launcher.
 * 4. Launching continuous 3-second self-hoisting hook scanner ensuring outermost decorator placement.
 * 5. Cleanly unwrapping provider chains and restoring APIHolder mappings upon unload.
 */
@CloudstreamPlugin
class AITranslatorPlugin : Plugin() {
    companion object {
        private const val TAG = "AITranslatorPlugin"
        private const val HOIST_POLLING_INTERVAL_MS = 3000L
    }

    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hookPollingJob: Job? = null

    override fun load(context: Context) {
        logI(TAG, "Initializing AI Subtitle Translator Plugin...")

        // 1. Initialize persistent configuration from SharedPreferences
        try {
            TranslatorConfig.init(context)
        } catch (e: Throwable) {
            logW(TAG, "Failed initializing TranslatorConfig: ${e.message}")
        }

        // 2. Start embedded HTTP loopback server for ExoPlayer streaming
        try {
            val serverPort = AITranslatorLocalServer.start(context)
            logI(TAG, "AITranslatorLocalServer active on port: $serverPort")
        } catch (e: Throwable) {
            logW(TAG, "Failed starting local loopback server: ${e.message}")
        }

        // 3. Register Settings Dialog launcher
        openSettings = { activityContext ->
            showSettings(activityContext)
        }

        // 4. Initial immediate hook scan & hoist
        try {
            AITranslatorHook.scanAndHoistHooks()
        } catch (e: Throwable) {
            logW(TAG, "Initial hook hoisting encountered: ${e.message}")
        }

        // 5. Launch continuous 3-second self-hoisting hook scanner
        hookPollingJob = pluginScope.launch {
            while (isActive) {
                try {
                    AITranslatorHook.scanAndHoistHooks()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    logW(TAG, "Hook scanning error: ${e.message}")
                }
                delay(HOIST_POLLING_INTERVAL_MS)
            }
        }
    }

    override fun beforeUnload() {
        logI(TAG, "Unloading AI Subtitle Translator Plugin...")

        // 1. Cancel background polling and active plugin coroutines
        hookPollingJob?.cancel()
        hookPollingJob = null
        try {
            pluginScope.cancel()
        } catch (_: Throwable) {}

        // 2. Detach all hooks cleanly: restore original provider chain and plugin mappings
        try {
            AITranslatorHook.detachHooks()
        } catch (e: Throwable) {
            logW(TAG, "Error restoring providers during unload: ${e.message}")
        }

        // 3. Stop loopback HTTP server
        try {
            AITranslatorLocalServer.stop()
        } catch (e: Throwable) {
            logW(TAG, "Error stopping local server: ${e.message}")
        }
    }

    /**
     * Invokes the Settings UI Dialog.
     * Uses reflective resolution to invoke AITranslatorSettingsDialog if present (Milestone 5),
     * providing a safe fallback if Milestone 5 is not yet compiled.
     */
    private fun showSettings(context: Context) {
        try {
            val clazz = Class.forName("com.lagradost.cloudstream3.subtitles.ai.ui.AITranslatorSettingsDialog")
            val method = clazz.getMethod("show", Context::class.java)
            method.invoke(null, context)
        } catch (_: ClassNotFoundException) {
            // Milestone 5 stub fallback
            try {
                android.widget.Toast.makeText(
                    context,
                    "AI Subtitle Translator Settings (Configured via SharedPreferences)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            logW(TAG, "Failed to open settings dialog: ${e.message}")
        }
    }

    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] [INFO] $msg") }
    }

    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) { System.err.println("[$tag] [WARN] $msg") }
    }
}
