package com.stremio.subenricher

import java.text.Normalizer

/**
 * Empirical Adversarial Test Harness for StremioSubMatcher.
 * Systematically stress-tests 6 core dimensions:
 * 1. Complex titles & Fansub noise stripping
 * 2. Vietnamese diacritics & Unicode characters (NFC/NFD/CJK/Cyrillic)
 * 3. Title similarity edge cases (short strings, substrings, transposition, symmetry)
 * 4. Season hint detection (Roman numerals, ordinals, keywords, false positives)
 * 5. Episode extraction (SxxExx, EPxx, Tập xx, dash, embedded numbers)
 * 6. Year bonus boundary conditions (diff 0, 1, 2, nulls, score=0 guard)
 */
object StremioAdversarialTest {

    data class TestResult(
        val category: String,
        val testName: String,
        val passed: Boolean,
        val detail: String,
        val isBug: Boolean = false
    )

    private val results = mutableListOf<TestResult>()

    fun record(category: String, testName: String, condition: Boolean, detail: String = "", isBug: Boolean = false) {
        val passed = condition
        results.add(TestResult(category, testName, passed, detail, isBug && !passed))
        val tag = if (passed) "[PASS]" else if (isBug) "[BUG!]" else "[FAIL]"
        if (passed) {
            println("  $tag ($category) $testName")
        } else {
            System.err.println("  $tag ($category) $testName -> $detail")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" STARTING STREMIO SUB MATCHER ADVERSARIAL CHALLENGE HARNESS")
        println("================================================================================")

        testCategory1_ComplexTitlesAndNoise()
        testCategory2_VietnameseAndUnicode()
        testCategory3_SimilarityEdgeCases()
        testCategory4_SeasonHintDetection()
        testCategory5_EpisodeParsing()
        testCategory6_YearBonusBoundaries()

        println("\n================================================================================")
        println(" ADVERSARIAL CHALLENGE EXECUTION SUMMARY")
        println("================================================================================")

        val total = results.size
        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        val bugs = results.filter { it.isBug }

        println("Total Test Scenarios: $total")
        println("Passed: $passed")
        println("Failed: $failed")
        println("Confirmed Robustness Bugs: ${bugs.size}")

        val byCategory = results.groupBy { it.category }
        for ((cat, catResults) in byCategory) {
            val catTotal = catResults.size
            val catPass = catResults.count { it.passed }
            val catFail = catResults.count { !it.passed }
            println(String.format("  %-40s : %3d / %3d passed (%d failures)", cat, catPass, catTotal, catFail))
        }

        if (bugs.isNotEmpty()) {
            println("\n--------------------------------------------------------------------------------")
            println(" CONFIRMED BUGS & VULNERABILITIES:")
            println("--------------------------------------------------------------------------------")
            for ((idx, bug) in bugs.withIndex()) {
                println("${idx + 1}. [${bug.category}] ${bug.testName}")
                println("   Detail: ${bug.detail}")
            }
        }
        println("================================================================================")
    }

