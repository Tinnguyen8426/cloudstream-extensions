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
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newHomePageResponse
import kotlinx.coroutines.runBlocking

/**
 * Challenger 2: Adversarial Permutation Stress Harness for Milestone 9.
 *
 * Verifies that in 100% of the 24 permutations (4! = 24) of the 4 enrichers:
 * - Anime47SubtitleEnricher
 * - StremioSubtitleEnricher
 * - KitsunekkoSubtitleEnricher
 * - AiSubtitleTranslator
 *
 * All non-HTTP & JSON payloads in load(url) and getMainPage() are preserved verbatim
 * to the leaf provider without string mangling or exceptions, and genuine relative
 * paths are correctly normalized against baseApi.mainUrl.
 */
object Milestone9Challenger2PermutationsTest {

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

    class LeafRecordingProvider(
        name: String = "MockLeafProvider",
        url: String = "https://base.mainUrl"
    ) : MainAPI() {
        init {
            this.name = name
            this.mainUrl = url
        }

        var lastReceivedLoadUrl: String? = null
        var lastReceivedMainPageRequest: MainPageRequest? = null

        override val supportedTypes: Set<TvType> get() = setOf(TvType.Anime, TvType.Movie)
        override val hasMainPage: Boolean get() = true

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            lastReceivedMainPageRequest = request
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }

