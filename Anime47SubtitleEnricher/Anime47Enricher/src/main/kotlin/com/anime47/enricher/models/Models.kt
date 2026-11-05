package com.anime47.enricher.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoginRequest(
    @JsonProperty("login") @SerializedName("login") val login: String,
    @JsonProperty("password") @SerializedName("password") val password: String,
    @JsonProperty("remember") @SerializedName("remember") val remember: Boolean = true
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoginResponse(
    @JsonProperty("access_token") @SerializedName("access_token") val accessToken: String? = null,
    @JsonProperty("refresh_token") @SerializedName("refresh_token") val refreshToken: String? = null,
    @JsonProperty("token_type") @SerializedName("token_type") val tokenType: String? = null,
    @JsonProperty("expires_in") @SerializedName("expires_in") val expiresIn: Long? = 3600,
    @JsonProperty("refresh_expires_at") @SerializedName("refresh_expires_at") val refreshExpiresAt: String? = null,
    @JsonProperty("user") @SerializedName("user") val user: UserProfile? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserProfile(
    @JsonProperty("id") @SerializedName("id") val id: Long? = null,
    @JsonProperty("username") @SerializedName("username") val username: String? = null,
    @JsonProperty("email") @SerializedName("email") val email: String? = null,
    @JsonProperty("is_vip") @SerializedName("is_vip") val isVip: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RefreshTokenRequest(
    @JsonProperty("refresh_token") @SerializedName("refresh_token") val refreshToken: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResponse(
    @JsonProperty("query") @SerializedName("query") val query: String? = null,
    @JsonProperty("results") @SerializedName("results") val results: List<SearchResult>? = emptyList(),
    @JsonProperty("count") @SerializedName("count") val count: Int? = 0,
    @JsonProperty("current_page") @SerializedName("current_page") val currentPage: Int? = 1,
    @JsonProperty("total_pages") @SerializedName("total_pages") val totalPages: Int? = 1
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
    @JsonProperty("id") @SerializedName("id") val id: Int,
    @JsonProperty("title") @SerializedName("title") val title: String,
    @JsonProperty("titles") @SerializedName("titles") val titles: List<AlternativeTitle>? = emptyList(),
    @JsonProperty("slug") @SerializedName("slug") val slug: String? = null,
    @JsonProperty("episodes") @SerializedName("episodes") val episodes: String? = null,
    @JsonProperty("current_episode") @SerializedName("current_episode") val currentEpisode: String? = null,
    @JsonProperty("status") @SerializedName("status") val status: String? = null,
    @JsonProperty("link") @SerializedName("link") val link: String? = null,
    @JsonProperty("image") @SerializedName("image") val image: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlternativeTitle(
    @JsonProperty("language") @SerializedName("language") val language: String? = null,
    @JsonProperty("title") @SerializedName("title") val title: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodesResponse(
    @JsonProperty("teams") @SerializedName("teams") val teams: List<EpisodeTeam>? = emptyList(),
    @JsonProperty("total_episodes") @SerializedName("total_episodes") val totalEpisodes: Int? = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeTeam(
    @JsonProperty("id") @SerializedName("id") val id: String? = null,
    @JsonProperty("team_name") @SerializedName("team_name") val teamName: String? = null,
    @JsonProperty("groups") @SerializedName("groups") val groups: List<EpisodeGroup>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeGroup(
    @JsonProperty("name") @SerializedName("name") val name: String? = null,
    @JsonProperty("range_start") @SerializedName("range_start") val rangeStart: Int? = null,
    @JsonProperty("range_end") @SerializedName("range_end") val rangeEnd: Int? = null,
    @JsonProperty("episodes") @SerializedName("episodes") val episodes: List<EpisodeItem>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeItem(
    @JsonProperty("id") @SerializedName("id") val id: Int,
    @JsonProperty("title") @SerializedName("title") val title: String? = null,
    @JsonProperty("number") @SerializedName("number") val number: Any? = null,
    @JsonProperty("link") @SerializedName("link") val link: String? = null
) {
    /**
     * Parses the episode number into an integer.
     */
    val parsedNumber: Int?
        get() {
            return when (number) {
                is Number -> number.toInt()
                is String -> number.toIntOrNull() ?: title?.trim()?.toIntOrNull()
                else -> title?.trim()?.toIntOrNull()
            }
        }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeWatchResponse(
    @JsonProperty("id") @SerializedName("id") val id: Int? = null,
    @JsonProperty("title") @SerializedName("title") val title: String? = null,
    @JsonProperty("number") @SerializedName("number") val number: Any? = null,
    @JsonProperty("streams") @SerializedName("streams") val streams: List<StreamItem>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamItem(
    @JsonProperty("id") @SerializedName("id") val id: Long? = null,
    @JsonProperty("server_name") @SerializedName("server_name") val serverName: String? = null,
    @JsonProperty("code") @SerializedName("code") val code: String? = null,
    @JsonProperty("url") @SerializedName("url") val url: String? = null,
    @JsonProperty("player_type") @SerializedName("player_type") val playerType: String? = null,
    @JsonProperty("subtitles") @SerializedName("subtitles") val subtitles: List<SubtitleItem>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleItem(
    @JsonProperty("file") @SerializedName("file") val file: String,
    @JsonProperty("label") @SerializedName("label") val label: String? = null,
    @JsonProperty("default") @SerializedName("default") val isDefault: Boolean = false
)

/**
 * Clean domain model for a parsed subtitle track.
 */
data class SubtitleTrack(
    val label: String,
    val url: String,
    val isDefault: Boolean = false,
    val server: String? = null
)

/**
 * Result of filename/title parsing.
 */
data class ParsedMediaInfo(
    val cleanTitle: String,
    val episodeNumber: Int?,
    val seasonNumber: Int? = null,
    val rawTitle: String
)
