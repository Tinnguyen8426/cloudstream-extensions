package com.kitsunekko.enricher

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName

/**
 * Represents an anime series folder parsed from Kitsunekko's root directory listing.
 *
 * @param name Display folder title (e.g., "Sousou no Frieren", "Jujutsu Kaisen 2nd Season")
 * @param path Relative path URL (e.g., "/dirlist.php?dir=subtitles%2Fjapanese%2FSousou+no+Frieren%2F")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsunekkoFolder(
    @JsonProperty("name")
    @SerializedName(value = "name", alternate = ["title"])
    val name: String,

    @JsonProperty("path")
    @SerializedName(value = "path", alternate = ["href"])
    val path: String
) {
    /** Alias for plan & reference compatibility */
    val title: String get() = name
    /** Alias for plan & reference compatibility */
    val href: String get() = path
}

/**
 * Represents a subtitle file (.ass, .srt, .ssa) or archive (.zip) discovered within an anime folder.
 *
 * @param name File name (e.g., "Sousou no Frieren - 01 [1080p].ass", "Sousou no Frieren 1-28.zip")
 * @param url Full percent-encoded HTTP URL or file:// URI for local playback
 * @param isZip Whether the file is a compressed .zip archive requiring ZipSubtitleExtractor
 * @param episodeNumber Parsed episode number (0..3000) or null if whole-season / unparseable
 * @param seasonNumber Detected season number (0..9) or null
 * @param ext File extension in lowercase ("ass", "srt", "ssa", "zip")
 * @param rawHref Raw relative href extracted from the HTML link
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsunekkoSubtitle(
    @JsonProperty("name")
    @SerializedName("name")
    val name: String,

    @JsonProperty("url")
    @SerializedName("url")
    val url: String,

    @JsonProperty("isZip")
    @SerializedName("isZip")
    val isZip: Boolean,

    @JsonProperty("episodeNumber")
    @SerializedName(value = "episodeNumber", alternate = ["episode"])
    val episodeNumber: Int? = null,

    @JsonProperty("seasonNumber")
    @SerializedName(value = "seasonNumber", alternate = ["season"])
    val seasonNumber: Int? = null,

    @JsonProperty("ext")
    @SerializedName("ext")
    val ext: String? = null,

    @JsonProperty("rawHref")
    @SerializedName("rawHref")
    val rawHref: String? = null
) {
    /** Alias for reference compatibility */
    val episode: Int? get() = episodeNumber

    /** Resolves clean lowercase file extension */
    val fileExtension: String
        get() = ext ?: name.substringAfterLast('.', "").lowercase()
}

/**
 * Normalized media metadata parsed from torrent filenames, stream links, or MainAPI episode responses.
 *
 * @param title Clean normalized title stripped of quality tags, codecs, and release groups
 * @param episodeNumber Parsed episode number or null
 * @param seasonNumber Parsed season number or null
 * @param year Release year (1900..2100) or null
 * @param rawTitle Original unparsed title string
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ParsedMediaInfo(
    @JsonProperty("title")
    @SerializedName(value = "title", alternate = ["cleanTitle"])
    val title: String,

    @JsonProperty("episodeNumber")
    @SerializedName("episodeNumber")
    val episodeNumber: Int? = null,

    @JsonProperty("seasonNumber")
    @SerializedName("seasonNumber")
    val seasonNumber: Int? = null,

    @JsonProperty("year")
    @SerializedName("year")
    val year: Int? = null,

    @JsonProperty("rawTitle")
    @SerializedName("rawTitle")
    val rawTitle: String? = null
) {
    /** Alias matching Stremio & Anime47 naming conventions */
    val cleanTitle: String get() = title
}

