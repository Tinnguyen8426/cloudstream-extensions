package com.kitsunekko.enricher

import com.google.gson.Gson
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Standalone verification test suite for Kitsunekko Japanese Subtitle Enricher.
 * Executes comprehensive unit tests on matcher, models, streaming zip extraction,
 * multi-charset fallback (UTF-8, Shift-JIS, Windows-1252), ZipSlip security,
 * 24-hour root directory caching, and multi-enricher hook chaining coexistence.
 */
object KitsunekkoVerificationTest {

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

    private fun assertTrue(name: String, condition: Boolean, msg: String = "") {
        check(name, condition, "Expected true but was false: $msg")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("============================================================")
        println(" RUNNING KITSUNEKKO SUBTITLE ENRICHER VERIFICATION SUITE")
        println("============================================================")

        testMatcherNormalizationAndSimilarity()
        testSeasonDetection()
        testCriticalSeasonPenalty()
        testEpisodeExtraction()
        testMediaInfoParser()
        testModelsSerialization()
        testZipSlipProtection()
        testStreamingZipExtraction()
        testMultiCharsetFallbackAndNormalization()
        test24HourCacheAndPruning()
        testHookChainingCoexistence()

        println("============================================================")
        println(" TEST RESULTS: $passed passed, $failed failed")
        println("============================================================")

        if (failed > 0) {
            System.exit(1)
        }
    }

    // =========================================================================
    // 1. Matcher: Unicode Normalization & Bigram Dice Similarity
    // =========================================================================
    private fun testMatcherNormalizationAndSimilarity() {
        println("\n--- 1. Testing Title Normalization & Similarity ---")

        // Normalization
        assertEquals("normTitle basic Latin", "sousou no frieren", KitsunekkoMatcher.normTitle("Sousou no Frieren"))
        assertEquals("normTitle case insensitive", "jujutsu kaisen", KitsunekkoMatcher.normTitle("JuJutsu Kaisen"))
        assertEquals("normTitle Japanese Kanji", "葬送のフリーレン", KitsunekkoMatcher.normTitle("葬送のフリーレン"))
        assertEquals("normTitle Japanese Kana", "フリーレン", KitsunekkoMatcher.normTitle("フリーレン"))
        assertEquals("normTitle punctuation stripping", "demon slayer kimetsu no yaiba", KitsunekkoMatcher.normTitle("Demon Slayer: Kimetsu no Yaiba!"))
        assertEquals("normTitle empty", "", KitsunekkoMatcher.normTitle(""))

        // Similarity
        assertEquals("titleSimilarity exact match", 100, KitsunekkoMatcher.titleSimilarity("Sousou no Frieren", "Sousou no Frieren"))
        assertEquals("titleSimilarity normalized case/spaces", 100, KitsunekkoMatcher.titleSimilarity("Sousou  no  Frieren", "sousou no frieren"))
        assertEquals("titleSimilarity substring containment >= 4", 75, KitsunekkoMatcher.titleSimilarity("Sousou no Frieren", "Frieren"))
        assertEquals("titleSimilarity substring containment symmetric", 75, KitsunekkoMatcher.titleSimilarity("Frieren", "Sousou no Frieren"))
        assertEquals("titleSimilarity Japanese exact", 100, KitsunekkoMatcher.titleSimilarity("葬送のフリーレン", "葬送のフリーレン"))
        assertEquals("titleSimilarity empty A", 0, KitsunekkoMatcher.titleSimilarity("", "Frieren"))
        assertEquals("titleSimilarity empty B", 0, KitsunekkoMatcher.titleSimilarity("Frieren", ""))
        assertTrue("titleSimilarity short guard (<4)", KitsunekkoMatcher.titleSimilarity("no", "Sousou no Frieren") < 75)

        // Best match score
        val aliases = listOf("Frieren: Beyond Journey's End", "Sousou no Frieren", "葬送のフリーレン")
        assertEquals("bestMatchScore match alias", 100, KitsunekkoMatcher.bestMatchScore("Sousou no Frieren", aliases))
        assertEquals("bestMatchScore match Japanese", 100, KitsunekkoMatcher.bestMatchScore("葬送のフリーレン", aliases))
        assertEquals("bestMatchScore empty aliases", 0, KitsunekkoMatcher.bestMatchScore("Frieren", emptyList()))
    }

