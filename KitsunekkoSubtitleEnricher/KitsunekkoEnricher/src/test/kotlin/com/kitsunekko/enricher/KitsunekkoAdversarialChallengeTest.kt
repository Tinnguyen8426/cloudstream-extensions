package com.kitsunekko.enricher

import java.text.Normalizer

/**
 * Empirical Adversarial Test Harness for KitsunekkoMatcher.
 * Stress-tests title normalization, Bigram Dice similarity, season hint detection,
 * critical season penalty calculations, folder ranking, episode parsing,
 * and media info extraction across complex edge cases and hostile inputs.
 */
object KitsunekkoAdversarialChallengeTest {

    private var passed = 0
    private var failed = 0
    private val failureMessages = mutableListOf<String>()

    private fun check(category: String, name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("  [PASS] [$category] $name")
            passed++
        } else {
            val msg = "  [FAIL] [$category] $name -> $detail"
            System.err.println(msg)
            failureMessages.add(msg)
            failed++
        }
    }

    private fun assertEquals(category: String, name: String, expected: Any?, actual: Any?) {
        check(category, name, expected == actual, "Expected: <$expected> but was: <$actual>")
    }

    private fun assertTrue(category: String, name: String, condition: Boolean, msg: String = "") {
        check(category, name, condition, "Expected true but was false: $msg")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println("   EMPIRICAL ADVERSARIAL CHALLENGE HARNESS: KitsunekkoMatcher.kt")
        println("================================================================================")

        val cat1 = testFansubFormattingAndPunctuation()
        val cat2 = testUnicodeAndJapaneseScripts()
        val cat3 = testTitleSimilarityEdgeCases()
        val cat4 = testSeasonHintDetection()
        val cat5 = testCriticalSeasonPenaltyRule()
        val cat6 = testEpisodeParsingAndExclusions()
        val cat7 = testMediaInfoParserRobustness()

        println("\n================================================================================")
        println(" ADVERSARIAL TEST SUMMARY:")
        println(" Category 1 (Fansub Formatting & Punctuation): ${cat1.first} passed, ${cat1.second} failed")
        println(" Category 2 (Unicode & Japanese Scripts):     ${cat2.first} passed, ${cat2.second} failed")
        println(" Category 3 (Similarity Edge Cases):          ${cat3.first} passed, ${cat3.second} failed")
        println(" Category 4 (Season Hint Detection):          ${cat4.first} passed, ${cat4.second} failed")
        println(" Category 5 (Critical Season Penalty):        ${cat5.first} passed, ${cat5.second} failed")
        println(" Category 6 (Episode Parsing & Exclusions):   ${cat6.first} passed, ${cat6.second} failed")
        println(" Category 7 (Media Info Parser):              ${cat7.first} passed, ${cat7.second} failed")
        println("--------------------------------------------------------------------------------")
        println(" TOTAL: $passed passed, $failed failed across ${passed + failed} adversarial scenarios")
        println("================================================================================")

        if (failureMessages.isNotEmpty()) {
            println("\nCONFIRMED FAILURE MODES:")
            failureMessages.forEach { println(it) }
            System.exit(1)
        } else {
            println("\nALL ADVERSARIAL STRESS TESTS PASSED SUCCESSFULLY!")
        }
    }

    // =========================================================================
    // Category 1: Fansub Formatting, Brackets & Complex Punctuation
    // =========================================================================
    private fun testFansubFormattingAndPunctuation(): Pair<Int, Int> {
        val startP = passed
        val startF = failed
        val cat = "Fansub & Punctuation"
        println("\n--- Testing Category 1: Fansub Formatting & Punctuation ---")

        // Leading fansub group stripping in parseMediaInfo
        val m1 = KitsunekkoMatcher.parseMediaInfo("[SubsPlease] Sousou no Frieren - 05 (1080p) [7BAA1C90].mkv")
        assertEquals(cat, "SubsPlease stripped", "Sousou no Frieren", m1.title)
        assertEquals(cat, "m1 episode extracted", 5, m1.episodeNumber)

        val m2 = KitsunekkoMatcher.parseMediaInfo("(HorribleSubs) Jujutsu Kaisen - 01 [720p].mkv")
        assertEquals(cat, "HorribleSubs stripped", "Jujutsu Kaisen", m2.title)
        assertEquals(cat, "m2 episode extracted", 1, m2.episodeNumber)

        val m3 = KitsunekkoMatcher.parseMediaInfo("[Erai-raws] [Multi-Sub] Kimetsu no Yaiba - 26 [1080p].mkv")
        assertEquals(cat, "Double bracket release group stripped", "Kimetsu no Yaiba", m3.title)
        assertEquals(cat, "m3 episode extracted", 26, m3.episodeNumber)

        val m4 = KitsunekkoMatcher.parseMediaInfo("[Underwater-FFF] Fate Zero - 01 (BD 1080p).mkv")
        assertEquals(cat, "Hyphenated fansub group stripped", "Fate Zero", m4.title)

        val m5 = KitsunekkoMatcher.parseMediaInfo("[Coalgirls] Toradora! - 25 [1080p].mkv")
        assertEquals(cat, "Coalgirls stripped", "Toradora!", m5.title)

        // CRC-32 and Checksum stripping
        val m6 = KitsunekkoMatcher.parseMediaInfo("[Group] Show - 04 [CRC32].mkv")
        assertEquals(cat, "CRC32 stripped", "Show", m6.title)

        val m7 = KitsunekkoMatcher.parseMediaInfo("[Group] Show - 04 [CRC-32].mkv")
        assertEquals(cat, "CRC-32 with hyphen stripped", "Show", m7.title)

        val m8 = KitsunekkoMatcher.parseMediaInfo("[Group] Show - 04 [12345678].mkv")
        assertEquals(cat, "Hex checksum stripped", "Show", m8.title)

        // Punctuation in titles
        assertEquals(cat, "normTitle colon stripped", "re zero kara hajimeru isekai seikatsu", KitsunekkoMatcher.normTitle("Re:Zero kara Hajimeru Isekai Seikatsu"))
        assertEquals(cat, "normTitle semicolon stripped", "steins gate", KitsunekkoMatcher.normTitle("Steins;Gate"))
        assertEquals(cat, "normTitle exclamation stripped", "k on", KitsunekkoMatcher.normTitle("K-On!!"))
        assertEquals(cat, "normTitle slash stripped", "fate stay night", KitsunekkoMatcher.normTitle("Fate/stay night"))
        assertEquals(cat, "normTitle dot stripped", "hack sign", KitsunekkoMatcher.normTitle(".hack//Sign"))
        assertEquals(cat, "normTitle apostrophe stripped", "frieren beyond journey s end", KitsunekkoMatcher.normTitle("Frieren: Beyond Journey's End"))
        assertEquals(cat, "normTitle tilde stripped", "working", KitsunekkoMatcher.normTitle("Working!!~"))
        assertEquals(cat, "normTitle multiple hyphens collapsed", "sword art online", KitsunekkoMatcher.normTitle("Sword-Art-Online"))
        assertEquals(cat, "normTitle whitespace tabs collapsed", "sousou no frieren", KitsunekkoMatcher.normTitle("Sousou \t\n no   Frieren "))

        return (passed - startP) to (failed - startF)
    }

    // =========================================================================
    // Category 2: Unicode & Japanese Scripts (Kanji, Kana, NFC/NFD)
    // =========================================================================
    private fun testUnicodeAndJapaneseScripts(): Pair<Int, Int> {
        val startP = passed
        val startF = failed
        val cat = "Unicode & Japanese"
        println("\n--- Testing Category 2: Unicode & Japanese Scripts ---")

        // Native Japanese Kanji
        assertEquals(cat, "Kanji preservation 葬送のフリーレン", "葬送のフリーレン", KitsunekkoMatcher.normTitle("葬送のフリーレン"))
        assertEquals(cat, "Kanji preservation 呪術廻戦", "呪術廻戦", KitsunekkoMatcher.normTitle("呪術廻戦"))
        assertEquals(cat, "Kanji preservation 鬼滅の刃", "鬼滅の刃", KitsunekkoMatcher.normTitle("鬼滅の刃"))
        assertEquals(cat, "Kanji preservation 進撃の巨人", "進撃の巨人", KitsunekkoMatcher.normTitle("進撃の巨人"))
        assertEquals(cat, "Kanji preservation 僕のヒーローアカデミア", "僕のヒーローアカデミア", KitsunekkoMatcher.normTitle("僕のヒーローアカデミア"))

        // Katakana prolonged sound mark (Chōonpu U+30FC)
        assertEquals(cat, "Chōonpu in Katakana フリーレン", "フリーレン", KitsunekkoMatcher.normTitle("フリーレン"))

        // Japanese Nakaguro (U+30FB) and fullwidth spaces (U+3000)
        assertEquals(cat, "Nakaguro replacement 懐玉・玉折", "呪術廻戦 懐玉 玉折", KitsunekkoMatcher.normTitle("呪術廻戦　懐玉・玉折"))

        // Zenkaku / Fullwidth Latin and Digits under NFC
        // Note: Form.NFC preserves fullwidth characters in Unicode category \p{L}.
        val zenkakuLatin = "Ｆｒｉｅｒｅｎ"
        assertEquals(cat, "Zenkaku Latin lowercase under NFC", "ｆｒｉｅｒｅｎ", KitsunekkoMatcher.normTitle(zenkakuLatin))
        assertEquals(cat, "Zenkaku self-similarity", 100, KitsunekkoMatcher.titleSimilarity("Ｆｒｉｅｒｅｎ", "Ｆｒｉｅｒｅｎ"))

        // Vietnamese Latin with diacritics
        assertEquals(cat, "Vietnamese NFC", "chú thuật hồi chiến", KitsunekkoMatcher.normTitle("Chú Thuật Hồi Chiến"))
        val vietnameseNfd = Normalizer.normalize("Chú Thuật Hồi Chiến", Normalizer.Form.NFD)
        assertEquals(cat, "Vietnamese NFD normalized", "chú thuật hồi chiến", KitsunekkoMatcher.normTitle(vietnameseNfd))
        assertEquals(cat, "Vietnamese NFC vs NFD similarity", 100, KitsunekkoMatcher.titleSimilarity("Chú Thuật Hồi Chiến", vietnameseNfd))

        // CJK cross-similarity
        assertEquals(cat, "Japanese exact title similarity", 100, KitsunekkoMatcher.titleSimilarity("葬送のフリーレン", "葬送のフリーレン"))
        assertEquals(cat, "Japanese substring title similarity", 75, KitsunekkoMatcher.titleSimilarity("葬送のフリーレン", "フリーレン"))
        assertEquals(cat, "Japanese symmetric substring similarity", 75, KitsunekkoMatcher.titleSimilarity("フリーレン", "葬送のフリーレン"))

        return (passed - startP) to (failed - startF)
    }

    // =========================================================================
    // Category 3: Title Similarity Edge Cases
    // =========================================================================
    private fun testTitleSimilarityEdgeCases(): Pair<Int, Int> {
        val startP = passed
        val startF = failed
        val cat = "Similarity Edge Cases"
        println("\n--- Testing Category 3: Title Similarity Edge Cases ---")

        // Exact equality
        assertEquals(cat, "Exact equality standard", 100, KitsunekkoMatcher.titleSimilarity("Attack on Titan", "Attack on Titan"))
        assertEquals(cat, "Exact equality case-insensitive", 100, KitsunekkoMatcher.titleSimilarity("ATTACK ON TITAN", "attack on titan"))
        assertEquals(cat, "Exact equality irregular spacing", 100, KitsunekkoMatcher.titleSimilarity("Attack   on   Titan", "Attack on Titan"))

        // Substring containment rule (75 points)
        assertEquals(cat, "Substring containment A in B", 75, KitsunekkoMatcher.titleSimilarity("Sousou no Frieren", "Frieren"))
        assertEquals(cat, "Substring containment B in A", 75, KitsunekkoMatcher.titleSimilarity("Frieren", "Sousou no Frieren"))
        assertEquals(cat, "Substring containment JJK", 75, KitsunekkoMatcher.titleSimilarity("Jujutsu Kaisen Season 2", "Jujutsu Kaisen"))

        // Short strings (< 4 chars) protection against spurious 75 pts
        assertTrue(cat, "Short string 'no' protected (<75)", KitsunekkoMatcher.titleSimilarity("no", "Sousou no Frieren") < 75)
        assertTrue(cat, "Short string 'the' protected (<75)", KitsunekkoMatcher.titleSimilarity("the", "The Rising of the Shield Hero") < 75)
        assertTrue(cat, "Short string 'in' protected (<75)", KitsunekkoMatcher.titleSimilarity("in", "Made in Abyss") < 75)

        // Single character equality & mismatch
        assertEquals(cat, "Single char match", 100, KitsunekkoMatcher.titleSimilarity("X", "x"))
        assertEquals(cat, "Single char mismatch", 0, KitsunekkoMatcher.titleSimilarity("A", "B"))

        // Empty and blank strings
        assertEquals(cat, "Empty A", 0, KitsunekkoMatcher.titleSimilarity("", "Sousou no Frieren"))
        assertEquals(cat, "Empty B", 0, KitsunekkoMatcher.titleSimilarity("Sousou no Frieren", ""))
        assertEquals(cat, "Both empty", 0, KitsunekkoMatcher.titleSimilarity("", ""))
        assertEquals(cat, "Whitespace only A", 0, KitsunekkoMatcher.titleSimilarity("   ", "Sousou no Frieren"))
        assertEquals(cat, "Whitespace only B", 0, KitsunekkoMatcher.titleSimilarity("Sousou no Frieren", "   "))
        assertEquals(cat, "Both whitespace", 0, KitsunekkoMatcher.titleSimilarity("   ", "   "))

        // Best match score
        val candidateList = listOf("Sousou no Frieren", "葬送のフリーレン", "Frieren: Beyond Journey's End")
        assertEquals(cat, "bestMatchScore Romaji", 100, KitsunekkoMatcher.bestMatchScore("Sousou no Frieren", candidateList))
        assertEquals(cat, "bestMatchScore Japanese", 100, KitsunekkoMatcher.bestMatchScore("葬送のフリーレン", candidateList))
        assertEquals(cat, "bestMatchScore English alias", 100, KitsunekkoMatcher.bestMatchScore("Frieren: Beyond Journey's End", candidateList))
        assertEquals(cat, "bestMatchScore partial substring", 75, KitsunekkoMatcher.bestMatchScore("Frieren", candidateList))
        assertEquals(cat, "bestMatchScore empty candidate list", 0, KitsunekkoMatcher.bestMatchScore("Frieren", emptyList()))
        assertEquals(cat, "bestMatchScore blank candidate list", 0, KitsunekkoMatcher.bestMatchScore("Frieren", listOf("", "   ")))

        return (passed - startP) to (failed - startF)
    }

    // =========================================================================
    // Category 4: Season Hint Detection (Roman, Ordinal, Keywords, Guards)
    // =========================================================================
    private fun testSeasonHintDetection(): Pair<Int, Int> {
        val startP = passed
        val startF = failed
        val cat = "Season Hint"
        println("\n--- Testing Category 4: Season Hint Detection ---")

        // Roman numerals (I..IX)
        assertEquals(cat, "Roman I with keyword Season I", 1, KitsunekkoMatcher.seasonOf("Show Season I"))
        assertEquals(cat, "Roman I with keyword Part I", 1, KitsunekkoMatcher.seasonOf("Show Part I"))
        assertEquals(cat, "Roman I trailing", 1, KitsunekkoMatcher.seasonOf("Overlord I"))
        assertEquals(cat, "Roman II", 2, KitsunekkoMatcher.seasonOf("Mob Psycho 100 II"))
        assertEquals(cat, "Roman III", 3, KitsunekkoMatcher.seasonOf("Mob Psycho 100 III"))
        assertEquals(cat, "Roman IV", 4, KitsunekkoMatcher.seasonOf("Overlord IV"))
        assertEquals(cat, "Roman V", 5, KitsunekkoMatcher.seasonOf("Symphogear V"))
        assertEquals(cat, "Roman VI", 6, KitsunekkoMatcher.seasonOf("Show VI"))
        assertEquals(cat, "Roman VII", 7, KitsunekkoMatcher.seasonOf("Show VII"))
        assertEquals(cat, "Roman VIII", 8, KitsunekkoMatcher.seasonOf("Show VIII"))
        assertEquals(cat, "Roman IX", 9, KitsunekkoMatcher.seasonOf("Show IX"))

        // Roman numeral I pronoun defense (Must NOT be season 1)
        assertEquals(cat, "Roman I pronoun 'I Want to Eat Your Pancreas'", null, KitsunekkoMatcher.seasonOf("I Want to Eat Your Pancreas"))
        assertEquals(cat, "Roman I pronoun 'I Shall Survive Using Potions!'", null, KitsunekkoMatcher.seasonOf("I Shall Survive Using Potions!"))
        assertEquals(cat, "Roman I pronoun 'I'm in Love with the Villainess'", null, KitsunekkoMatcher.seasonOf("I'm in Love with the Villainess"))

        // Ordinals (1st..5th, Cour, Part)
        assertEquals(cat, "Ordinal 1st Season", 1, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen 1st Season"))
        assertEquals(cat, "Ordinal 2nd Season", 2, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen 2nd Season"))
        assertEquals(cat, "Ordinal 3rd Season", 3, KitsunekkoMatcher.seasonOf("Attack on Titan 3rd Season"))
        assertEquals(cat, "Ordinal 4th Season", 4, KitsunekkoMatcher.seasonOf("Kingdom 4th Season"))
        assertEquals(cat, "Ordinal 5th Season", 5, KitsunekkoMatcher.seasonOf("Major 5th Season"))
        assertEquals(cat, "Ordinal 2nd Cour", 2, KitsunekkoMatcher.seasonOf("Spy x Family 2nd Cour"))
        assertEquals(cat, "Ordinal 2nd Part", 2, KitsunekkoMatcher.seasonOf("Bleach 2nd Part"))
        assertEquals(cat, "Ordinal trailing 1st", 1, KitsunekkoMatcher.seasonOf("Show 1st"))
        assertEquals(cat, "Ordinal trailing 2nd", 2, KitsunekkoMatcher.seasonOf("Show 2nd"))
        assertEquals(cat, "Ordinal trailing 3rd", 3, KitsunekkoMatcher.seasonOf("Show 3rd"))
        assertEquals(cat, "Ordinal trailing 4th", 4, KitsunekkoMatcher.seasonOf("Show 4th"))
        assertEquals(cat, "Ordinal Lupin the 3rd", 3, KitsunekkoMatcher.seasonOf("Lupin the 3rd"))
        assertEquals(cat, "Ordinal non-season defense 'The 1st Movement'", null, KitsunekkoMatcher.seasonOf("The 1st Movement"))

        // Keyword markers
        assertEquals(cat, "Keyword Season 1", 1, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen Season 1"))
        assertEquals(cat, "Keyword Season 2", 2, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen Season 2"))
        assertEquals(cat, "Keyword Part 1", 1, KitsunekkoMatcher.seasonOf("Bleach Part 1"))
        assertEquals(cat, "Keyword Part 2", 2, KitsunekkoMatcher.seasonOf("Bleach Part 2"))
        assertEquals(cat, "Keyword Cour 2", 2, KitsunekkoMatcher.seasonOf("Sousou no Frieren Cour 2"))
        assertEquals(cat, "Keyword SS1", 1, KitsunekkoMatcher.seasonOf("Show SS1"))
        assertEquals(cat, "Keyword SS2", 2, KitsunekkoMatcher.seasonOf("Show SS2"))
        assertEquals(cat, "Keyword Act 2", 2, KitsunekkoMatcher.seasonOf("Diamond no Ace Act 2"))
        assertEquals(cat, "Keyword Arc 2", 2, KitsunekkoMatcher.seasonOf("Show Arc 2"))

        // Single-letter S
        assertEquals(cat, "Single S2", 2, KitsunekkoMatcher.seasonOf("Spy x Family S2"))
        assertEquals(cat, "Single S1", 1, KitsunekkoMatcher.seasonOf("Spy x Family S1"))
        assertEquals(cat, "Single S02", 2, KitsunekkoMatcher.seasonOf("Show S02"))

        // Trailing numbers & Movie 0 / Prequels
        assertEquals(cat, "Trailing 2 One Punch Man", 2, KitsunekkoMatcher.seasonOf("One Punch Man 2"))
        assertEquals(cat, "Trailing 3 Show", 3, KitsunekkoMatcher.seasonOf("Show 3"))
        assertEquals(cat, "Movie 0 Jujutsu Kaisen 0", 0, KitsunekkoMatcher.seasonOf("Jujutsu Kaisen 0"))
        assertEquals(cat, "Movie 0 Steins Gate 0", 0, KitsunekkoMatcher.seasonOf("Steins;Gate 0"))

        // Numeric Guards (Must NOT be recognized as seasons)
        assertEquals(cat, "Guard anime 86", null, KitsunekkoMatcher.seasonOf("86"))
        assertEquals(cat, "Guard 86 - Eighty Six", null, KitsunekkoMatcher.seasonOf("86 - Eighty Six"))
        assertEquals(cat, "Guard District 9", null, KitsunekkoMatcher.seasonOf("District 9"))
        assertEquals(cat, "Guard Super 8", null, KitsunekkoMatcher.seasonOf("Super 8"))
        assertEquals(cat, "Guard Apollo 13", null, KitsunekkoMatcher.seasonOf("Apollo 13"))
        assertEquals(cat, "Guard Big Hero 6", null, KitsunekkoMatcher.seasonOf("Big Hero 6"))
        assertEquals(cat, "Guard Ocean's 11", null, KitsunekkoMatcher.seasonOf("Ocean's 11"))
        assertEquals(cat, "Guard Oceans 11", null, KitsunekkoMatcher.seasonOf("Oceans 11"))
        assertEquals(cat, "Guard Mob Psycho 100 (3 digits ignored)", null, KitsunekkoMatcher.seasonOf("Mob Psycho 100"))

        // Season hint aggregation
        assertEquals(cat, "seasonHintOf lowest", 1, KitsunekkoMatcher.seasonHintOf(listOf("Show Season 2", "Show Season 1", "Show")))
        assertEquals(cat, "seasonHintOf single", 2, KitsunekkoMatcher.seasonHintOf(listOf("Show Season 2", "Show")))
        assertEquals(cat, "seasonHintOf Movie 0", 0, KitsunekkoMatcher.seasonHintOf(listOf("Jujutsu Kaisen 0", "Jujutsu Kaisen")))
        assertEquals(cat, "seasonHintOf null if none", null, KitsunekkoMatcher.seasonHintOf(listOf("Sousou no Frieren", "Frieren")))

        return (passed - startP) to (failed - startF)
    }

    // =========================================================================
    // Category 5: Critical Season Penalty Rule & Folder Ranking
    // =========================================================================
    private fun testCriticalSeasonPenaltyRule(): Pair<Int, Int> {
        val startP = passed
        val startF = failed
        val cat = "Season Penalty"
        println("\n--- Testing Category 5: Critical Season Penalty Rule ---")

        // 1. Target: Jujutsu Kaisen Season 1 (seasonHint = 1)
        val s1Titles = listOf("Jujutsu Kaisen", "Jujutsu Kaisen Season 1")
        val scoreS1CorrectUnlabeled = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen", s1Titles, seasonHint = 1)
        val scoreS1AgainstS2 = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen Season 2", s1Titles, seasonHint = 1)
        val scoreS1Against2nd = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen 2nd Season", s1Titles, seasonHint = 1)
        val scoreS1AgainstII = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen II", s1Titles, seasonHint = 1)
        val scoreS1AgainstS0 = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen 0", s1Titles, seasonHint = 1)

        // Verifications for S1
        assertEquals(cat, "S1 folder without season label (-10 penalty)", 90, scoreS1CorrectUnlabeled)
        assertTrue(cat, "S1 vs S2 heavily penalized by -200 (< 0)", scoreS1AgainstS2 < 0, "Score was $scoreS1AgainstS2")
        assertTrue(cat, "S1 vs 2nd Season heavily penalized (< 0)", scoreS1Against2nd < 0, "Score was $scoreS1Against2nd")
        assertTrue(cat, "S1 vs II heavily penalized (< 0)", scoreS1AgainstII < 0, "Score was $scoreS1AgainstII")
        assertTrue(cat, "S1 vs Movie 0 heavily penalized (< 0)", scoreS1AgainstS0 < 0, "Score was $scoreS1AgainstS0")

        // 2. Target: Jujutsu Kaisen Season 2 (seasonHint = 2)
        val s2Titles = listOf("Jujutsu Kaisen Season 2")
        val scoreS2Exact = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen Season 2", s2Titles, seasonHint = 2)
        val scoreS2Ordinal = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen 2nd Season", s2Titles, seasonHint = 2)
        val scoreS2Roman = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen II", s2Titles, seasonHint = 2)
        val scoreS2FallbackUnlabeled = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen", s2Titles, seasonHint = 2)
        val scoreS2AgainstS1 = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen Season 1", s2Titles, seasonHint = 2)
        val scoreS2AgainstS0 = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen 0", s2Titles, seasonHint = 2)

        assertEquals(cat, "S2 exact match score 100", 100, scoreS2Exact)
        assertTrue(cat, "S2 Roman II Bigram Dice match >= 70 (threshold 40)", scoreS2Roman >= 70, "Score was $scoreS2Roman")
        val scoreS2RomanWithBase = KitsunekkoMatcher.scoreFolder("Jujutsu Kaisen II", listOf("Jujutsu Kaisen", "Jujutsu Kaisen Season 2"), seasonHint = 2)
        assertEquals(cat, "S2 Roman II with base alias gets substring 75", 75, scoreS2RomanWithBase)
        assertEquals(cat, "S2 fallback unlabeled folder (75 - 10 = 65)", 65, scoreS2FallbackUnlabeled)
        assertTrue(cat, "S2 vs S1 heavily penalized by -200 (< 0)", scoreS2AgainstS1 < 0, "Score was $scoreS2AgainstS1")
        assertTrue(cat, "S2 vs S0 heavily penalized by -200 (< 0)", scoreS2AgainstS0 < 0, "Score was $scoreS2AgainstS0")

        // 3. Target: Unversioned Show (seasonHint = null)
        val frierenTitles = listOf("Sousou no Frieren")
        val scoreFrierenCorrect = KitsunekkoMatcher.scoreFolder("Sousou no Frieren", frierenTitles, seasonHint = null)
        val scoreFrierenAgainstS2 = KitsunekkoMatcher.scoreFolder("Sousou no Frieren Season 2", frierenTitles, seasonHint = null)

        assertEquals(cat, "Unversioned correct match score 100", 100, scoreFrierenCorrect)
        assertEquals(cat, "Unversioned vs candidate Season 2 (-15 penalty: 75 - 15 = 60)", 60, scoreFrierenAgainstS2)

        // 4. Folder Ranking & Rejection verification
        val mockFolders = listOf(
            KitsunekkoFolder(name = "Jujutsu Kaisen", path = "/dirlist.php?dir=jjk"),
            KitsunekkoFolder(name = "Jujutsu Kaisen Season 2", path = "/dirlist.php?dir=jjk2"),
            KitsunekkoFolder(name = "Jujutsu Kaisen 2nd Season", path = "/dirlist.php?dir=jjk2nd"),
            KitsunekkoFolder(name = "Jujutsu Kaisen 0", path = "/dirlist.php?dir=jjk0"),
            KitsunekkoFolder(name = "Bleach", path = "/dirlist.php?dir=bleach")
        )

        // Querying for Season 1: Jujutsu Kaisen Season 2 MUST be rejected!
        val rankedS1 = KitsunekkoMatcher.rankFolders(mockFolders, listOf("Jujutsu Kaisen Season 1"), seasonHint = 1, threshold = 40, limit = 2)
        assertEquals(cat, "rankedS1 returns exactly 1 valid candidate", 1, rankedS1.size)
        assertEquals(cat, "rankedS1 selects 'Jujutsu Kaisen'", "Jujutsu Kaisen", rankedS1[0].first.name)
        assertTrue(cat, "rankedS1 completely excludes Season 2 and 0", rankedS1.none { it.first.name.contains("2") || it.first.name.contains("0") })

        // Querying for Season 2: Jujutsu Kaisen Season 2 & 2nd Season must rank highest!
        val rankedS2 = KitsunekkoMatcher.rankFolders(mockFolders, listOf("Jujutsu Kaisen Season 2"), seasonHint = 2, threshold = 40, limit = 2)
        assertEquals(cat, "rankedS2 returns top 2 candidates", 2, rankedS2.size)
        assertEquals(cat, "rankedS2 rank 1 is 'Jujutsu Kaisen Season 2'", "Jujutsu Kaisen Season 2", rankedS2[0].first.name)
        assertEquals(cat, "rankedS2 rank 2 is 'Jujutsu Kaisen 2nd Season'", "Jujutsu Kaisen 2nd Season", rankedS2[1].first.name)
        assertTrue(cat, "rankedS2 completely excludes Season 0", rankedS2.none { it.first.name == "Jujutsu Kaisen 0" })

        return (passed - startP) to (failed - startF)
    }

    // =========================================================================
    // Category 6: Episode Parsing & False Positive Exclusions
    // =========================================================================
    private fun testEpisodeParsingAndExclusions(): Pair<Int, Int> {
        val startP = passed
        val startF = failed
        val cat = "Episode Parsing"
        println("\n--- Testing Category 6: Episode Parsing & Exclusions ---")

        // 1. SxxExx pattern
        assertEquals(cat, "S01E05", 5, KitsunekkoMatcher.extractEpisodeNumber("Frieren.S01E05.1080p.mkv"))
        assertEquals(cat, "S02E14", 14, KitsunekkoMatcher.extractEpisodeNumber("JJK.S02E14.WEBRip.srt"))
        assertEquals(cat, "S01E00 (Episode 0)", 0, KitsunekkoMatcher.extractEpisodeNumber("Fate.S01E00.mkv"))
        assertEquals(cat, "S01E1000", 1000, KitsunekkoMatcher.extractEpisodeNumber("OnePiece.S01E1000.mkv"))

        // 2. Keyword tags (EP, Episode, Tập, Tap)
        assertEquals(cat, "EP14", 14, KitsunekkoMatcher.extractEpisodeNumber("[Subs] Show - EP14.ass"))
        assertEquals(cat, "ep 05", 5, KitsunekkoMatcher.extractEpisodeNumber("Show - ep 05.ass"))
        assertEquals(cat, "Episode 3", 3, KitsunekkoMatcher.extractEpisodeNumber("Anime Episode 03.ass"))
        assertEquals(cat, "Tập 12", 12, KitsunekkoMatcher.extractEpisodeNumber("Show Tập 12.srt"))
        assertEquals(cat, "Tap 07", 7, KitsunekkoMatcher.extractEpisodeNumber("Show Tap 07.srt"))
        assertEquals(cat, "ep.09", 9, KitsunekkoMatcher.extractEpisodeNumber("Show ep.09.ass"))
        assertEquals(cat, "ep_02", 2, KitsunekkoMatcher.extractEpisodeNumber("Show_ep_02.ass"))
        assertEquals(cat, "ep-11", 11, KitsunekkoMatcher.extractEpisodeNumber("Show-ep-11.ass"))
        assertEquals(cat, "Episode 00", 0, KitsunekkoMatcher.extractEpisodeNumber("Show Episode 00.ass"))

        // 3. Anime dash notation
        assertEquals(cat, "Anime dash - 05", 5, KitsunekkoMatcher.extractEpisodeNumber("[SubsPlease] Sousou no Frieren - 05 (1080p).mkv"))
        assertEquals(cat, "Anime dash with version - 14v2", 14, KitsunekkoMatcher.extractEpisodeNumber("[Erai-raws] Show - 14v2 [1080p].ass"))
        assertEquals(cat, "Anime dash single digit - 1", 1, KitsunekkoMatcher.extractEpisodeNumber("Show - 1.mkv"))
        assertEquals(cat, "Anime dash triple digit - 100", 100, KitsunekkoMatcher.extractEpisodeNumber("Show - 100.mkv"))
        assertEquals(cat, "Anime dash Episode 00", 0, KitsunekkoMatcher.extractEpisodeNumber("Show - 00.mkv"))

        // 4. Standalone E notation
        assertEquals(cat, "Standalone E08", 8, KitsunekkoMatcher.extractEpisodeNumber("Show E08.ass"))
        assertEquals(cat, "Standalone E14v2", 14, KitsunekkoMatcher.extractEpisodeNumber("Show E14v2.ass"))

        // 5. Standalone numbers with fallback
        assertEquals(cat, "Standalone 01", 1, KitsunekkoMatcher.extractEpisodeNumber("Anime 01.ass"))
        assertEquals(cat, "Standalone 25", 25, KitsunekkoMatcher.extractEpisodeNumber("Anime 25.srt"))
        assertEquals(cat, "Standalone 0", 0, KitsunekkoMatcher.extractEpisodeNumber("Anime 0.srt"))

        // 6. Technical tag and resolution false positive exclusions
        assertEquals(cat, "Exclude resolution 1080p", null, KitsunekkoMatcher.extractEpisodeNumber("Movie 1080p BluRay.mkv"))
        assertEquals(cat, "Exclude resolution 720p", null, KitsunekkoMatcher.extractEpisodeNumber("Movie 720p WEB-DL.mkv"))
        assertEquals(cat, "Exclude resolution 2160p", null, KitsunekkoMatcher.extractEpisodeNumber("Movie 2160p UHD.mkv"))
        assertEquals(cat, "Exclude resolution 480p", null, KitsunekkoMatcher.extractEpisodeNumber("Movie 480p DVDRip.mkv"))
        assertEquals(cat, "Exclude bare resolution 1080", null, KitsunekkoMatcher.extractEpisodeNumber("Movie 1080 BluRay.mkv"))
        assertEquals(cat, "Exclude bare resolution 720", null, KitsunekkoMatcher.extractEpisodeNumber("Movie 720 HDTV.mkv"))
        assertEquals(cat, "Exclude bare resolution 2160", null, KitsunekkoMatcher.extractEpisodeNumber("Movie 2160 Remux.mkv"))

        // 7. Year false positive exclusions (1900..2100)
        assertEquals(cat, "Exclude year 2023 with dash ep", 5, KitsunekkoMatcher.extractEpisodeNumber("Series 2023 - 05.srt"))
        assertEquals(cat, "Exclude year 2024 without ep", null, KitsunekkoMatcher.extractEpisodeNumber("Series 2024.mkv"))
        assertEquals(cat, "Exclude year 2021 in parens", null, KitsunekkoMatcher.extractEpisodeNumber("Series (2021).mkv"))
        assertEquals(cat, "Exclude year 1999 retro anime", null, KitsunekkoMatcher.extractEpisodeNumber("Cowboy Bebop 1998.mkv"))

        // 8. Video / Audio / Bitrate tags exclusions
        assertEquals(cat, "Exclude complex tags 10bit DDP5.1", null, KitsunekkoMatcher.extractEpisodeNumber("Anime.1080p.x265.10bit.DDP5.1.mkv"))
        assertEquals(cat, "Exclude HDR10+ TrueHD 7.1", null, KitsunekkoMatcher.extractEpisodeNumber("Anime.2160p.HDR10+.TrueHD.7.1.mkv"))
        assertEquals(cat, "Exclude 60fps 1000kbps", null, KitsunekkoMatcher.extractEpisodeNumber("Anime.60fps.1000kbps.mkv"))
        assertEquals(cat, "Exclude 4K AAC2.0", null, KitsunekkoMatcher.extractEpisodeNumber("Anime.4K.AAC2.0.mkv"))

        // 9. Boundary checks (0..3000)
        assertEquals(cat, "Upper boundary 3000 valid", 3000, KitsunekkoMatcher.extractEpisodeNumber("Show 3000.mkv"))
        assertEquals(cat, "Upper boundary 3001 excluded", null, KitsunekkoMatcher.extractEpisodeNumber("Show 3001.mkv"))
        assertEquals(cat, "High number 99999 excluded", null, KitsunekkoMatcher.extractEpisodeNumber("Show 99999.srt"))

        return (passed - startP) to (failed - startF)
    }

    // =========================================================================
    // Category 7: Media Info Parser Robustness
    // =========================================================================
    private fun testMediaInfoParserRobustness(): Pair<Int, Int> {
        val startP = passed
        val startF = failed
        val cat = "Media Info"
        println("\n--- Testing Category 7: Media Info Parser Robustness ---")

        // Standard Anime Release
        val p1 = KitsunekkoMatcher.parseMediaInfo("[SubsPlease] Sousou no Frieren - 05 (1080p) [7BAA1C90].mkv")
        assertEquals(cat, "p1 clean title", "Sousou no Frieren", p1.title)
        assertEquals(cat, "p1 episode", 5, p1.episodeNumber)
        assertEquals(cat, "p1 season", null, p1.seasonNumber)

        // Multi-season Anime Release
        val p2 = KitsunekkoMatcher.parseMediaInfo("[Erai-raws] Jujutsu Kaisen 2nd Season - 14 [1080p][HEVC].mkv")
        assertEquals(cat, "p2 clean title", "Jujutsu Kaisen 2nd Season", p2.title)
        assertEquals(cat, "p2 episode", 14, p2.episodeNumber)
        assertEquals(cat, "p2 season", 2, p2.seasonNumber)

        // SxxExx formatted release
        val p3 = KitsunekkoMatcher.parseMediaInfo("Frieren.Beyond.Journey's.End.S01E05.1080p.WEBRip.x265.mkv")
        assertEquals(cat, "p3 clean title", "Frieren Beyond Journey's End", p3.title)
        assertEquals(cat, "p3 episode", 5, p3.episodeNumber)
        assertEquals(cat, "p3 season", 1, p3.seasonNumber)

        // URL encoded Magnet link
        val p4 = KitsunekkoMatcher.parseMediaInfo("magnet:?xt=urn:btih:d3b07384d1&dn=%5BSubsPlease%5D+Sousou+no+Frieren+-+05+%281080p%29%5B7BAA1C90%5D.mkv")
        assertEquals(cat, "p4 magnet clean title", "Sousou no Frieren", p4.title)
        assertEquals(cat, "p4 magnet episode", 5, p4.episodeNumber)

        // Empty and blank inputs
        val p5 = KitsunekkoMatcher.parseMediaInfo("")
        assertEquals(cat, "p5 empty input title", "", p5.title)
        assertEquals(cat, "p5 empty input ep", null, p5.episodeNumber)

        val p6 = KitsunekkoMatcher.parseMediaInfo("   ")
        assertEquals(cat, "p6 whitespace input title", "", p6.title)
        assertEquals(cat, "p6 whitespace input ep", null, p6.episodeNumber)

        return (passed - startP) to (failed - startF)
    }
}
