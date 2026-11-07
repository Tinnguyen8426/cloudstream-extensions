cloudstream {
    version = 3
    authors = listOf("Stremio Subtitle Enricher")
    description = "Tự động trích xuất và nhúng phụ đề đa ngôn ngữ từ Kitsu, OpenSubtitles v3 và Cinemeta vào CloudStream"
    status = 1 // Down = 0, Ok = 1, Slow = 2, Beta = 3
    types = listOf(
        com.lagradost.cloudstream3.gradle.CloudstreamType.Anime,
        com.lagradost.cloudstream3.gradle.CloudstreamType.Movie,
        com.lagradost.cloudstream3.gradle.CloudstreamType.TvSeries
    )
}
