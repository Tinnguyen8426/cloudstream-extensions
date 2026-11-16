package com.lagradost.cloudstream3.subtitles.ai.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coroutine rate limiter ensuring minimum spacing between consecutive API requests.
 * Enforces 4.0s interval (15 RPM) for Google Gemini Free Tier.
 * Uses a safe monotonic clock fallback for Android runtime and desktop JVM test environments.
 */
class PacingGovernor(val minIntervalMs: Long = 4000L) {
    private val mutex = Mutex()
    private var lastRequestTime: Long = 0L

    companion object {
        fun getMonotonicTimeMs(): Long {
            return try {
                android.os.SystemClock.uptimeMillis()
            } catch (_: Throwable) {
                System.nanoTime() / 1_000_000L
            }
        }
    }

    suspend fun pace() {
        if (minIntervalMs <= 0L) return
        mutex.withLock {
            val now = getMonotonicTimeMs()
            val elapsed = now - lastRequestTime
            if (lastRequestTime > 0L && elapsed < minIntervalMs) {
                delay(minIntervalMs - elapsed)
            }
            lastRequestTime = getMonotonicTimeMs()
        }
    }

    fun reset() {
        lastRequestTime = 0L
    }
}