        override suspend fun load(url: String): LoadResponse? {
            lastReceivedLoadUrl = url
            return newMovieLoadResponse(name, url, TvType.Movie, url) {
                this.year = 2026
            }
        }
    }

    private fun resetHooks() {
        AITranslatorHook.detachHooks()
        StremioSubHook.detachHooks()
        KitsunekkoHook.detachHooks()
        Anime47Hook.detachHooks()
        APIHolder.allProviders.clear()
    }

    private fun wrap(leaf: MainAPI, enricher: String): MainAPI = when (enricher) {
        "Anime47" -> Anime47Hook.EnrichedMainAPI(leaf)
        "Kitsunekko" -> KitsunekkoHook.KitsunekkoEnrichedMainAPI(leaf)
        "Stremio" -> StremioSubHook.StremioEnrichedMainAPI(leaf)
        "AiTranslator" -> AITranslatorEnrichedMainAPI(leaf)
        else -> throw IllegalArgumentException("Unknown enricher: $enricher")
    }

    private fun <T> permutations(list: List<T>): List<List<T>> {
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

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("================================================================================")
        println(" CHALLENGER 2: 24-PERMUTATION ADVERSARIAL PAYLOAD PRESERVATION HARNESS (M9)")
        println("================================================================================")

        val enricherNames = listOf("Anime47", "Stremio", "Kitsunekko", "AiTranslator")
        val allPerms = permutations(enricherNames)
        println("[*] Total permutations generated: ${allPerms.size} (4! = 24)")

        // =====================================================================
        // PART 1: Pure Direct Constructor Layering Permutations (24 Permutations)
        // =====================================================================
        println("\n>>> PART 1: Direct Wrapping Permutations (All 24 Ordering Configurations)")
        var permIdx = 0
        for (perm in allPerms) {
            permIdx++
            val label = "Direct Perm #$permIdx: " + perm.joinToString(" -> ")
            println("\n--- $label ---")

            val leaf = LeafRecordingProvider("Leaf_$permIdx", "https://base.mainUrl")
            var head: MainAPI = leaf
            for (enricher in perm) {
                head = wrap(head, enricher)
            }

            testPayloadSuite(label, head, leaf, "https://base.mainUrl")
        }

        // =====================================================================
        // PART 2: Dynamic Hook Scanned & Hoisted Permutations (24 Permutations)
        // =====================================================================
        println("\n>>> PART 2: APIHolder Scanned Hook Permutations (All 24 Attachment Orders)")
        permIdx = 0
        for (perm in allPerms) {
            permIdx++
            val label = "Hook Perm #$permIdx: " + perm.joinToString(" -> ")
            println("\n--- $label ---")

            resetHooks()
            val leaf = LeafRecordingProvider("ScannedLeaf_$permIdx", "https://base.mainUrl")
            APIHolder.allProviders.add(leaf)

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

            AITranslatorHook.scanAndHoistHooks()
            assertEquals("$label: APIHolder provider count", 1, APIHolder.allProviders.size)
            val head = APIHolder.allProviders[0]

            testPayloadSuite(label, head, leaf, "https://base.mainUrl")
        }

        println("\n================================================================================")
        println(" CHALLENGER 2 SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailures encountered:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL 24 PERMUTATIONS VERIFIED FLAWLESSLY WITH ZERO REGRESSIONS (APPROVE)!")
            System.exit(0)
        }
    }

    private suspend fun testPayloadSuite(
        label: String,
        head: MainAPI,
        leaf: LeafRecordingProvider,
        expectedBaseUrl: String
    ) {
        // 1. Mandatory Vector: JSON Object Payload {"id":822653,"type":"movie"}
        val jsonPayload1 = """{"id":822653,"type":"movie"}"""
        val resp1 = head.load(jsonPayload1)
        assertTrue("$label: load() non-null for JSON object", resp1 != null)
        assertEquals("$label: Leaf received verbatim JSON object", jsonPayload1, leaf.lastReceivedLoadUrl)

        // 2. Mandatory Vector: JSON Array Payload [{"key":"val"}]
        val jsonPayload2 = """[{"key":"val"}]"""
        val resp2 = head.load(jsonPayload2)
        assertTrue("$label: load() non-null for JSON array", resp2 != null)
        assertEquals("$label: Leaf received verbatim JSON array", jsonPayload2, leaf.lastReceivedLoadUrl)

        // 3. Mandatory Vector: kitsu:anime:123 URN
        val urnPayload = "kitsu:anime:123"
        val resp3 = head.load(urnPayload)
        assertTrue("$label: load() non-null for kitsu URN", resp3 != null)
        assertEquals("$label: Leaf received verbatim kitsu URN", urnPayload, leaf.lastReceivedLoadUrl)

        // 4. Mandatory Vector: /relative/path -> resolved to https://base.mainUrl/relative/path
        val relativePayload = "/relative/path"
        val resp4 = head.load(relativePayload)
        assertTrue("$label: load() non-null for relative path", resp4 != null)
        assertEquals("$label: Leaf received normalized relative URL", "$expectedBaseUrl/relative/path", leaf.lastReceivedLoadUrl)

        // 5. Mandatory Vector: getMainPage with JSON request.data
        val mainPageJson = """{"filter":"popular"}"""
        val mpReq = MainPageRequest("Test", mainPageJson, false)
        val mpResp = head.getMainPage(1, mpReq)
        assertTrue("$label: getMainPage() non-null", mpResp != null)
        assertEquals("$label: Leaf received verbatim request.data JSON", mainPageJson, leaf.lastReceivedMainPageRequest?.data)

        // 6. Adversarial Vector: Nested JSON with formatting and newlines
        val formattedNestedJson = "{\n  \"nested\": {\"id\": 822653, \"list\": [1, 2, 3]},\n  \"type\": \"movie\"\n}"
        val resp6 = head.load(formattedNestedJson)
        assertTrue("$label: load() non-null for formatted nested JSON", resp6 != null)
        assertEquals("$label: Leaf received verbatim formatted nested JSON", formattedNestedJson, leaf.lastReceivedLoadUrl)

        // 7. Adversarial Vector: Magnet URI
        val magnetPayload = "magnet:?xt=urn:btih:3b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c"
        val resp7 = head.load(magnetPayload)
        assertTrue("$label: load() non-null for magnet URI", resp7 != null)
        assertEquals("$label: Leaf received verbatim magnet URI", magnetPayload, leaf.lastReceivedLoadUrl)

        // 8. Adversarial Vector: Numeric TMDb ID (e.g. "822653")
        val numericId = "822653"
        val resp8 = head.load(numericId)
        assertTrue("$label: load() non-null for numeric ID", resp8 != null)
        assertEquals("$label: Leaf received verbatim numeric ID", numericId, leaf.lastReceivedLoadUrl)

        // 9. Adversarial Vector: IMDb ID (e.g. "tt0111161")
        val imdbId = "tt0111161"
        val resp9 = head.load(imdbId)
        assertTrue("$label: load() non-null for IMDb ID", resp9 != null)
        assertEquals("$label: Leaf received verbatim IMDb ID", imdbId, leaf.lastReceivedLoadUrl)

        // 10. Adversarial Vector: Relative path without leading slash (e.g. "relative/subpath")
        val relNoSlash = "relative/subpath"
        val resp10 = head.load(relNoSlash)
        assertTrue("$label: load() non-null for relative path without leading slash", resp10 != null)
        assertEquals("$label: Leaf received normalized relative URL for no-slash path", "$expectedBaseUrl/relative/subpath", leaf.lastReceivedLoadUrl)
    }
}
