@file:Suppress("DEPRECATION")
package com.cloudstream.test.e2e

import com.anime47.enricher.Anime47Hook
import com.kitsunekko.enricher.KitsunekkoHook
import com.stremio.subenricher.StremioSubHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression suite for the "empty home pages" defect.
 *
 * Decorators must forward provider `MainPageRequest.data` and `load(url)` verbatim so
 * providers that build URLs from raw section data keep working. Only the core `fixUrl`
 * (called by APIRepository before delegation) is allowed to resolve relative URLs.
 */
object MainPageVerbatimForwardingTest {

    private val passedAssertions = AtomicInteger(0)
    private val failedAssertions = mutableListOf<String>()

    private fun assertTrue(message: String, condition: Boolean) {
        if (condition) {
            passedAssertions.incrementAndGet()
            println("  [PASS] $message")
        } else {
            val failMsg = "  [FAIL] $message: Expected true but was false"
            failedAssertions.add(failMsg)
            System.err.println(failMsg)
        }
    }

    private fun assertEquals(message: String, expected: Any?, actual: Any?) {
        if (expected == actual) {
            passedAssertions.incrementAndGet()
            println("  [PASS] $message")
        } else {
            val failMsg = "  [FAIL] $message: Expected '$expected' but was '$actual'"
            failedAssertions.add(failMsg)
            System.err.println(failMsg)
        }
    }

    /**
     * Simulates a real provider whose home sections build URLs from raw section data,
     * exactly like the providers broken by the old prefixing sanitizer.
     */
    class RealisticHomeProvider(
        nameVal: String = "RealisticProvider",
        mainUrlVal: String = "https://site.com"
    ) : MainAPI() {
        init {
            name = nameVal
            mainUrl = mainUrlVal
        }

        var lastReceivedData: String? = null
        var lastReceivedLoadUrl: String? = null
        val builtUrls = mutableListOf<String>()

        override val mainPage: List<MainPageData> = listOf(
            MainPageData("Phim Mới", "popular", false),
            MainPageData("Trang Chủ", "", false)
        )
        override val hasMainPage: Boolean get() = true
        override val getMainPageTimeoutMs: Long? get() = 4321L
        override val loadLinksTimeoutMs: Long? get() = 8765L

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            lastReceivedData = request.data
            builtUrls.add(buildUrl(request.data))
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }

        override suspend fun load(url: String): LoadResponse? {
            lastReceivedLoadUrl = url
            return newMovieLoadResponse(name, url, TvType.Movie, url)
        }

