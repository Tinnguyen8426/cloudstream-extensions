@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.test.e2e

import com.anime47.enricher.Anime47Hook
import com.kitsunekko.enricher.KitsunekkoHook
import com.stremio.subenricher.StremioSubHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.*
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone 8 Challenger Empirical Adversarial Test Harness.
 *
 * Exhaustively stress-tests:
 * 1. URL and Request Sanitization Matrix (schemeless, empty, relative, malformed, trailing slashes).
 * 2. Complete Crash Barrier Isolation against Hostile Leaf Exceptions (IllegalArgument, SocketTimeout,
 *    UnknownHost, NPE, IOOBE, Jackson parsing errors) across all 9 MainAPI entry points.
 * 3. Bidirectional Dynamic Property Delegation & Mutability across the entire 4-layer wrapper chain.
 * 4. High-stress concurrent registration and provider deduplication in APIHolder.apis.
 */
object AdversarialMilestone8ChallengerTest {

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

    private fun resetAllEnricherHooks() {
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

    // =========================================================================
    // SUITE 1: Extreme URL & Request Sanitization Matrix
    // =========================================================================
    private fun testUrlAndRequestSanitizationMatrix() = runBlocking {
        println("\n>>> Suite 1: Extreme URL & Request Sanitization Matrix")

        class InspectableLeafProvider(baseUrl: String) : MainAPI() {
            init {
                name = "InspectableLeaf"
                mainUrl = baseUrl
            }
            var lastReceivedMainPageData: String? = null
            var lastReceivedLoadUrl: String? = null

            override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
                lastReceivedMainPageData = request.data
                // Verify OkHttp would accept this scheme
                if (request.data.isNotBlank() && !request.data.startsWith("http://") && !request.data.startsWith("https://")) {
                    throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for ${request.data}")
                }
                return newHomePageResponse(request.name, emptyList(), false)
            }

            override suspend fun load(url: String): LoadResponse? {
                lastReceivedLoadUrl = url
                if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for $url")
                }
                return newMovieLoadResponse("TestMovie", url, TvType.Movie, url)
            }
        }

        // Test with standard base URL: "https://vegamovies.im"
        val leaf1 = InspectableLeafProvider("https://vegamovies.im")
        val chain1 = AITranslatorEnrichedMainAPI(
            StremioSubHook.StremioEnrichedMainAPI(
                KitsunekkoHook.KitsunekkoEnrichedMainAPI(
                    Anime47Hook.EnrichedMainAPI(leaf1)
                )
            )
        )

        val testCases = listOf(
            // rawData -> expectedResolvedData
            "" to "https://vegamovies.im",
            "/trending" to "https://vegamovies.im/trending",
            "trending" to "https://vegamovies.im/trending",
            "//nested/path" to "https://vegamovies.im//nested/path",
            "/category/action?page=2&sort=latest" to "https://vegamovies.im/category/action?page=2&sort=latest",
            "/item#reviews" to "https://vegamovies.im/item#reviews",
            "https://cdn.external.com/api/feed" to "https://cdn.external.com/api/feed",
            "http://mirror.vegamovies.im/list" to "http://mirror.vegamovies.im/list"
        )

        for ((input, expected) in testCases) {
            val req = MainPageRequest("Cat", input, false)
            val res = chain1.getMainPage(1, req)
            assertTrue("getMainPage accepted '$input' without crash", res != null)
            assertEquals("getMainPage resolved '$input' correctly", expected, leaf1.lastReceivedMainPageData)

            val loadRes = chain1.load(input)
            assertTrue("load accepted '$input' without crash", loadRes != null)
            assertEquals("load resolved '$input' correctly", expected, leaf1.lastReceivedLoadUrl)
        }