    // =========================================================================
    // CATEGORY 1: Complex titles with fansub noise, resolution, audio, release
    // =========================================================================
    private fun testCategory1_ComplexTitlesAndNoise() {
        println("\n>>> Category 1: Complex Titles & Noise Stripping")
        val cat = "Noise & Complex Titles"

        // Standard clean parsing
        val p1 = StremioSubMatcher.parseMediaInfo("[SubsPlease] Sousou no Frieren - 05 (1080p) [ABCD1234].mkv")
        record(cat, "SubsPlease anime clean title", p1.cleanTitle == "Sousou no Frieren", "got '${p1.cleanTitle}'")
        record(cat, "SubsPlease anime episode", p1.episodeNumber == 5, "got ${p1.episodeNumber}")

        val p2 = StremioSubMatcher.parseMediaInfo("[Erai-raws] Jujutsu Kaisen 2nd Season - 15 [1080p][Multiple Subtitle][CRC32].mkv")
        record(cat, "Erai-raws anime title prefix", p2.cleanTitle.startsWith("Jujutsu Kaisen"), "got '${p2.cleanTitle}'")
        record(cat, "Erai-raws anime episode", p2.episodeNumber == 15, "got ${p2.episodeNumber}")

        // Release types
        val releases = listOf("WEB-DL", "WEBRip", "BluRay", "blu-ray", "BDRip", "BRRip", "HDTV", "DVDRip", "Remux", "BDRemux", "telesync", "camrip")
        for (rel in releases) {
            val p = StremioSubMatcher.parseMediaInfo("Oppenheimer.2023.$rel.x264.mp4")
            record(cat, "Release type stripped: $rel", p.cleanTitle == "Oppenheimer", "for $rel got '${p.cleanTitle}'")
        }

        // Resolution tags
        val resolutions = listOf("2160p", "1080p", "1080i", "720p", "576p", "480p", "360p", "4K", "2K", "UHD", "FHD", "HD")
        for (res in resolutions) {
            val p = StremioSubMatcher.parseMediaInfo("Dune.Part.Two.2024.$res.WEBRip.mkv")
            record(cat, "Resolution stripped: $res", !p.cleanTitle.contains(res, ignoreCase = true), "for $res got '${p.cleanTitle}'")
        }

        // Audio tags
        val audioTags = listOf("FLAC", "FLAC 2.0", "AAC", "AAC2.0", "AAC5.1", "AC3", "DTS", "DTS-HD", "TrueHD", "Atmos", "EAC3", "MP3", "DDP5.1")
        for (audio in audioTags) {
            val p = StremioSubMatcher.parseMediaInfo("Inception.2010.1080p.$audio.x264.mkv")
            record(cat, "Audio tag stripped: $audio", !p.cleanTitle.contains(audio.replace(" ", "."), ignoreCase = true), "for $audio got '${p.cleanTitle}'")
        }

        // Audio tag edge case: DDP 5.1 with space or separate 5.1
        val pDdpSpace = StremioSubMatcher.parseMediaInfo("Inception.2010.1080p.DDP.5.1.x264.mkv")
        record(cat, "Audio DDP with separate 5.1 stripped", !pDdpSpace.cleanTitle.contains("5 1") && !pDdpSpace.cleanTitle.contains("5.1"), "got '${pDdpSpace.cleanTitle}'", isBug = true)

        // Missing HDR tags in TECHNICAL_TAGS_REGEX
        val pHdr = StremioSubMatcher.parseMediaInfo("Interstellar.2014.2160p.HDR10.HEVC.mkv")
        record(cat, "HDR10 stripped from title", !pHdr.cleanTitle.contains("HDR", ignoreCase = true), "got '${pHdr.cleanTitle}'", isBug = true)

        val pDovi = StremioSubMatcher.parseMediaInfo("Interstellar.2014.2160p.DV.DoVi.HEVC.mkv")
        record(cat, "Dolby Vision tags stripped", !pDovi.cleanTitle.contains("DV") && !pDovi.cleanTitle.contains("DoVi", ignoreCase = true), "got '${pDovi.cleanTitle}'", isBug = true)

        // Title preservation with internal hyphens
        val pSpiderman = StremioSubMatcher.parseMediaInfo("Spider-Man.No.Way.Home.2021.1080p.mkv")
        record(cat, "Spider-Man title preserved", pSpiderman.cleanTitle.contains("Spider", ignoreCase = true) && pSpiderman.cleanTitle.contains("No Way Home", ignoreCase = true), "got '${pSpiderman.cleanTitle}'")

        // Title with brackets that are part of the title: Fate/stay night [Unlimited Blade Works]
        val pFate = StremioSubMatcher.parseMediaInfo("Fate stay night [Unlimited Blade Works] - 01 [1080p].mkv")
        record(cat, "Title bracketed subtitle loss", pFate.cleanTitle.contains("Unlimited Blade Works"), "Unlimited Blade Works was stripped: '${pFate.cleanTitle}'", isBug = true)

        // Uppercase file extension
        val pUpperExt = StremioSubMatcher.parseMediaInfo("Oppenheimer.2023.1080p.MKV")
        record(cat, "Uppercase .MKV extension stripped", !pUpperExt.cleanTitle.endsWith("MKV", ignoreCase = true), "got '${pUpperExt.cleanTitle}'")

        // Degenerate input: only noise tags
        val pDegenerate = StremioSubMatcher.parseMediaInfo("1080p.WEB-DL.x264.mkv")
        record(cat, "Degenerate noise-only input does not throw", pDegenerate.cleanTitle.isNotEmpty(), "got empty cleanTitle")
    }

