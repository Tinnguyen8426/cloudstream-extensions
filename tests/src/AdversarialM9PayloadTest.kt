@file:Suppress("DEPRECATION")
package com.cloudstream.test.e2e

import com.anime47.enricher.Anime47Hook
import com.kitsunekko.enricher.KitsunekkoHook
import com.stremio.subenricher.StremioSubHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newHomePageResponse
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger Empirical Adversarial Test Suite for Milestone 9:
 * Non-HTTP & JSON Payload Preservation in `load(url)` and `getMainPage(request)`
 * Across All 4 Enricher Wrappers.
 */
object AdversarialM9PayloadTest {

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

    class TestLeafProvider(
        nameVal: String = "TestLeafProvider",
        mainUrlVal: String = "https://api.themoviedb.org"
    ) : MainAPI() {
        init {
            name = nameVal
            mainUrl = mainUrlVal
        }

        var lastReceivedLoadUrl: String? = null
        var lastReceivedMainPageData: String? = null
        var shouldThrowInLoad: Throwable? = null
        var shouldThrowInMainPage: Throwable? = null

        override suspend fun load(url: String): LoadResponse? {
            lastReceivedLoadUrl = url
            shouldThrowInLoad?.let { throw it }
            return newMovieLoadResponse(name, url, TvType.Movie, url) {
                this.year = 2024
            }
        }

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            lastReceivedMainPageData = request.data
            shouldThrowInMainPage?.let { throw it }
            return newHomePageResponse(request.name, emptyList(), false)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("================================================================================")
        println(" EMPIRICAL ADVERSARIAL CHALLENGER SUITE: M9 PAYLOAD PRESERVATION")
        println("================================================================================")

        testIndividualWrapperPayloadPreservation()
        testChained4LayerPayloadPreservation()
        testRelativeUrlResolutionAcrossChain()
        testAbsoluteUrlPreservationAcrossChain()
        testMainPageRequestPayloadPreservation()
        testCrashBarrierTrappingUnexpectedErrors()
        testArbitraryPermutationPayloadPreservation()

        println("================================================================================")
        println(" CHALLENGER M9 SUMMARY: ${passedAssertions.get()} PASSED, ${failedAssertions.size} FAILED")
        println("================================================================================")

        if (failedAssertions.isNotEmpty()) {
            System.err.println("\nFailed assertions:")
            failedAssertions.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL EMPIRICAL ADVERSARIAL PAYLOAD TESTS PASSED SUCCESSFULLY!")
        }
    }

    /**
     * Test 1: Verify each of the 4 wrapper classes preserves non-HTTP payloads when tested directly (1-to-1).
     */
    private suspend fun testIndividualWrapperPayloadPreservation() {
        println("\n>>> Test 1: Individual Wrapper Payload Preservation (1-to-1 Isolation)")

        val nonHttpVectors = listOf(
            // JSON Objects
            """{"id":822653,"type":"movie","aniId":null,"malId":null}""",
            """   {"id":822653}   """,
            "\n\r\t{\"id\":822653,\"name\":\"test\"}\r\n",
            """{"meta":{"id":822653,"seasons":[{"ep":1}]}}""",
            """{"title":"葬送のフリーレン","id":"822653","tags":["anime"]}""",
            "{}",
            "  {   }  ",

            // JSON Arrays
            """[{"id":1},{"id":2}]""",
            """["item1","item2"]""",
            "[1, 2, 3, 4]",
            "[]",
            "  [   ]  ",
            "[[1, 2], [3, 4]]",

            // Custom Schemes & URIs
            "kitsu:123",
            "mal:456",
            "urn:series:789",
            "urn:uuid:f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
            "magnet:?xt=urn:btih:3b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c",
            "data:application/json;base64,eyJrZXkiOiAidmFsdWUifQ==",
            "stremio:detail:series:tt0944947:1:1",
            "tmdb:822653",
            "simkl:anime/1234",
            "anilist:10087",
            "trakt:movie/avatar-2009",
            "imdb:tt0944947",
            "custom+scheme-1.0:data_payload",

            // Identifiers
            "822653",
            "0",
            "99999999999",
            "tt1234567",
            "tt0000001",
            "tt99999999"
        )

        for ((idx, vector) in nonHttpVectors.withIndex()) {
            val leaf = TestLeafProvider()
            val aiWrap = AITranslatorEnrichedMainAPI(leaf)
            val respAi = aiWrap.load(vector)
            assertTrue("AI wrapper load() succeeded for vector #$idx", respAi != null)
            assertEquals("AI wrapper preserved vector #$idx verbatim", vector, leaf.lastReceivedLoadUrl)

            val stremioWrap = StremioSubHook.StremioEnrichedMainAPI(leaf)
            val respStremio = stremioWrap.load(vector)
            assertTrue("Stremio wrapper load() succeeded for vector #$idx", respStremio != null)
            assertEquals("Stremio wrapper preserved vector #$idx verbatim", vector, leaf.lastReceivedLoadUrl)

            val kitsuWrap = KitsunekkoHook.KitsunekkoEnrichedMainAPI(leaf)
            val respKitsu = kitsuWrap.load(vector)
            assertTrue("Kitsunekko wrapper load() succeeded for vector #$idx", respKitsu != null)
            assertEquals("Kitsunekko wrapper preserved vector #$idx verbatim", vector, leaf.lastReceivedLoadUrl)

            val anime47Wrap = Anime47Hook.EnrichedMainAPI(leaf)
            val respA47 = anime47Wrap.load(vector)
            assertTrue("Anime47 wrapper load() succeeded for vector #$idx", respA47 != null)
            assertEquals("Anime47 wrapper preserved vector #$idx verbatim", vector, leaf.lastReceivedLoadUrl)
        }
    }

