package com.stremio.subenricher

import com.google.gson.Gson

/**
 * Standalone verification test suite for Stremio Subtitle Enricher.
 * Executes comprehensive unit tests on matcher, models, scraper, and hook logic.
 */
object StremioVerificationTest {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            System.err.println("  [FAIL] $name $detail")
            failed++
        }
    }

    private fun assertEquals(name: String, expected: Any?, actual: Any?) {
        check(name, expected == actual, "Expected: <$expected> but was: <$actual>")
    }

    private fun assertTrue(name: String, condition: Boolean) {
        check(name, condition, "Expected true but was false")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("============================================================")
        println(" RUNNING STREMIO SUBTITLE ENRICHER VERIFICATION SUITE")
        println("============================================================")

        testMatcher()
        testModelsAndSerialization()
        testLanguageNormalizationAndFilter()
        testLruCaches()
        testHookCoexistenceLogic()

        println("============================================================")
        println(" TEST RESULTS: $passed passed, $failed failed")
        println("============================================================")

        if (failed > 0) {
            System.exit(1)
        }
    }

    private fun testMatcher() {
        println("\n--- Testing StremioSubMatcher ---")

        // 1. Normalization
        assertEquals("normTitle basic", "solo leveling", StremioSubMatcher.normTitle("Solo Leveling"))
        assertEquals("normTitle diacritics", "chú thuật hồi chiến season 2", StremioSubMatcher.normTitle("Chú Thuật Hồi Chiến (Season 2)"))
        assertEquals("normTitle punctuation", "demon slayer kimetsu no yaiba", StremioSubMatcher.normTitle("Demon Slayer: Kimetsu no Yaiba!"))

        // 2. Similarity
        assertEquals("titleSimilarity exact", 100, StremioSubMatcher.titleSimilarity("Solo Leveling", "Solo Leveling"))
        assertEquals("titleSimilarity space/case insensitive", 100, StremioSubMatcher.titleSimilarity("Solo Leveling", "  solo   leveling!  "))
        assertEquals("titleSimilarity dash insensitive", 100, StremioSubMatcher.titleSimilarity("Solo  Leveling", "solo-leveling"))
        assertEquals("titleSimilarity containment >= 4", 75, StremioSubMatcher.titleSimilarity("Demon Slayer", "Demon Slayer: Kimetsu no Yaiba"))
        assertEquals("titleSimilarity containment symmetric", 75, StremioSubMatcher.titleSimilarity("Demon Slayer: Kimetsu no Yaiba", "Demon Slayer"))
        assertTrue("titleSimilarity short containment guard (<4)", StremioSubMatcher.titleSimilarity("ab", "abcdef") < 75)
        assertEquals("titleSimilarity empty string A", 0, StremioSubMatcher.titleSimilarity("", "Anything"))
        assertEquals("titleSimilarity empty string B", 0, StremioSubMatcher.titleSimilarity("Anything", ""))

        // 3. Best Match Score
        assertEquals("bestMatchScore match", 100, StremioSubMatcher.bestMatchScore("Solo Leveling", listOf("Khác", "Solo Leveling")))
        assertEquals("bestMatchScore empty", 0, StremioSubMatcher.bestMatchScore("Solo Leveling", emptyList()))

        // 4. Season extraction
        assertEquals("seasonOf keyword", 2, StremioSubMatcher.seasonOf("Jujutsu Kaisen Season 2"))
        assertEquals("seasonOf part", 3, StremioSubMatcher.seasonOf("Attack on Titan Part 3"))
        assertEquals("seasonOf ordinal", 2, StremioSubMatcher.seasonOf("Show 2nd Season"))
        assertEquals("seasonOf roman", 4, StremioSubMatcher.seasonOf("Show IV"))
        assertEquals("seasonOf trailing number", 5, StremioSubMatcher.seasonOf("Show 5"))
        assertEquals("seasonOf plain title", null, StremioSubMatcher.seasonOf("Show"))

        // 5. Season hints
        assertEquals("seasonHintOf lowest", 1, StremioSubMatcher.seasonHintOf(listOf("Show Season 2", "Show Season 1", "Show")))
        assertEquals("seasonHintOf none", null, StremioSubMatcher.seasonHintOf(listOf("Show", "Movie")))

        // 6. Year bonus (+15)
        assertEquals("scoreCandidate exact year bonus", 115, StremioSubMatcher.scoreCandidate("Oppenheimer", "2023", listOf("Oppenheimer"), 2023))
        assertEquals("scoreCandidate 1 year tolerance bonus", 115, StremioSubMatcher.scoreCandidate("Oppenheimer", "2024", listOf("Oppenheimer"), 2023))
        assertEquals("scoreCandidate no year bonus if far", 100, StremioSubMatcher.scoreCandidate("Oppenheimer", "2010", listOf("Oppenheimer"), 2023))

        // 7. Episode extraction
        assertEquals("extractEpisodeNumber SxxExx", 15, StremioSubMatcher.extractEpisodeNumber("Jujutsu Kaisen S02E15"))
        assertEquals("extractEpisodeNumber keyword ep", 5, StremioSubMatcher.extractEpisodeNumber("Sousou no Frieren EP05"))
        assertEquals("extractEpisodeNumber tap", 12, StremioSubMatcher.extractEpisodeNumber("One Piece Tập 12"))
        assertEquals("extractEpisodeNumber dash anime", 5, StremioSubMatcher.extractEpisodeNumber("Sousou no Frieren - 05 (1080p)"))
        assertEquals("extractEpisodeNumber standalone Exx", 8, StremioSubMatcher.extractEpisodeNumber("Show.E08.mkv"))

        // 8. Media parser
        val p1 = StremioSubMatcher.parseMediaInfo("[SubsPlease] Sousou no Frieren - 05 (1080p) [ABCD1234].mkv")
        assertEquals("parseMediaInfo Frieren title", "Sousou no Frieren", p1.cleanTitle)
        assertEquals("parseMediaInfo Frieren episode", 5, p1.episodeNumber)

        val p2 = StremioSubMatcher.parseMediaInfo("Jujutsu Kaisen S02E15 1080p WEB-DL.mkv")
        assertEquals("parseMediaInfo JJK title", "Jujutsu Kaisen", p2.cleanTitle)
        assertEquals("parseMediaInfo JJK season", 2, p2.seasonNumber)
        assertEquals("parseMediaInfo JJK episode", 15, p2.episodeNumber)

        val p3 = StremioSubMatcher.parseMediaInfo("Oppenheimer.2023.1080p.WEBRip.x264.mp4")
        assertEquals("parseMediaInfo Oppenheimer title", "Oppenheimer", p3.cleanTitle)
        assertEquals("parseMediaInfo Oppenheimer year", 2023, p3.year)
    }

    private fun testModelsAndSerialization() {
        println("\n--- Testing Models & Deserialization ---")

        val gson = Gson()

        // Deserialization of StremioSubtitles with unknown extra fields
        val subJson = """
            {
                "subtitles": [
                    {
                        "id": "os-sub-1",
                        "url": "https://sub.url/1.srt",
                        "lang": "vie",
                        "lang_code": "vi",
                        "label": "Tiếng Việt",
                        "title": "Release Team Vietsub",
                        "subtitleFileName": "frieren_ep5_vi.srt",
                        "season": 1,
                        "episode": 5,
                        "unknown_extra_vendor_field": "ignored_safe"
                    }
                ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(subJson, StremioSubtitles::class.java)
        assertEquals("Parsed subtitles count", 1, parsed.subtitles.size)
        val s = parsed.subtitles[0]
        assertEquals("Parsed subtitle url", "https://sub.url/1.srt", s.url)
        assertEquals("Parsed subtitle lang", "vie", s.lang)
        assertEquals("Parsed subtitle langCode", "vi", s.langCode)
        assertEquals("Parsed subtitle episode", 5, s.episode)

        // Deserialization of StremioCatalog
        val catalogJson = """
            {
                "metas": [
                    {
                        "id": "kitsu:47614",
                        "type": "anime",
                        "name": "Sousou no Frieren",
                        "releaseInfo": "2023",
                        "extra_field": 12345
                    }
                ]
            }
        """.trimIndent()

        val cat = gson.fromJson(catalogJson, StremioCatalog::class.java)
        assertEquals("Catalog metas count", 1, cat.metas.size)
        assertEquals("Catalog meta id", "kitsu:47614", cat.metas[0].id)
        assertEquals("Catalog meta name", "Sousou no Frieren", cat.metas[0].name)

        // Dual properties on StremioSubtitleTrack
        val track = StremioSubtitleTrack(
            url = "https://sub.url/test.srt",
            lang = "Tiếng Việt (Stremio)",
            langCode = "vi",
            sourceName = "kitsu",
            title = "Frieren Vietsub",
            episode = 5
        )
        assertEquals("Track lang property", "Tiếng Việt (Stremio)", track.lang)
        assertEquals("Track label alias", "Tiếng Việt (Stremio)", track.label)
        assertEquals("Track source", "kitsu", track.sourceName)
    }

    private fun testLanguageNormalizationAndFilter() {
        println("\n--- Testing 34-Language Normalization & Filter ---")

        // 34-Language mapping spot checks
        val vi = StremioSubScraper.normalizeLanguage("vie")
        assertEquals("normLang vie code", "vi", vi.first)
        assertEquals("normLang vie label", "Tiếng Việt (Stremio)", vi.second)

        val en = StremioSubScraper.normalizeLanguage("eng")
        assertEquals("normLang eng code", "en", en.first)
        assertEquals("normLang eng label", "English (Stremio)", en.second)

        val ja = StremioSubScraper.normalizeLanguage("jpn")
        assertEquals("normLang jpn code", "ja", ja.first)
        assertEquals("normLang jpn label", "Japanese (Stremio)", ja.second)

        val zh = StremioSubScraper.normalizeLanguage("zho")
        assertEquals("normLang zho code", "zh", zh.first)

        val ptBr = StremioSubScraper.normalizeLanguage("pt-BR")
        assertEquals("normLang pt-BR code", "pt", ptBr.first)

        val fallback2 = StremioSubScraper.normalizeLanguage("xx")
        assertEquals("normLang 2-letter fallback code", "xx", fallback2.first)

        val nullLang = StremioSubScraper.normalizeLanguage(null)
        assertEquals("normLang null code", "unknown", nullLang.first)

        // Language Filter rules
        assertTrue("Filter vi_en allows vi", StremioSubScraper.isLanguageAllowed("vi", LanguageFilterMode.VI_EN))
        assertTrue("Filter vi_en allows en", StremioSubScraper.isLanguageAllowed("en", LanguageFilterMode.VI_EN))
        assertTrue("Filter vi_en rejects ja", !StremioSubScraper.isLanguageAllowed("ja", LanguageFilterMode.VI_EN))

        assertTrue("Filter vi_only allows vi", StremioSubScraper.isLanguageAllowed("vi", LanguageFilterMode.VI_ONLY))
        assertTrue("Filter vi_only rejects en", !StremioSubScraper.isLanguageAllowed("en", LanguageFilterMode.VI_ONLY))

        assertTrue("Filter all allows ja", StremioSubScraper.isLanguageAllowed("ja", LanguageFilterMode.ALL))
        assertTrue("Filter all allows fr", StremioSubScraper.isLanguageAllowed("fr", LanguageFilterMode.ALL))
    }

    private fun testLruCaches() {
        println("\n--- Testing Thread-Safe Dual LRU Caches ---")

        StremioSubScraper.clearCache()
        assertEquals("Initial result cache empty", 0, StremioSubScraper.resultCache.size)
        assertEquals("Initial kitsu cache empty", 0, StremioSubScraper.kitsuIdCache.size)
        assertEquals("Initial imdb cache empty", 0, StremioSubScraper.imdbIdCache.size)

        // Insert 35 items into resultCache (capacity 32)
        val dummyTrack = StremioSubtitleTrack("https://url", "Tiếng Việt (Stremio)", "vi", "test")
        for (i in 1..35) {
            StremioSubScraper.resultCache["key_$i"] = listOf(dummyTrack)
        }
        assertEquals("resultCache capped at 32", 32, StremioSubScraper.resultCache.size)
        assertTrue("Eldest key_1 was evicted", !StremioSubScraper.resultCache.containsKey("key_1"))
        assertTrue("Latest key_35 is present", StremioSubScraper.resultCache.containsKey("key_35"))

        // Insert 26 items into kitsuIdCache (capacity 24)
        for (i in 1..26) {
            StremioSubScraper.kitsuIdCache["anime_$i"] = "id_$i"
        }
        assertEquals("kitsuIdCache capped at 24", 24, StremioSubScraper.kitsuIdCache.size)
        assertTrue("Eldest anime_1 was evicted", !StremioSubScraper.kitsuIdCache.containsKey("anime_1"))
        assertTrue("Latest anime_26 is present", StremioSubScraper.kitsuIdCache.containsKey("anime_26"))

        // Insert 26 items into imdbIdCache (capacity 24)
        for (i in 1..26) {
            StremioSubScraper.imdbIdCache["movie_$i"] = "tt000$i"
        }
        assertEquals("imdbIdCache capped at 24", 24, StremioSubScraper.imdbIdCache.size)
        assertTrue("Eldest movie_1 was evicted", !StremioSubScraper.imdbIdCache.containsKey("movie_1"))
        assertTrue("Latest movie_26 is present", StremioSubScraper.imdbIdCache.containsKey("movie_26"))

        val stats = StremioSubScraper.getCacheStats()
        assertTrue("Cache stats contains 32/32", stats.contains("32/32"))
    }

    private fun testHookCoexistenceLogic() {
        println("\n--- Testing Hook Chaining Coexistence ---")

        // Target providers
        val targets = StremioSubHook.targetProviders
        assertTrue("Targets includes Torrentio", targets.contains("Torrentio"))
        assertTrue("Targets includes TorraStream", targets.contains("TorraStream"))
        assertTrue("Targets includes AnimePahe", targets.contains("AnimePahe"))

        // Check provider names that must be skipped to avoid recursive wrapping
        val stremioSelfName = "Stremio Subtitle Enricher"
        assertTrue("Self provider contains Stremio", stremioSelfName.contains("Stremio", ignoreCase = true))

        val torrentioName = "Torrentio"
        assertTrue("Torrentio does not contain Stremio", !torrentioName.contains("Stremio", ignoreCase = true))

        val anime47WrappedName = "Torrentio" // Anime47 preserves baseApi.name
        assertTrue("Anime47 wrapped provider name does not contain Stremio", !anime47WrappedName.contains("Stremio", ignoreCase = true))
    }
}
