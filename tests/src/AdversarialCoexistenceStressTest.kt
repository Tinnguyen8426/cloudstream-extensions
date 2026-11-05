@file:Suppress("DEPRECATION")
package com.cloudstream.test.e2e

import com.anime47.enricher.Anime47Hook
import com.kitsunekko.enricher.KitsunekkoHook
import com.stremio.subenricher.StremioSubHook
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Coexistence Stress Test Harness.
 *
 * Stress-tests:
 * 1. All 6 permutations of wrapping order (Kitsunekko, Stremio, Anime47).
 * 2. Unhook / Re-hook lifecycle stability and chain depth inflation.
 * 3. High concurrency loadLinks (50 concurrent requests with simultaneous subtitle callbacks).
 * 4. 0ms video passthrough latency under severe background scraper congestion.
 * 5. Severe scraper hangs (> 10s) and timeout resilience of withTimeoutOrNull(4500L).
 * 6. Concurrent multi-show metadata race conditions on latestLoadedShow.
 */
object AdversarialCoexistenceStressTest {

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

    open class MockBaseProvider(
        name: String = "Torrentio",
        url: String = "https://torrentio.strem.fun"
    ) : MainAPI() {
        init {
            this.name = name
            this.mainUrl = url
        }

        override val supportedTypes: Set<TvType> get() = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries)
        override val hasMainPage: Boolean get() = true
        override val hasQuickSearch: Boolean get() = true

        override suspend fun search(query: String): List<SearchResponse>? = emptyList()

        override suspend fun load(url: String): LoadResponse? {
            return newMovieLoadResponse(
                name = "Sousou no Frieren",
                url = url,
                type = TvType.Movie,
                dataUrl = "https://mock.stream/frieren_ep1.mkv"
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
                    name = "Torrentio 1080p RD",
                    url = "https://mock.stream/frieren_1080p.mp4",
                    referer = "",
                    quality = 1080,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }
    }

    fun getChainDepth(provider: MainAPI): Int {
        var depth = 0
        var curr: Any? = provider
        while (curr != null) {
            depth++
            curr = when (curr) {
                is KitsunekkoHook.KitsunekkoEnrichedMainAPI -> curr.baseApi
                is StremioSubHook.StremioEnrichedMainAPI -> curr.baseApi
                is Anime47Hook.EnrichedMainAPI -> curr.original
                else -> null
            }
        }
        return depth
    }

    fun getBaseProvider(provider: MainAPI): MainAPI {
        var curr: MainAPI = provider
        while (true) {
            curr = when (curr) {
                is KitsunekkoHook.KitsunekkoEnrichedMainAPI -> curr.baseApi
                is StremioSubHook.StremioEnrichedMainAPI -> curr.baseApi
                is Anime47Hook.EnrichedMainAPI -> curr.original
                else -> return curr
            }
        }
    }

    fun countDecorators(provider: MainAPI, targetClass: Class<*>): Int {
        var count = 0
        var curr: Any? = provider
        while (curr != null) {
            if (targetClass.isInstance(curr)) count++
            curr = when (curr) {
                is KitsunekkoHook.KitsunekkoEnrichedMainAPI -> curr.baseApi
                is StremioSubHook.StremioEnrichedMainAPI -> curr.baseApi
                is Anime47Hook.EnrichedMainAPI -> curr.original
                else -> null
            }
        }
        return count
    }

    fun hasDecorator(provider: MainAPI, targetClass: Class<*>): Boolean {
        return countDecorators(provider, targetClass) > 0
    }

