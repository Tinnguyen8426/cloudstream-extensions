@file:Suppress("DEPRECATION")
package com.cloudstream.test.e2e

import com.anime47.enricher.Anime47Hook
import com.kitsunekko.enricher.KitsunekkoHook
import com.stremio.subenricher.StremioSubHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger 2 Adversarial Stress Test: All 24 Permutations of 4-Enricher Coexistence.
 *
 * Exhaustively stress-tests:
 * - All 24 permutations (4!) of attachment order: Anime47, Kitsunekko, Stremio, AiSubtitleTranslator.
 * - Outermost self-hoisting invariant for AiSubtitleTranslator across every permutation.
 * - Exact chain depth == 4 wrappers + 1 leaf = 5 nodes, with 0 duplicates and 0 missing enrichers.
 * - Name, mainUrl, and supportedTypes delegation preservation.
 * - load() show metadata preservation across all 4 tiers.
 * - loadLinks() video link passthrough latency (<150ms budget).
 * - Full unwrap on detach restoring original base provider without residue.
 */
object Full24PermutationsCoexistenceTest {

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
                    name = "Torrentio 1080p Stream",
                    url = "https://mock.stream/frieren_1080p.mp4",
                    referer = "",
                    quality = 1080,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }
    }

    private fun resetAllEnricherHooks() {
        AITranslatorHook.detachHooks()
        StremioSubHook.detachHooks()
        KitsunekkoHook.detachHooks()
        Anime47Hook.detachHooks()
        APIHolder.allProviders.clear()
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

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("================================================================================")
        println(" CHALLENGER 2: EMPIRICAL 24-PERMUTATION 4-ENRICHER COEXISTENCE HARNESS")
        println("================================================================================")

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
        println("[*] Total permutations to stress-test: ${allPerms.size}")

        var permIndex = 0
        for (perm in allPerms) {
            permIndex++
            val permLabel = perm.joinToString(" -> ")
            println("\n>>> Permutation #$permIndex: $permLabel")

            // 1. Reset state
            resetAllEnricherHooks()
            val baseProvider = MockBaseProvider("BaseProvider_$permIndex", "https://base.provider/$permIndex")
            APIHolder.allProviders.add(baseProvider)

            // 2. Attach enrichers in this permutation's order
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

            // 3. Final hoist check to guarantee outermost invariant
            AITranslatorHook.scanAndHoistHooks()

            // 4. Assertions on the hooked structure
            assertEquals("Perm #$permIndex: APIHolder provider count", 1, APIHolder.allProviders.size)
            val head = APIHolder.allProviders[0]

            // 4a. Outermost invariant
            assertTrue("Perm #$permIndex: Head is AITranslatorEnrichedMainAPI",
                head is AITranslatorEnrichedMainAPI,
                "Actual head class: ${head.javaClass.name}")

            // 4b. Inspect chain nodes
            val chain = getChainClasses(head)
            assertEquals("Perm #$permIndex: Total chain depth (4 wrappers + 1 leaf)", 5, chain.size)

            val classNames = chain.map { it.name }
            val hasAi = classNames.any { it.contains("AITranslatorEnrichedMainAPI") }
            val hasKitsunekko = classNames.any { it.contains("KitsunekkoEnrichedMainAPI") }
            val hasStremio = classNames.any { it.contains("StremioEnrichedMainAPI") }
            val hasAnime47 = classNames.any { it.contains("Anime47Hook") && it.contains("EnrichedMainAPI") }
            val hasLeaf = classNames.last().contains("MockBaseProvider")

            assertTrue("Perm #$permIndex: Contains AI Translator wrapper", hasAi)
            assertTrue("Perm #$permIndex: Contains Kitsunekko wrapper", hasKitsunekko)
            assertTrue("Perm #$permIndex: Contains Stremio wrapper", hasStremio)
            assertTrue("Perm #$permIndex: Contains Anime47 wrapper", hasAnime47)
            assertTrue("Perm #$permIndex: Leaf is MockBaseProvider", hasLeaf)

            // 4c. Property delegation
            assertEquals("Perm #$permIndex: Delegated name preserved", "BaseProvider_$permIndex", head.name)
            assertEquals("Perm #$permIndex: Delegated mainUrl preserved", "https://base.provider/$permIndex", head.mainUrl)
            assertEquals("Perm #$permIndex: Supported types preserved", setOf(TvType.Anime, TvType.Movie, TvType.TvSeries), head.supportedTypes)

            // 4d. load() delegation
            val loadResp = head.load("https://base.provider/$permIndex/frieren")
            assertTrue("Perm #$permIndex: load() returned MovieLoadResponse", loadResp is MovieLoadResponse)
            val movieResp = loadResp as? MovieLoadResponse
            assertEquals("Perm #$permIndex: Show title preserved", "Sousou no Frieren", movieResp?.name)
            assertEquals("Perm #$permIndex: Year preserved", 2023, movieResp?.year)

            // 4e. loadLinks() 0ms video passthrough latency (<150ms)
            val videoReceived = AtomicBoolean(false)
            val videoArrivalMs = AtomicInteger(-1)
            val startTime = System.currentTimeMillis()

            head.loadLinks(
                data = "https://base.provider/$permIndex/frieren_1080p.mp4",
                isCasting = false,
                subtitleCallback = {},
                callback = { link ->
                    videoReceived.set(true)
                    videoArrivalMs.set((System.currentTimeMillis() - startTime).toInt())
                }
            )

            assertTrue("Perm #$permIndex: Video link callback received", videoReceived.get())
            assertTrue("Perm #$permIndex: Video arrival latency < 150ms (actual: ${videoArrivalMs.get()}ms)",
                videoArrivalMs.get() in 0..150, "Latency was ${videoArrivalMs.get()}ms")

            // 4f. Test Clean Detach
            AITranslatorHook.detachHooks()
            val afterAiDetach = APIHolder.allProviders[0]
            assertTrue("Perm #$permIndex: After AI detach, head is not AI wrapper",
                afterAiDetach !is AITranslatorEnrichedMainAPI)

            KitsunekkoHook.detachHooks()
            StremioSubHook.detachHooks()
            Anime47Hook.detachHooks()
            val afterFullDetach = APIHolder.allProviders[0]
            assertEquals("Perm #$permIndex: Fully unwrapped back to base provider",
                baseProvider, afterFullDetach)
        }

        println("\n================================================================================")
        println(" 24-PERMUTATION STRESS TEST SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed Permutations / Assertions:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL 24 PERMUTATIONS PASSED WITH ZERO REGRESSIONS!")
            System.exit(0)
        }
    }
}