        fun buildUrl(data: String): String = if (data.isBlank()) mainUrl else "$mainUrl/$data"
    }

    private val wrappers: List<Pair<String, (MainAPI) -> MainAPI>> = listOf(
        "AITranslator" to { api: MainAPI -> AITranslatorEnrichedMainAPI(api) },
        "Stremio" to { api: MainAPI -> StremioSubHook.StremioEnrichedMainAPI(api) },
        "Kitsunekko" to { api: MainAPI -> KitsunekkoHook.KitsunekkoEnrichedMainAPI(api) },
        "Anime47" to { api: MainAPI -> Anime47Hook.EnrichedMainAPI(api) }
    )

    private fun buildChain(leaf: MainAPI, reverse: Boolean = false): MainAPI {
        val layers: List<(MainAPI) -> MainAPI> = listOf(
            { AITranslatorEnrichedMainAPI(it) },
            { StremioSubHook.StremioEnrichedMainAPI(it) },
            { KitsunekkoHook.KitsunekkoEnrichedMainAPI(it) },
            { Anime47Hook.EnrichedMainAPI(it) }
        )
        var head: MainAPI = leaf
        for (wrap in if (reverse) layers.reversed() else layers) {
            head = wrap(head)
        }
        return head
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("================================================================================")
        println(" REGRESSION SUITE: MAIN PAGE VERBATIM FORWARDING (EMPTY HOME PAGES DEFECT)")
        println("================================================================================")

        testRealProviderSectionForwarding()
        testVerbatimDataMatrix()
        testDelegation()
        testCrashBarrier()
        testCoreFixUrlResolution()

        println("================================================================================")
        println(" MAINPAGE VERBATIM SUMMARY: ${passedAssertions.get()} PASSED, ${failedAssertions.size} FAILED")
        println("================================================================================")

        if (failedAssertions.isNotEmpty()) {
            System.err.println("\nFailed assertions:")
            failedAssertions.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL MAIN PAGE VERBATIM FORWARDING TESTS PASSED SUCCESSFULLY!")
        }
    }

    /**
     * The exact user-reported scenario: sections "popular" and "" must reach the provider
     * untouched so it builds https://site.com/popular and https://site.com.
     */
    private suspend fun testRealProviderSectionForwarding() {
        println("\n>>> Test 1: Realistic provider section forwarding ('popular', '')")

        val sections = listOf(
            Triple("Phim Mới", "popular", "https://site.com/popular"),
            Triple("Trang Chủ", "", "https://site.com")
        )

        for ((wrapperName, wrap) in wrappers) {
            for ((sectionName, data, expectedUrl) in sections) {
                val leaf = RealisticHomeProvider()
                val head = wrap(leaf)
                val resp = head.getMainPage(1, MainPageRequest(sectionName, data, false))
                assertTrue("[$wrapperName] getMainPage('$sectionName') returned a response", resp != null)
                assertEquals("[$wrapperName] section '$sectionName' data forwarded verbatim", data, leaf.lastReceivedData)
                assertEquals("[$wrapperName] section '$sectionName' provider built expected URL", expectedUrl, leaf.builtUrls.last())
                assertTrue(
                    "[$wrapperName] section '$sectionName' URL has no doubled scheme prefix",
                    !leaf.builtUrls.last().contains("https://site.com/https://")
                )
            }
        }

        val chainLeaf = RealisticHomeProvider()
        val chain = buildChain(chainLeaf)
        for ((sectionName, data, expectedUrl) in sections) {
            val resp = chain.getMainPage(1, MainPageRequest(sectionName, data, false))
            assertTrue("[4-layer] getMainPage('$sectionName') returned a response", resp != null)
            assertEquals("[4-layer] section '$sectionName' data forwarded verbatim", data, chainLeaf.lastReceivedData)
            assertEquals("[4-layer] section '$sectionName' provider built expected URL", expectedUrl, chainLeaf.builtUrls.last())
            assertTrue(
                "[4-layer] section '$sectionName' URL has no doubled scheme prefix",
                !chainLeaf.builtUrls.last().contains("https://site.com/https://")
            )
        }
    }

    /**
     * Full matrix of provider-style section data and load payloads, through every single
     * wrapper and both chain directions. All values must arrive verbatim.
     */
    private suspend fun testVerbatimDataMatrix() {
        println("\n>>> Test 2: Verbatim data matrix across each wrapper and both chain directions")

        val dataVectors = listOf(
            "popular",
            "",
            "   ",
            "/trending",
            "trending",
            "//nested/path",
            "/category/action?page=2&sort=latest",
            "https://cdn.external.com/api/feed",
            "http://mirror.site.com/list",
            """{"category":"trending","page":1}""",
            """[{"key":"val"}]""",
            "urn:series:789",
            "kitsu:123",
            "magnet:?xt=urn:btih:3b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c",
            "data:application/json;base64,eyJrZXkiOiJ2YWx1ZSJ9",
            "822653",
            "tt0944947"
        )

        for ((wrapperName, wrap) in wrappers) {
            for (data in dataVectors) {
                val leaf = RealisticHomeProvider()
                val head = wrap(leaf)
                val resp = head.getMainPage(3, MainPageRequest("Section", data, true))
                assertTrue("[$wrapperName] getMainPage non-null for data '$data'", resp != null)
                assertEquals("[$wrapperName] data '$data' forwarded verbatim", data, leaf.lastReceivedData)
            }
        }

        for (reverse in listOf(false, true)) {
            val direction = if (reverse) "reverse" else "forward"
            for (data in dataVectors) {
                val leaf = RealisticHomeProvider()
                val chain = buildChain(leaf, reverse)
                val resp = chain.getMainPage(1, MainPageRequest("Section", data, false))
                assertTrue("[chain $direction] getMainPage non-null for data '$data'", resp != null)
                assertEquals("[chain $direction] data '$data' forwarded verbatim", data, leaf.lastReceivedData)
            }
        }
    }

    /**
     * Structural delegation must stay intact: sections declared by the provider and
     * timeout settings must be visible through the whole chain unchanged.
     */
    private fun testDelegation() {
        println("\n>>> Test 3: mainPage / hasMainPage / timeout delegation")

        val leaf = RealisticHomeProvider()
        leaf.sequentialMainPage = true
        val head = buildChain(leaf)

        assertEquals("mainPage list delegated unchanged", leaf.mainPage, head.mainPage)
        assertEquals("mainPage 'Phim Mới' data unchanged", "popular", head.mainPage[0].data)
        assertEquals("mainPage 'Trang Chủ' data unchanged", "", head.mainPage[1].data)
        assertTrue("hasMainPage delegated", head.hasMainPage)
        assertTrue("sequentialMainPage delegated", head.sequentialMainPage)
        assertEquals("getMainPageTimeoutMs delegated", 4321L, head.getMainPageTimeoutMs)
        assertEquals("loadLinksTimeoutMs delegated", 8765L, head.loadLinksTimeoutMs)
    }

    /**
     * The crash barrier must keep isolating leaf failures: null result, never an exception.
     */
    private suspend fun testCrashBarrier() {
        println("\n>>> Test 4: Crash barrier isolates leaf exceptions")

        val throwingLeaf = object : MainAPI() {
            init {
                name = "ThrowingLeaf"
                mainUrl = "https://site.com"
            }

            override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? =
                throw RuntimeException("home section crashed: ${request.data}")

            override suspend fun load(url: String): LoadResponse? =
                throw RuntimeException("load crashed: $url")

            override suspend fun search(query: String): List<SearchResponse>? = null
        }

        for ((wrapperName, wrap) in wrappers) {
            val head = wrap(throwingLeaf)
            var caught: Throwable? = null
            val mp = try {
                head.getMainPage(1, MainPageRequest("Section", "popular", false))
            } catch (t: Throwable) {
                caught = t
                null
            }
            assertTrue("[$wrapperName] getMainPage exception trapped", caught == null && mp == null)

            caught = null
            val ld = try {
                head.load("popular")
            } catch (t: Throwable) {
                caught = t
                null
            }
            assertTrue("[$wrapperName] load exception trapped", caught == null && ld == null)
        }

        val chain = buildChain(throwingLeaf)
        var caught: Throwable? = null
        val mp = try {
            chain.getMainPage(1, MainPageRequest("Section", "", false))
        } catch (t: Throwable) {
            caught = t
            null
        }
        assertTrue("[4-layer] getMainPage exception trapped", caught == null && mp == null)

        caught = null
        val ld = try {
            chain.load("/relative")
        } catch (t: Throwable) {
            caught = t
            null
        }
        assertTrue("[4-layer] load exception trapped", caught == null && ld == null)
    }

    /**
     * Documents the intended ownership of relative-URL resolution: the core fixUrl,
     * never the decorators.
     */
    private fun testCoreFixUrlResolution() {
        println("\n>>> Test 5: Core fixUrl owns relative URL resolution")

        val leaf = RealisticHomeProvider()
        assertEquals("Core fixUrl resolves '/trending' against mainUrl",
            "https://site.com/trending", leaf.fixUrl("/trending"))
        assertEquals("Core fixUrl resolves 'trending' against mainUrl",
            "https://site.com/trending", leaf.fixUrl("trending"))
        assertEquals("Core fixUrl keeps absolute URLs unchanged",
            "https://cdn.external.com/api/feed", leaf.fixUrl("https://cdn.external.com/api/feed"))
    }
}