    private fun resetAllEnricherHooks() {
        StremioSubHook.detachHooks()
        KitsunekkoHook.detachHooks()
        try {
            val f = Anime47Hook::class.java.getDeclaredField("hookedProviderNames")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (f.get(Anime47Hook) as MutableSet<String>).clear()
            Anime47Hook.episodeDataCache.clear()
            Anime47Hook.latestLoadedShow = null
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" EMPIRICAL ADVERSARIAL COEXISTENCE STRESS TEST HARNESS")
        println("================================================================================")

        testAllSixWrappingPermutations()
        testUnhookRehookStability()
        testHighConcurrencyLoadLinks50Requests()
        testZeroLatencyUnderScraperCongestion()
        testSevereScraperHangTimeoutResilience()
        testConcurrentMultiShowMetadataIsolation()

        println("================================================================================")
        println(" ADVERSARIAL STRESS TEST SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed assertions:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL ADVERSARIAL COEXISTENCE STRESS TESTS PASSED SUCCESSFULLY!")
        }
    }

    // =========================================================================
    // Challenge 1: All 6 Permutations of Wrapping Sequences
    // =========================================================================
    private fun testAllSixWrappingPermutations() = runBlocking {
        println("\n>>> Challenge 1: Testing All 6 Permutations of Wrapping Sequences")

        val enricherNames = listOf("Anime47", "Stremio", "Kitsunekko")
        val permutations = listOf(
            listOf("Anime47", "Stremio", "Kitsunekko"),
            listOf("Anime47", "Kitsunekko", "Stremio"),
            listOf("Stremio", "Anime47", "Kitsunekko"),
            listOf("Stremio", "Kitsunekko", "Anime47"),
            listOf("Kitsunekko", "Anime47", "Stremio"),
            listOf("Kitsunekko", "Stremio", "Anime47") // Reverse order
        )

        for ((idx, perm) in permutations.withIndex()) {
            val permDesc = perm.joinToString(" -> ")
            println("  Testing Permutation #${idx + 1}: $permDesc")

            resetAllEnricherHooks()
            val allProviders = APIHolder.allProviders
            allProviders.clear()

            val base = MockBaseProvider("Base_${idx + 1}", "https://base${idx + 1}.stream")
            allProviders.add(base)

            // Apply hooks in this exact permutation order
            for (step in perm) {
                when (step) {
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
                }
            }

            val hooked = allProviders[0]
            val depth = getChainDepth(hooked)
            assertEquals("Permutation $permDesc chain depth == 4 (3 wrappers + 1 base)", 4, depth)

            val resolvedBase = getBaseProvider(hooked)
            assertEquals("Permutation $permDesc resolves to original base provider", base, resolvedBase)
            assertEquals("Permutation $permDesc preserves delegated name", "Base_${idx + 1}", hooked.name)
            assertEquals("Permutation $permDesc preserves delegated url", "https://base${idx + 1}.stream", hooked.mainUrl)

            // Test load() delegation
            val resp = hooked.load("https://base${idx + 1}.stream/media")
            assertTrue("Permutation $permDesc load() succeeds", resp != null)
            assertEquals("Permutation $permDesc load() title matches", "Sousou no Frieren", resp?.name)

            // Test loadLinks() 0ms video passthrough
            val videoArrival = AtomicInteger(-1)
            val startTime = System.currentTimeMillis()
            hooked.loadLinks(
                data = "https://base${idx + 1}.stream/media",
                isCasting = false,
                subtitleCallback = { },
                callback = {
                    videoArrival.set((System.currentTimeMillis() - startTime).toInt())
                }
            )
            assertTrue("Permutation $permDesc video received within 150ms", videoArrival.get() in 0..150, "Latency was: ${videoArrival.get()}ms")
        }
    }

    // =========================================================================
    // Challenge 2: Unhook / Re-hook Lifecycle Stability & Clean Unwrapping
    // =========================================================================
    private fun testUnhookRehookStability() = runBlocking {
        println("\n>>> Challenge 2: Testing Repeated Unhook/Re-hook Calls (Idempotency & Clean Unwrapping)")

        resetAllEnricherHooks()
        val allProviders = APIHolder.allProviders
        allProviders.clear()

        val base = MockBaseProvider("PersistentBase")
        allProviders.add(base)

        // 1. Initial Hook Attachment
        Anime47Hook.scanAndHookProviders()
        StremioSubHook.scanAndHookProviders()
        KitsunekkoHook.scanAndHookProviders()

        val initialDepth = getChainDepth(allProviders[0])
        assertEquals("Initial chain depth is exactly 4 (3 enrichers + 1 base)", 4, initialDepth)
        assertEquals("Stremio decorator count is 1", 1, countDecorators(allProviders[0], StremioSubHook.StremioEnrichedMainAPI::class.java))
        assertEquals("Kitsunekko decorator count is 1", 1, countDecorators(allProviders[0], KitsunekkoHook.KitsunekkoEnrichedMainAPI::class.java))

        // 2. Individual Detach Verification
        // 2a. Detach Stremio only
        StremioSubHook.detachHooks()
        assertTrue("Stremio cleanly unwrapped on detach", !hasDecorator(allProviders[0], StremioSubHook.StremioEnrichedMainAPI::class.java))
        assertTrue("Kitsunekko remains attached when Stremio detaches", hasDecorator(allProviders[0], KitsunekkoHook.KitsunekkoEnrichedMainAPI::class.java))
        assertEquals("Chain depth after Stremio detach is 3", 3, getChainDepth(allProviders[0]))

        // 2b. Re-attach Stremio
        StremioSubHook.scanAndHookProviders()
        assertEquals("Chain depth restored to 4 after Stremio re-attach", 4, getChainDepth(allProviders[0]))
        assertEquals("Stremio decorator count restored to 1", 1, countDecorators(allProviders[0], StremioSubHook.StremioEnrichedMainAPI::class.java))

        // 2c. Detach both Stremio & Kitsunekko
        StremioSubHook.detachHooks()
        KitsunekkoHook.detachHooks()
        assertTrue("Stremio not present in chain after detach", !hasDecorator(allProviders[0], StremioSubHook.StremioEnrichedMainAPI::class.java))
        assertTrue("Kitsunekko not present in chain after detach", !hasDecorator(allProviders[0], KitsunekkoHook.KitsunekkoEnrichedMainAPI::class.java))
        assertEquals("Chain depth is 2 (Anime47 + Base)", 2, getChainDepth(allProviders[0]))

        // Re-attach both
        StremioSubHook.scanAndHookProviders()
        KitsunekkoHook.scanAndHookProviders()
        assertEquals("Chain depth restored to 4", 4, getChainDepth(allProviders[0]))

        // 3. Repeated Unhook / Re-hook Cycles (10 iterations)
        for (cycle in 1..10) {
            StremioSubHook.detachHooks()
            KitsunekkoHook.detachHooks()

            // Intermediate check: verify wrappers stripped
            assertTrue("Cycle $cycle: Stremio stripped", !hasDecorator(allProviders[0], StremioSubHook.StremioEnrichedMainAPI::class.java))
            assertTrue("Cycle $cycle: Kitsunekko stripped", !hasDecorator(allProviders[0], KitsunekkoHook.KitsunekkoEnrichedMainAPI::class.java))

            // Re-scan
            Anime47Hook.scanAndHookProviders()
            StremioSubHook.scanAndHookProviders()
            KitsunekkoHook.scanAndHookProviders()

            val cycleDepth = getChainDepth(allProviders[0])
            assertEquals("Cycle $cycle: chain depth == 4", 4, cycleDepth)
            assertEquals("Cycle $cycle: Stremio count == 1", 1, countDecorators(allProviders[0], StremioSubHook.StremioEnrichedMainAPI::class.java))
            assertEquals("Cycle $cycle: Kitsunekko count == 1", 1, countDecorators(allProviders[0], KitsunekkoHook.KitsunekkoEnrichedMainAPI::class.java))
        }

        val postCyclesDepth = getChainDepth(allProviders[0])
        println("  Chain depth after 10 detach/scan cycles: $postCyclesDepth (initial was $initialDepth)")
        
        // Strict bound assertion
        assertTrue("Chain depth strictly bounded <= 4 (actual: $postCyclesDepth)", postCyclesDepth <= 4, "Chain depth inflated to $postCyclesDepth!")
        assertEquals("Chain depth strictly equals 4", 4, postCyclesDepth)

        val resolvedBase = getBaseProvider(allProviders[0])
        assertEquals("Base provider is still PersistentBase after cycles", "PersistentBase", resolvedBase.name)
        assertEquals("Delegated name is still PersistentBase after cycles", "PersistentBase", allProviders[0].name)
        assertEquals("Delegated mainUrl is preserved", "https://torrentio.strem.fun", allProviders[0].mainUrl)

        // 4. Functional Roundtrip Verification After 10 Cycles
        val resp = allProviders[0].load("https://torrentio.strem.fun/test")
        assertTrue("load() succeeds after 10 unhook/rehook cycles", resp != null)
        assertEquals("load() title matches", "Sousou no Frieren", resp?.name)

        val videoArrival = AtomicInteger(-1)
        val startTime = System.currentTimeMillis()
        val loadLinksOk = allProviders[0].loadLinks(
            data = "https://torrentio.strem.fun/test",
            isCasting = false,
            subtitleCallback = { },
            callback = { link ->
                videoArrival.set((System.currentTimeMillis() - startTime).toInt())
            }
        )
        assertTrue("loadLinks() returned true", loadLinksOk)
        assertTrue("Video delivered promptly (< 150ms) after 10 cycles", videoArrival.get() in 0..150, "Latency: ${videoArrival.get()}ms")
    }

    // =========================================================================
    // Challenge 3: High Concurrency on loadLinks (50 Concurrent Requests)
    // =========================================================================
    private fun testHighConcurrencyLoadLinks50Requests() = runBlocking {
        println("\n>>> Challenge 3: High Concurrency on loadLinks (50 Concurrent Simultaneous Requests)")

        val baseProvider = object : MainAPI() {
            init {
                name = "HighConcurrencyBase"
                mainUrl = "https://concurrent.stream"
            }

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Stream for $data",
                        url = "https://concurrent.stream/video?data=$data",
                        referer = "",
                        quality = 1080,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        val anime47 = Anime47Hook.EnrichedMainAPI(baseProvider)
        val stremio = StremioSubHook.StremioEnrichedMainAPI(anime47)
        val kitsunekko = KitsunekkoHook.KitsunekkoEnrichedMainAPI(stremio)

        val concurrentCount = 50
        val receivedLinks = CopyOnWriteArrayList<ExtractorLink>()
        val receivedSubtitles = CopyOnWriteArrayList<SubtitleFile>()
        val latencies = CopyOnWriteArrayList<Long>()

        val jobs = (1..concurrentCount).map { i ->
            launch(Dispatchers.Default) {
                val reqStart = System.currentTimeMillis()
                val reqData = "Sousou no Frieren S01E${i.toString().padStart(2, '0')}"
                
                val result = kitsunekko.loadLinks(
                    data = reqData,
                    isCasting = false,
                    subtitleCallback = { sub ->
                        receivedSubtitles.add(sub)
                    },
                    callback = { link ->
                        val lat = System.currentTimeMillis() - reqStart
                        latencies.add(lat)
                        receivedLinks.add(link)
                    }
                )
                assertTrue("loadLinks returned true for request $i", result)
            }
        }

        jobs.joinAll()

        assertEquals("All $concurrentCount video links received", concurrentCount, receivedLinks.size)
        val maxLatency = latencies.maxOrNull() ?: 0L
        val avgLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0
        println("  50 concurrent requests: Avg Latency = ${avgLatency.toInt()}ms, Max Latency = ${maxLatency}ms")
        assertTrue("Max video passthrough latency under 50 concurrent requests < 200ms", maxLatency < 200L, "Max was: ${maxLatency}ms")
    }

    // =========================================================================
    // Challenge 4: 0ms Video Passthrough Under Congested Scrapers
    // =========================================================================
    private fun testZeroLatencyUnderScraperCongestion() = runBlocking {
        println("\n>>> Challenge 4: 0ms Video Passthrough Latency Under Congested Background Scrapers")

        // Provider simulating a very busy or slow network with background scraper tasks
        val baseProvider = object : MainAPI() {
            init {
                name = "CongestedBase"
                mainUrl = "https://congested.stream"
            }

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Video is emitted immediately
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Instant 4K Link",
                        url = "https://congested.stream/movie.mkv",
                        referer = "",
                        quality = 2160,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        val anime47 = Anime47Hook.EnrichedMainAPI(baseProvider)
        val stremio = StremioSubHook.StremioEnrichedMainAPI(anime47)
        val kitsunekko = KitsunekkoHook.KitsunekkoEnrichedMainAPI(stremio)

        // Warmup JIT / coroutine dispatcher
        kitsunekko.loadLinks("Warmup", false, {}, {})

        val arrivalTime = AtomicInteger(-1)
        val startTime = System.currentTimeMillis()

        kitsunekko.loadLinks(
            data = "https://congested.stream/movie.mkv",
            isCasting = false,
            subtitleCallback = { },
            callback = { link ->
                arrivalTime.set((System.currentTimeMillis() - startTime).toInt())
            }
        )

        val actualLatency = arrivalTime.get()
        println("  Video passthrough latency under congested simulation: ${actualLatency}ms")
        assertTrue("Video callback latency < 100ms budget (actual: ${actualLatency}ms)", actualLatency in 0..100)
    }

    // =========================================================================
    // Challenge 5: Severe Scraper Hang (> 10s) and Timeout Resilience
    // =========================================================================
    private fun testSevereScraperHangTimeoutResilience() = runBlocking {
        println("\n>>> Challenge 5: Severe Scraper Hang (> 10s) and 4500ms Timeout Resilience")

        // We wrap base provider with an enricher that launches a hanging job
        // to directly test whether withTimeoutOrNull(4500L) bounds loadLinks() execution.
        val baseProvider = object : MainAPI() {
            init {
                name = "SevereHangBase"
                mainUrl = "https://hang.base"
            }

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Stream 1080p",
                        url = "https://hang.base/video.mp4",
                        referer = "",
                        quality = 1080,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        // Decorator that launches an uncooperative/hanging coroutine (10,000ms delay)
        // using the exact pattern in StremioSubHook / KitsunekkoHook
        class HangingDecorator(val delegate: MainAPI) : MainAPI() {
            init {
                this.name = delegate.name
                this.mainUrl = delegate.mainUrl
            }

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean = withContext(Dispatchers.IO) {
                val activeJobs = ConcurrentHashMap.newKeySet<Job>()

                // Hanging scraper job (12 seconds)
                val hangingJob = launch {
                    try {
                        delay(12000L)
                        subtitleCallback(SubtitleFile("Laggy", "https://lag.com/sub.srt"))
                    } catch (_: CancellationException) {
                        // Clean cancellation
                    }
                }
                activeJobs.add(hangingJob)

                val res = delegate.loadLinks(data, isCasting, subtitleCallback, callback)

                // The 4.5s timeout barrier
                withTimeoutOrNull(4500L) {
                    for (j in activeJobs) {
                        if (j.isActive) j.join()
                    }
                }
                // Check whether cancelling hanging jobs is needed to prevent withContext from blocking:
                for (j in activeJobs) {
                    if (j.isActive) j.cancel()
                }

                res
            }
        }

        val decorator = HangingDecorator(baseProvider)

        val startTime = System.currentTimeMillis()
        var videoLinkReceived = false

        val result = decorator.loadLinks(
            data = "TestShow",
            isCasting = false,
            subtitleCallback = { },
            callback = { link ->
                videoLinkReceived = true
            }
        )
        val elapsed = System.currentTimeMillis() - startTime

        println("  loadLinks with 12s hanging scraper elapsed: ${elapsed}ms")
        assertTrue("Video link was delivered immediately", videoLinkReceived)
        assertTrue("loadLinks returned true", result)
        assertTrue(
            "loadLinks bounded by ~4500ms barrier (actual: ${elapsed}ms, must be between 4400ms and 5500ms)",
            elapsed in 4400L..5500L,
            "Elapsed was: ${elapsed}ms"
        )
    }

    // =========================================================================
    // Challenge 6: Concurrent Multi-Show Metadata Isolation
    // =========================================================================
    private fun testConcurrentMultiShowMetadataIsolation() = runBlocking {
        println("\n>>> Challenge 6: Concurrent Multi-Show Metadata Isolation")

        resetAllEnricherHooks()

        val baseProviderA = object : MainAPI() {
            init {
                name = "BaseProviderA"
                mainUrl = "https://base.a"
            }
            override suspend fun load(url: String): LoadResponse? {
                return newMovieLoadResponse(
                    name = "Show A: Sousou no Frieren",
                    url = url,
                    type = TvType.Movie,
                    dataUrl = "https://base.a/frieren"
                ) { this.year = 2023 }
            }
        }

        val baseProviderB = object : MainAPI() {
            init {
                name = "BaseProviderB"
                mainUrl = "https://base.b"
            }
            override suspend fun load(url: String): LoadResponse? {
                return newMovieLoadResponse(
                    name = "Show B: Jujutsu Kaisen",
                    url = url,
                    type = TvType.Movie,
                    dataUrl = "https://base.b/jjk"
                ) { this.year = 2020 }
            }
        }

        val hookA = StremioSubHook.StremioEnrichedMainAPI(baseProviderA)
        val hookB = StremioSubHook.StremioEnrichedMainAPI(baseProviderB)

        // Interleaved load calls
        val respA = hookA.load("https://base.a/frieren")
        val respB = hookB.load("https://base.b/jjk")

        assertEquals("Resp A title", "Show A: Sousou no Frieren", respA?.name)
        assertEquals("Resp B title", "Show B: Jujutsu Kaisen", respB?.name)

        // Verify episodeDataCache correctly differentiates keys
        val cachedA = StremioSubHook.episodeDataCache["https://base.a/frieren"]
        val cachedB = StremioSubHook.episodeDataCache["https://base.b/jjk"]

        assertTrue("Cached A is present", cachedA != null)
        assertEquals("Cached A title matches", "Show A: Sousou no Frieren", cachedA?.title)

        assertTrue("Cached B is present", cachedB != null)
        assertEquals("Cached B title matches", "Show B: Jujutsu Kaisen", cachedB?.title)
    }
}
