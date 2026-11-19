package com.lagradost.cloudstream3.subtitles.ai.profile

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.CharacterEntity
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Zero-token cast pre-scan resolver using AniList GraphQL API.
 * Fetches character names, native names, roles, and genders to establish
 * ground-truth character rosters and Vietnamese pronoun hints before translation starts.
 */
object AniListCastResolver {

    private const val ANILIST_ENDPOINT = "https://graphql.anilist.co"

    private const val ANILIST_QUERY = """query (${'$'}search: String) {
  Media(search: ${'$'}search, type: ANIME) {
    id
    title {
      romaji
      english
      native
    }
    characters(sort: ROLE, perPage: 15) {
      edges {
        role
        node {
          name {
            full
            native
          }
          gender
        }
      }
    }
  }
}"""

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val defaultClient: OkHttpClient by lazy {
        try {
            com.lagradost.cloudstream3.app.baseClient
        } catch (_: Throwable) {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Cleans raw title / torrent filename by stripping bracket tags,
     * resolutions, codec descriptors, season/episode markers, and file extensions.
     */
    fun cleanAnimeTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return ""
        var clean = rawTitle

        // 1. Strip bracketed release group or audio tags: [...], (...)
        clean = clean.replace(Regex("\\[.*?\\]"), " ")
        clean = clean.replace(Regex("\\(.*?\\)"), " ")

        // 2. Strip resolutions, video codecs, audio codecs, containers
        clean = clean.replace(
            Regex("(?i)\\b(1080p|720p|480p|4k|2160p|x264|x265|hevc|av1|aac|flac|dts|mkv|mp4|avi)\\b"),
            " "
        )

        // 3. Strip season/episode notations: S01E07, Season 1, Episode 7, Ep 07, - 07
        clean = clean.replace(
            Regex("(?i)\\b(season\\s*\\d+|s\\d+e\\d+|s\\d+|e\\d+|ep\\s*\\d+|episode\\s*\\d+)\\b"),
            " "
        )
        clean = clean.replace(Regex("-\\s*\\d+\\b"), " ")

        // 4. Collapse consecutive whitespace and trim surrounding punctuation
        clean = clean.replace(Regex("\\s+"), " ").trim()
        clean = clean.trim('-', '.', '_', ' ')

        return clean
    }

    /**
     * Deprecated: Vietnamese pronoun hints should not be hardcoded at character entity level.
     * Pronouns are dynamic dyadic relations between speakers, not static node attributes.
     */
    @Deprecated("Vietnamese pronoun hints should not be hardcoded at character entity level. Relationships are dyadic.")
    fun inferVietnamesePronounHint(gender: String?, role: String?, name: String = ""): String? {
        return null
    }

    /**
     * Asynchronously queries AniList GraphQL endpoint for anime characters.
     * Fails gracefully on 404, rate limit, timeout, or network error.
     */
    suspend fun resolveCast(rawTitle: String, httpClient: OkHttpClient? = null): List<CharacterEntity> {
        val cleanTitle = cleanAnimeTitle(rawTitle)
        if (cleanTitle.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val client = httpClient ?: defaultClient

                val payload = mapOf(
                    "query" to ANILIST_QUERY,
                    "variables" to mapOf("search" to cleanTitle)
                )
                val jsonBody = mapper.writeValueAsString(payload)
                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(ANILIST_ENDPOINT)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "CloudStream-AiTranslator/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext emptyList()
                    }

                    val bodyString = response.body?.string() ?: return@withContext emptyList()
                    val root = mapper.readTree(bodyString)

                    val errors = root.get("errors")
                    if (errors != null && errors.isArray && !errors.isEmpty) {
                        return@withContext emptyList()
                    }

                    val data = root.get("data")?.get("Media") ?: return@withContext emptyList()
                    val edges = data.get("characters")?.get("edges") ?: return@withContext emptyList()

                    val result = mutableListOf<CharacterEntity>()
                    for (edge in edges) {
                        val role = edge.get("role")?.asText() ?: ""
                        val node = edge.get("node") ?: continue
                        val nameNode = node.get("name")
                        val full = nameNode?.get("full")?.asText()?.takeIf { it.isNotBlank() }
                        val native = nameNode?.get("native")?.asText()?.takeIf { it.isNotBlank() }
                        val name = full ?: native ?: continue
                        val gender = node.get("gender")?.asText()?.takeIf { it.isNotBlank() } ?: "unknown"
                        val aliases = if (native != null && native != name) listOf(native) else emptyList()
                        result.add(
                            CharacterEntity(
                                name = name,
                                aliases = aliases,
                                gender = gender,
                                role = role,
                                viPronounHint = null,
                                isEphemeral = false
                            )
                        )
                    }
                    result
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    /**
     * Resolves characters from AniList and caches the resulting TwoTierCharacterRegistry to disk.
     * Safe to call from background coroutine without blocking playback.
     */
    suspend fun resolveAndSave(
        context: Context?,
        rawTitle: String,
        httpClient: OkHttpClient? = null
    ): TwoTierCharacterRegistry? {
        val cleanTitle = cleanAnimeTitle(rawTitle)
        if (cleanTitle.isBlank()) return null

        val existing = CharacterProfileManager.getProfile(context, cleanTitle)
            ?: CharacterProfileManager.getProfile(context, rawTitle)
        if (existing != null && existing.characters.isNotEmpty()) {
            return existing
        }

        val characters = resolveCast(cleanTitle, httpClient)
        if (characters.isEmpty()) {
            return null
        }

        val registry = TwoTierCharacterRegistry(
            mediaTitle = cleanTitle,
            characters = characters,
            relationships = emptyList()
        )
        CharacterProfileManager.saveProfile(context, cleanTitle, registry, isEphemeral = false)
        if (rawTitle.isNotBlank() && rawTitle != cleanTitle) {
            CharacterProfileManager.saveProfile(context, rawTitle, registry, isEphemeral = false)
        }
        return registry
    }
}
