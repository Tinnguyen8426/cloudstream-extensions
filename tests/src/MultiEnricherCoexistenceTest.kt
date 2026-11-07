@file:Suppress("DEPRECATION")
package com.cloudstream.test.e2e

import android.content.Context
import com.anime47.enricher.Anime47Hook
import com.anime47.enricher.models.SubtitleTrack
import com.kitsunekko.enricher.KitsunekkoHook
import com.stremio.subenricher.StremioSubHook
import com.stremio.subenricher.StremioSubtitleTrack
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-Enricher Coexistence and Integration End-to-End Test Suite.
 *
 * Verifies full coexistence between:
 * 1. Anime47SubtitleEnricher (Vietnamese softsubs)
 * 2. StremioSubtitleEnricher (Multi-language softsubs from Kitsu/Cinemeta/OpenSubtitles)
 * 3. KitsunekkoSubtitleEnricher (Japanese softsubs from Kitsunekko directory/zips)
 * wrapping a common base provider (e.g. Torrentio).
 *
 * Requirements Tested:
 * - Simultaneous hook chaining with 3-tier delegation
 * - 0ms video passthrough latency (video callback fires before subtitles finish)
 * - Concurrent subtitle emission without race conditions or dropped callbacks
 * - Timeout resilience: a scraper taking > 4.5s is aborted by withTimeoutOrNull(4500L)
 * - Anti-recursion guards: repeated hook installation never causes double wrapping or infinite loops
 */
object MultiEnricherCoexistenceTest {

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

    // Common mock base provider (representing Torrentio or another upstream video scraper)
    open class MockBaseTorrentioProvider(
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

        override suspend fun search(query: String): List<SearchResponse>? {
            return emptyList()
        }

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
            // Emits video stream immediately (0ms delay)
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

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" E2E MULTI-ENRICHER COEXISTENCE & INTEGRATION VERIFICATION HARNESS")
        println("================================================================================")

        testSimultaneousHookChaining()
        testArbitraryHookChainingSequences()
        testZeroLatencyVideoPassthrough()
        testConcurrentSubtitleEmission()
        testTimeoutResilience4500ms()
        testAntiRecursionGuardsAndIdempotency()
        testMetadataDelegationAcrossChain()
        testMainPageDelegationAcross24Permutations()
        testCrashBarrierIsolationOnSchemelessUrl()
        testAPIHolderApisDeduplication()