    /**
     * Test 2: Full 4-Tier Hook Chaining (AITranslator -> Stremio -> Kitsunekko -> Anime47 -> Leaf).
     */
    private suspend fun testChained4LayerPayloadPreservation() {
        println("\n>>> Test 2: 4-Tier Wrapper Chaining Payload Preservation")

        val complexVectors = mapOf(
            "User TMDB defect JSON" to """{"id":822653,"type":"movie","aniId":null,"malId":null}""",
            "Whitespace JSON Object" to "   \t\n{\"id\":822653,\"type\":\"series\"}\n\t   ",
            "Deep Nested Metadata" to """{"query":{"meta":{"show":{"id":822653,"seasons":[{"season":1,"episodes":[{"ep":1,"id":"ep_101"}]}]}}}}""",
            "JSON Array with spaces" to "  [ {\"key\": \"value\"}, {\"key2\": 42} ]  ",
            "Empty Object" to "{}",
            "Empty Array" to "[]",
            "Numeric TMDB ID" to "822653",
            "IMDb Alphanumeric ID" to "tt0944947",
            "Custom Sync Kitsu" to "kitsu:12345",
            "Custom Sync Mal" to "mal:67890",
            "URN Identifier" to "urn:series:game_of_thrones",
            "Magnet URI" to "magnet:?xt=urn:btih:3b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c&dn=Episode1",
            "Base64 Data URI" to "data:application/json;base64,eyJrZXkiOiJ2YWx1ZSJ9"
        )

        for ((label, vector) in complexVectors) {
            val leaf = TestLeafProvider()
            val anime47 = Anime47Hook.EnrichedMainAPI(leaf)
            val kitsu = KitsunekkoHook.KitsunekkoEnrichedMainAPI(anime47)
            val stremio = StremioSubHook.StremioEnrichedMainAPI(kitsu)
            val aiTranslator = AITranslatorEnrichedMainAPI(stremio)

            val resp = aiTranslator.load(vector)
            assertTrue("Chained 4-layer load() non-null for '$label'", resp != null)
            assertEquals("Chained 4-layer load() preserved '$label' verbatim", vector, leaf.lastReceivedLoadUrl)
        }
    }