        // Test with trailing slash in mainUrl: "https://vegamovies.im/"
        val leaf2 = InspectableLeafProvider("https://vegamovies.im/")
        val chain2 = AITranslatorEnrichedMainAPI(
            StremioSubHook.StremioEnrichedMainAPI(
                KitsunekkoHook.KitsunekkoEnrichedMainAPI(
                    Anime47Hook.EnrichedMainAPI(leaf2)
                )
            )
        )

        val resTrailing = chain2.getMainPage(1, MainPageRequest("Home", "/movies", false))
        assertTrue("Trailing slash base accepted without crash", resTrailing != null)
        assertEquals("Trailing slash correctly stripped before appending path",
            "https://vegamovies.im/movies", leaf2.lastReceivedMainPageData)

        // Test with empty base URL ("") and relative path
        // Leaf will throw IllegalArgumentException on schemeless path, but crash barrier must catch it
        val leafEmptyBase = InspectableLeafProvider("")
        val chainEmptyBase = AITranslatorEnrichedMainAPI(
            StremioSubHook.StremioEnrichedMainAPI(
                KitsunekkoHook.KitsunekkoEnrichedMainAPI(
                    Anime47Hook.EnrichedMainAPI(leafEmptyBase)
                )
            )
        )

        var caughtEx: Throwable? = null
        val emptyBaseRes = try {
            chainEmptyBase.getMainPage(1, MainPageRequest("EmptyBase", "/relative", false))
        } catch (t: Throwable) {
            caughtEx = t
            null
        }
        assertTrue("Crash barrier gracefully trapped empty base URL leaf exception on getMainPage",
            caughtEx == null && emptyBaseRes == null)

