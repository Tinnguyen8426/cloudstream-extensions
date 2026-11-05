cloudstream {
    authors = listOf("Kitsunekko Japanese Subtitle Enricher")
    description = "Tự động trích xuất và nhúng phụ đề tiếng Nhật (.ass, .srt, .zip) từ Kitsunekko vào CloudStream"
    status = 1 // Down = 0, Ok = 1, Slow = 2, Beta = 3
    types = listOf(
        com.lagradost.cloudstream3.gradle.CloudstreamType.Anime,
        com.lagradost.cloudstream3.gradle.CloudstreamType.AnimeMovie,
        com.lagradost.cloudstream3.gradle.CloudstreamType.OVA
    )
}