    /**
     * Test 3: Genuine relative paths MUST be resolved against baseApi.mainUrl across all 4 layers.
     */
    private suspend fun testRelativeUrlResolutionAcrossChain() {
        println("\n>>> Test 3: Genuine Relative URL Path Resolution Across 4 Layers")

        val testCases = listOf(
            "/movie/822653" to "https://api.themoviedb.org/movie/822653",
            "movie/822653" to "https://api.themoviedb.org/movie/822653",
            "trending" to "https://api.themoviedb.org/trending",
            "/trending" to "https://api.themoviedb.org/trending",
            "/api/v3/tv/popular?page=1" to "https://api.themoviedb.org/api/v3/tv/popular?page=1",
            "api/v3/tv/popular?page=1" to "https://api.themoviedb.org/api/v3/tv/popular?page=1",
            "" to "https://api.themoviedb.org",
            "   " to "https://api.themoviedb.org"
        )

        for ((relativeInput, expectedResolved) in testCases) {
            val leaf = TestLeafProvider(mainUrlVal = "https://api.themoviedb.org/")
            val anime47 = Anime47Hook.EnrichedMainAPI(leaf)
            val kitsu = KitsunekkoHook.KitsunekkoEnrichedMainAPI(anime47)
            val stremio = StremioSubHook.StremioEnrichedMainAPI(kitsu)
            val aiTranslator = AITranslatorEnrichedMainAPI(stremio)

            val resp = aiTranslator.load(relativeInput)
            assertTrue("Relative load('$relativeInput') non-null", resp != null)
            assertEquals("Relative path '$relativeInput' correctly resolved against mainUrl", expectedResolved, leaf.lastReceivedLoadUrl)
        }

        // Test with baseUrl ending without slash and with slash
        val leafSlash = TestLeafProvider(mainUrlVal = "https://vegamovies.im/")
        val aiSlash = AITranslatorEnrichedMainAPI(Anime47Hook.EnrichedMainAPI(leafSlash))
        aiSlash.load("/movies/interstellar")
        assertEquals("BaseUrl with slash does not produce double slash", "https://vegamovies.im/movies/interstellar", leafSlash.lastReceivedLoadUrl)

        val leafNoSlash = TestLeafProvider(mainUrlVal = "https://vegamovies.im")
        val aiNoSlash = AITranslatorEnrichedMainAPI(Anime47Hook.EnrichedMainAPI(leafNoSlash))
        aiNoSlash.load("movies/interstellar")
        assertEquals("BaseUrl without slash properly prepends single slash", "https://vegamovies.im/movies/interstellar", leafNoSlash.lastReceivedLoadUrl)
    }

    /**
     * Test 4: Absolute HTTP/HTTPS URLs must be preserved verbatim.
     */
    private suspend fun testAbsoluteUrlPreservationAcrossChain() {
        println("\n>>> Test 4: Absolute HTTP/HTTPS URLs Preservation Across 4 Layers")

        val absoluteUrls = listOf(
            "https://example.com/movie/822653",
            "http://insecure.example.com/stream/index.m3u8",
            "HTTPS://CAPS.EXAMPLE.COM/SHOW/123",
            "https://api.themoviedb.org:8443/3/movie/822653?api_key=xyz&lang=en",
            "http://localhost:8080/manifest.json"
        )

        for (url in absoluteUrls) {
            val leaf = TestLeafProvider(mainUrlVal = "https://leaf-provider.org")
            val anime47 = Anime47Hook.EnrichedMainAPI(leaf)
            val kitsu = KitsunekkoHook.KitsunekkoEnrichedMainAPI(anime47)
            val stremio = StremioSubHook.StremioEnrichedMainAPI(kitsu)
            val aiTranslator = AITranslatorEnrichedMainAPI(stremio)

            val resp = aiTranslator.load(url)
            assertTrue("Absolute load('$url') non-null", resp != null)
            assertEquals("Absolute URL '$url' preserved verbatim without leaf mainUrl prepended", url, leaf.lastReceivedLoadUrl)
        }
    }

    /**
     * Test 5: MainPageRequest data preservation (JSON payloads, relative paths, absolute URLs).
     */
    private suspend fun testMainPageRequestPayloadPreservation() {
        println("\n>>> Test 5: MainPageRequest Data Preservation Across 4 Layers")

        val leaf = TestLeafProvider(mainUrlVal = "https://api.themoviedb.org")
        val anime47 = Anime47Hook.EnrichedMainAPI(leaf)
        val kitsu = KitsunekkoHook.KitsunekkoEnrichedMainAPI(anime47)
        val stremio = StremioSubHook.StremioEnrichedMainAPI(kitsu)
        val aiTranslator = AITranslatorEnrichedMainAPI(stremio)

        // 1. JSON payload in MainPageRequest
        val jsonPayload = """{"category":"trending","page":1,"time_window":"week"}"""
        val req1 = MainPageRequest("Trending", jsonPayload, false)
        val resp1 = aiTranslator.getMainPage(1, req1)
        assertTrue("getMainPage(JSON) succeeded", resp1 != null)
        assertEquals("MainPageRequest JSON data preserved verbatim", jsonPayload, leaf.lastReceivedMainPageData)

        // 2. Relative path in MainPageRequest
        val relPath = "/3/trending/movie/week"
        val req2 = MainPageRequest("Trending Movies", relPath, false)
        val resp2 = aiTranslator.getMainPage(1, req2)
        assertTrue("getMainPage(Relative) succeeded", resp2 != null)
        assertEquals("MainPageRequest relative data resolved against mainUrl", "https://api.themoviedb.org/3/trending/movie/week", leaf.lastReceivedMainPageData)

        // 3. Absolute URL in MainPageRequest
        val absUrl = "https://external.api.org/catalog"
        val req3 = MainPageRequest("External", absUrl, false)
        val resp3 = aiTranslator.getMainPage(1, req3)
        assertTrue("getMainPage(Absolute) succeeded", resp3 != null)
        assertEquals("MainPageRequest absolute data preserved verbatim", absUrl, leaf.lastReceivedMainPageData)
    }

