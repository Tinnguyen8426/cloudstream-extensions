package com.lagradost.cloudstream3.subtitles.ai.profile

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.CharacterEntity
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    private fun JsonNode?.textOrNull(): String? =
        this?.takeIf { !it.isNull && it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonNode?.textOrNumberString(): String? =
        this?.takeIf { !it.isNull && (it.isTextual || it.isNumber) }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private data class BoundedHttpResponse(
        val isSuccessful: Boolean,
        val body: String?
    )

    private fun boundedClient(httpClient: OkHttpClient?): OkHttpClient {
        if (httpClient == null) return defaultClient
        return httpClient.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun executeBoundedCall(
        client: OkHttpClient,
        request: Request
    ): BoundedHttpResponse = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        val completed = AtomicBoolean(false)
        val cancelledByCoroutine = AtomicBoolean(false)

        cont.invokeOnCancellation {
            cancelledByCoroutine.set(true)
            try {
                call.cancel()
            } catch (_: Throwable) {}
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (completed.compareAndSet(false, true) && cont.isActive) {
                    if (cancelledByCoroutine.get()) {
                        cont.resumeWithException(CancellationException("Cast resolver HTTP call cancelled", e))
                    } else {
                        cont.resumeWithException(e)
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { resp ->
                        BoundedHttpResponse(
                            isSuccessful = resp.isSuccessful,
                            body = resp.body?.string()
                        )
                    }
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(result)
                    }
                } catch (t: Throwable) {
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        if (cancelledByCoroutine.get()) {
                            cont.resumeWithException(CancellationException("Cast resolver HTTP response cancelled", t))
                        } else {
                            cont.resumeWithException(t)
                        }
                    }
                }
            }
        })
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

        return withTimeoutOrNull(3000L) {
            withContext(Dispatchers.IO) {
                try {
                    val client = boundedClient(httpClient)

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

                    val response = executeBoundedCall(client, request)
                    if (!response.isSuccessful) {
                        return@withContext emptyList()
                    }

                    val bodyString = response.body ?: return@withContext emptyList()
                    val root = mapper.readTree(bodyString)

                    val errors = root.get("errors")
                    if (errors != null && errors.isArray && !errors.isEmpty) {
                        return@withContext emptyList()
                    }

                    val data = root.get("data")?.get("Media") ?: return@withContext emptyList()
                    val edges = data.get("characters")?.get("edges") ?: return@withContext emptyList()

                    val result = mutableListOf<CharacterEntity>()
                    for (edge in edges) {
                        if (edge == null || edge.isNull || !edge.isObject) continue
                        val role = edge.get("role").textOrNull() ?: ""
                        val node = edge.get("node")?.takeIf { !it.isNull && it.isObject } ?: continue
                        val nameNode = node.get("name")?.takeIf { !it.isNull && it.isObject }
                        val full = nameNode?.get("full").textOrNull()
                        val native = nameNode?.get("native").textOrNull()
                        val name = full ?: native ?: continue
                        val gender = node.get("gender").textOrNull() ?: "unknown"
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
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    emptyList()
                }
            }
        } ?: emptyList()
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

    /**
     * Universal cast resolver supporting Anime (AniList) and Live-Action Movies/Series/AsianDrama
     * (CloudStream ActorData.roleString + Cinemeta catalog/meta API within a <= 3s timeout).
     */
    suspend fun resolveUniversalCast(
        context: Context?,
        rawTitle: String,
        mediaContext: MediaContext?,
        httpClient: OkHttpClient? = null,
        cinemetaBaseUrl: String = "https://v3-cinemeta.strem.io"
    ): TwoTierCharacterRegistry? {
        val seedTitle = mediaContext?.title?.takeIf { it.isNotBlank() } ?: rawTitle
        val cleanTitle = cleanAnimeTitle(seedTitle)
        if (cleanTitle.isBlank()) return null

        val existing = CharacterProfileManager.getProfile(context, cleanTitle)
            ?: CharacterProfileManager.getProfile(context, rawTitle)
        if (existing != null && existing.characters.isNotEmpty()) {
            return existing
        }

        val combinedTags = (mediaContext?.tags ?: emptyList()) + (mediaContext?.genres ?: emptyList())
        val isAnime = mediaContext == null ||
            mediaContext.mediaType.equals("Anime", ignoreCase = true) ||
            mediaContext.mediaType.equals("AnimeMovie", ignoreCase = true) ||
            mediaContext.mediaType.equals("OVA", ignoreCase = true) ||
            (mediaContext.mediaType.isBlank() && combinedTags.any { it.contains("anime", ignoreCase = true) }) ||
            (mediaContext.mediaType.isBlank() && mediaContext.characterActors.isEmpty())

        if (isAnime) {
            val animeChars = resolveCast(cleanTitle, httpClient)
            if (animeChars.isEmpty()) return null
            val registry = TwoTierCharacterRegistry(
                mediaTitle = cleanTitle,
                characters = animeChars,
                relationships = emptyList()
            )
            CharacterProfileManager.saveProfile(context, cleanTitle, registry, isEphemeral = false)
            if (rawTitle.isNotBlank() && rawTitle != cleanTitle) {
                CharacterProfileManager.saveProfile(context, rawTitle, registry, isEphemeral = false)
            }
            return registry
        }

        // Live-Action path: seed from LoadResponse characterActors + Cinemeta /catalog/ & /meta/
        val chars = mutableListOf<CharacterEntity>()
        val seenNames = mutableSetOf<String>()

        fun addCharacterRole(rawRole: String, actorName: String) {
            val parts = rawRole.split("/")
            for (part in parts) {
                val cleaned = part
                    .replace(Regex("(?i)\\((voice|uncredited|as\\s+[^)]+)\\)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (cleaned.isBlank()) continue
                val key = cleaned.lowercase(Locale.ROOT)
                if (seenNames.add(key)) {
                    val roleDesc = if (actorName.isNotBlank()) "Played by $actorName" else "Cast"
                    chars.add(
                        CharacterEntity(
                            name = cleaned,
                            role = roleDesc,
                            gender = "unknown",
                            isEphemeral = false
                        )
                    )
                }
            }
        }

        mediaContext?.characterActors?.forEach { (actorName, roleString) ->
            if (roleString.isNotBlank()) {
                addCharacterRole(roleString, actorName.trim())
            }
        }

        var toneGuide = ""
        val cinemetaResult = withTimeoutOrNull(3000L) {
            withContext(Dispatchers.IO) {
                try {
                    val client = boundedClient(httpClient)
                    val base = cinemetaBaseUrl.trimEnd('/')
                    val cinemetaType = if (
                        mediaContext?.mediaType.equals("TvSeries", ignoreCase = true) ||
                        mediaContext?.mediaType.equals("AsianDrama", ignoreCase = true)
                    ) "series" else "movie"

                    val encoded = URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
                    val searchUrl = "$base/catalog/$cinemetaType/top/search=$encoded.json"
                    val searchReq = Request.Builder()
                        .url(searchUrl)
                        .get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "CloudStream-AiTranslator/1.0")
                        .build()

                    val searchResp = executeBoundedCall(client, searchReq)
                    val imdbId: String? = if (!searchResp.isSuccessful) {
                        null
                    } else {
                        val body = searchResp.body
                        if (body == null) null else {
                            val root = mapper.readTree(body)
                            val metas = root.get("metas")
                            if (metas == null || !metas.isArray || metas.isEmpty) {
                                null
                            } else {
                                val cleanLower = cleanTitle.lowercase(Locale.ROOT)
                                val targetYear = mediaContext?.year?.toString()
                                var bestId: String? = null
                                var bestScore = Int.MIN_VALUE
                                for (meta in metas) {
                                    if (meta == null || meta.isNull || !meta.isObject) continue
                                    val candidateId = meta.get("imdb_id").textOrNull()?.takeIf { it.startsWith("tt") }
                                        ?: meta.get("id").textOrNull()?.takeIf { it.startsWith("tt") }
                                        ?: continue
                                    val name = meta.get("name").textOrNull()?.lowercase(Locale.ROOT) ?: ""
                                    var score = 0
                                    if (name.isNotEmpty()) {
                                        if (name == cleanLower) score += 10
                                        else if (name.startsWith(cleanLower) || cleanLower.startsWith(name)) score += 5
                                        else if (name.contains(cleanLower)) score += 2
                                    }
                                    val releaseInfo = meta.get("releaseInfo").textOrNumberString()
                                        ?: meta.get("year").textOrNumberString()
                                        ?: ""
                                    if (targetYear != null && releaseInfo.contains(targetYear)) {
                                        score += 5
                                    }
                                    if (score > bestScore) {
                                        bestScore = score
                                        bestId = candidateId
                                    }
                                }
                                bestId
                            }
                        }
                    }

                    if (imdbId == null) return@withContext ""

                    val metaUrl = "$base/meta/$cinemetaType/$imdbId.json"
                    val metaReq = Request.Builder()
                        .url(metaUrl)
                        .get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "CloudStream-AiTranslator/1.0")
                        .build()

                    val metaResp = executeBoundedCall(client, metaReq)
                    if (!metaResp.isSuccessful) return@withContext ""
                    val body = metaResp.body ?: return@withContext ""
                    val root = mapper.readTree(body)
                    val metaNode = root.get("meta") ?: return@withContext ""

                    val appCast = metaNode.get("app_extras")?.get("cast")
                    if (appCast != null && appCast.isArray) {
                        for (entry in appCast) {
                            if (entry == null || entry.isNull || !entry.isObject) continue
                            val actor = entry.get("name").textOrNull() ?: ""
                            val charName = entry.get("character").textOrNull() ?: ""
                            if (charName.isNotBlank()) {
                                addCharacterRole(charName, actor)
                            }
                        }
                    }

                    if (chars.isEmpty()) {
                        val rawCast = metaNode.get("cast")
                        if (rawCast != null && rawCast.isArray) {
                            var count = 0
                            for (actorNode in rawCast) {
                                if (count >= 10) break
                                val actor = actorNode.textOrNull() ?: ""
                                if (actor.isNotBlank() && seenNames.add(actor.lowercase(Locale.ROOT))) {
                                    chars.add(
                                        CharacterEntity(
                                            name = actor,
                                            role = "Cast",
                                            gender = "unknown",
                                            isEphemeral = false
                                        )
                                    )
                                    count++
                                }
                            }
                        }
                    }

                    val metaGenres = mutableListOf<String>()
                    val genresNode = metaNode.get("genres")?.takeIf { !it.isNull && it.isArray }
                        ?: metaNode.get("genre")?.takeIf { !it.isNull && it.isArray }
                    if (genresNode != null) {
                        for (g in genresNode) {
                            val gs = g.textOrNull()
                            if (!gs.isNullOrBlank()) metaGenres.add(gs)
                        }
                    }
                    if (metaGenres.isNotEmpty()) {
                        "Genres: ${metaGenres.joinToString(", ")}"
                    } else {
                        ""
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    ""
                }
            }
        }
        if (!cinemetaResult.isNullOrBlank()) {
            toneGuide = cinemetaResult
        }

        if (chars.isEmpty() && toneGuide.isBlank()) {
            return null
        }

        val registry = TwoTierCharacterRegistry(
            mediaTitle = cleanTitle,
            characters = chars,
            relationships = emptyList(),
            toneGuide = toneGuide
        )
        CharacterProfileManager.saveProfile(context, cleanTitle, registry, isEphemeral = false)
        if (rawTitle.isNotBlank() && rawTitle != cleanTitle) {
            CharacterProfileManager.saveProfile(context, rawTitle, registry, isEphemeral = false)
        }
        return registry
    }
}
