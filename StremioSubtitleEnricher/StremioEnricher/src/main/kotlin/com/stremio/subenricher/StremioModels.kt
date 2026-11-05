package com.stremio.subenricher

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName

/**
 * Represents a single subtitle item returned by a Stremio subtitle addon.
 * Conforms to Stremio Addon Protocol v3 Subtitle resource format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioSub(
    @JsonProperty("id") @SerializedName("id") val id: String? = null,
    @JsonProperty("url") @SerializedName("url") val url: String? = null,
    @JsonProperty("lang") @SerializedName("lang") val lang: String? = null,
    @JsonProperty("lang_code") @SerializedName("lang_code") val langCode: String? = null,
    @JsonProperty("label") @SerializedName("label") val label: String? = null,
    @JsonProperty("title") @SerializedName("title") val title: String? = null,
    @JsonProperty("subtitleFileName") @SerializedName("subtitleFileName") val subtitleFileName: String? = null,
    @JsonProperty("season") @SerializedName("season") val season: Int? = null,
    @JsonProperty("episode") @SerializedName("episode") val episode: Int? = null
)

/**
 * Top-level response container for Stremio subtitle endpoints:
 * GET /subtitles/{type}/{id}.json -> { "subtitles": [ ... ] }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioSubtitles(
    @JsonProperty("subtitles") @SerializedName("subtitles") val subtitles: List<StremioSub> = emptyList()
)

/**
 * Metadata preview item returned in catalog searches (Cinemeta and Kitsu).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioMeta(
    @JsonProperty("id") @SerializedName("id") val id: String? = null,
    @JsonProperty("type") @SerializedName("type") val type: String? = null,
    @JsonProperty("name") @SerializedName("name") val name: String? = null,
    @JsonProperty("poster") @SerializedName("poster") val poster: String? = null,
    @JsonProperty("releaseInfo") @SerializedName("releaseInfo") val releaseInfo: String? = null,
    @JsonProperty("description") @SerializedName("description") val description: String? = null,
    @JsonProperty("imdbRating") @SerializedName("imdbRating") val imdbRating: String? = null,
    @JsonProperty("genres") @SerializedName("genres") val genres: List<String>? = emptyList()
)

/**
 * Top-level catalog response:
 * GET /catalog/{type}/{id}/search={keyword}.json -> { "metas": [ ... ] }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalog(
    @JsonProperty("metas") @SerializedName("metas") val metas: List<StremioMeta> = emptyList()
)

/**
 * Internal parsed media representation extracted from raw stream filenames, magnet links, or torrent labels.
 */
data class ParsedMediaInfo(
    val cleanTitle: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val year: Int? = null,
    val rawTitle: String
)

/**
 * Internal clean subtitle track ready for emission to CloudStream's subtitleCallback.
 * Provides dual properties (lang and label) for seamless integration with StremioSubHook.
 */
data class StremioSubtitleTrack(
    val url: String,
    val lang: String,                       // Formatted display name (e.g. "Tiếng Việt (Stremio)")
    val langCode: String,                   // Normalized ISO 639-1 two-letter code (e.g. "vi", "en")
    val sourceName: String,                 // Source identifier ("kitsu", "opensubtitles", "custom_addon")
    val title: String? = null,              // Optional file or release title
    val episode: Int? = null                // Episode number
) {
    /** Alias for lang, matching standard SubtitleFile conventions. */
    val label: String get() = lang
}

/**
 * Metadata captured during MainAPI.load(url) to assist subtitle scraping during loadLinks.
 */
data class ShowMetadata(
    val title: String,
    val altTitles: List<String> = emptyList(),
    val seasonYear: Int? = null,
    val episodeNum: Int? = null,
    val seasonNum: Int? = null,
    val malId: Int? = null,
    val aniId: Int? = null
)

/**
 * Constants defining language filter configurations.
 */
object LanguageFilterMode {
    const val VI_EN = "vi_en"       // Tiếng Việt & English (Default)
    const val VI_ONLY = "vi_only"   // Chỉ Tiếng Việt
    const val EN_ONLY = "en_only"   // Chỉ English
    const val ALL = "all"           // Tất cả ngôn ngữ
    const val CUSTOM = "custom"     // Tùy chỉnh danh sách mã ngôn ngữ
}