    /**
     * Test 6: Crash Barrier Trapping.
     * Leaf provider throwing unexpected exceptions (RuntimeException, IllegalArgumentException, NPE)
     * must be safely trapped by the wrappers returning null and NEVER propagate or crash the coroutine.
     */
    private suspend fun testCrashBarrierTrappingUnexpectedErrors() {
        println("\n>>> Test 6: Crash Barrier Trapping Leaf Exceptions (Isolation Guarantee)")

        val exceptionsToTest = listOf(
            RuntimeException("Severe database connection failure"),
            IllegalArgumentException("Expected URL scheme http or https"),
            NullPointerException("Unexpected null reference in leaf parser"),
            IllegalStateException("Provider state is uninitialized")
        )

        for (ex in exceptionsToTest) {
            val leaf = TestLeafProvider()
            leaf.shouldThrowInLoad = ex

            val anime47 = Anime47Hook.EnrichedMainAPI(leaf)
            val kitsu = KitsunekkoHook.KitsunekkoEnrichedMainAPI(anime47)
            val stremio = StremioSubHook.StremioEnrichedMainAPI(kitsu)
            val aiTranslator = AITranslatorEnrichedMainAPI(stremio)

            var caughtEx: Throwable? = null
            val result = try {
                aiTranslator.load("""{"id":822653}""")
            } catch (t: Throwable) {
                caughtEx = t
                null
            }

            assertTrue("Exception '${ex.javaClass.simpleName}' was trapped by crash barrier (caughtEx == null)", caughtEx == null)
            assertEquals("Trapped exception resulted in null LoadResponse", null, result)
        }

        // Test crash barrier in getMainPage as well
        for (ex in exceptionsToTest) {
            val leaf = TestLeafProvider()
            leaf.shouldThrowInMainPage = ex

            val anime47 = Anime47Hook.EnrichedMainAPI(leaf)
            val kitsu = KitsunekkoHook.KitsunekkoEnrichedMainAPI(anime47)
            val stremio = StremioSubHook.StremioEnrichedMainAPI(kitsu)
            val aiTranslator = AITranslatorEnrichedMainAPI(stremio)

            var caughtEx: Throwable? = null
            val result = try {
                aiTranslator.getMainPage(1, MainPageRequest("Test", "trending", false))
            } catch (t: Throwable) {
                caughtEx = t
                null
            }

            assertTrue("getMainPage exception '${ex.javaClass.simpleName}' was trapped (caughtEx == null)", caughtEx == null)
            assertEquals("Trapped exception in getMainPage resulted in null HomePageResponse", null, result)
        }
    }

    /**
     * Test 7: Verify all 24 permutations of wrapping order preserve non-HTTP JSON payloads.
     */
    private suspend fun testArbitraryPermutationPayloadPreservation() {
        println("\n>>> Test 7: All 24 Wrapper Permutations Preserve JSON Payloads")

        val wrappers: List<(MainAPI) -> MainAPI> = listOf(
            { AITranslatorEnrichedMainAPI(it) },
            { StremioSubHook.StremioEnrichedMainAPI(it) },
            { KitsunekkoHook.KitsunekkoEnrichedMainAPI(it) },
            { Anime47Hook.EnrichedMainAPI(it) }
        )

        val permutations = generatePermutations(wrappers)
        assertEquals("Generated exactly 24 permutations for 4 wrappers", 24, permutations.size)

        val testPayload = """{"id":822653,"type":"movie","aniId":null,"malId":null}"""

        for ((pIdx, perm) in permutations.withIndex()) {
            val leaf = TestLeafProvider()
            var current: MainAPI = leaf
            for (wrapFunc in perm) {
                current = wrapFunc(current)
            }

            val resp = current.load(testPayload)
            assertTrue("Permutation #$pIdx returned non-null response", resp != null)
            assertEquals("Permutation #$pIdx preserved JSON payload verbatim", testPayload, leaf.lastReceivedLoadUrl)
        }
    }

    private fun <T> generatePermutations(list: List<T>): List<List<T>> {
        if (list.size <= 1) return listOf(list)
        val result = mutableListOf<List<T>>()
        for (i in list.indices) {
            val element = list[i]
            val rest = list.subList(0, i) + list.subList(i + 1, list.size)
            for (subPerm in generatePermutations(rest)) {
                result.add(listOf(element) + subPerm)
            }
        }
        return result
    }
}
