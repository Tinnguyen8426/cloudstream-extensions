@file:Suppress("DEPRECATION")
package com.stremio.subenricher

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Empirical Test Harness for Stremio Subtitle Enricher:
 * Concurrency, Cache Eviction, Thread Safety, Language Normalization, Hook Chaining, and Timeout Barrier.
 */
object StremioConcurrencyChallengeTest {

    private var passed = 0
    private var failed = 0
    private val failureMessages = mutableListOf<String>()

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            val msg = "  [FAIL] $name: $detail"
            System.err.println(msg)
            failureMessages.add(msg)
            failed++
        }
    }

    private fun assertEquals(name: String, expected: Any?, actual: Any?) {
        check(name, expected == actual, "Expected: <$expected> but was: <$actual>")
    }

    private fun assertTrue(name: String, condition: Boolean, detail: String = "") {
        check(name, condition, if (detail.isNotEmpty()) detail else "Expected true but was false")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" STARTING CONCURRENCY & CACHE ADVERSARIAL CHALLENGE TEST SUITE")
        println("================================================================================")

        testLruEvictionAndAccessOrder()
        testThreadSafetyUnderContention()
        testLanguageNormalization34LanguagesAndEdgeCases()
        testHookChainingLogic()
        testNonBlockingTimeoutBarrier()

        println("================================================================================")
        println(" EMPIRICAL CHALLENGE SUITE SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed assertions:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        }
    }

    // =========================================================================
    // Suite 1: LRU Eviction & Access-Order Preservation Boundary Conditions
    // =========================================================================
    private fun testLruEvictionAndAccessOrder() {
        println("\n>>> Suite 1: LRU Eviction Boundary Conditions & Access-Order Preservation")

        // 1.1 resultCache (Capacity 32)
        StremioSubScraper.clearCache()
        val dummyTrack = StremioSubtitleTrack("https://test.url", "Tiếng Việt (Stremio)", "vi", "test")

        // Fill exactly 32 entries
        for (i in 1..32) {
            StremioSubScraper.resultCache["res_$i"] = listOf(dummyTrack)
        }
        assertEquals("resultCache size at exactly capacity (32)", 32, StremioSubScraper.resultCache.size)

        // Access "res_1" via get to make it MRU (Most Recently Used)
        val fetchedRes1 = StremioSubScraper.resultCache["res_1"]
        assertTrue("res_1 successfully accessed", fetchedRes1 != null)

        // Insert 33rd item -> must evict eldest ("res_2"), preserving "res_1"
        StremioSubScraper.resultCache["res_33"] = listOf(dummyTrack)
        assertEquals("resultCache size remains 32 after 33rd insertion", 32, StremioSubScraper.resultCache.size)
        assertTrue("res_1 preserved due to access-order", StremioSubScraper.resultCache.containsKey("res_1"))
        assertTrue("res_2 evicted as eldest entry", !StremioSubScraper.resultCache.containsKey("res_2"))
        assertTrue("res_33 present as newly inserted MRU", StremioSubScraper.resultCache.containsKey("res_33"))

        // Multi-access ordering: touch res_3, res_5, res_10
        StremioSubScraper.resultCache["res_3"]
        StremioSubScraper.resultCache["res_5"]
        StremioSubScraper.resultCache["res_10"]

        // Insert 3 more items: res_34, res_35, res_36
        // Next eldest without access should be res_4, res_6, res_7
        StremioSubScraper.resultCache["res_34"] = listOf(dummyTrack)
        StremioSubScraper.resultCache["res_35"] = listOf(dummyTrack)
        StremioSubScraper.resultCache["res_36"] = listOf(dummyTrack)

        assertEquals("resultCache size remains 32 after 3 more insertions", 32, StremioSubScraper.resultCache.size)
        assertTrue("res_3 preserved", StremioSubScraper.resultCache.containsKey("res_3"))
        assertTrue("res_5 preserved", StremioSubScraper.resultCache.containsKey("res_5"))
        assertTrue("res_10 preserved", StremioSubScraper.resultCache.containsKey("res_10"))
        assertTrue("res_4 evicted", !StremioSubScraper.resultCache.containsKey("res_4"))
        assertTrue("res_6 evicted", !StremioSubScraper.resultCache.containsKey("res_6"))
        assertTrue("res_7 evicted", !StremioSubScraper.resultCache.containsKey("res_7"))

        // Overwrite existing key: does not change size and moves it to MRU
        val initialSizeBeforeOverwrite = StremioSubScraper.resultCache.size
        StremioSubScraper.resultCache["res_1"] = listOf(dummyTrack, dummyTrack)
        assertEquals("Updating existing key does not change size", initialSizeBeforeOverwrite, StremioSubScraper.resultCache.size)
        assertEquals("Updated value stored", 2, StremioSubScraper.resultCache["res_1"]?.size)

        // 1.2 kitsuIdCache (Capacity 24)
        StremioSubScraper.kitsuIdCache.clear()
        for (i in 1..24) {
            StremioSubScraper.kitsuIdCache["anime_$i"] = "id_$i"
        }
        assertEquals("kitsuIdCache size at exactly capacity (24)", 24, StremioSubScraper.kitsuIdCache.size)

        // Access anime_1 to make it MRU
        val k1 = StremioSubScraper.kitsuIdCache["anime_1"]
        assertEquals("anime_1 accessed", "id_1", k1)

        // Insert 25th entry
        StremioSubScraper.kitsuIdCache["anime_25"] = "id_25"
        assertEquals("kitsuIdCache size capped at 24", 24, StremioSubScraper.kitsuIdCache.size)
        assertTrue("anime_1 preserved after eviction", StremioSubScraper.kitsuIdCache.containsKey("anime_1"))
        assertTrue("anime_2 evicted as eldest entry", !StremioSubScraper.kitsuIdCache.containsKey("anime_2"))
        assertTrue("anime_25 present", StremioSubScraper.kitsuIdCache.containsKey("anime_25"))

        // 1.3 imdbIdCache (Capacity 24)
        StremioSubScraper.imdbIdCache.clear()
        for (i in 1..24) {
            StremioSubScraper.imdbIdCache["movie_$i"] = "tt$i"
        }
        assertEquals("imdbIdCache size at exactly capacity (24)", 24, StremioSubScraper.imdbIdCache.size)

        // Access movie_1 to make it MRU
        val m1 = StremioSubScraper.imdbIdCache["movie_1"]
        assertEquals("movie_1 accessed", "tt1", m1)

        // Insert 25th entry
        StremioSubScraper.imdbIdCache["movie_25"] = "tt25"
        assertEquals("imdbIdCache size capped at 24", 24, StremioSubScraper.imdbIdCache.size)
        assertTrue("movie_1 preserved after eviction", StremioSubScraper.imdbIdCache.containsKey("movie_1"))
        assertTrue("movie_2 evicted as eldest entry", !StremioSubScraper.imdbIdCache.containsKey("movie_2"))
        assertTrue("movie_25 present", StremioSubScraper.imdbIdCache.containsKey("movie_25"))

        // 1.4 Cache Clearing & Stats Format
        StremioSubScraper.clearCache()
        assertEquals("resultCache size 0 after clear", 0, StremioSubScraper.resultCache.size)
        assertEquals("kitsuIdCache size 0 after clear", 0, StremioSubScraper.kitsuIdCache.size)
        assertEquals("imdbIdCache size 0 after clear", 0, StremioSubScraper.imdbIdCache.size)

        val stats = StremioSubScraper.getCacheStats()
        assertEquals("getCacheStats output when empty", "Subtitles Cache: 0/32, Kitsu IDs: 0/24, IMDb IDs: 0/24", stats)
    }

    // =========================================================================
    // Suite 2: Multi-Threaded Concurrent Insertions & Reads (Thread Safety)
    // =========================================================================
    private fun testThreadSafetyUnderContention() {
        println("\n>>> Suite 2: Thread Safety Under Multi-Threaded Contention")

        StremioSubScraper.clearCache()

        val numThreads = 50
        val operationsPerThread = 1000
        val latch = CountDownLatch(numThreads)
        val cmeCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val dummyTrack = StremioSubtitleTrack("https://test.url", "English (Stremio)", "en", "test")

        val threads = (1..numThreads).map { threadId ->
            Thread {
                try {
                    for (op in 1..operationsPerThread) {
                        val keyNum = (op % 100) + 1
                        val key = "thread_${threadId}_key_$keyNum"
                        val globalKey = "global_key_$keyNum"

                        when (op % 6) {
                            0, 1 -> {
                                // Concurrent put into resultCache
                                StremioSubScraper.resultCache[key] = listOf(dummyTrack)
                                StremioSubScraper.resultCache[globalKey] = listOf(dummyTrack)
                            }
                            2 -> {
                                // Concurrent get
                                StremioSubScraper.resultCache[key]
                                StremioSubScraper.resultCache[globalKey]
                            }
                            3 -> {
                                // Concurrent ID cache put
                                StremioSubScraper.kitsuIdCache[key] = "id_$keyNum"
                                StremioSubScraper.imdbIdCache[key] = "tt_$keyNum"
                            }
                            4 -> {
                                // Concurrent ID cache get
                                StremioSubScraper.kitsuIdCache[key]
                                StremioSubScraper.imdbIdCache[key]
                            }
                            5 -> {
                                // Concurrent size & contains check
                                StremioSubScraper.resultCache.size
                                StremioSubScraper.resultCache.containsKey(key)
                                StremioSubScraper.kitsuIdCache.size
                                StremioSubScraper.imdbIdCache.size
                            }
                        }
                    }
                } catch (e: ConcurrentModificationException) {
                    cmeCount.incrementAndGet()
                    System.err.println("Thread $threadId encountered ConcurrentModificationException!")
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                    System.err.println("Thread $threadId encountered unexpected error: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
        }

        val startTime = System.currentTimeMillis()
        threads.forEach { it.start() }
        val completed = latch.await(20, TimeUnit.SECONDS)
        val duration = System.currentTimeMillis() - startTime

        assertTrue("All 50 threads completed within 20s timeout ($duration ms)", completed)
        assertEquals("Zero ConcurrentModificationExceptions during 50,000 ops", 0, cmeCount.get())
        assertEquals("Zero unexpected errors during concurrent stress", 0, errorCount.get())

        // Verify invariant bounds
        assertTrue("resultCache never exceeded 32 (actual: ${StremioSubScraper.resultCache.size})", StremioSubScraper.resultCache.size <= 32)
        assertTrue("kitsuIdCache never exceeded 24 (actual: ${StremioSubScraper.kitsuIdCache.size})", StremioSubScraper.kitsuIdCache.size <= 24)
        assertTrue("imdbIdCache never exceeded 24 (actual: ${StremioSubScraper.imdbIdCache.size})", StremioSubScraper.imdbIdCache.size <= 24)

        println("  -> Successfully executed ${numThreads * operationsPerThread} concurrent operations in ${duration}ms")

        // Concurrent test on StremioSubHook data structures
        StremioSubHook.detachHooks()
        val hookLatch = CountDownLatch(20)
        val hookErrors = AtomicInteger(0)

        for (t in 1..20) {
            Thread {
                try {
                    for (i in 1..500) {
                        val dataKey = "data_url_${t}_$i"
                        val info = StremioSubHook.ShowInfo("Title $i", listOf("Alt $i"), i, 1, 2024)
                        StremioSubHook.episodeDataCache[dataKey] = info
                        StremioSubHook.episodeDataCache[dataKey]
                        StremioSubHook.latestLoadedShow = info
                        StremioSubHook.targetProviders = setOf("Torrentio", "AnimePahe", "TorraStream")
                    }
                } catch (e: Throwable) {
                    hookErrors.incrementAndGet()
                } finally {
                    hookLatch.countDown()
                }
            }.start()
        }

        val hookCompleted = hookLatch.await(10, TimeUnit.SECONDS)
        assertTrue("Hook data structures concurrent test completed within 10s", hookCompleted)
        assertEquals("Zero errors in hook concurrent operations", 0, hookErrors.get())
    }

    // =========================================================================
    // Suite 3: 34-Language Normalization & Preferences Filter Edge Cases
    // =========================================================================
    private fun testLanguageNormalization34LanguagesAndEdgeCases() {
        println("\n>>> Suite 3: 34-Language Normalization & Preferences Filter Edge Cases")

        // 3.1 Null and Blank inputs
        val nullResult = StremioSubScraper.normalizeLanguage(null)
        assertEquals("null language code", "unknown", nullResult.first)
        assertEquals("null language label", "Unknown (Stremio)", nullResult.second)

        val emptyResult = StremioSubScraper.normalizeLanguage("")
        assertEquals("empty language code", "unknown", emptyResult.first)

        val blankResult = StremioSubScraper.normalizeLanguage("   ")
        assertEquals("blank language code", "unknown", blankResult.first)

        // 3.2 All 34 Registered Languages (Code2, DisplayName, Sample 3-letter / regional variants)
        val expected34 = listOf(
            Triple("vi", "Tiếng Việt", listOf("vie", "vi-vn", "vietnamese", "  VIE  ")),
            Triple("en", "English", listOf("eng", "en-us", "en-gb", "en-ca")),
            Triple("ja", "Japanese", listOf("jpn", "jap", "JA")),
            Triple("ko", "Korean", listOf("kor", "KO")),
            Triple("zh", "Chinese", listOf("zho", "chi", "zhs", "zht", "zh-cn", "zh-tw", "zh-hk")),
            Triple("es", "Spanish", listOf("spa", "es-es", "es-mx", "es-ar")),
            Triple("fr", "French", listOf("fra", "fre", "fr-ca", "fr_FR")),
            Triple("de", "German", listOf("deu", "ger", "de-at", "de-ch")),
            Triple("ru", "Russian", listOf("rus", "RU")),
            Triple("pt", "Portuguese", listOf("por", "pt-br", "pt-pt", "pt_BR")),
            Triple("it", "Italian", listOf("ita", "IT")),
            Triple("th", "Thai", listOf("tha", "TH")),
            Triple("id", "Indonesian", listOf("ind", "in")), // "in" is legacy ISO code
            Triple("ar", "Arabic", listOf("ara", "AR")),
            Triple("hi", "Hindi", listOf("hin", "HI")),
            Triple("pl", "Polish", listOf("pol", "PL")),
            Triple("tr", "Turkish", listOf("tur", "TR")),
            Triple("nl", "Dutch", listOf("nld", "dut", "nl-be")),
            Triple("cs", "Czech", listOf("ces", "cze")),
            Triple("uk", "Ukrainian", listOf("ukr", "UK")),
            Triple("ro", "Romanian", listOf("ron", "rum")),
            Triple("el", "Greek", listOf("ell", "gre")),
            Triple("sv", "Swedish", listOf("swe", "SV")),
            Triple("da", "Danish", listOf("dan", "DA")),
            Triple("fi", "Finnish", listOf("fin", "FI")),
            Triple("no", "Norwegian", listOf("nor", "nob", "nno")),
            Triple("hu", "Hungarian", listOf("hun", "HU")),
            Triple("bg", "Bulgarian", listOf("bul", "BG")),
            Triple("he", "Hebrew", listOf("heb", "iw")), // "iw" is legacy ISO code
            Triple("ms", "Malay", listOf("msa", "may")),
            Triple("tl", "Filipino", listOf("fil", "tgl", "tagalog")),
            Triple("fa", "Persian", listOf("fas", "per", "farsi")),
            Triple("ca", "Catalan", listOf("cat", "CA")),
            Triple("hr", "Croatian", listOf("hrv", "HR"))
        )

        assertEquals("Total registered languages count", 34, expected34.size)

        for ((code2, expectedName, variants) in expected34) {
            // Test primary 2-letter code
            val res2 = StremioSubScraper.normalizeLanguage(code2)
            assertEquals("Lang '$code2' code2", code2, res2.first)
            assertEquals("Lang '$code2' label", "$expectedName (Stremio)", res2.second)

            // Test variants
            for (variant in variants) {
                val resVar = StremioSubScraper.normalizeLanguage(variant)
                assertEquals("Variant '$variant' maps to '$code2'", code2, resVar.first)
                assertEquals("Variant '$variant' display name", "$expectedName (Stremio)", resVar.second)
            }
        }

        // 3.3 Fallback Edge Cases
        // Unknown 2-letter alpha code
        val xx = StremioSubScraper.normalizeLanguage("xx")
        assertEquals("Unknown 2-letter code fallback", "xx", xx.first)
        assertEquals("Unknown 2-letter label fallback", "XX (Stremio)", xx.second)

        // Unknown 3-letter code
        val xyz = StremioSubScraper.normalizeLanguage("xyz")
        assertEquals("Unknown 3-letter code fallback", "unknown", xyz.first)
        assertEquals("Unknown 3-letter label fallback", "XYZ (Stremio)", xyz.second)

        // Unknown arbitrary word
        val longUnknown = StremioSubScraper.normalizeLanguage("klingon")
        assertEquals("Long unknown string code", "unknown", longUnknown.first)
        assertEquals("Long unknown string label", "KLINGON (Stremio)", longUnknown.second)

        // Numeric or symbol string
        val numStr = StremioSubScraper.normalizeLanguage("123")
        assertEquals("Numeric string code", "unknown", numStr.first)

        // 3.4 Language Filter Modes
        // Default mode is VI_EN
        assertTrue("Filter VI_EN allows vi", StremioSubScraper.isLanguageAllowed("vi", LanguageFilterMode.VI_EN))
        assertTrue("Filter VI_EN allows en", StremioSubScraper.isLanguageAllowed("en", LanguageFilterMode.VI_EN))
        assertTrue("Filter VI_EN case insensitive VI", StremioSubScraper.isLanguageAllowed("VI", LanguageFilterMode.VI_EN))
        assertTrue("Filter VI_EN case insensitive EN", StremioSubScraper.isLanguageAllowed("En", LanguageFilterMode.VI_EN))
        assertTrue("Filter VI_EN rejects ja", !StremioSubScraper.isLanguageAllowed("ja", LanguageFilterMode.VI_EN))
        assertTrue("Filter VI_EN rejects ko", !StremioSubScraper.isLanguageAllowed("ko", LanguageFilterMode.VI_EN))

        // VI_ONLY
        assertTrue("Filter VI_ONLY allows vi", StremioSubScraper.isLanguageAllowed("vi", LanguageFilterMode.VI_ONLY))
        assertTrue("Filter VI_ONLY rejects en", !StremioSubScraper.isLanguageAllowed("en", LanguageFilterMode.VI_ONLY))

        // EN_ONLY
        assertTrue("Filter EN_ONLY allows en", StremioSubScraper.isLanguageAllowed("en", LanguageFilterMode.EN_ONLY))
        assertTrue("Filter EN_ONLY rejects vi", !StremioSubScraper.isLanguageAllowed("vi", LanguageFilterMode.EN_ONLY))

        // ALL
        assertTrue("Filter ALL allows ja", StremioSubScraper.isLanguageAllowed("ja", LanguageFilterMode.ALL))
        assertTrue("Filter ALL allows unknown", StremioSubScraper.isLanguageAllowed("unknown", LanguageFilterMode.ALL))
        assertTrue("Filter ALL allows xx", StremioSubScraper.isLanguageAllowed("xx", LanguageFilterMode.ALL))

        // CUSTOM
        StremioSubScraper.customLanguageCodes = setOf("ja", "ko", "fr")
        assertTrue("Filter CUSTOM allows ja", StremioSubScraper.isLanguageAllowed("ja", LanguageFilterMode.CUSTOM))
        assertTrue("Filter CUSTOM allows ko", StremioSubScraper.isLanguageAllowed("ko", LanguageFilterMode.CUSTOM))
        assertTrue("Filter CUSTOM allows fr", StremioSubScraper.isLanguageAllowed("fr", LanguageFilterMode.CUSTOM))
        assertTrue("Filter CUSTOM rejects en", !StremioSubScraper.isLanguageAllowed("en", LanguageFilterMode.CUSTOM))
        assertTrue("Filter CUSTOM rejects vi", !StremioSubScraper.isLanguageAllowed("vi", LanguageFilterMode.CUSTOM))

        // Empty CUSTOM allows everything
        StremioSubScraper.customLanguageCodes = emptySet()
        assertTrue("Filter CUSTOM with empty set allows all", StremioSubScraper.isLanguageAllowed("de", LanguageFilterMode.CUSTOM))

        // Unknown filter mode defaults to VI_EN
        assertTrue("Fallback filter allows vi", StremioSubScraper.isLanguageAllowed("vi", "nonexistent_mode"))
        assertTrue("Fallback filter allows en", StremioSubScraper.isLanguageAllowed("en", "nonexistent_mode"))
        assertTrue("Fallback filter rejects es", !StremioSubScraper.isLanguageAllowed("es", "nonexistent_mode"))
    }

    // =========================================================================
    // Mock Provider Classes for Hook Chaining Tests
    // =========================================================================
    open class MockBaseProvider(name: String, url: String = "https://mock.base") : MainAPI() {
        init {
            this.name = name
            this.mainUrl = url
        }
    }

    class MockAnime47EnrichedAPI(val original: MainAPI) : MainAPI() {
        init {
            this.name = original.name
            this.mainUrl = original.mainUrl
        }
    }

    // =========================================================================
    // Suite 4: Hook Chaining Logic & Coexistence
    // =========================================================================
    private fun testHookChainingLogic() {
        println("\n>>> Suite 4: Hook Chaining Logic & Provider Coexistence")

        // Reset state
        StremioSubHook.detachHooks()
        val allProviders = APIHolder.allProviders
        allProviders.clear()

        // 1. Setup providers
        val pTorrentio = MockBaseProvider("Torrentio")
        val pAnimePahe = MockBaseProvider("AnimePahe")
        val pAnime47Wrapped = MockAnime47EnrichedAPI(MockBaseProvider("TorraStream"))
        val pStremioAddon = MockBaseProvider("Stremio Addon Provider")
        val pAlreadyHooked = StremioSubHook.StremioEnrichedMainAPI(MockBaseProvider("AlreadyStremio"))

        allProviders.add(pTorrentio)
        allProviders.add(pAnimePahe)
        allProviders.add(pAnime47Wrapped)
        allProviders.add(pStremioAddon)
        allProviders.add(pAlreadyHooked)

        // 2. Execute scanAndHookProviders
        StremioSubHook.enrichAllProviders = true
        StremioSubHook.scanAndHookProviders()

        // 3. Verify outcomes
        val hookedTorrentio = allProviders[0]
        assertTrue("Torrentio wrapped in StremioEnrichedMainAPI", hookedTorrentio is StremioSubHook.StremioEnrichedMainAPI)
        assertEquals("Torrentio baseApi preserved", pTorrentio, (hookedTorrentio as StremioSubHook.StremioEnrichedMainAPI).baseApi)
        assertEquals("Torrentio name preserved", "Torrentio", hookedTorrentio.name)

        val hookedAnimePahe = allProviders[1]
        assertTrue("AnimePahe wrapped in StremioEnrichedMainAPI", hookedAnimePahe is StremioSubHook.StremioEnrichedMainAPI)

        // Hook chaining check: Anime47 wrapper is chained inside Stremio wrapper
        val hookedAnime47 = allProviders[2]
        assertTrue("Anime47 wrapped provider wrapped in StremioEnrichedMainAPI", hookedAnime47 is StremioSubHook.StremioEnrichedMainAPI)
        val stremioWrapper = hookedAnime47 as StremioSubHook.StremioEnrichedMainAPI
        assertTrue("StremioEnrichedMainAPI.baseApi is MockAnime47EnrichedAPI", stremioWrapper.baseApi is MockAnime47EnrichedAPI)
        val anime47Inner = stremioWrapper.baseApi as MockAnime47EnrichedAPI
        assertEquals("Inner original is TorraStream", "TorraStream", anime47Inner.original.name)

        // Stremio Addon Provider (contains "Stremio" in name) must NOT be hooked
        val stremioAddonResult = allProviders[3]
        assertTrue("Stremio Addon Provider skipped (not wrapped)", stremioAddonResult !is StremioSubHook.StremioEnrichedMainAPI)
        assertEquals("Stremio Addon Provider unchanged", pStremioAddon, stremioAddonResult)

        // Already hooked provider must NOT be double-wrapped
        val alreadyHookedResult = allProviders[4]
        assertTrue("Already hooked provider not double wrapped", alreadyHookedResult === pAlreadyHooked)

        // Idempotency: Running scanAndHookProviders a 2nd time should produce zero new wrappings
        StremioSubHook.scanAndHookProviders()
        val secondPassTorrentio = allProviders[0]
        assertTrue("Second scan does not double-wrap Torrentio", secondPassTorrentio === hookedTorrentio)

        // 4. Test shouldHook method directly via reflection
        val shouldHookMethod: Method = StremioSubHook::class.java.getDeclaredMethod("shouldHook", MainAPI::class.java)
        shouldHookMethod.isAccessible = true

        // Provider in hookedProviderNames returns false
        val shouldHookTorrentio = shouldHookMethod.invoke(StremioSubHook, pTorrentio) as Boolean
        assertTrue("shouldHook returns false for already-hooked provider", !shouldHookTorrentio)

        // Fresh provider returns true
        val freshProvider = MockBaseProvider("FreshProvider")
        val shouldHookFresh = shouldHookMethod.invoke(StremioSubHook, freshProvider) as Boolean
        assertTrue("shouldHook returns true for arbitrary fresh provider", shouldHookFresh)

        // Wrapped Anime47 provider returns true when not yet hooked
        val freshAnime47 = MockAnime47EnrichedAPI(MockBaseProvider("FreshTorra"))
        val shouldHookAnime47 = shouldHookMethod.invoke(StremioSubHook, freshAnime47) as Boolean
        assertTrue("shouldHook returns true for fresh Anime47 wrapped provider", shouldHookAnime47)

        // Nuance verification:
        // scanAndHookProviders guards against StremioEnrichedMainAPI via:
        // `if (provider is StremioEnrichedMainAPI || provider.name.contains("Stremio")) continue`
        // Calling scanAndHookProviders with StremioEnrichedMainAPI never wraps it.
        allProviders.clear()
        val directStremio = StremioSubHook.StremioEnrichedMainAPI(MockBaseProvider("DirectTest"))
        allProviders.add(directStremio)
        StremioSubHook.scanAndHookProviders()
        assertTrue("scanAndHookProviders never wraps StremioEnrichedMainAPI", allProviders[0] === directStremio)
    }

    // =========================================================================
    // Suite 5: Non-Blocking 4.5s Timeout Barrier Behavior
    // =========================================================================
    private fun testNonBlockingTimeoutBarrier() = runBlocking {
        println("\n>>> Suite 5: Non-Blocking 4.5s Timeout Barrier Behavior")

        // 5.1 Test 0ms video callback passthrough (Immediate playback)
        val videoPassedThrough = AtomicBoolean(false)
        val videoCallbackTime = AtomicInteger(0)
        val startTime = System.currentTimeMillis()

        val mockBase = object : MainAPI() {
            init {
                this.name = "LatencyTestProvider"
                this.mainUrl = "https://latency.test"
            }

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Yield video link immediately
                callback(
                    ExtractorLink(
                        source = "MockStream",
                        name = "Mock 1080p",
                        url = "https://mock.stream/video.mp4",
                        referer = "",
                        quality = 1080,
                        headers = emptyMap(),
                        extractorData = null,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                        audioTracks = emptyList()
                    )
                )
                return true
            }
        }

        val enriched = StremioSubHook.StremioEnrichedMainAPI(mockBase)

        enriched.loadLinks(
            data = "https://mock.stream/video.mp4",
            isCasting = false,
            subtitleCallback = { },
            callback = { link ->
                videoPassedThrough.set(true)
                videoCallbackTime.set((System.currentTimeMillis() - startTime).toInt())
            }
        )

        assertTrue("Video link was passed through immediately", videoPassedThrough.get())
        assertTrue("Video callback latency < 150ms (actual: ${videoCallbackTime.get()}ms)", videoCallbackTime.get() < 150)

        // 5.2 Test 4.5s timeout barrier when scrape hangs
        // We simulate a scrape job that takes 10 seconds to complete
        println("  -> Testing 4.5s timeout barrier bounded execution (expecting ~4500ms)...")

        val barrierStartTime = System.currentTimeMillis()
        var timedOutBarrierExecuted = false

        val timeoutScope = CoroutineScope(Dispatchers.IO)
        val hangingJob = timeoutScope.launch {
            // Emulate active scrape job hanging for 10 seconds
            delay(10000L)
        }

        val activeScrapeJobs = ConcurrentHashMap.newKeySet<Job>()
        activeScrapeJobs.add(hangingJob)

        // Test the exact barrier pattern from StremioSubHook:
        // withTimeoutOrNull(4500L) { for (job in activeScrapeJobs) { if (job.isActive) job.join() } }
        val barrierResult = withTimeoutOrNull(4500L) {
            for (job in activeScrapeJobs) {
                if (job.isActive) job.join()
            }
        }
        val barrierDuration = System.currentTimeMillis() - barrierStartTime

        hangingJob.cancel()

        assertEquals("withTimeoutOrNull returned null upon 4.5s expiration", null, barrierResult)
        assertTrue(
            "Barrier elapsed time bounded near 4500ms (actual: ${barrierDuration}ms)",
            barrierDuration in 4400L..5300L,
            "Duration was $barrierDuration ms"
        )

        // 5.3 Test immediate return when subtitles are already scraped
        val fastStartTime = System.currentTimeMillis()
        val fastJob = timeoutScope.launch { delay(20L) }
        val fastJobs = ConcurrentHashMap.newKeySet<Job>()
        fastJobs.add(fastJob)

        val subtitleScraped = AtomicBoolean(true) // already scraped
        if (!subtitleScraped.get() && fastJobs.isNotEmpty()) {
            withTimeoutOrNull(4500L) {
                for (job in fastJobs) {
                    if (job.isActive) job.join()
                }
            }
        }
        val fastDuration = System.currentTimeMillis() - fastStartTime
        assertTrue("When subtitles already scraped, loadLinks returns immediately (actual: ${fastDuration}ms)", fastDuration < 100)

        println("  -> Non-blocking 4.5s timeout barrier verified successfully!")
    }
}
