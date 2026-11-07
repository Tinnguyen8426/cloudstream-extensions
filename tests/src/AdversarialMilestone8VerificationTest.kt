@file:Suppress("DEPRECATION")
package com.cloudstream.test.e2e

import com.anime47.enricher.Anime47Hook
import com.kitsunekko.enricher.KitsunekkoHook
import com.stremio.subenricher.StremioSubHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger 2 Empirical Adversarial Lifecycle & Concurrency Verification Test.
 *
 * Exhaustive empirical verification of:
 * 1. High-concurrency lifecycle: multi-threaded additions to allProviders, scans, attach/detach, UI queries.
 * 2. All 24 permutations of 4-enricher initialization order: verifying strictly 1 entry per provider in APIHolder.apis.
 * 3. Repetitive hook runs: 100 consecutive attachHooks passes to test memory / provider accumulation in APIHolder.apis.
 * 4. Plugin unloading / detachHooks: granular detach, middle-chain detach, and clean unwrapped restoration.
 */
object AdversarialMilestone8VerificationTest {

    private var passed = 0
    private var failed = 0
    private val failureMessages = mutableListOf<String>()

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("    [PASS] $name")
            passed++
        } else {
            val msg = "    [FAIL] $name: $detail"
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

    class TestBaseProvider(
        name: String,
        url: String,
        val shouldCrashOnSchemeless: Boolean = false
    ) : MainAPI() {
        init {
            this.name = name
            this.mainUrl = url
        }

        override val supportedTypes: Set<TvType> get() = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries)
        override val hasMainPage: Boolean get() = true
        override val hasQuickSearch: Boolean get() = true

        override val mainPage: List<MainPageData> = listOf(
            MainPageData("Featured", if (shouldCrashOnSchemeless) "" else "https://provider.stream/featured", false)
        )

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            if (shouldCrashOnSchemeless) {
                if (!request.data.startsWith("http://") && !request.data.startsWith("https://")) {
                    throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for ${request.data}")
                }
            }
            return newHomePageResponse(
                name = "Mock Show ($name)",
                list = listOf(
                    newAnimeSearchResponse(
                        name = "Mock Show ($name)",
                        url = "$mainUrl/show/1"
                    )
                ),
                hasNext = false
            )
        }

        override suspend fun search(query: String): List<SearchResponse>? {
            return listOf(
                newAnimeSearchResponse(
                    name = "$name Result for $query",
                    url = "$mainUrl/search/1"
                )
            )
        }

        override suspend fun load(url: String): LoadResponse? {
            if (shouldCrashOnSchemeless) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for $url")
                }
            }
            return newMovieLoadResponse(
                name = "Sousou no Frieren ($name)",
                url = url,
                type = TvType.Movie,
                dataUrl = "$mainUrl/video.mkv"
            ) {
                this.year = 2023
            }
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "$name 1080p Stream",
                    url = "$mainUrl/stream.mp4",
                    referer = "",
                    quality = 1080,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }
    }

    private fun resetAllState() {
        try { AITranslatorHook.detachHooks() } catch (_: Throwable) {}
        try { StremioSubHook.detachHooks() } catch (_: Throwable) {}
        try { KitsunekkoHook.detachHooks() } catch (_: Throwable) {}
        try { Anime47Hook.detachHooks() } catch (_: Throwable) {}

        APIHolder.allProviders.clear()
        try {
            APIHolder.apis.withLock {
                APIHolder.apis = APIHolder.apis.filter { false }
            }
        } catch (_: Throwable) {}
    }

    private fun getChainClasses(head: MainAPI): List<Class<*>> {
        val list = mutableListOf<Class<*>>()
        var curr: MainAPI? = head
        while (curr != null) {
            list.add(curr.javaClass)
            curr = AITranslatorHook.getInnerDelegate(curr)
        }
        return list
    }

    private fun setupStandardProviders(): List<TestBaseProvider> {
        val pVega = TestBaseProvider("VegaMovies", "https://vegamovies.im", shouldCrashOnSchemeless = true)
        val pTorrentio = TestBaseProvider("Torrentio", "https://torrentio.strem.fun")
        val pAnimePahe = TestBaseProvider("AnimePahe", "https://animepahe.ru")
        val pCineStream = TestBaseProvider("CineStream", "https://cinestream.tv")

        val list = listOf(pVega, pTorrentio, pAnimePahe, pCineStream)
        for (p in list) {
            APIHolder.allProviders.add(p)
            try {
                APIHolder.addPluginMapping(p)
            } catch (_: Throwable) {}
        }
        return list
    }

    // =========================================================================
    // SECTION 1: ALL 24 PERMUTATIONS OF HOOK INITIALIZATION ORDER
    // =========================================================================
    fun testAll24Permutations() = runBlocking {
        println("\n>>> [SECTION 1] Testing All 24 Permutations of 4-Hook Attachment Order")

        val enricherNames = listOf("Anime47", "Stremio", "Kitsunekko", "AiTranslator")

        fun <T> permutations(list: List<T>): List<List<T>> {
            if (list.size <= 1) return listOf(list)
            val result = mutableListOf<List<T>>()
            for (i in list.indices) {
                val element = list[i]
                val rest = list.filterIndexed { index, _ -> index != i }
                for (subPerm in permutations(rest)) {
                    result.add(listOf(element) + subPerm)
                }
            }
            return result
        }

        val allPerms = permutations(enricherNames)
        assertEquals("Total permutations count is 4! = 24", 24, allPerms.size)

        var permIdx = 0
        for (perm in allPerms) {
            permIdx++
            val permLabel = perm.joinToString(" -> ")
            println("\n  --- Permutation #$permIdx: $permLabel ---")

            resetAllState()
            val baseList = setupStandardProviders()

            // Attach in permutation sequence
            for (enricher in perm) {
                when (enricher) {
                    "Anime47" -> {
                        Anime47Hook.enrichAllProviders = true
                        Anime47Hook.scanAndHookProviders()
                    }
                    "Stremio" -> {
                        StremioSubHook.enrichAllProviders = true
                        StremioSubHook.scanAndHookProviders()
                    }
                    "Kitsunekko" -> {
                        KitsunekkoHook.enrichAllProviders = true
                        KitsunekkoHook.scanAndHookProviders()
                    }
                    "AiTranslator" -> {
                        TranslatorConfig.isEnabled = true
                        AITranslatorHook.scanAndHoistHooks()
                    }
                }
            }

            // Final hoist pass ensures outermost invariant
            AITranslatorHook.scanAndHoistHooks()

            // 1. APIHolder.apis deduplication assertion: strictly 1 entry per provider name!
            val apis = APIHolder.apis
            assertEquals("Perm #$permIdx: APIHolder.apis size exactly 4", 4, apis.size)
            val apiNames = apis.map { it.name }
            assertEquals("Perm #$permIdx: Zero duplicate names in APIHolder.apis", 4, apiNames.distinct().size)

            // 2. APIHolder.allProviders deduplication assertion
            val allProviders = APIHolder.allProviders
            assertEquals("Perm #$permIdx: APIHolder.allProviders size exactly 4", 4, allProviders.size)
            val allNames = allProviders.map { it.name }
            assertEquals("Perm #$permIdx: Zero duplicate names in APIHolder.allProviders", 4, allNames.distinct().size)

            // 3. For every provider, head in apis and allProviders must be AITranslatorEnrichedMainAPI
            for (provider in apis) {
                assertTrue("Perm #$permIdx: Provider '${provider.name}' head is AITranslatorEnrichedMainAPI",
                    provider is AITranslatorEnrichedMainAPI, "Actual: ${provider.javaClass.name}")

                val chain = getChainClasses(provider)
                assertEquals("Perm #$permIdx: Chain depth for '${provider.name}' == 5", 5, chain.size)

                val classNames = chain.map { it.name }
                assertTrue("Perm #$permIdx: '${provider.name}' chain contains AiTranslator",
                    classNames.any { it.contains("AITranslatorEnrichedMainAPI") })
                assertTrue("Perm #$permIdx: '${provider.name}' chain contains Kitsunekko",
                    classNames.any { it.contains("KitsunekkoEnrichedMainAPI") })
                assertTrue("Perm #$permIdx: '${provider.name}' chain contains Stremio",
                    classNames.any { it.contains("StremioEnrichedMainAPI") })
                assertTrue("Perm #$permIdx: '${provider.name}' chain contains Anime47",
                    classNames.any { it.contains("Anime47Hook") && it.contains("EnrichedMainAPI") })
                assertTrue("Perm #$permIdx: '${provider.name}' leaf is TestBaseProvider",
                    classNames.last().contains("TestBaseProvider"))

                // Delegation checks
                assertTrue("Perm #$permIdx: '${provider.name}' mainUrl preserved",
                    provider.mainUrl.startsWith("https://"))
                assertEquals("Perm #$permIdx: '${provider.name}' supportedTypes preserved",
                    setOf(TvType.Anime, TvType.Movie, TvType.TvSeries), provider.supportedTypes)

                // Crash Barrier & relative URL resolution check on VegaMovies
                val homeResp = provider.getMainPage(1, MainPageRequest("Featured", "", false))
                assertTrue("Perm #$permIdx: '${provider.name}' getMainPage did not crash",
                    homeResp != null || provider.name == "VegaMovies")

                // Video link passthrough check
                val videoReceived = AtomicBoolean(false)
                provider.loadLinks(
                    data = "${provider.mainUrl}/stream",
                    isCasting = false,
                    subtitleCallback = {},
                    callback = { videoReceived.set(true) }
                )
                assertTrue("Perm #$permIdx: '${provider.name}' video link callback received", videoReceived.get())
            }

            // 4. Detach assertion for this permutation
            AITranslatorHook.detachHooks()
            StremioSubHook.detachHooks()
            KitsunekkoHook.detachHooks()
            Anime47Hook.detachHooks()

            assertEquals("Perm #$permIdx: APIHolder.apis size after detach == 4", 4, APIHolder.apis.size)
            assertEquals("Perm #$permIdx: APIHolder.apis distinct names after detach == 4", 4, APIHolder.apis.map { it.name }.distinct().size)
            for (p in APIHolder.apis) {
                assertTrue("Perm #$permIdx: Provider '${p.name}' unwrapped to TestBaseProvider",
                    p is TestBaseProvider, "Actual: ${p.javaClass.name}")
            }
        }
    }

    // =========================================================================
    // SECTION 2: REPETITIVE HOOK RUNS (100 CONSECUTIVE PASSES)
    // =========================================================================
    fun testRepetitive100Passes() = runBlocking {
        println("\n>>> [SECTION 2] Repetitive Hook Runs (100 Consecutive Passes)")

        resetAllState()
        setupStandardProviders()

        Anime47Hook.enrichAllProviders = true
        StremioSubHook.enrichAllProviders = true
        KitsunekkoHook.enrichAllProviders = true
        TranslatorConfig.isEnabled = true

        val startTime = System.currentTimeMillis()

        for (pass in 1..100) {
            Anime47Hook.scanAndHookProviders()
            KitsunekkoHook.scanAndHookProviders()
            StremioSubHook.scanAndHookProviders()
            AITranslatorHook.scanAndHoistHooks()

            // Verify on every 10th pass and pass 100
            if (pass % 10 == 0 || pass == 100) {
                assertEquals("Pass #$pass: APIHolder.apis size strictly 4", 4, APIHolder.apis.size)
                assertEquals("Pass #$pass: APIHolder.apis distinct names strictly 4", 4, APIHolder.apis.map { it.name }.distinct().size)
                assertEquals("Pass #$pass: APIHolder.allProviders size strictly 4", 4, APIHolder.allProviders.size)

                for (provider in APIHolder.apis) {
                    val chain = getChainClasses(provider)
                    assertEquals("Pass #$pass: Provider '${provider.name}' chain depth strictly 5", 5, chain.size)
                    assertTrue("Pass #$pass: Head is AITranslatorEnrichedMainAPI", provider is AITranslatorEnrichedMainAPI)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        println("    [*] 100 consecutive hook scan passes completed in ${elapsed}ms (avg: ${elapsed / 100.0}ms per pass)")
        assertTrue("100 passes finished within reasonable time (< 15000ms)", elapsed < 15000)
    }

    // =========================================================================
    // SECTION 3: PLUGIN UNLOADING & DETACH HOOKS LIFECYCLE
    // =========================================================================
    fun testPluginUnloadingLifecycle() = runBlocking {
        println("\n>>> [SECTION 3] Plugin Unloading & detachHooks Lifecycle")

        resetAllState()
        setupStandardProviders()

        Anime47Hook.enrichAllProviders = true
        StremioSubHook.enrichAllProviders = true
        KitsunekkoHook.enrichAllProviders = true
        TranslatorConfig.isEnabled = true

        // Fully attach
        Anime47Hook.scanAndHookProviders()
        KitsunekkoHook.scanAndHookProviders()
        StremioSubHook.scanAndHookProviders()
        AITranslatorHook.scanAndHoistHooks()

        assertEquals("Pre-unload: APIHolder.apis has 4 providers", 4, APIHolder.apis.size)
        assertEquals("Pre-unload: all providers chain depth == 5", 5, getChainClasses(APIHolder.apis[0]).size)

        // Step 1: Unload AiTranslator
        println("  [*] Step 1: Unloading AiSubtitleTranslator...")
        AITranslatorHook.detachHooks()
        assertEquals("Post-AI detach: APIHolder.apis has 4 providers", 4, APIHolder.apis.size)
        assertEquals("Post-AI detach: zero duplicates", 4, APIHolder.apis.map { it.name }.distinct().size)
        for (p in APIHolder.apis) {
            assertTrue("Post-AI detach: '${p.name}' is not AI wrapper", p !is AITranslatorEnrichedMainAPI)
            val depth = getChainClasses(p).size
            assertEquals("Post-AI detach: '${p.name}' chain depth == 4", 4, depth)
        }

        // Step 2: Unload Middle Enricher: Kitsunekko
        println("  [*] Step 2: Unloading KitsunekkoSubtitleEnricher...")
        KitsunekkoHook.detachHooks()
        assertEquals("Post-Kitsunekko detach: APIHolder.apis has 4 providers", 4, APIHolder.apis.size)
        assertEquals("Post-Kitsunekko detach: zero duplicates", 4, APIHolder.apis.map { it.name }.distinct().size)
        for (p in APIHolder.apis) {
            val chain = getChainClasses(p)
            assertEquals("Post-Kitsunekko detach: '${p.name}' chain depth == 3", 3, chain.size)
            assertTrue("Post-Kitsunekko detach: does not contain Kitsunekko",
                chain.none { it.name.contains("KitsunekkoEnrichedMainAPI") })
        }

        // Step 3: Unload Stremio
        println("  [*] Step 3: Unloading StremioSubtitleEnricher...")
        StremioSubHook.detachHooks()
        assertEquals("Post-Stremio detach: APIHolder.apis has 4 providers", 4, APIHolder.apis.size)
        assertEquals("Post-Stremio detach: zero duplicates", 4, APIHolder.apis.map { it.name }.distinct().size)
        for (p in APIHolder.apis) {
            val chain = getChainClasses(p)
            assertEquals("Post-Stremio detach: '${p.name}' chain depth == 2", 2, chain.size)
            assertTrue("Post-Stremio detach: does not contain Stremio",
                chain.none { it.name.contains("StremioEnrichedMainAPI") })
        }

        // Step 4: Unload Anime47 (Complete restoration to leaf)
        println("  [*] Step 4: Unloading Anime47SubtitleEnricher...")
        Anime47Hook.detachHooks()
        assertEquals("Post-all detach: APIHolder.apis has 4 providers", 4, APIHolder.apis.size)
        assertEquals("Post-all detach: zero duplicates", 4, APIHolder.apis.map { it.name }.distinct().size)
        for (p in APIHolder.apis) {
            assertTrue("Post-all detach: '${p.name}' is TestBaseProvider", p is TestBaseProvider)
            assertEquals("Post-all detach: '${p.name}' chain depth == 1", 1, getChainClasses(p).size)
        }

        // Step 5: Middle-out detach test: re-attach all 4, then detach middle enricher Kitsunekko while AI is active
        println("  [*] Step 5: Testing Middle-Out Detach with Active Outer Head...")
        Anime47Hook.scanAndHookProviders()
        StremioSubHook.scanAndHookProviders()
        KitsunekkoHook.scanAndHookProviders()
        AITranslatorHook.scanAndHoistHooks()

        assertEquals("Re-attached: APIHolder.apis size == 4", 4, APIHolder.apis.size)
        for (p in APIHolder.apis) {
            assertEquals("Re-attached: chain depth == 5", 5, getChainClasses(p).size)
            assertTrue("Re-attached: head is AITranslator", p is AITranslatorEnrichedMainAPI)
        }

        // Now detach Kitsunekko while AI translator is the outer head
        KitsunekkoHook.detachHooks()
        AITranslatorHook.scanAndHoistHooks()

        assertEquals("Middle-out detach: APIHolder.apis size == 4", 4, APIHolder.apis.size)
        assertEquals("Middle-out detach: zero duplicates in apis", 4, APIHolder.apis.map { it.name }.distinct().size)
        for (p in APIHolder.apis) {
            assertTrue("Middle-out detach: head remains AITranslatorEnrichedMainAPI", p is AITranslatorEnrichedMainAPI)
            val chain = getChainClasses(p)
            assertEquals("Middle-out detach: chain depth decreased to 4", 4, chain.size)
            assertTrue("Middle-out detach: Kitsunekko cleanly excised from chain",
                chain.none { it.name.contains("KitsunekkoEnrichedMainAPI") })
            assertTrue("Middle-out detach: Stremio remains present",
                chain.any { it.name.contains("StremioEnrichedMainAPI") })
            assertTrue("Middle-out detach: Anime47 remains present",
                chain.any { it.name.contains("Anime47Hook") && it.name.contains("EnrichedMainAPI") })
        }

        // Step 6: 10 rapid attach/detach churn cycles
        println("  [*] Step 6: 10 Rapid Attach/Detach Churn Cycles...")
        for (cycle in 1..10) {
            KitsunekkoHook.scanAndHookProviders()
            AITranslatorHook.scanAndHoistHooks()
            Anime47Hook.detachHooks()
            StremioSubHook.detachHooks()
            Anime47Hook.scanAndHookProviders()
            StremioSubHook.scanAndHookProviders()
            AITranslatorHook.scanAndHoistHooks()
            KitsunekkoHook.detachHooks()
        }
        AITranslatorHook.scanAndHoistHooks()
        AITranslatorHook.deduplicateApis()

        assertEquals("Post-churn: APIHolder.apis size == 4", 4, APIHolder.apis.size)
        assertEquals("Post-churn: zero duplicates in apis", 4, APIHolder.apis.map { it.name }.distinct().size)
        assertEquals("Post-churn: APIHolder.allProviders size == 4", 4, APIHolder.allProviders.size)
    }

    // =========================================================================
    // SECTION 4: HIGH-CONCURRENCY LIFECYCLE STRESS
    // =========================================================================
    fun testHighConcurrencyStress() {
        println("\n>>> [SECTION 4] High-Concurrency Lifecycle Stress Test (8 Concurrent Threads)")

        resetAllState()
        setupStandardProviders()

        Anime47Hook.enrichAllProviders = true
        StremioSubHook.enrichAllProviders = true
        KitsunekkoHook.enrichAllProviders = true
        TranslatorConfig.isEnabled = true

        val running = AtomicBoolean(true)
        val errorCount = AtomicInteger(0)
        val caughtExceptions = CopyOnWriteArrayList<Throwable>()
        val iterationsPerThread = 80
        val latch = CountDownLatch(8)

        // Thread 1: Dynamic provider registration (adds new providers or re-adds existing providers)
        val t1 = Thread({
            try {
                for (i in 1..iterationsPerThread) {
                    if (!running.get()) break
                    val providerName = if (i % 2 == 0) "VegaMovies" else "DynamicProvider_${i % 5}"
                    val p = TestBaseProvider(providerName, "https://dynamic.stream/$i")
                    APIHolder.allProviders.withLock {
                        if (APIHolder.allProviders.none { it.name == p.name }) {
                            APIHolder.allProviders.add(p)
                        }
                    }
                    try {
                        APIHolder.addPluginMapping(p)
                    } catch (_: Throwable) {}
                    Thread.sleep(2)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                caughtExceptions.add(t)
            } finally {
                latch.countDown()
            }
        }, "Concurrency-T1-DynamicAdd")

        // Thread 2: Anime47 Hook scanning & deduplication
        val t2 = Thread({
            try {
                for (i in 1..iterationsPerThread) {
                    if (!running.get()) break
                    Anime47Hook.scanAndHookProviders()
                    Anime47Hook.deduplicateApis()
                    Thread.sleep(3)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                caughtExceptions.add(t)
            } finally {
                latch.countDown()
            }
        }, "Concurrency-T2-Anime47Scan")

        // Thread 3: Kitsunekko Hook scanning & deduplication
        val t3 = Thread({
            try {
                for (i in 1..iterationsPerThread) {
                    if (!running.get()) break
                    KitsunekkoHook.scanAndHookProviders()
                    KitsunekkoHook.deduplicateApis()
                    Thread.sleep(3)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                caughtExceptions.add(t)
            } finally {
                latch.countDown()
            }
        }, "Concurrency-T3-KitsunekkoScan")

        // Thread 4: Stremio Hook scanning & deduplication
        val t4 = Thread({
            try {
                for (i in 1..iterationsPerThread) {
                    if (!running.get()) break
                    StremioSubHook.scanAndHookProviders()
                    StremioSubHook.deduplicateApis()
                    Thread.sleep(3)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                caughtExceptions.add(t)
            } finally {
                latch.countDown()
            }
        }, "Concurrency-T4-StremioScan")

        // Thread 5: AI Translator Hook scanning & hoisting
        val t5 = Thread({
            try {
                for (i in 1..iterationsPerThread) {
                    if (!running.get()) break
                    AITranslatorHook.scanAndHoistHooks()
                    AITranslatorHook.deduplicateApis()
                    Thread.sleep(2)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                caughtExceptions.add(t)
            } finally {
                latch.countDown()
            }
        }, "Concurrency-T5-AIScanHoist")

        // Thread 6: Periodic attach/detach cycles for Stremio & Kitsunekko
        val t6 = Thread({
            try {
                for (i in 1..iterationsPerThread) {
                    if (!running.get()) break
                    if (i % 4 == 0) {
                        KitsunekkoHook.detachHooks()
                    } else if (i % 4 == 2) {
                        StremioSubHook.detachHooks()
                    }
                    Thread.sleep(4)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                caughtExceptions.add(t)
            } finally {
                latch.countDown()
            }
        }, "Concurrency-T6-EnricherChurn")

        // Thread 7: Periodic AI Translator detach & re-hoist
        val t7 = Thread({
            try {
                for (i in 1..iterationsPerThread) {
                    if (!running.get()) break
                    if (i % 5 == 0) {
                        AITranslatorHook.detachHooks()
                    } else {
                        AITranslatorHook.scanAndHoistHooks()
                    }
                    Thread.sleep(4)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                caughtExceptions.add(t)
            } finally {
                latch.countDown()
            }
        }, "Concurrency-T7-AIDetachChurn")

        // Thread 8: CloudStream UI Reader (reads apis, inspects properties, simulates source picker)
        val t8 = Thread({
            try {
                for (i in 1..iterationsPerThread) {
                    if (!running.get()) break
                    val currentApis = APIHolder.apis
                    for (api in currentApis) {
                        val n = api.name
                        val u = api.mainUrl
                        val st = api.supportedTypes
                    }
                    Thread.sleep(1)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                caughtExceptions.add(t)
            } finally {
                latch.countDown()
            }
        }, "Concurrency-T8-UIReader")

        val threads = listOf(t1, t2, t3, t4, t5, t6, t7, t8)
        threads.forEach { it.start() }

        val completedInTime = latch.await(20, TimeUnit.SECONDS)
        running.set(false)
        threads.forEach { it.join(1000) }

        assertTrue("All 8 concurrent threads completed without deadlock", completedInTime)
        if (caughtExceptions.isNotEmpty()) {
            for (ex in caughtExceptions) {
                System.err.println("  [!] Concurrency exception: ${ex.message}")
                ex.printStackTrace()
            }
        }
        assertEquals("Zero unhandled exceptions or CME across 8 concurrent threads", 0, errorCount.get())

        // Post-concurrency stabilization pass
        Anime47Hook.scanAndHookProviders()
        KitsunekkoHook.scanAndHookProviders()
        StremioSubHook.scanAndHookProviders()
        AITranslatorHook.scanAndHoistHooks()
        AITranslatorHook.deduplicateApis()

        val finalApis = APIHolder.apis
        val finalNames = finalApis.map { it.name }
        val distinctFinalNames = finalNames.distinct()

        println("    [*] Post-concurrency provider count: ${finalApis.size}, distinct names: ${distinctFinalNames.size}")
        assertEquals("Post-concurrency: APIHolder.apis strictly deduplicated (size == distinct names)",
            distinctFinalNames.size, finalApis.size)

        for (provider in finalApis) {
            assertTrue("Post-concurrency: '${provider.name}' head is AITranslatorEnrichedMainAPI",
                provider is AITranslatorEnrichedMainAPI)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" CHALLENGER 2: EMPIRICAL ADVERSARIAL MILESTONE 8 VERIFICATION HARNESS")
        println("================================================================================")

        try {
            testAll24Permutations()
            testRepetitive100Passes()
            testPluginUnloadingLifecycle()
            testHighConcurrencyStress()
        } catch (t: Throwable) {
            System.err.println("[!] Fatal harness error: ${t.message}")
            t.printStackTrace()
            failed++
        }

        println("\n================================================================================")
        println(" MILESTONE 8 ADVERSARIAL VERIFICATION SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed Assertions:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL ADVERSARIAL VERIFICATION CHALLENGES PASSED SUCCESSFULLY!")
            System.exit(0)
        }
    }
}
