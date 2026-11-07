cloudstream {
    version = 5
    authors = listOf("Anime47 Enricher")
    description = "Tự động trích xuất và nhúng phụ đề Tiếng Việt (softsub) từ Anime47 vào CloudStream"
    status = 1 // Down = 0, Ok = 1, Slow = 2, Beta = 3
    types = listOf(
        com.lagradost.cloudstream3.gradle.CloudstreamType.Anime
    )
}
