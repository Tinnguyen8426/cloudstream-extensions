cloudstream {
    authors = listOf("AI Subtitle Translator")
    description = "Dịch phụ đề phim tự động bằng AI (Gemini, OpenAI, Claude) trực tiếp trên CloudStream với độ trễ 0ms và hỗ trợ xử lý ngữ cảnh đa ngôn ngữ"
    status = 1 // Down = 0, Ok = 1, Slow = 2, Beta = 3
    types = listOf(
        com.lagradost.cloudstream3.gradle.CloudstreamType.Anime,
        com.lagradost.cloudstream3.gradle.CloudstreamType.Movie,
        com.lagradost.cloudstream3.gradle.CloudstreamType.TvSeries
    )
}