    // =========================================================================
    // CATEGORY 2: Vietnamese diacritics and Unicode characters
    // =========================================================================
    private fun testCategory2_VietnameseAndUnicode() {
        println("\n>>> Category 2: Vietnamese Diacritics & Unicode Characters")
        val cat = "Vietnamese & Unicode"

        // NFC Vietnamese letters
        val viTitles = listOf(
            "Chú Thuật Hồi Chiến",
            "Đại Chiến Titan",
            "Thanh Gươm Diệt Quỷ",
            "Thám Tử Lừng Danh Conan",
            "Học Viện Siêu Anh Hùng",
            "Đấu La Đại Lục"
        )
        for (vt in viTitles) {
            val norm = StremioSubMatcher.normTitle(vt)
            record(cat, "NFC Vietnamese preserved: $vt", norm.isNotEmpty() && norm.length >= 8, "got norm='$norm'")
            val sim = StremioSubMatcher.titleSimilarity(vt, vt)
            record(cat, "NFC Vietnamese self-similarity 100: $vt", sim == 100, "got sim=$sim")
        }

        // Specific Đ character lowercase handling
        val normD = StremioSubMatcher.normTitle("Đấu La Đại Lục")
        record(cat, "Vietnamese letter Đ normalized to đ", normD.startsWith("đ"), "got norm='$normD'")

        // Vietnamese NFD (Decomposed form) vs NFC
        val nfcVi = "Chú Thuật Hồi Chiến"
        val nfdVi = Normalizer.normalize(nfcVi, Normalizer.Form.NFD)
        val normNfd = StremioSubMatcher.normTitle(nfdVi)
        val simNfcNfd = StremioSubMatcher.titleSimilarity(nfcVi, nfdVi)
        record(cat, "Vietnamese NFD normalized matches NFC", simNfcNfd >= 75, "sim(NFC, NFD) = $simNfcNfd (normNFD='$normNfd')", isBug = true)

        // CJK Characters (Japanese / Chinese / Korean)
        val japaneseTitles = listOf(
            "葬送のフリーレン" to "Sousou no Frieren",
            "呪術廻戦" to "Jujutsu Kaisen",
            "進撃の巨人" to "Attack on Titan",
            "鬼滅の刃" to "Demon Slayer"
        )
        for ((jp, en) in japaneseTitles) {
            val normJp = StremioSubMatcher.normTitle(jp)
            val simJp = StremioSubMatcher.titleSimilarity(jp, jp)
            record(cat, "Japanese title normTitle non-empty: $jp", normJp.isNotEmpty(), "norm='$normJp'", isBug = true)
            record(cat, "Japanese title self-similarity 100: $jp", simJp == 100, "sim(jp, jp)=$simJp", isBug = true)
        }

        // Korean Hangul
        val koreanTitle = "나 혼자만 레벨업" // Solo Leveling
        val normKo = StremioSubMatcher.normTitle(koreanTitle)
        val simKo = StremioSubMatcher.titleSimilarity(koreanTitle, koreanTitle)
        record(cat, "Korean title self-similarity 100: $koreanTitle", simKo == 100, "norm='$normKo', sim=$simKo", isBug = true)

        // Cyrillic
        val cyrillicTitle = "Атака титанов"
        val normCy = StremioSubMatcher.normTitle(cyrillicTitle)
        val simCy = StremioSubMatcher.titleSimilarity(cyrillicTitle, cyrillicTitle)
        record(cat, "Cyrillic title self-similarity 100", simCy == 100, "norm='$normCy', sim=$simCy", isBug = true)
    }