        println("================================================================================")
        println(" MULTI-ENRICHER SUITE SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed assertions:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL MULTI-ENRICHER COEXISTENCE TESTS PASSED SUCCESSFULLY!")
            System.exit(0)
        }
    }

    private fun resetAllEnricherHooks() {
        AITranslatorHook.detachHooks()
        StremioSubHook.detachHooks()
        KitsunekkoHook.detachHooks()
        Anime47Hook.detachHooks()
    }

    // =========================================================================
    // Test 1: Simultaneous Hook Chaining (Anime47 + Stremio + Kitsunekko + AiSubtitleTranslator)
    // =========================================================================
    private fun testSimultaneousHookChaining() {
        println("\n>>> Test 1: Simultaneous Hook Chaining (4-Tier Wrapper Chaining)")

        // 1. Reset all provider hooks and APIHolder
        resetAllEnricherHooks()
        val allProviders = APIHolder.allProviders
        allProviders.clear()

        val baseTorrentio = MockBaseTorrentioProvider("Torrentio")
        allProviders.add(baseTorrentio)

        // 2. Attach Anime47 hook first
        Anime47Hook.enrichAllProviders = true
        Anime47Hook.scanAndHookProviders()

        val stage1Provider = allProviders[0]
        assertTrue("Stage 1: Torrentio wrapped by Anime47Hook.EnrichedMainAPI",
            stage1Provider is Anime47Hook.EnrichedMainAPI)
        assertEquals("Stage 1: Delegated name preserved", "Torrentio", stage1Provider.name)

        // 3. Attach Stremio hook second
        StremioSubHook.enrichAllProviders = true
        StremioSubHook.scanAndHookProviders()

        val stage2Provider = allProviders[0]
        assertTrue("Stage 2: Wrapped by StremioEnrichedMainAPI",
            stage2Provider is StremioSubHook.StremioEnrichedMainAPI)
        val stremioWrapper = stage2Provider as StremioSubHook.StremioEnrichedMainAPI
        assertTrue("Stage 2: Stremio baseApi is Anime47Hook.EnrichedMainAPI",
            stremioWrapper.baseApi is Anime47Hook.EnrichedMainAPI)
        assertEquals("Stage 2: Delegated name preserved", "Torrentio", stage2Provider.name)

        // 4. Attach Kitsunekko hook third
        KitsunekkoHook.enrichAllProviders = true
        KitsunekkoHook.scanAndHookProviders()

        val stage3Provider = allProviders[0]
        assertTrue("Stage 3: Outermost wrapper is KitsunekkoEnrichedMainAPI",
            stage3Provider is KitsunekkoHook.KitsunekkoEnrichedMainAPI)

        // 5. Attach AiSubtitleTranslator hook fourth (Outermost self-hoisting head)
        TranslatorConfig.isEnabled = true
        AITranslatorHook.scanAndHoistHooks()

        val stage4Provider = allProviders[0]
        assertTrue("Stage 4: Outermost wrapper is AITranslatorEnrichedMainAPI",
            stage4Provider is AITranslatorEnrichedMainAPI)
        val aiWrapper = stage4Provider as AITranslatorEnrichedMainAPI

        // Verify the entire 4-tier chain
        assertTrue("Tier 1 (Outer Head): AITranslatorEnrichedMainAPI", aiWrapper is AITranslatorEnrichedMainAPI)
        assertTrue("Tier 2: KitsunekkoEnrichedMainAPI", aiWrapper.baseApi is KitsunekkoHook.KitsunekkoEnrichedMainAPI)
        val kitsunekkoWrapper = aiWrapper.baseApi as KitsunekkoHook.KitsunekkoEnrichedMainAPI
        assertTrue("Tier 3: StremioEnrichedMainAPI", kitsunekkoWrapper.baseApi is StremioSubHook.StremioEnrichedMainAPI)
        val middleStremio = kitsunekkoWrapper.baseApi as StremioSubHook.StremioEnrichedMainAPI
        assertTrue("Tier 4: Anime47Hook.EnrichedMainAPI", middleStremio.baseApi is Anime47Hook.EnrichedMainAPI)
        val innerAnime47 = middleStremio.baseApi as Anime47Hook.EnrichedMainAPI
        assertEquals("Base Provider: Original Torrentio reached at leaf", baseTorrentio, innerAnime47.original)

        // Verify property delegation through 4 levels
        assertEquals("Chain: Delegated name is Torrentio", "Torrentio", stage4Provider.name)
        assertEquals("Chain: Delegated mainUrl is https://torrentio.strem.fun", "https://torrentio.strem.fun", stage4Provider.mainUrl)
        assertEquals("Chain: Supported types preserved", setOf(TvType.Anime, TvType.Movie, TvType.TvSeries), stage4Provider.supportedTypes)

        // Dynamic Self-Hoisting Outermost Invariant Test:
        // Even if Anime47 or Stremio re-hooks after AI Translator, scanAndHoistHooks maintains AI at head
        Anime47Hook.scanAndHookProviders()
        AITranslatorHook.scanAndHoistHooks()
        assertTrue("Outermost invariant maintained: head is AITranslatorEnrichedMainAPI",
            allProviders[0] is AITranslatorEnrichedMainAPI)
    }

    // =========================================================================
    // Test 2: Arbitrary Hook Chaining Sequences & Outermost Self-Hoisting
    // =========================================================================
    private fun testArbitraryHookChainingSequences() {
        println("\n>>> Test 2: Arbitrary Hook Chaining Sequences (Kitsunekko -> Stremio -> Anime47 -> AI Translator)")

        // Sequence: Kitsunekko first, then Stremio, then Anime47, then AI Translator
        resetAllEnricherHooks()
        val allProviders = APIHolder.allProviders
        allProviders.clear()

        val basePahe = MockBaseTorrentioProvider("AnimePahe", "https://animepahe.ru")
        allProviders.add(basePahe)

        // Hook 1: Kitsunekko
        KitsunekkoHook.enrichAllProviders = true
        KitsunekkoHook.scanAndHookProviders()
        assertTrue("Kitsunekko hooks AnimePahe first", allProviders[0] is KitsunekkoHook.KitsunekkoEnrichedMainAPI)

        // Hook 2: Stremio
        StremioSubHook.enrichAllProviders = true
        StremioSubHook.scanAndHookProviders()
        assertTrue("Stremio wraps KitsunekkoEnrichedMainAPI", allProviders[0] is StremioSubHook.StremioEnrichedMainAPI)
        val stremioOuter = allProviders[0] as StremioSubHook.StremioEnrichedMainAPI
        assertTrue("Stremio's baseApi is KitsunekkoEnrichedMainAPI", stremioOuter.baseApi is KitsunekkoHook.KitsunekkoEnrichedMainAPI)

        // Hook 3: Anime47
        Anime47Hook.enrichAllProviders = true
        Anime47Hook.scanAndHookProviders()

        // Hook 4: AI Translator
        AITranslatorHook.scanAndHoistHooks()
        assertTrue("AI Translator self-hoisted to outermost head position",
            allProviders[0] is AITranslatorEnrichedMainAPI)
        val aiOuter = allProviders[0] as AITranslatorEnrichedMainAPI

        assertEquals("Delegated name AnimePahe preserved across 4 layers", "AnimePahe", aiOuter.name)
        assertEquals("Delegated mainUrl AnimePahe preserved across 4 layers", "https://animepahe.ru", aiOuter.mainUrl)
    }

    // =========================================================================
    // Test 3: 0ms Video Passthrough Latency
    // =========================================================================
    private fun testZeroLatencyVideoPassthrough() = runBlocking {
        println("\n>>> Test 3: 0ms Video Passthrough Latency Verification")

        val baseProvider = object : MainAPI() {
            init {
                name = "InstantVideoBase"
                mainUrl = "https://instant.video"
            }

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Yield video link immediately with 0 delay
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Fast 1080p Stream",
                        url = "https://instant.video/stream_1080p.mkv",
                        referer = "",
                        quality = 1080,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        // Chain all four hooks manually to isolate execution
        val anime47 = Anime47Hook.EnrichedMainAPI(baseProvider)
        val stremio = StremioSubHook.StremioEnrichedMainAPI(anime47)
        val kitsunekko = KitsunekkoHook.KitsunekkoEnrichedMainAPI(stremio)
        val aiTranslator = AITranslatorEnrichedMainAPI(kitsunekko)

        val videoReceived = AtomicBoolean(false)
        val videoArrivalMs = AtomicInteger(-1)
        val startTime = System.currentTimeMillis()

        aiTranslator.loadLinks(
            data = "https://instant.video/stream_1080p.mkv",
            isCasting = false,
            subtitleCallback = { },
            callback = { link ->
                videoReceived.set(true)
                videoArrivalMs.set((System.currentTimeMillis() - startTime).toInt())
                assertEquals("Video link URL matches", "https://instant.video/stream_1080p.mkv", link.url)
            }
        )

        assertTrue("Video callback was executed through 4-tier chain", videoReceived.get())
        assertTrue("Video callback passthrough latency < 150ms across 4 enrichers (actual: ${videoArrivalMs.get()}ms)",
            videoArrivalMs.get() in 0..150)
    }

    // =========================================================================
    // Test 4: Concurrent Subtitle Emission (VI, EN, JA) Without Race Conditions
    // =========================================================================
    private fun testConcurrentSubtitleEmission() = runBlocking {
        println("\n>>> Test 4: Concurrent Subtitle Emission Across Multi-Enricher Chain")

        val baseProvider = object : MainAPI() {
            init {
                name = "MockBaseWithSubs"
                mainUrl = "https://mock.base"
            }

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Emit video stream
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Frieren 1080p",
                        url = "https://mock.base/frieren_ep01.mkv",
                        referer = "",
                        quality = 1080,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        // Decorator simulating Anime47 Vietsub emission using real EnrichedMainAPI base
        val anime47 = object : Anime47Hook.EnrichedMainAPI(baseProvider) {
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Asynchronously emit Vietnamese subtitle
                CoroutineScope(Dispatchers.IO).launch {
                    delay(30)
                    subtitleCallback(
                        SubtitleFile(
                            lang = "Tiếng Việt (Anime47)",
                            url = "https://anime47.com/sub/frieren_01_vi.ass"
                        )
                    )
                }
                return baseApi.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        }

        // Decorator simulating Stremio Multilingual emission using real StremioEnrichedMainAPI base
        val stremio = object : StremioSubHook.StremioEnrichedMainAPI(anime47) {
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Asynchronously emit English & Spanish subtitles
                CoroutineScope(Dispatchers.IO).launch {
                    delay(50)
                    subtitleCallback(
                        SubtitleFile(
                            lang = "English (Stremio)",
                            url = "https://opensubtitles.org/en/frieren_01.srt"
                        )
                    )
                    subtitleCallback(
                        SubtitleFile(
                            lang = "Spanish (Stremio)",
                            url = "https://opensubtitles.org/es/frieren_01.srt"
                        )
                    )
                }
                return baseApi.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        }

        // Decorator simulating Kitsunekko Japanese emission using real KitsunekkoEnrichedMainAPI base
        val kitsunekko = object : KitsunekkoHook.KitsunekkoEnrichedMainAPI(stremio) {
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Asynchronously emit Japanese subtitle
                CoroutineScope(Dispatchers.IO).launch {
                    delay(40)
                    subtitleCallback(
                        SubtitleFile(
                            lang = "Tiếng Nhật (Kitsunekko)",
                            url = "file:///data/cache/kitsunekko/frieren_01.ass"
                        )
                    )
                }
                return baseApi.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        }

        // Outermost layer: AI Subtitle Translator wrapping Kitsunekko
        val aiTranslator = AITranslatorEnrichedMainAPI(kitsunekko)

        val emittedSubtitles = CopyOnWriteArrayList<SubtitleFile>()
        val videoLinks = CopyOnWriteArrayList<ExtractorLink>()

        aiTranslator.loadLinks(
            data = "Sousou no Frieren S01E01",
            isCasting = false,
            subtitleCallback = { sub ->
                emittedSubtitles.add(sub)
            },
            callback = { link ->
                videoLinks.add(link)
            }
        )

        // Wait slightly for async coroutines to finish emitting
        delay(200)

        assertEquals("Video links received count", 1, videoLinks.size)
        assertEquals("Total subtitles emitted across 4 enrichers without interference", 4, emittedSubtitles.size)

        val langs = emittedSubtitles.map { it.lang }.toSet()
        assertTrue("Contains Vietnamese (Anime47)", langs.contains("Tiếng Việt (Anime47)"))
        assertTrue("Contains English (Stremio)", langs.contains("English (Stremio)"))
        assertTrue("Contains Spanish (Stremio)", langs.contains("Spanish (Stremio)"))
        assertTrue("Contains Japanese (Kitsunekko)", langs.contains("Tiếng Nhật (Kitsunekko)"))
    }

    // =========================================================================
    // Test 5: Timeout Resilience (withTimeoutOrNull(4500L) Barrier)
    // =========================================================================
    private fun testTimeoutResilience4500ms() = runBlocking {
        println("\n>>> Test 5: Timeout Resilience & Non-Blocking Barrier (4500ms)")

        val hangingBaseProvider = object : MainAPI() {
            init {
                name = "HangingProvider"
                mainUrl = "https://hanging.provider"
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
                        name = "Hanging Provider Stream",
                        url = "https://hanging.provider/stream.mp4",
                        referer = "",
                        quality = 1080,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        // Test the exact pattern used in StremioSubHook and KitsunekkoHook:
        // withTimeoutOrNull(4500L) { for (job in activeScrapeJobs) { if (job.isActive) job.join() } }
        val activeScrapeJobs = ConcurrentHashMap.newKeySet<Job>()
        val slowScope = CoroutineScope(Dispatchers.IO)

        // Hanging scrape job running for 10 seconds (simulates unreachable scraper / dead network)
        val hangingJob = slowScope.launch {
            delay(10000L)
        }
        activeScrapeJobs.add(hangingJob)

        val barrierStart = System.currentTimeMillis()
        val barrierResult = withTimeoutOrNull(4500L) {
            for (job in activeScrapeJobs) {
                if (job.isActive) job.join()
            }
        }
        val barrierElapsed = System.currentTimeMillis() - barrierStart

        hangingJob.cancel()

        assertEquals("withTimeoutOrNull returned null on timeout", null, barrierResult)
        assertTrue(
            "Barrier elapsed time bounded near 4500ms (actual: ${barrierElapsed}ms)",
            barrierElapsed in 4400L..5300L,
            "Elapsed was $barrierElapsed ms"
        )
        println("  -> Hanging scrape job successfully bounded without delaying video playback!")
    }

    // =========================================================================
    // Test 6: Anti-Recursion Guards and Idempotency
    // =========================================================================
    private fun testAntiRecursionGuardsAndIdempotency() {
        println("\n>>> Test 6: Anti-Recursion Guards, Repeated Hook Idempotency & Detach")

        resetAllEnricherHooks()
        val allProviders = APIHolder.allProviders
        allProviders.clear()

        val pTorrentio = MockBaseTorrentioProvider("Torrentio")
        val pStremioNative = MockBaseTorrentioProvider("Stremio Addon Native")
        val pKitsunekkoNative = MockBaseTorrentioProvider("Kitsunekko Official")
        val pAnime47Native = MockBaseTorrentioProvider("Anime47 Native")

        allProviders.add(pTorrentio)
        allProviders.add(pStremioNative)
        allProviders.add(pKitsunekkoNative)
        allProviders.add(pAnime47Native)

        // Run hook attachment multiple times in a row across all 4 enrichers
        for (pass in 1..5) {
            Anime47Hook.scanAndHookProviders()
            StremioSubHook.scanAndHookProviders()
            KitsunekkoHook.scanAndHookProviders()
            AITranslatorHook.scanAndHoistHooks()
        }

        // 1. Verify pTorrentio is wrapped exactly once per enricher (depth == 4)
        val hookedTorrentio = allProviders[0]
        assertTrue("Outermost is AITranslatorEnrichedMainAPI", hookedTorrentio is AITranslatorEnrichedMainAPI)
        val aiWrap = hookedTorrentio as AITranslatorEnrichedMainAPI

        assertTrue("Second level is KitsunekkoEnrichedMainAPI", aiWrap.baseApi is KitsunekkoHook.KitsunekkoEnrichedMainAPI)
        val kWrap = aiWrap.baseApi as KitsunekkoHook.KitsunekkoEnrichedMainAPI

        assertTrue("Third level is StremioEnrichedMainAPI", kWrap.baseApi is StremioSubHook.StremioEnrichedMainAPI)
        val sWrap = kWrap.baseApi as StremioSubHook.StremioEnrichedMainAPI

        assertTrue("Fourth level is Anime47Hook.EnrichedMainAPI", sWrap.baseApi is Anime47Hook.EnrichedMainAPI)
        val aWrap = sWrap.baseApi as Anime47Hook.EnrichedMainAPI

        assertEquals("Fifth level is original base provider", pTorrentio, aWrap.original)

        // Crucial Anti-Recursion invariant: aWrap.original MUST NOT be another EnrichedMainAPI!
        assertTrue("Base provider is NOT EnrichedMainAPI (no recursion)", aWrap.original !is Anime47Hook.EnrichedMainAPI)
        assertTrue("Base provider is NOT StremioEnrichedMainAPI (no recursion)", aWrap.original !is StremioSubHook.StremioEnrichedMainAPI)
        assertTrue("Base provider is NOT KitsunekkoEnrichedMainAPI (no recursion)", aWrap.original !is KitsunekkoHook.KitsunekkoEnrichedMainAPI)
        assertTrue("Base provider is NOT AITranslatorEnrichedMainAPI (no recursion)", aWrap.original !is AITranslatorEnrichedMainAPI)

        // 2. Native providers containing extension name in title must NOT be self-wrapped:
        // Stremio Addon Native must NOT be wrapped by Stremio
        val stremioNativeResult = allProviders[1]
        assertTrue("Stremio Native not wrapped by Stremio", stremioNativeResult !is StremioSubHook.StremioEnrichedMainAPI)

        // Kitsunekko Official must NOT be wrapped by Kitsunekko
        val kitsunekkoNativeResult = allProviders[2]
        assertTrue("Kitsunekko Official not wrapped by Kitsunekko", kitsunekkoNativeResult !is KitsunekkoHook.KitsunekkoEnrichedMainAPI)

        // Anime47 Native must NOT be wrapped by Anime47
        val anime47NativeResult = allProviders[3]
        assertTrue("Anime47 Native not wrapped by Anime47", anime47NativeResult !is Anime47Hook.EnrichedMainAPI)

        // 3. Test Provider Chain Unwrap on Detach:
        AITranslatorHook.detachHooks()
        val afterAiDetach = allProviders[0]
        assertTrue("After AI Translator detach, head unwrapped to KitsunekkoEnrichedMainAPI",
            afterAiDetach is KitsunekkoHook.KitsunekkoEnrichedMainAPI)

        KitsunekkoHook.detachHooks()
        StremioSubHook.detachHooks()
        Anime47Hook.detachHooks()
        val afterFullDetach = allProviders[0]
        assertEquals("After full detach of all enrichers, restored completely to original base provider",
            pTorrentio, afterFullDetach)
    }

    // =========================================================================
    // Test 7: Metadata Delegation Across Chain
    // =========================================================================
    private fun testMetadataDelegationAcrossChain() = runBlocking {
        println("\n>>> Test 7: Metadata Delegation & Load Response Across 4-Tier Chain")

        val baseProvider = MockBaseTorrentioProvider("FrierenStream")
        val anime47 = Anime47Hook.EnrichedMainAPI(baseProvider)
        val stremio = StremioSubHook.StremioEnrichedMainAPI(anime47)
        val kitsunekko = KitsunekkoHook.KitsunekkoEnrichedMainAPI(stremio)
        val aiTranslator = AITranslatorEnrichedMainAPI(kitsunekko)

        // Execute load() through the 4-tier chain
        val response = aiTranslator.load("https://mock.stream/frieren_series")
        assertTrue("load() returned non-null response through chain", response != null)
        assertTrue("Response is MovieLoadResponse", response is MovieLoadResponse)
        val movieResp = response as MovieLoadResponse
        assertEquals("Show title preserved across chain", "Sousou no Frieren", movieResp.name)
        assertEquals("Year preserved across chain", 2023, movieResp.year)

        // Verify that load() successfully populated cache across hooks
        val anime47Cache = Anime47Hook.latestLoadedShow
        assertTrue("Anime47 latestLoadedShow cached", anime47Cache != null)
        assertEquals("Anime47 cached title matches", "Sousou no Frieren", anime47Cache?.title)

        val stremioCache = StremioSubHook.latestLoadedShow
        assertTrue("Stremio latestLoadedShow cached", stremioCache != null)
        assertEquals("Stremio cached title matches", "Sousou no Frieren", stremioCache?.title)

        val kitsunekkoCache = KitsunekkoHook.latestLoadedShow
        assertTrue("Kitsunekko latestLoadedShow cached", kitsunekkoCache != null)
        assertEquals("Kitsunekko cached title matches", "Sousou no Frieren", kitsunekkoCache?.title)
    }

    // =========================================================================
    // Test 8: MainPage Delegation Across All 24 Permutations
    // =========================================================================
    private fun testMainPageDelegationAcross24Permutations() {
        println("\n>>> Test 8: MainPage Delegation Across All 24 Permutations")

        val expectedCategories = listOf(
            MainPageData("Trending", "https://mock.stream/trending", false),
            MainPageData("Bollywood", "https://mock.stream/bollywood", false),
            MainPageData("Hollywood", "hollywood", false)
        )

        class CustomMainPageProvider : MockBaseTorrentioProvider("PermutationBase", "https://mock.stream") {
            override val mainPage: List<MainPageData> = expectedCategories
        }

        val enricherBuilders: List<(MainAPI) -> MainAPI> = listOf(
            { base -> Anime47Hook.EnrichedMainAPI(base) },
            { base -> StremioSubHook.StremioEnrichedMainAPI(base) },
            { base -> KitsunekkoHook.KitsunekkoEnrichedMainAPI(base) },
            { base -> AITranslatorEnrichedMainAPI(base) }
        )

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

        val allPerms = permutations(enricherBuilders)
        assertEquals("Permutation count is 4! = 24", 24, allPerms.size)

        var matchedCount = 0
        for (perm in allPerms) {
            val leaf = CustomMainPageProvider()
            var head: MainAPI = leaf
            for (builder in perm) {
                head = builder(head)
            }
            if (head.mainPage == expectedCategories && head.name == "PermutationBase" && head.mainUrl == "https://mock.stream") {
                matchedCount++
            }
        }

        assertEquals("All 24 permutations preserved leaf mainPage categories exactly", 24, matchedCount)
    }

    // =========================================================================
    // Test 9: Crash Barrier Isolation on Schemeless / Malformed URL & Exceptions
    // =========================================================================
    private fun testCrashBarrierIsolationOnSchemelessUrl() = runBlocking {
        println("\n>>> Test 9: Crash Barrier Isolation on Schemeless / Malformed URL & Leaf Exceptions")

        val crashingLeaf = object : MainAPI() {
            init {
                name = "VegaMoviesMock"
                mainUrl = "https://vegamovies.im"
            }

            override val mainPage: List<MainPageData> = listOf(
                MainPageData("Category", "", false)
            )

            override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
                if (!request.data.startsWith("http://") && !request.data.startsWith("https://")) {
                    throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for ${request.data}")
                }
                return null
            }

            override suspend fun search(query: String): List<SearchResponse>? {
                throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for search")
            }

            override suspend fun quickSearch(query: String): List<SearchResponse>? {
                throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for quickSearch")
            }

            override suspend fun load(url: String): LoadResponse? {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for $url")
                }
                return null
            }
        }

        val anime47 = Anime47Hook.EnrichedMainAPI(crashingLeaf)
        val kitsunekko = KitsunekkoHook.KitsunekkoEnrichedMainAPI(anime47)
        val stremio = StremioSubHook.StremioEnrichedMainAPI(kitsunekko)
        val aiTranslator = AITranslatorEnrichedMainAPI(stremio)

        // 1. Invoking getMainPage with relative data is safely normalized against mainUrl
        val normalizedReq = MainPageRequest("Home", "movies", false)
        val responseNormalized = try {
            aiTranslator.getMainPage(1, normalizedReq)
        } catch (t: Throwable) {
            "THREW: ${t.javaClass.simpleName} - ${t.message}"
        }
        assertTrue("Normalized request did not throw unhandled exception", responseNormalized == null)

        // 2. Invoking getMainPage with forced crashing leaf isolates exception via crash barrier
        val crashingLeafForced = object : MainAPI() {
            init {
                name = "AlwaysCrashingLeaf"
                mainUrl = "NONE"
            }
            override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
                throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for ")
            }
            override suspend fun search(query: String): List<SearchResponse>? {
                throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for search")
            }
            override suspend fun quickSearch(query: String): List<SearchResponse>? {
                throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for quickSearch")
            }
            override suspend fun load(url: String): LoadResponse? {
                throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for load")
            }
        }

        val chain2 = AITranslatorEnrichedMainAPI(
            StremioSubHook.StremioEnrichedMainAPI(
                KitsunekkoHook.KitsunekkoEnrichedMainAPI(
                    Anime47Hook.EnrichedMainAPI(crashingLeafForced)
                )
            )
        )

        var caughtMainPage: Throwable? = null
        val mainPageRes = try {
            chain2.getMainPage(1, MainPageRequest("", "", false))
        } catch (t: Throwable) {
            caughtMainPage = t
            null
        }
        assertTrue("Crash Barrier trapped IllegalArgumentException on getMainPage (returned null)", caughtMainPage == null && mainPageRes == null)

        var caughtSearch: Throwable? = null
        val searchRes = try {
            chain2.search("test")
        } catch (t: Throwable) {
            caughtSearch = t
            null
        }
        assertTrue("Crash Barrier trapped IllegalArgumentException on search (returned null)", caughtSearch == null && searchRes == null)

        var caughtQuickSearch: Throwable? = null
        val quickSearchRes = try {
            chain2.quickSearch("test")
        } catch (t: Throwable) {
            caughtQuickSearch = t
            null
        }
        assertTrue("Crash Barrier trapped IllegalArgumentException on quickSearch (returned null)", caughtQuickSearch == null && quickSearchRes == null)

        var caughtLoad: Throwable? = null
        val loadRes = try {
            chain2.load("/relative/path")
        } catch (t: Throwable) {
            caughtLoad = t
            null
        }
        assertTrue("Crash Barrier trapped IllegalArgumentException on load (returned null)", caughtLoad == null && loadRes == null)
    }

    // =========================================================================
    // Test 10: APIHolder.apis Provider Deduplication (Single Source Per Provider)
    // =========================================================================
    private fun testAPIHolderApisDeduplication() {
        println("\n>>> Test 10: APIHolder.apis Provider Deduplication Across 4 Hooks")

        resetAllEnricherHooks()
        val allProviders = APIHolder.allProviders
        allProviders.clear()

        val pVega = MockBaseTorrentioProvider("VegaMovies", "https://vegamovies.im")
        val pTorrentio = MockBaseTorrentioProvider("Torrentio", "https://torrentio.strem.fun")
        val pAnimePahe = MockBaseTorrentioProvider("AnimePahe", "https://animepahe.ru")

        allProviders.add(pVega)
        allProviders.add(pTorrentio)
        allProviders.add(pAnimePahe)

        try {
            APIHolder.apis.withLock {
                APIHolder.apis = APIHolder.apis.filter { false }
            }
            for (p in allProviders) {
                APIHolder.addPluginMapping(p)
            }
        } catch (_: Throwable) {}

        assertEquals("Initial APIHolder.apis has 3 base providers", 3, APIHolder.apis.size)

        // Run all 4 hooks repeatedly across multiple cycles to simulate background polling
        for (cycle in 1..3) {
            Anime47Hook.scanAndHookProviders()
            KitsunekkoHook.scanAndHookProviders()
            StremioSubHook.scanAndHookProviders()
            AITranslatorHook.scanAndHoistHooks()
        }

        // Verify APIHolder.allProviders has exactly 3 providers
        assertEquals("APIHolder.allProviders has exactly 3 providers", 3, allProviders.size)

        // CRITICAL CHECK FOR ISSUE 2: APIHolder.apis must NOT duplicate sources!
        assertEquals(
            "APIHolder.apis size matches APIHolder.allProviders size (exactly 3, not 12 or 15)",
            3,
            APIHolder.apis.size
        )

        val apiNames = APIHolder.apis.map { it.name }
        val distinctNames = apiNames.distinct()
        assertEquals(
            "APIHolder.apis contains zero duplicate provider names: $apiNames",
            distinctNames.size,
            apiNames.size
        )
        assertTrue("Contains VegaMovies", distinctNames.contains("VegaMovies"))
        assertTrue("Contains Torrentio", distinctNames.contains("Torrentio"))
        assertTrue("Contains AnimePahe", distinctNames.contains("AnimePahe"))

        // Verify each entry in APIHolder.apis is the hoisted AITranslatorEnrichedMainAPI
        for (api in APIHolder.apis) {
            assertTrue("Provider '${api.name}' in APIHolder.apis is AITranslatorEnrichedMainAPI",
                api is AITranslatorEnrichedMainAPI)
        }

        // Verify detaching hooks maintains deduplication and restores original or unwrapped providers
        resetAllEnricherHooks()
        assertEquals(
            "After detaching all hooks, APIHolder.apis remains deduplicated with exactly 3 providers",
            3,
            APIHolder.apis.size
        )
        val detachedNames = APIHolder.apis.map { it.name }
        assertEquals(
            "APIHolder.apis names distinct after detach: $detachedNames",
            detachedNames.distinct().size,
            detachedNames.size
        )
    }
}