/**
 * Information on an individual file entry discovered inside a streaming .zip archive.
 *
 * @param entryName Internal path of entry in the zip (e.g., "Subs/Frieren_EP01.ass")
 * @param episodeNumber Extracted episode number (0..3000) or null
 * @param isSubtitle True if file extension is .ass, .srt, or .ssa
 * @param fileName Clean file name without parent directories (e.g., "Frieren_EP01.ass")
 * @param ext File extension in lowercase
 * @param size Uncompressed file size in bytes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ZipEntryInfo(
    @JsonProperty("entryName")
    @SerializedName("entryName")
    val entryName: String,

    @JsonProperty("episodeNumber")
    @SerializedName("episodeNumber")
    val episodeNumber: Int? = null,

    @JsonProperty("isSubtitle")
    @SerializedName("isSubtitle")
    val isSubtitle: Boolean = true,

    @JsonProperty("fileName")
    @SerializedName("fileName")
    val fileName: String = entryName.substringAfterLast('/'),

    @JsonProperty("ext")
    @SerializedName("ext")
    val ext: String = fileName.substringAfterLast('.', "").lowercase(),

    @JsonProperty("size")
    @SerializedName("size")
    val size: Long = 0L
)

/**
 * Cache metrics and statistics for display in the Kitsunekko Plugin Settings UI.
 *
 * @param rootCacheAgeHours Age of the root directory cache in hours (e.g. 3.5 hrs; -1.0 if not cached)
 * @param cachedSubsCount Total number of extracted subtitle files in `cacheDir/kitsunekko_subs`
 * @param cacheSizeBytes Aggregate disk usage in bytes across zip cache, subs cache, and root cache
 * @param directoryItemCount Total anime series folders in root cache (~2,600)
 * @param directoryLastUpdated Timestamp in ms when root directory was last fetched
 * @param zipCacheBytes Disk bytes consumed by cached .zip packages
 * @param subsCacheBytes Disk bytes consumed by extracted .ass / .srt files
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsunekkoCacheSummary(
    @JsonProperty("rootCacheAgeHours")
    @SerializedName("rootCacheAgeHours")
    val rootCacheAgeHours: Double = -1.0,

    @JsonProperty("cachedSubsCount")
    @SerializedName("cachedSubsCount")
    val cachedSubsCount: Int = 0,

    @JsonProperty("cacheSizeBytes")
    @SerializedName(value = "cacheSizeBytes", alternate = ["totalBytes"])
    val cacheSizeBytes: Long = 0L,

    @JsonProperty("directoryItemCount")
    @SerializedName("directoryItemCount")
    val directoryItemCount: Int = 0,

    @JsonProperty("directoryLastUpdated")
    @SerializedName("directoryLastUpdated")
    val directoryLastUpdated: Long = 0L,

    @JsonProperty("zipCacheBytes")
    @SerializedName("zipCacheBytes")
    val zipCacheBytes: Long = 0L,

    @JsonProperty("subsCacheBytes")
    @SerializedName("subsCacheBytes")
    val subsCacheBytes: Long = 0L
) {
    /** Alias for plan & UI compatibility */
    val totalBytes: Long get() = cacheSizeBytes
}

/**
 * Persistent root directory cache wrapper serialized to `kitsunekko_root_cache.json`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsunekkoRootCachePayload(
    @JsonProperty("timestamp")
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @JsonProperty("folders")
    @SerializedName("folders")
    val folders: List<KitsunekkoFolder> = emptyList()
)

/**
 * Media metadata passed from MainAPI.load(url) to assist subtitle scraping during loadLinks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsunekkoShowInfo(
    @JsonProperty("title")
    @SerializedName("title")
    val title: String,

    @JsonProperty("altTitles")
    @SerializedName("altTitles")
    val altTitles: List<String> = emptyList(),

    @JsonProperty("year")
    @SerializedName("year")
    val year: Int? = null,

    @JsonProperty("season")
    @SerializedName("season")
    val season: Int? = null,

    @JsonProperty("episode")
    @SerializedName("episode")
    val episode: Int? = null
)