    // =========================================================================
    // CATEGORY 3: Title similarity edge cases
    // =========================================================================
    private fun testCategory3_SimilarityEdgeCases() {
        println("\n>>> Category 3: Title Similarity Edge Cases")
        val cat = "Similarity Edge Cases"

        // Empty & blank strings
        record(cat, "Similarity empty vs empty is 0", StremioSubMatcher.titleSimilarity("", "") == 0)
        record(cat, "Similarity empty vs valid is 0", StremioSubMatcher.titleSimilarity("", "Solo Leveling") == 0)
        record(cat, "Similarity whitespace vs valid is 0", StremioSubMatcher.titleSimilarity("   \t  ", "Solo Leveling") == 0)
        record(cat, "Similarity punctuation-only vs valid is 0", StremioSubMatcher.titleSimilarity("???...---", "Solo Leveling") == 0)

        // Short strings (<4 chars)
        record(cat, "Single char equal 'A' vs 'A' is 100", StremioSubMatcher.titleSimilarity("A", "A") == 100)
        record(cat, "Single char different 'A' vs 'B' is 0", StremioSubMatcher.titleSimilarity("A", "B") == 0)
        record(cat, "2-char equal 'Ab' vs 'ab' is 100", StremioSubMatcher.titleSimilarity("Ab", "ab") == 100)
        record(cat, "2-char different 'ab' vs 'cd' is 0", StremioSubMatcher.titleSimilarity("ab", "cd") == 0)
        record(cat, "3-char equal 'Arc' vs 'arc' is 100", StremioSubMatcher.titleSimilarity("Arc", "arc") == 100)

        // Short string transposition
        val simTrans = StremioSubMatcher.titleSimilarity("abc", "acb")
        record(cat, "3-char transposition score valid range 0..100", simTrans in 0..100, "got $simTrans")

        // Monotonous repeating characters: "aaa" vs "aaaa"
        // Line 56: if (x.toSet().size == 1 && y.toSet().size == 1) return if (x == y) 100 else 0
        val simAaa = StremioSubMatcher.titleSimilarity("aaa", "aaaa")
        record(cat, "Monotonous char mismatch 'aaa' vs 'aaaa' anomaly check", simAaa > 0, "sim('aaa', 'aaaa') was $simAaa due to toSet().size==1 rule", isBug = true)

        // Containment rule: >= 4 characters
        val simContain = StremioSubMatcher.titleSimilarity("Demon Slayer", "Demon Slayer Kimetsu no Yaiba")
        record(cat, "Containment >= 4 returns 75", simContain == 75, "got $simContain")

        val simContainSymmetric = StremioSubMatcher.titleSimilarity("Demon Slayer Kimetsu no Yaiba", "Demon Slayer")
        record(cat, "Containment is symmetric", simContainSymmetric == 75, "got $simContainSymmetric")

        // Short containment guard (<4 chars)
        val simShortContain = StremioSubMatcher.titleSimilarity("ab", "abcdef")
        record(cat, "Short containment (<4) does not trigger 75", simShortContain < 75, "got $simShortContain")

        // Accidental containment of generic 4-letter word
        val simGenericHero = StremioSubMatcher.titleSimilarity("Hero", "My Hero Academia")
        record(cat, "Generic 4-letter title containment trigger", simGenericHero == 75, "got $simGenericHero")

        // Typos and transpositions on longer strings
        val simDemonTypo = StremioSubMatcher.titleSimilarity("Demon Slayer", "Dmeon Slayer")
        record(cat, "Single transposition retains high similarity (>= 60)", simDemonTypo >= 60, "got $simDemonTypo")

        val simFrierenMissing = StremioSubMatcher.titleSimilarity("Sousou no Frieren", "Sousou no Friren")
        record(cat, "Single missing character retains high similarity (>= 70)", simFrierenMissing >= 70, "got $simFrierenMissing")

        // Symmetry test across diverse pairs
        val pairs = listOf(
            "Solo Leveling" to "Solo Leveling Season 2",
            "Oppenheimer" to "Oppenheimer 2023",
            "Attack on Titan" to "Shingeki no Kyojin",
            "Chainsaw Man" to "Chainsaw Man (TV)"
        )
        for ((a, b) in pairs) {
            val s1 = StremioSubMatcher.titleSimilarity(a, b)
            val s2 = StremioSubMatcher.titleSimilarity(b, a)
            record(cat, "Symmetry for '$a' vs '$b'", s1 == s2, "s1=$s1, s2=$s2")
        }
    }