        val emptyBaseLoadRes = try {
            chainEmptyBase.load("/relative")
        } catch (t: Throwable) {
            caughtEx = t
            null
        }
        assertTrue("Crash barrier gracefully trapped empty base URL leaf exception on load",
            caughtEx == null && emptyBaseLoadRes == null)
    }

    // =========================================================================
    // SUITE 2: Hostile Leaf Exception Injection Across All Entry Points
    // =========================================================================
    private fun testHostileLeafExceptionInjection() = runBlocking {
        println("\n>>> Suite 2: Hostile Leaf Exception Injection Across All 9 Entry Points")

        val hostileExceptions = listOf(
            IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for "),
            SocketTimeoutException("Read timed out after 30000ms"),
            UnknownHostException("Unable to resolve host vegamovies.im: No address associated with hostname"),
            NullPointerException("Scraper element query returned null"),
            IndexOutOfBoundsException("Index 0 out of bounds for length 0"),
            IllegalStateException("Provider state is uninitialized"),
            RuntimeException("Generic scraper failure occurred"),
            com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character '{'")
        )

        for (ex in hostileExceptions) {
            val exName = ex.javaClass.simpleName

            val hostileLeaf = object : MainAPI() {
                init {
                    name = "HostileLeaf_$exName"
                    mainUrl = "https://hostile.leaf"
                }

                override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
                    throw ex
                }

                override suspend fun search(query: String): List<SearchResponse>? {
                    throw ex
                }

                override suspend fun search(query: String, page: Int): SearchResponseList? {
                    throw ex
                }

                override suspend fun quickSearch(query: String): List<SearchResponse>? {
                    throw ex
                }

                override suspend fun load(url: String): LoadResponse? {
                    throw ex
                }

                override suspend fun loadLinks(
                    data: String,
                    isCasting: Boolean,
                    subtitleCallback: (SubtitleFile) -> Unit,
                    callback: (ExtractorLink) -> Unit
                ): Boolean {
                    throw ex
                }

                override suspend fun extractorVerifierJob(extractorData: String?) {
                    throw ex
                }

                override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? {
                    throw ex
                }

                override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
                    throw ex
                }
            }

            // Wrap in full 4-layer chain
            val chain = AITranslatorEnrichedMainAPI(
                StremioSubHook.StremioEnrichedMainAPI(
                    KitsunekkoHook.KitsunekkoEnrichedMainAPI(
                        Anime47Hook.EnrichedMainAPI(hostileLeaf)
                    )
                )
            )

            // 1. getMainPage
            var err1: Throwable? = null
            val r1 = try { chain.getMainPage(1, MainPageRequest("test", "/test", false)) } catch (t: Throwable) { err1 = t; null }
            assertTrue("Barrier trapped $exName on getMainPage", err1 == null && r1 == null)

            // 2. search
            var err2: Throwable? = null
            val r2 = try { chain.search("test") } catch (t: Throwable) { err2 = t; null }
            assertTrue("Barrier trapped $exName on search", err2 == null && r2 == null)

            // 3. search with page
            var err3: Throwable? = null
            val r3 = try { chain.search("test", 1) } catch (t: Throwable) { err3 = t; null }
            assertTrue("Barrier trapped $exName on paginated search", err3 == null && r3 == null)

            // 4. quickSearch
            var err4: Throwable? = null
            val r4 = try { chain.quickSearch("test") } catch (t: Throwable) { err4 = t; null }
            assertTrue("Barrier trapped $exName on quickSearch", err4 == null && r4 == null)

            // 5. load
            var err5: Throwable? = null
            val r5 = try { chain.load("/movie/123") } catch (t: Throwable) { err5 = t; null }
            assertTrue("Barrier trapped $exName on load", err5 == null && r5 == null)

            // 6. loadLinks
            var err6: Throwable? = null
            val r6 = try { chain.loadLinks("data", false, {}, {}) } catch (t: Throwable) { err6 = t; null }
            assertTrue("Barrier trapped $exName on loadLinks", err6 == null && r6 == false)

            // 7. extractorVerifierJob
            var err7: Throwable? = null
            try { chain.extractorVerifierJob("extData") } catch (t: Throwable) { err7 = t }
            assertTrue("Barrier trapped $exName on extractorVerifierJob", err7 == null)

            // 8. getVideoInterceptor
            var err8: Throwable? = null
            val r8 = try {
                chain.getVideoInterceptor(
                    ExtractorLink(
                        source = "HostileLeaf",
                        name = "Fast",
                        url = "https://mock.vid",
                        referer = "",
                        quality = 1080,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            } catch (t: Throwable) { err8 = t; null }
            assertTrue("Barrier trapped $exName on getVideoInterceptor", err8 == null && r8 == null)

            // 9. getLoadUrl
            var err9: Throwable? = null
            val r9 = try { chain.getLoadUrl(SyncIdName.MyAnimeList, "12345") } catch (t: Throwable) { err9 = t; null }
            assertTrue("Barrier trapped $exName on getLoadUrl", err9 == null && r9 == null)
        }
    }

    // =========================================================================
    // SUITE 3: Bidirectional Dynamic Property Delegation & Mutability
    // =========================================================================
    private fun testDynamicPropertyDelegationAndMutability() = runBlocking {
        println("\n>>> Suite 3: Bidirectional Dynamic Property Delegation & Mutability")

        open class MutableBaseProvider : MainAPI() {
            init {
                name = "OriginalBaseName"
                mainUrl = "https://original-domain.com"
                lang = "vi"
                canBeOverridden = true
                sequentialMainPage = false
                sequentialMainPageDelay = 100L
                sequentialMainPageScrollDelay = 200L
                storedCredentials = "auth_token_initial"
            }

            override val vpnStatus: VPNStatus = VPNStatus.MightBeNeeded
            override val providerType: ProviderType = ProviderType.DirectProvider
            override val instantLinkLoading: Boolean = false

            override val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)
            override val hasMainPage: Boolean = true
            override val hasQuickSearch: Boolean = true
            override val usesWebView: Boolean = true
            override val hasChromecastSupport: Boolean = true
            override val hasDownloadSupport: Boolean = true

            override val loadLinksTimeoutMs: Long = 5000L
            override val getMainPageTimeoutMs: Long = 6000L
            override val searchTimeoutMs: Long = 7000L
            override val quickSearchTimeoutMs: Long = 8000L
            override val loadTimeoutMs: Long = 9000L

            var dynamicCategories: List<MainPageData> = listOf(
                MainPageData("Initial Category 1", "/cat1", false),
                MainPageData("Initial Category 2", "/cat2", false)
            )

            override val mainPage: List<MainPageData>
                get() = dynamicCategories
        }

        val leaf = MutableBaseProvider()
        val anime47 = Anime47Hook.EnrichedMainAPI(leaf)
        val kitsunekko = KitsunekkoHook.KitsunekkoEnrichedMainAPI(anime47)
        val stremio = StremioSubHook.StremioEnrichedMainAPI(kitsunekko)
        val head = AITranslatorEnrichedMainAPI(stremio)

        // 1. Initial State Assertions
        assertEquals("Delegated name matches initial leaf", "OriginalBaseName", head.name)
        assertEquals("Delegated mainUrl matches initial leaf", "https://original-domain.com", head.mainUrl)
        assertEquals("Delegated lang matches initial leaf", "vi", head.lang)
        assertEquals("Delegated vpnStatus matches initial leaf", VPNStatus.MightBeNeeded, head.vpnStatus)
        assertEquals("Delegated providerType matches initial leaf", ProviderType.DirectProvider, head.providerType)
        assertEquals("Delegated instantLinkLoading matches initial leaf", false, head.instantLinkLoading)
        assertEquals("Delegated canBeOverridden matches initial leaf", true, head.canBeOverridden)
        assertEquals("Delegated sequentialMainPage matches initial leaf", false, head.sequentialMainPage)
        assertEquals("Delegated sequentialMainPageDelay matches initial leaf", 100L, head.sequentialMainPageDelay)
        assertEquals("Delegated sequentialMainPageScrollDelay matches initial leaf", 200L, head.sequentialMainPageScrollDelay)
        assertEquals("Delegated storedCredentials matches initial leaf", "auth_token_initial", head.storedCredentials)
        assertEquals("Delegated supportedTypes matches initial leaf", setOf(TvType.Movie, TvType.TvSeries), head.supportedTypes)
        assertEquals("Delegated hasMainPage matches initial leaf", true, head.hasMainPage)
        assertEquals("Delegated hasQuickSearch matches initial leaf", true, head.hasQuickSearch)
        assertEquals("Delegated usesWebView matches initial leaf", true, head.usesWebView)
        assertEquals("Delegated hasChromecastSupport matches initial leaf", true, head.hasChromecastSupport)
        assertEquals("Delegated hasDownloadSupport matches initial leaf", true, head.hasDownloadSupport)

        // Timeouts
        assertEquals("Delegated loadLinksTimeoutMs matches", 5000L, head.loadLinksTimeoutMs)
        assertEquals("Delegated getMainPageTimeoutMs matches", 6000L, head.getMainPageTimeoutMs)
        assertEquals("Delegated searchTimeoutMs matches", 7000L, head.searchTimeoutMs)
        assertEquals("Delegated quickSearchTimeoutMs matches", 8000L, head.quickSearchTimeoutMs)
        assertEquals("Delegated loadTimeoutMs matches", 9000L, head.loadTimeoutMs)

        // mainPage dynamic delegation
        assertEquals("head.mainPage returns dynamic categories size", 2, head.mainPage.size)
        assertEquals("head.mainPage category 1 name", "Initial Category 1", head.mainPage[0].name)

        // 2. Dynamic Mutation on Leaf -> Read from Head
        leaf.mainUrl = "https://mirror-2.newdomain.org"
        leaf.name = "MutatedLeafName"
        leaf.lang = "ja"
        leaf.storedCredentials = "updated_auth_jwt"
        leaf.sequentialMainPage = true
        leaf.dynamicCategories = listOf(
            MainPageData("Anime Top", "/anime/top", true),
            MainPageData("Movies Hot", "/movies/hot", false),
            MainPageData("TV Latest", "/tv/latest", false)
        )

        assertEquals("head.mainUrl dynamically reflects leaf mutation", "https://mirror-2.newdomain.org", head.mainUrl)
        assertEquals("head.name dynamically reflects leaf mutation", "MutatedLeafName", head.name)
        assertEquals("head.lang dynamically reflects leaf mutation", "ja", head.lang)
        assertEquals("head.storedCredentials dynamically reflects leaf mutation", "updated_auth_jwt", head.storedCredentials)
        assertEquals("head.sequentialMainPage dynamically reflects leaf mutation", true, head.sequentialMainPage)
        assertEquals("head.mainPage dynamically reflects leaf new categories size", 3, head.mainPage.size)
        assertEquals("head.mainPage category 0 matches", "Anime Top", head.mainPage[0].name)

        // 3. Dynamic Mutation on Head -> Reflected in Leaf
        head.mainUrl = "https://outermost-override.com"
        head.name = "OutermostOverrideName"
        head.lang = "en"
        head.storedCredentials = "outermost_secret_token"
        head.canBeOverridden = false
        head.sequentialMainPage = false
        head.sequentialMainPageDelay = 500L
        head.sequentialMainPageScrollDelay = 600L

        assertEquals("leaf.mainUrl received write from head", "https://outermost-override.com", leaf.mainUrl)
        assertEquals("leaf.name received write from head", "OutermostOverrideName", leaf.name)
        assertEquals("leaf.lang received write from head", "en", leaf.lang)
        assertEquals("leaf.storedCredentials received write from head", "outermost_secret_token", leaf.storedCredentials)
        assertEquals("leaf.canBeOverridden received write from head", false, leaf.canBeOverridden)
        assertEquals("leaf.sequentialMainPage received write from head", false, leaf.sequentialMainPage)
        assertEquals("leaf.sequentialMainPageDelay received write from head", 500L, leaf.sequentialMainPageDelay)
        assertEquals("leaf.sequentialMainPageScrollDelay received write from head", 600L, leaf.sequentialMainPageScrollDelay)
    }

    // =========================================================================
    // SUITE 4: Source List Deduplication & APIHolder Concurrency Stress
    // =========================================================================
    private fun testAPIHolderDeduplicationAndLifecycleStress() = runBlocking {
        println("\n>>> Suite 4: Source List Deduplication & APIHolder Concurrency Stress")

        resetAllEnricherHooks()

        val providerCount = 12
        val baseProviders = (1..providerCount).map { id ->
            object : MainAPI() {
                init {
                    name = "UniqueProvider_$id"
                    mainUrl = "https://provider$id.test"
                }
            }
        }

        APIHolder.allProviders.addAll(baseProviders)
        try {
            APIHolder.apis.withLock {
                APIHolder.apis = APIHolder.apis.filter { false }
            }
            for (p in baseProviders) {
                APIHolder.addPluginMapping(p)
            }
        } catch (_: Throwable) {}

        assertEquals("Initial APIHolder.allProviders count", providerCount, APIHolder.allProviders.size)
        assertEquals("Initial APIHolder.apis count", providerCount, APIHolder.apis.size)

        // Perform 25 randomized interleaved scan cycles
        val enrichers = listOf("Anime47", "Kitsunekko", "Stremio", "AiTranslator")
        for (cycle in 1..25) {
            val shuffled = enrichers.shuffled()
            for (enricher in shuffled) {
                when (enricher) {
                    "Anime47" -> {
                        Anime47Hook.enrichAllProviders = true
                        Anime47Hook.scanAndHookProviders()
                    }
                    "Kitsunekko" -> {
                        KitsunekkoHook.enrichAllProviders = true
                        KitsunekkoHook.scanAndHookProviders()
                    }
                    "Stremio" -> {
                        StremioSubHook.enrichAllProviders = true
                        StremioSubHook.scanAndHookProviders()
                    }
                    "AiTranslator" -> {
                        TranslatorConfig.isEnabled = true
                        AITranslatorHook.scanAndHoistHooks()
                    }
                }
            }
            // Ensure AI Translator hoisting
            AITranslatorHook.scanAndHoistHooks()

            // Invariant 1: allProviders size must remain strictly providerCount
            assertEquals("Cycle $cycle: allProviders size bounded", providerCount, APIHolder.allProviders.size)

            // Invariant 2: apis size must remain strictly providerCount (NO 4x DUPLICATES!)
            assertEquals("Cycle $cycle: apis size bounded to providerCount (NO DUPLICATES)", providerCount, APIHolder.apis.size)

            // Invariant 3: Provider names in apis are 100% unique
            val distinctNames = APIHolder.apis.map { it.name }.distinct()
            assertEquals("Cycle $cycle: apis provider names distinct count", providerCount, distinctNames.size)

            // Invariant 4: Outermost wrapper in apis is always AITranslatorEnrichedMainAPI
            val nonAiWrappersInApis = APIHolder.apis.filter { it !is AITranslatorEnrichedMainAPI }
            assertTrue("Cycle $cycle: All providers in apis are AITranslatorEnrichedMainAPI",
                nonAiWrappersInApis.isEmpty(),
                "Found non-AI wrappers: ${nonAiWrappersInApis.map { it.javaClass.simpleName }}")
        }

        // Stress test concurrent calls to deduplicateApis() across 10 coroutines
        println("  [*] Running concurrent deduplication stress across 10 threads...")
        val jobs = (1..10).map {
            launch(Dispatchers.Default) {
                for (j in 1..50) {
                    Anime47Hook.deduplicateApis()
                    KitsunekkoHook.deduplicateApis()
                    StremioSubHook.deduplicateApis()
                    AITranslatorHook.deduplicateApis()
                }
            }
        }
        jobs.joinAll()

        assertEquals("After concurrent deduplication stress: apis size strictly intact",
            providerCount, APIHolder.apis.size)

        // Detach all hooks and verify full restoration without dangling duplicates
        AITranslatorHook.detachHooks()
        StremioSubHook.detachHooks()
        KitsunekkoHook.detachHooks()
        Anime47Hook.detachHooks()

        assertEquals("After full detach: allProviders size strictly intact",
            providerCount, APIHolder.allProviders.size)
        assertEquals("After full detach: apis size strictly intact",
            providerCount, APIHolder.apis.size)

        val namesAfterDetach = APIHolder.apis.map { it.name }
        assertEquals("After full detach: apis names distinct", providerCount, namesAfterDetach.distinct().size)

        val anyWrapperRemaining = APIHolder.apis.any {
            it is AITranslatorEnrichedMainAPI ||
            it is StremioSubHook.StremioEnrichedMainAPI ||
            it is KitsunekkoHook.KitsunekkoEnrichedMainAPI ||
            it is Anime47Hook.EnrichedMainAPI
        }
        assertTrue("After full detach: zero wrapper residue in apis", !anyWrapperRemaining)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" EMPIRICAL ADVERSARIAL CHALLENGER VERIFICATION HARNESS (MILESTONE 8)")
        println("================================================================================")

        try {
            testUrlAndRequestSanitizationMatrix()
            testHostileLeafExceptionInjection()
            testDynamicPropertyDelegationAndMutability()
            testAPIHolderDeduplicationAndLifecycleStress()
        } catch (t: Throwable) {
            System.err.println("[!] Fatal harness error: ${t.message}")
            t.printStackTrace(System.err)
            failed++
        }

        println("\n================================================================================")
        println(" CHALLENGER 1 ADVERSARIAL SUITE SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed assertions:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL ADVERSARIAL CHALLENGER TESTS PASSED SUCCESSFULLY!")
            System.exit(0)
        }
    }
}
