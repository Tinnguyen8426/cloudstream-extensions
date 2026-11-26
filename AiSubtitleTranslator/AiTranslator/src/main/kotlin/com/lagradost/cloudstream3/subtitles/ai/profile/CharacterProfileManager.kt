package com.lagradost.cloudstream3.subtitles.ai.profile

import android.content.Context
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import java.io.File
import java.security.MessageDigest

/**
 * Two-tier cache manager for character rosters and relationship graphs.
 * Tier 1 (Persistent Core): Saved as JSON files under filesDir/ai_profiles/ across episodes.
 * Tier 2 (Episodic State): Ephemeral RAM cache for dynamic emotion/conflict shifts, reset per episode.
 */
object CharacterProfileManager {
    @Volatile
    var customBaseDir: File? = null

    // RAM LRU cache for core profiles (max 20 series)
    private val coreMemoryCache = LinkedHashMap<String, TwoTierCharacterRegistry>(20, 0.75f, true)

    // Ephemeral RAM cache for episodic states (reset on episode transition)
    private val episodicStateCache = mutableMapOf<String, TwoTierCharacterRegistry>()

    private val mapper: ObjectMapper = ObjectMapper().apply {
        registerModule(
            KotlinModule.Builder()
                .enable(KotlinFeature.NullIsSameAsDefault)
                .enable(KotlinFeature.NullToEmptyCollection)
                .enable(KotlinFeature.NullToEmptyMap)
                .build()
        )
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        setDefaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.SKIP))
    }

    private fun hashKey(seriesTitle: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(seriesTitle.trim().lowercase().toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun resetEpisodicState(seriesTitle: String? = null) {
        if (seriesTitle.isNullOrBlank()) {
            episodicStateCache.clear()
        } else {
            val key = hashKey(seriesTitle)
            episodicStateCache.remove(key)
        }
    }

    @Synchronized
    fun getProfile(context: Context?, seriesTitle: String?): TwoTierCharacterRegistry? {
        if (seriesTitle.isNullOrBlank()) return null
        val key = hashKey(seriesTitle)

        // 1. Check episodic RAM state (active overrides for current episode)
        episodicStateCache[key]?.let { return it }

        // 2. Check core RAM cache
        coreMemoryCache[key]?.let { return it }

        // 3. Check persistent disk file cache
        val baseDir = customBaseDir ?: context?.filesDir ?: return null
        val cacheDir = File(baseDir, "ai_profiles")
        val file = File(cacheDir, "$key.json")
        if (file.exists()) {
            return try {
                val json = file.readText(Charsets.UTF_8)
                val profile = mapper.readValue(json, TwoTierCharacterRegistry::class.java)
                coreMemoryCache[key] = profile
                profile
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    @Synchronized
    fun saveProfile(
        context: Context?,
        seriesTitle: String?,
        profile: TwoTierCharacterRegistry,
        isEphemeral: Boolean = false
    ) {
        if (seriesTitle.isNullOrBlank()) return
        val key = hashKey(seriesTitle)

        if (isEphemeral) {
            // Ephemeral emotional escalation shifts (only live in RAM for the active episode)
            episodicStateCache[key] = profile
            return
        }

        // Persistent core profile: filter out ephemeral entries when saving to disk/core memory
        val persistentProfile = profile.copy(
            characters = profile.characters.filter { !it.isEphemeral },
            relationships = profile.relationships.filter { !it.isEphemeral }
        )
        coreMemoryCache[key] = persistentProfile
        episodicStateCache.remove(key)

        val baseDir = customBaseDir ?: context?.filesDir ?: return
        try {
            val cacheDir = File(baseDir, "ai_profiles")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, "$key.json")
            val json = mapper.writeValueAsString(persistentProfile)
            file.writeText(json, Charsets.UTF_8)
        } catch (_: Throwable) {
            // Non-blocking I/O fault tolerance
        }
    }

    @Synchronized
    fun clearAll() {
        coreMemoryCache.clear()
        episodicStateCache.clear()
    }
}