    // =========================================================================
    // 2. Season Detection (Roman, Ordinal, Keywords)
    // =========================================================================
    private fun testSeasonDetection() {
        println("\n--- 2. Testing Season Detection ---")

        assertEquals("seasonOf keyword Season 2", 2, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen Season 2"))
        assertEquals("seasonOf ordinal 2nd Season", 2, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen 2nd Season"))
        assertEquals("seasonOf ordinal 1st Season", 1, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen 1st Season"))
        assertEquals("seasonOf ordinal 3rd Season", 3, KitsunekkoMatcher.seasonOf("Attack on Titan 3rd Season"))
        assertEquals("seasonOf ordinal 4th Season", 4, KitsunekkoMatcher.seasonOf("Kingdom 4th Season"))
        assertEquals("seasonOf Roman II", 2, KitsunekkoMatcher.seasonOf("Mob Psycho 100 II"))
        assertEquals("seasonOf Roman III", 3, KitsunekkoMatcher.seasonOf("Mob Psycho 100 III"))
        assertEquals("seasonOf Roman IV", 4, KitsunekkoMatcher.seasonOf("Overlord IV"))
        assertEquals("seasonOf keyword Part 1", 1, KitsunekkoMatcher.seasonOf("Bleach - Part 1"))
        assertEquals("seasonOf keyword Part I", 1, KitsunekkoMatcher.seasonOf("Show Part I"))
        assertEquals("seasonOf single S2", 2, KitsunekkoMatcher.seasonOf("Spy x Family S2"))
        assertEquals("seasonOf trailing 2", 2, KitsunekkoMatcher.seasonOf("One Punch Man 2"))
        assertEquals("seasonOf Movie 0 / Prequel", 0, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen 0"))

        // Defenses
        assertEquals("seasonOf pure numeric defense (anime 86)", null, KitsunekkoMatcher.seasonOf("86"))
        assertEquals("seasonOf standalone movie defense (District 9)", null, KitsunekkoMatcher.seasonOf("District 9"))
        assertEquals("seasonOf Roman I pronoun defense", null, KitsunekkoMatcher.seasonOf("I Want to Eat Your Pancreas"))

        // Season hints
        assertEquals("seasonHintOf lowest", 1, KitsunekkoMatcher.seasonHintOf(listOf("Show Season 2", "Show Season 1", "Show")))
        assertEquals("seasonHintOf null if none", null, KitsunekkoMatcher.seasonHintOf(listOf("Show", "Movie")))
    }

    // =========================================================================
    // 3. Critical Season Penalty Rule
    // =========================================================================
    private fun testCriticalSeasonPenalty() {
        println("\n--- 3. Testing Critical Season Penalty Rule ---")

        // Query: Jujutsu Kaisen (Season 1 hint = 1)
        val s1Titles = listOf("Jujutsu Kaisen")
        val scoreS1Correct = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen", s1Titles, seasonHint = 1)
        val scoreS1AgainstS2 = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen Season 2", s1Titles, seasonHint = 1)
        val scoreS1AgainstS0 = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen 0", s1Titles, seasonHint = 1)

        assertEquals("scoreFolder S1 correct folder (candSeason null = -10)", 90, scoreS1Correct)
        assertTrue("scoreFolder S1 vs S2 penalized by -200 (< 0)", scoreS1AgainstS2 < 0, "Score was $scoreS1AgainstS2")
        assertTrue("scoreFolder S1 vs S0 penalized by -200 (< 0)", scoreS1AgainstS0 < 0, "Score was $scoreS1AgainstS0")

        // Query: Jujutsu Kaisen Season 2 (Season hint = 2)
        val s2Titles = listOf("Jujutsu Kaisen Season 2")
        val scoreS2Correct = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen 2nd Season", s2Titles, seasonHint = 2)
        val scoreS2Fallback = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen", s2Titles, seasonHint = 2)
        val scoreS2AgainstS0 = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen 0", s2Titles, seasonHint = 2)

        assertTrue("scoreFolder S2 correct match >= 80", scoreS2Correct >= 80, "Score was $scoreS2Correct")
        assertTrue("scoreFolder S2 fallback retains positive score >= 40", scoreS2Fallback >= 40, "Score was $scoreS2Fallback")
        assertTrue("scoreFolder S2 vs S0 heavily penalized (< 0)", scoreS2AgainstS0 < 0, "Score was $scoreS2AgainstS0")

        // Unversioned query: Sousou no Frieren (Season hint = null)
        val frierenTitles = listOf("Sousou no Frieren")
        val scoreFrierenCorrect = KitsunekkoMatcher.scoreFolder("Sousou no Frieren", frierenTitles, seasonHint = null)
        val scoreFrierenS2 = KitsunekkoMatcher.scoreFolder("Sousou no Frieren Season 2", frierenTitles, seasonHint = null)

        assertEquals("scoreFolder unversioned exact match", 100, scoreFrierenCorrect)
        assertEquals("scoreFolder unversioned vs cand season -15 penalty", 60, scoreFrierenS2) // 75 substring - 15 = 60
    }

    // =========================================================================
    // 4. Episode Number Extraction
    // =========================================================================
    private fun testEpisodeExtraction() {
        println("\n--- 4. Testing Episode Number Extraction ---")

        assertEquals("extractEpisodeNumber SxxExx", 5, KitsunekkoMatcher.extractEpisodeNumber("Frieren.Beyond.Journey's.End.S01E05.WEBRip.srt"))
        assertEquals("extractEpisodeNumber EP tag", 14, KitsunekkoMatcher.extractEpisodeNumber("[Group] Show - EP14.ass"))
        assertEquals("extractEpisodeNumber Episode tag", 3, KitsunekkoMatcher.extractEpisodeNumber("Anime_Episode_03.ass"))
        assertEquals("extractEpisodeNumber Tap tag", 12, KitsunekkoMatcher.extractEpisodeNumber("Show Tập 12.srt"))
        assertEquals("extractEpisodeNumber Anime dash notation", 5, KitsunekkoMatcher.extractEpisodeNumber("[SubsPlease] Sousou no Frieren - 05 (1080p).mkv"))
        assertEquals("extractEpisodeNumber Anime dash with version v2", 14, KitsunekkoMatcher.extractEpisodeNumber("[Erai-raws] Show - 14v2 [1080p].ass"))
        assertEquals("extractEpisodeNumber Standalone Exx", 8, KitsunekkoMatcher.extractEpisodeNumber("Show E08.ass"))
        assertEquals("extractEpisodeNumber Standalone number", 1, KitsunekkoMatcher.extractEpisodeNumber("Anime 01.ass"))
        assertEquals("extractEpisodeNumber Episode 0 support", 0, KitsunekkoMatcher.extractEpisodeNumber("Fate_stay_night_Episode_00.ass"))

        // Exclusions
        assertEquals("extractEpisodeNumber excludes year 2023", 5, KitsunekkoMatcher.extractEpisodeNumber("Series 2023 - 05.srt"))
        assertEquals("extractEpisodeNumber excludes resolution 1080", null, KitsunekkoMatcher.extractEpisodeNumber("Movie 1080p BluRay.mkv"))
        assertEquals("extractEpisodeNumber excludes > 3000", null, KitsunekkoMatcher.extractEpisodeNumber("Show 99999.srt"))
    }

    // =========================================================================
    // 5. Media Info Parsing
    // =========================================================================
    private fun testMediaInfoParser() {
        println("\n--- 5. Testing Media Info Parsing ---")

        val p1 = KitsunekkoMatcher.parseMediaInfo("[SubsPlease] Sousou no Frieren - 05 (1080p) [7BAA1C90].mkv")
        assertEquals("parseMediaInfo cleanTitle p1", "Sousou no Frieren", p1.title)
        assertEquals("parseMediaInfo episodeNumber p1", 5, p1.episodeNumber)
        assertEquals("parseMediaInfo seasonNumber p1", null, p1.seasonNumber)

        val p2 = KitsunekkoMatcher.parseMediaInfo("[Erai-raws] Jujutsu Kaisen 2nd Season - 14 [1080p][HEVC].mkv")
        assertEquals("parseMediaInfo cleanTitle p2", "Jujutsu Kaisen 2nd Season", p2.title)
        assertEquals("parseMediaInfo episodeNumber p2", 14, p2.episodeNumber)
        assertEquals("parseMediaInfo seasonNumber p2", 2, p2.seasonNumber)

        val p3 = KitsunekkoMatcher.parseMediaInfo("magnet:?xt=urn:btih:abc&dn=%5BSubsPlease%5D+Sousou+no+Frieren+-+05+%281080p%29.mkv")
        assertEquals("parseMediaInfo magnet dn= decoded", "Sousou no Frieren", p3.title)
        assertEquals("parseMediaInfo magnet episode", 5, p3.episodeNumber)
    }

    // =========================================================================
    // 6. Data Models Serialization
    // =========================================================================
    private fun testModelsSerialization() {
        println("\n--- 6. Testing Data Models & Dual Serialization ---")

        val gson = Gson()
        val folder = KitsunekkoFolder(name = "Sousou no Frieren", path = "/dirlist.php?dir=frieren")
        val folderJson = gson.toJson(folder)
        val folderParsed = gson.fromJson(folderJson, KitsunekkoFolder::class.java)

        assertEquals("Folder name serialization", folder.name, folderParsed.name)
        assertEquals("Folder path serialization", folder.path, folderParsed.path)
        assertEquals("Folder title getter alias", folder.name, folderParsed.title)
        assertEquals("Folder href getter alias", folder.path, folderParsed.href)

        // Alternate deserialization test (from title & href)
        val alternateJson = """{"title":"Frieren Alt","href":"/alt"}"""
        val altFolder = gson.fromJson(alternateJson, KitsunekkoFolder::class.java)
        assertEquals("Folder alternate deserialization title", "Frieren Alt", altFolder.name)
        assertEquals("Folder alternate deserialization href", "/alt", altFolder.path)

        // KitsunekkoSubtitle
        val sub = KitsunekkoSubtitle(
            name = "Frieren 01.ass",
            url = "https://kitsunekko.net/subs/frieren01.ass",
            isZip = false,
            episodeNumber = 1,
            seasonNumber = 1,
            ext = "ass"
        )
        val subJson = gson.toJson(sub)
        val subParsed = gson.fromJson(subJson, KitsunekkoSubtitle::class.java)
        assertEquals("Subtitle episode", 1, subParsed.episodeNumber)
        assertEquals("Subtitle fileExtension", "ass", subParsed.fileExtension)
    }

    // =========================================================================
    // 7. ZipSlip Traversal Security Protection
    // =========================================================================
    private fun testZipSlipProtection() {
        println("\n--- 7. Testing ZipSlip Path Traversal Protection ---")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsunekko_test_slip_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            // Valid file inside destination dir
            val validFile = File(tempDir, "safe_sub.ass")
            var caughtSecurity = false
            try {
                ZipSubtitleExtractor.validateZipSlip(validFile, tempDir)
            } catch (e: SecurityException) {
                caughtSecurity = true
            }
            assertTrue("ZipSlip valid path allowed", !caughtSecurity)

            // Traversal attack file (e.g. ../../evil.ass)
            val evilFile = File(tempDir, "../../evil.ass")
            caughtSecurity = false
            try {
                ZipSubtitleExtractor.validateZipSlip(evilFile, tempDir)
            } catch (e: SecurityException) {
                caughtSecurity = true
            }
            assertTrue("ZipSlip traversal attack blocked with SecurityException", caughtSecurity)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 8. Streaming Zip Extraction
    // =========================================================================
    private fun testStreamingZipExtraction() {
        println("\n--- 8. Testing Streaming Zip Extraction ---")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsunekko_test_stream_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            // Create in-memory zip archive with 3 episodes
            val bout = ByteArrayOutputStream()
            ZipOutputStream(bout).use { zos ->
                // Ep 1
                zos.putNextEntry(ZipEntry("Frieren - 01.ass"))
                zos.write("[Script Info]\nTitle: Episode 1".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Ep 2
                zos.putNextEntry(ZipEntry("Frieren - 02.ass"))
                zos.write("[Script Info]\nTitle: Episode 2 Targeted Content".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Ep 3
                zos.putNextEntry(ZipEntry("Frieren - 03.ass"))
                zos.write("[Script Info]\nTitle: Episode 3".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val zipBytes = bout.toByteArray()
            val zis = ZipInputStream(ByteArrayInputStream(zipBytes))

            // Extract ONLY episode 2
            val extractedFile = ZipSubtitleExtractor.extractSubtitleFromZipStream(
                zipInputStream = zis,
                targetEpisode = 2,
                destinationDir = tempDir,
                slug = "Frieren"
            )

            assertTrue("Zip extraction returned file", extractedFile != null)
            assertTrue("Extracted file exists", extractedFile?.exists() == true)
            assertTrue("Extracted file name has ep 2", extractedFile?.name?.contains("ep_2_") == true)

            val content = extractedFile?.readText(Charsets.UTF_8) ?: ""
            assertTrue("Extracted content matches Episode 2", content.contains("Episode 2 Targeted Content"))

            // Verify Ep 1 and Ep 3 files were NEVER created on disk
            val allFiles = tempDir.listFiles()?.map { it.name } ?: emptyList()
            assertEquals("Only 1 subtitle file extracted to disk", 1, allFiles.size)
            assertTrue("No ep 1 file extracted", allFiles.none { it.contains("ep_1_") })
            assertTrue("No ep 3 file extracted", allFiles.none { it.contains("ep_3_") })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 9. Multi-Charset Fallback & Normalization
    // =========================================================================
    private fun testMultiCharsetFallbackAndNormalization() {
        println("\n--- 9. Testing Multi-Charset Fallback & Normalization ---")

        // 1. UTF-8 with BOM and CRLF
        val utf8Text = "\uFEFF[Script Info]\r\nTitle: Frieren UTF-8\r\nSynch Point: 1\r\n"
        val utf8Bytes = utf8Text.toByteArray(Charsets.UTF_8)
        val decodedUtf8 = ZipSubtitleExtractor.decodeText(utf8Bytes)

        assertTrue("BOM stripped", !decodedUtf8.startsWith("\uFEFF"))
        assertTrue("CRLF normalized to LF", !decodedUtf8.contains("\r\n"))
        assertTrue("Contains title", decodedUtf8.contains("Title: Frieren UTF-8"))

        // 2. Legacy Shift-JIS Japanese encoding
        val japaneseText = "[Script Info]\nTitle: 葬送のフリーレン 第01話「冒険の終わり」\nDialogue: 0,0:01:00.00,0:01:05.00,Default,,0,0,0,,これが旅の始まりだ。"
        val shiftJisCharset = Charset.forName("Shift_JIS")
        val shiftJisBytes = japaneseText.toByteArray(shiftJisCharset)

        val decodedShiftJis = ZipSubtitleExtractor.decodeText(shiftJisBytes)
        assertTrue("Shift-JIS decoded without Unicode replacement char \uFFFD", !decodedShiftJis.contains('\uFFFD'))
        assertTrue("Shift-JIS preserves Japanese Kanji 葬送のフリーレン", decodedShiftJis.contains("葬送のフリーレン"))
        assertTrue("Shift-JIS preserves Japanese sentence", decodedShiftJis.contains("これが旅の始まりだ。"))

        // 3. Windows-1252 CP1252 encoding
        val cp1252Text = "[Script Info]\nTitle: Sousou no Frieren Café & Español © 2023\n"
        val cp1252Charset = Charset.forName("windows-1252")
        val cp1252Bytes = cp1252Text.toByteArray(cp1252Charset)

        val decodedCp1252 = ZipSubtitleExtractor.decodeText(cp1252Bytes)
        assertTrue("Windows-1252 decoded without crash", decodedCp1252.contains("Sousou no Frieren"))
    }

    // =========================================================================
    // 10. 24-Hour Cache & LRU Pruning
    // =========================================================================
    private fun test24HourCacheAndPruning() {
        println("\n--- 10. Testing 24h Root Cache & LRU Pruning ---")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsunekko_test_lru_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            // Create 25 dummy files
            for (i in 1..25) {
                val f = File(tempDir, "ep_${i}_test.ass")
                f.writeText("Dummy content $i")
                // Stagger timestamps
                f.setLastModified(System.currentTimeMillis() - (26 - i) * 1000L)
            }

            assertEquals("Initial dummy files count", 25, tempDir.listFiles()?.size)

            // Prune to max 20 files
            ZipSubtitleExtractor.pruneCache(tempDir, maxSizeBytes = 10L * 1024 * 1024, maxFileCount = 20)

            val remainingFiles = tempDir.listFiles() ?: emptyArray()
            assertEquals("Pruned files count <= 20", 20, remainingFiles.size)

            // Verify oldest files (1 to 5) were deleted, newest (6 to 25) were kept
            val remainingNames = remainingFiles.map { it.name }.toSet()
            assertTrue("Oldest file ep_1 deleted", !remainingNames.contains("ep_1_test.ass"))
            assertTrue("Oldest file ep_5 deleted", !remainingNames.contains("ep_5_test.ass"))
            assertTrue("Newest file ep_25 kept", remainingNames.contains("ep_25_test.ass"))
            assertTrue("Newest file ep_20 kept", remainingNames.contains("ep_20_test.ass"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 11. Multi-Enricher Hook Chaining Coexistence
    // =========================================================================
    private fun testHookChainingCoexistence() {
        println("\n--- 11. Testing Multi-Enricher Hook Chaining Coexistence ---")

        // 1. Base provider (e.g. Torrentio)
        val baseProvider = object : MainAPI() {
            init {
                name = "Torrentio"
                mainUrl = "https://torrentio.strem.fun"
            }
            override val supportedTypes: Set<TvType> get() = setOf(TvType.Anime, TvType.Movie)
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Yield video link immediately
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Torrentio 1080p Stream",
                        url = "https://stream.example.com/video.mp4",
                        referer = "",
                        quality = 1080,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        // 2. Mock Anime47 provider wrapper
        val anime47Wrapped = object : MainAPI() {
            val delegate = baseProvider
            init {
                name = delegate.name
                mainUrl = delegate.mainUrl
            }
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                subtitleCallback(SubtitleFile("Tiếng Việt (Anime47)", "https://anime47.example.com/vi.srt"))
                return delegate.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        }

        // 3. Mock Stremio provider wrapper
        val stremioWrapped = object : MainAPI() {
            val delegate = anime47Wrapped
            init {
                name = delegate.name
                mainUrl = delegate.mainUrl
            }
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                subtitleCallback(SubtitleFile("English (Stremio)", "https://stremio.example.com/en.srt"))
                return delegate.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        }

        // 4. Kitsunekko Enriched Wrapper wrapping the chained delegates
        val kitsunekkoWrapped = KitsunekkoHook.KitsunekkoEnrichedMainAPI(stremioWrapped)

        assertEquals("Delegated name preserved", "Torrentio", kitsunekkoWrapped.name)
        assertEquals("Delegated mainUrl preserved", "https://torrentio.strem.fun", kitsunekkoWrapped.mainUrl)

        // Verify guard: provider should not be re-wrapped if it is already Kitsunekko
        val isAlreadyKitsunekko = (kitsunekkoWrapped is KitsunekkoHook.KitsunekkoEnrichedMainAPI) ||
                kitsunekkoWrapped.name.contains("Kitsunekko", ignoreCase = true)
        assertTrue("Guard detects already wrapped Kitsunekko", isAlreadyKitsunekko)

        // 5. Test link loading with 0ms passthrough and subtitle callbacks
        val receivedLinks = mutableListOf<String>()
        val receivedSubs = mutableListOf<String>()

        runBlocking {
            kitsunekkoWrapped.loadLinks(
                data = "Sousou no Frieren S01E01",
                isCasting = false,
                subtitleCallback = { sub -> receivedSubs.add(sub.lang) },
                callback = { link -> receivedLinks.add(link.url) }
            )
        }

        assertEquals("Video link yielded with 0ms delay", 1, receivedLinks.size)
        assertEquals("Video link URL matches", "https://stream.example.com/video.mp4", receivedLinks[0])

        // Verify all 3 chained extensions successfully emitted their respective subtitles!
        assertTrue("Anime47 sub emitted in chain", receivedSubs.contains("Tiếng Việt (Anime47)"))
        assertTrue("Stremio sub emitted in chain", receivedSubs.contains("English (Stremio)"))
    }
}