    // =========================================================================
    // CATEGORY 4: Season hint detection
    // =========================================================================
    private fun testCategory4_SeasonHintDetection() {
        println("\n>>> Category 4: Season Hint Detection")
        val cat = "Season Hints"

        // Keywords: Season, Part, SS
        record(cat, "seasonOf 'Season 2'", StremioSubMatcher.seasonOf("Show Season 2") == 2)
        record(cat, "seasonOf 'Season 12'", StremioSubMatcher.seasonOf("Show Season 12") == 12)
        record(cat, "seasonOf 'Part 3'", StremioSubMatcher.seasonOf("Attack on Titan Part 3") == 3)
        record(cat, "seasonOf 'SS2'", StremioSubMatcher.seasonOf("Show SS2") == 2)

        // Single letter 'S2' without space
        val seasonS2 = StremioSubMatcher.seasonOf("Show S2")
        record(cat, "seasonOf 'S2' without space recognized", seasonS2 == 2, "seasonOf('Show S2') returned $seasonS2", isBug = true)

        // Ordinals with "Season"
        record(cat, "seasonOf '1st Season'", StremioSubMatcher.seasonOf("Show 1st Season") == 1)
        record(cat, "seasonOf '2nd Season'", StremioSubMatcher.seasonOf("Show 2nd Season") == 2)
        record(cat, "seasonOf '3rd Season'", StremioSubMatcher.seasonOf("Show 3rd Season") == 3)
        record(cat, "seasonOf '4th Season'", StremioSubMatcher.seasonOf("Show 4th Season") == 4)

        // Ordinals WITHOUT "Season" (e.g. "My Hero Academia 2nd", "Show 3rd")
        val season2nd = StremioSubMatcher.seasonOf("Show 2nd")
        record(cat, "seasonOf '2nd' without 'season' keyword", season2nd == 2, "seasonOf('Show 2nd') returned $season2nd", isBug = true)

        val season3rd = StremioSubMatcher.seasonOf("Show 3rd")
        record(cat, "seasonOf '3rd' without 'season' keyword", season3rd == 3, "seasonOf('Show 3rd') returned $season3rd", isBug = true)

        // Roman numerals: II..IX
        val romanMap = listOf("II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9)
        for ((rom, expected) in romanMap) {
            val actual = StremioSubMatcher.seasonOf("Show $rom")
            record(cat, "Roman numeral $rom -> $expected", actual == expected, "got $actual")
        }

        // Roman numeral 'I' (Season 1)
        val seasonRomanI = StremioSubMatcher.seasonOf("Show I")
        record(cat, "Roman numeral I detected as Season 1", seasonRomanI == 1, "seasonOf('Show I') returned $seasonRomanI", isBug = true)

        val seasonPartI = StremioSubMatcher.seasonOf("Show Part I")
        record(cat, "Season 'Part I' detected as Season 1", seasonPartI == 1, "seasonOf('Show Part I') returned $seasonPartI", isBug = true)

        // Trailing numbers
        record(cat, "Trailing number 'Show 2'", StremioSubMatcher.seasonOf("Show 2") == 2)
        record(cat, "Trailing number 'Show 5'", StremioSubMatcher.seasonOf("Show 5") == 5)

        // False positives on movie titles ending in numbers
        val fpMovies = listOf(
            "District 9" to 9,
            "Super 8" to 8,
            "Apollo 13" to 13,
            "Ocean's 11" to 11,
            "Big Hero 6" to 6
        )
        for ((title, num) in fpMovies) {
            val detected = StremioSubMatcher.seasonOf(title)
            record(cat, "False positive check on movie: '$title'", detected == null, "returned season $detected for standalone movie '$title'", isBug = true)
        }

        // Season hints across multiple titles
        val hint1 = StremioSubMatcher.seasonHintOf(listOf("Show Season 3", "Show Season 1", "Show"))
        record(cat, "seasonHintOf lowest season", hint1 == 1, "got $hint1")

        val hintNone = StremioSubMatcher.seasonHintOf(listOf("Solo Leveling", "Movie"))
        record(cat, "seasonHintOf none returns null", hintNone == null, "got $hintNone")

        val hintEmpty = StremioSubMatcher.seasonHintOf(emptyList())
        record(cat, "seasonHintOf empty list returns null", hintEmpty == null, "got $hintEmpty")
    }

    // =========================================================================
    // CATEGORY 5: Episode parsing
    // =========================================================================
    private fun testCategory5_EpisodeParsing() {
        println("\n>>> Category 5: Episode Parsing")
        val cat = "Episode Parsing"

        // 1. SxxExx
        record(cat, "S01E05", StremioSubMatcher.extractEpisodeNumber("Show S01E05") == 5)
        record(cat, "s01e05", StremioSubMatcher.extractEpisodeNumber("Show s01e05") == 5)
        record(cat, "S2.E15", StremioSubMatcher.extractEpisodeNumber("Show S02.E15") == 15)
        record(cat, "S02-E15", StremioSubMatcher.extractEpisodeNumber("Show S02-E15") == 15)
        record(cat, "S01E1000", StremioSubMatcher.extractEpisodeNumber("One Piece S01E1000") == 1000)

        // 2. Keyword EP / Episode / Tap / Tập
        record(cat, "EP05", StremioSubMatcher.extractEpisodeNumber("Sousou no Frieren EP05") == 5)
        record(cat, "EP 05", StremioSubMatcher.extractEpisodeNumber("Sousou no Frieren EP 05") == 5)
        record(cat, "Episode 25", StremioSubMatcher.extractEpisodeNumber("Show Episode 25") == 25)
        record(cat, "Tap 12", StremioSubMatcher.extractEpisodeNumber("One Piece Tap 12") == 12)
        record(cat, "Tập 12 (lower/NFC)", StremioSubMatcher.extractEpisodeNumber("One Piece Tập 12") == 12)

        // Uppercase Vietnamese TẬP 12 with Unicode diacritics
        val epTapUpper = StremioSubMatcher.extractEpisodeNumber("One Piece TẬP 12")
        record(cat, "TẬP 12 (Uppercase Vietnamese diacritics)", epTapUpper == 12, "got $epTapUpper", isBug = true)

        // 3. Anime Dash notation
        record(cat, "Anime dash ' - 05 '", StremioSubMatcher.extractEpisodeNumber("Sousou no Frieren - 05 (1080p)") == 5)
        record(cat, "Anime dash versioned ' - 05v2 '", StremioSubMatcher.extractEpisodeNumber("Sousou no Frieren - 05v2 (1080p)") == 5)
        record(cat, "Anime high episode ' - 1088 '", StremioSubMatcher.extractEpisodeNumber("One Piece - 1088 (1080p)") == 1088)

        // Compact dash without spaces: "Show-05.mkv"
        val epCompactDash = StremioSubMatcher.extractEpisodeNumber("Sousou.no.Frieren-05.mkv")
        record(cat, "Compact dash 'Frieren-05.mkv'", epCompactDash == 5, "got $epCompactDash")

        val pCompactDashMedia = StremioSubMatcher.parseMediaInfo("Sousou.no.Frieren-05.mkv")
        record(cat, "parseMediaInfo compact dash 'Frieren-05.mkv'", pCompactDashMedia.episodeNumber == 5, "parseMediaInfo got episode=${pCompactDashMedia.episodeNumber}", isBug = true)

        // 4. Standalone Exx
        record(cat, "Standalone E08", StremioSubMatcher.extractEpisodeNumber("Show.E08.mkv") == 8)
        record(cat, "Standalone E12v2", StremioSubMatcher.extractEpisodeNumber("Show.E12v2.mkv") == 12)

        // 5. False positive checks on embedded numbers: Resolution & Years
        val epRes1080 = StremioSubMatcher.extractEpisodeNumber("Movie.Name.2023.1080p.mkv")
        record(cat, "1080p not parsed as episode", epRes1080 == null, "got $epRes1080")

        val epRes720 = StremioSubMatcher.extractEpisodeNumber("Movie.Name.1999.720p.mkv")
        record(cat, "720p and 1999 not parsed as episode", epRes720 == null, "got $epRes720")

        // 4K resolution false positive in extractEpisodeNumber
        val ep4k = StremioSubMatcher.extractEpisodeNumber("Movie.2024.4K.UHD.mkv")
        record(cat, "4K resolution not parsed as episode 4", ep4k == null, "4K parsed as episode $ep4k", isBug = true)

        // 2K resolution false positive in extractEpisodeNumber
        val ep2k = StremioSubMatcher.extractEpisodeNumber("Movie.2024.2K.WEB-DL.mkv")
        record(cat, "2K resolution not parsed as episode 2", ep2k == null, "2K parsed as episode $ep2k", isBug = true)

        // Codec numbers: H.264 / H.265 false positives
        val epH264 = StremioSubMatcher.extractEpisodeNumber("Movie.2023.1080p.H.264.mkv")
        record(cat, "H.264 not parsed as episode 264", epH264 == null, "H.264 parsed as episode $epH264", isBug = true)

        val epH265 = StremioSubMatcher.extractEpisodeNumber("Movie.2023.1080p.H.265.mkv")
        record(cat, "H.265 not parsed as episode 265", epH265 == null, "H.265 parsed as episode $epH265", isBug = true)

        // Audio channels: 5.1 / 7.1 false positives
        val ep51 = StremioSubMatcher.extractEpisodeNumber("Movie.2023.1080p.DDP5.1.mkv")
        record(cat, "Audio 5.1 not parsed as episode 5", ep51 == null, "5.1 parsed as episode $ep51", isBug = true)

        // Color depth: 10bit / 8bit false positives
        val ep10bit = StremioSubMatcher.extractEpisodeNumber("Movie.2023.1080p.10bit.mkv")
        record(cat, "10bit color depth not parsed as episode 10", ep10bit == null, "10bit parsed as episode $ep10bit", isBug = true)

        // Bitrate: 320kbps
        val ep320k = StremioSubMatcher.extractEpisodeNumber("Audio.320kbps.mp3")
        record(cat, "320kbps not parsed as episode 320", ep320k == null, "320kbps parsed as episode $ep320k", isBug = true)

        // Episode 0 (Special/Prologue)
        val epZero = StremioSubMatcher.extractEpisodeNumber("Fate Stay Night UBW S01E00")
        record(cat, "Episode 0 supported", epZero == 0, "Episode 0 returned $epZero (rejected by 1..3000 guard)", isBug = true)
    }

    // =========================================================================
    // CATEGORY 6: Year bonus boundary conditions in scoreCandidate
    // =========================================================================
    private fun testCategory6_YearBonusBoundaries() {
        println("\n>>> Category 6: Year Bonus Boundary Conditions")
        val cat = "Year Bonus"

        val title = "Oppenheimer"
        val queryTitles = listOf("Oppenheimer")
        val baseScore = StremioSubMatcher.bestMatchScore(title, queryTitles)
        record(cat, "Base match score is 100", baseScore == 100, "got $baseScore")

        // Diff == 0: Exact match -> +15 bonus
        val scoreDiff0 = StremioSubMatcher.scoreCandidate(title, "2023", queryTitles, 2023)
        record(cat, "Year diff == 0 receives +15 bonus (115)", scoreDiff0 == 115, "got $scoreDiff0")

        // Diff == 1: +1 year tolerance -> +15 bonus
        val scoreDiffPlus1 = StremioSubMatcher.scoreCandidate(title, "2024", queryTitles, 2023)
        record(cat, "Year diff == +1 receives +15 bonus (115)", scoreDiffPlus1 == 115, "got $scoreDiffPlus1")

        // Diff == 1: -1 year tolerance -> +15 bonus
        val scoreDiffMinus1 = StremioSubMatcher.scoreCandidate(title, "2022", queryTitles, 2023)
        record(cat, "Year diff == -1 receives +15 bonus (115)", scoreDiffMinus1 == 115, "got $scoreDiffMinus1")

        // Diff == 2: +2 years -> NO bonus (100)
        val scoreDiffPlus2 = StremioSubMatcher.scoreCandidate(title, "2025", queryTitles, 2023)
        record(cat, "Year diff == +2 receives NO bonus (100)", scoreDiffPlus2 == 100, "got $scoreDiffPlus2")

        // Diff == 2: -2 years -> NO bonus (100)
        val scoreDiffMinus2 = StremioSubMatcher.scoreCandidate(title, "2021", queryTitles, 2023)
        record(cat, "Year diff == -2 receives NO bonus (100)", scoreDiffMinus2 == 100, "got $scoreDiffMinus2")

        // Null queryYear -> NO bonus (100)
        val scoreNullQueryYear = StremioSubMatcher.scoreCandidate(title, "2023", queryTitles, null)
        record(cat, "Null queryYear receives NO bonus (100)", scoreNullQueryYear == 100, "got $scoreNullQueryYear")

        // Null candidateReleaseInfo -> NO bonus (100)
        val scoreNullReleaseInfo = StremioSubMatcher.scoreCandidate(title, null, queryTitles, 2023)
        record(cat, "Null candidateReleaseInfo receives NO bonus (100)", scoreNullReleaseInfo == 100, "got $scoreNullReleaseInfo")

        // Both null -> NO bonus (100)
        val scoreBothNull = StremioSubMatcher.scoreCandidate(title, null, queryTitles, null)
        record(cat, "Both null receive NO bonus (100)", scoreBothNull == 100, "got $scoreBothNull")

        // Non-year string in candidateReleaseInfo -> NO bonus (100)
        val scoreNonYearRelease = StremioSubMatcher.scoreCandidate(title, "TV Series - Complete", queryTitles, 2023)
        record(cat, "Non-year candidateReleaseInfo receives NO bonus (100)", scoreNonYearRelease == 100, "got $scoreNonYearRelease")

        // TV series year range: "2023-2024"
        val scoreYearRange = StremioSubMatcher.scoreCandidate(title, "2023-2024", queryTitles, 2023)
        record(cat, "Year range '2023-2024' matches start year", scoreYearRange == 115, "got $scoreYearRange")

        // Score 0 guard: Completely mismatched title with matching year -> MUST BE 0!
        val scoreZeroGuard = StremioSubMatcher.scoreCandidate("Completely Different Show", "2023", queryTitles, 2023)
        record(cat, "Score 0 guard prevents bonus on mismatched title", scoreZeroGuard == 0, "got $scoreZeroGuard")
    }
}
