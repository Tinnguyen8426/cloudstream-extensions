@file:Suppress("DEPRECATION")
package com.cloudstream.test.e2e

import com.anime47.enricher.Anime47Hook
import com.kitsunekko.enricher.KitsunekkoHook
import com.stremio.subenricher.StremioSubHook
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.MainAPI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression suite for the AniList "Personal" defect.
 *
 * AniList sync items carry apiName "AniList" and url "https://anilist.co/anime/<id>/".
 * CloudStream resolves them via APIHolder.getApiFromUrlNull, which picks the FIRST provider
 * whose mainUrl is a prefix of the url. Providers registered with a blank mainUrl (StremioX(""))
 * match every url; hooks keep them last so real-domain providers (TorraStream-Anime,
 * mainUrl "https://anilist.co") win resolution deterministically.
 */
object UrlResolutionPriorityTest {

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

    private fun assertIdentityOrder(message: String, expected: List<MainAPI>, actual: List<MainAPI>) {
        assertEquals("$message: size", expected.size, actual.size)
        for (i in expected.indices) {
            if (i < actual.size) {
                assertTrue("$message: element[$i] '${expected[i].name}' in original position", expected[i] === actual[i])
            }
        }
    }

    class FakeProvider(nameVal: String, urlVal: String) : MainAPI() {
        init {
            name = nameVal
            mainUrl = urlVal
        }
    }

    private fun blankStremioX() = FakeProvider("StremioX", "")

    private fun torraStreamAnime() = FakeProvider("TorraStream-Anime", "https://anilist.co")

    private fun regularProvider(nameVal: String = "Anime47Provider") =
        FakeProvider(nameVal, "https://anime47.best")

    private fun otherProvider() = FakeProvider("OtherProvider", "https://example.com")

    private val implementations: List<Pair<String, (List<MainAPI>) -> List<MainAPI>>> = listOf(
        "StremioSubHook" to { list -> StremioSubHook.prioritizeDomainProviders(list) },
        "KitsunekkoHook" to { list -> KitsunekkoHook.prioritizeDomainProviders(list) },
        "Anime47Hook" to { list -> Anime47Hook.prioritizeDomainProviders(list) },
        "AITranslatorHook" to { list -> AITranslatorHook.prioritizeDomainProviders(list) }
    )

    private val wrapperFactories: List<Pair<String, (MainAPI) -> MainAPI>> = listOf(
        "AITranslator" to { api -> AITranslatorEnrichedMainAPI(api) },
        "Stremio" to { api -> StremioSubHook.StremioEnrichedMainAPI(api) },
        "Kitsunekko" to { api -> KitsunekkoHook.KitsunekkoEnrichedMainAPI(api) },
        "Anime47" to { api -> Anime47Hook.EnrichedMainAPI(api) }
    )

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" REGRESSION SUITE: URL RESOLUTION PRIORITY (ANILIST PERSONAL ITEM DEFECT)")
        println("================================================================================")

        testBlankProviderMovedLast()
        testMultipleBlankProvidersKeepRelativeOrder()
        testAllDomainProvidersUnchanged()
        testAlreadyOrderedStable()
        testResolutionSimulation()
        testWrappersDelegateBlankMainUrl()
        testFullDecoratorChain()

        println("================================================================================")
        println(" URL RESOLUTION PRIORITY SUMMARY: ${passedAssertions.get()} PASSED, ${failedAssertions.size} FAILED")
        println("================================================================================")

        if (failedAssertions.isNotEmpty()) {
            System.err.println("\nFailed assertions:")
            failedAssertions.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL URL RESOLUTION PRIORITY TESTS PASSED SUCCESSFULLY!")
        }
    }

    private fun testBlankProviderMovedLast() {
        println("\n>>> Test 1: blank-mainUrl provider is moved last")
        for ((implName, prioritize) in implementations) {
            val blank = blankStremioX()
            val anilist = torraStreamAnime()
            val other = otherProvider()

            val ordered = prioritize(listOf(blank, anilist, other))

            assertEquals("[$implName] ordered names", listOf("TorraStream-Anime", "OtherProvider", "StremioX"), ordered.map { it.name })
            assertTrue("[$implName] TorraStream-Anime first", ordered[0] === anilist)
            assertTrue("[$implName] blank StremioX last", ordered[2] === blank)
        }
    }

    private fun testMultipleBlankProvidersKeepRelativeOrder() {
        println("\n>>> Test 2: multiple blank providers move last, relative order preserved")
        for ((implName, prioritize) in implementations) {
            val other = otherProvider()
            val blankA = blankStremioX()
            val anilist = torraStreamAnime()
            val blankB = FakeProvider("StremioC", "")

            val ordered = prioritize(listOf(other, blankA, anilist, blankB))

            assertEquals("[$implName] ordered names", listOf("OtherProvider", "TorraStream-Anime", "StremioX", "StremioC"), ordered.map { it.name })
            assertTrue("[$implName] blankA precedes blankB", ordered[2] === blankA && ordered[3] === blankB)
        }
    }

    private fun testAllDomainProvidersUnchanged() {
        println("\n>>> Test 3: all-domain provider list is preserved exactly")
        for ((implName, prioritize) in implementations) {
            val a = regularProvider("Anime47")
            val b = torraStreamAnime()
            val c = otherProvider()

            val ordered = prioritize(listOf(a, b, c))

            assertIdentityOrder("[$implName]", listOf(a, b, c), ordered)
        }
    }

    private fun testAlreadyOrderedStable() {
        println("\n>>> Test 4: already-prioritized list stays stable (idempotent)")
        for ((implName, prioritize) in implementations) {
            val anilist = torraStreamAnime()
            val other = otherProvider()
            val blank = blankStremioX()
            val input = listOf(anilist, other, blank)

            val once = prioritize(input)
            val twice = prioritize(once)

            assertIdentityOrder("[$implName] first pass", input, once)
            assertIdentityOrder("[$implName] second pass", input, twice)
        }
    }

    private fun testResolutionSimulation() {
        println("\n>>> Test 5: URL resolution simulation (getApiFromUrlNull semantics)")
        val anilistUrl = "https://anilist.co/anime/206949/"

        for ((implName, prioritize) in implementations) {
            val blank = blankStremioX()
            val anilist = torraStreamAnime()

            // Documented defect: first prefix match on the raw order picks the blank provider.
            val unresolved = listOf(blank, anilist).firstOrNull { anilistUrl.startsWith(it.mainUrl) }
            assertTrue("[$implName] pre-fix raw order matches blank provider (defect reproduced)", unresolved === blank)

            val ordered = prioritize(listOf(blank, anilist))
            val resolved = ordered.firstOrNull { anilistUrl.startsWith(it.mainUrl) }
            assertTrue("[$implName] post-fix resolution picks TorraStream-Anime", resolved === anilist)
        }
    }

    private fun testWrappersDelegateBlankMainUrl() {
        println("\n>>> Test 6: wrappers delegate blank mainUrl; ordering sees through decorators")
        for ((wrapperName, wrap) in wrapperFactories) {
            val wrapped = wrap(blankStremioX())

            assertEquals("[$wrapperName] wrapped name delegates", "StremioX", wrapped.name)
            assertTrue("[$wrapperName] wrapped mainUrl stays blank", wrapped.mainUrl.isBlank())

            for ((implName, prioritize) in implementations) {
                val anilist = torraStreamAnime()
                val ordered = prioritize(listOf(wrapped, anilist))
                assertTrue("[$wrapperName->$implName] wrapped blank last", ordered.last() === wrapped)
                assertTrue("[$wrapperName->$implName] domain provider first", ordered.first() === anilist)
            }
        }
    }

    private fun testFullDecoratorChain() {
        println("\n>>> Test 7: full 4-layer decorator chain over blank provider stays last")
        val chain: MainAPI = AITranslatorEnrichedMainAPI(
            StremioSubHook.StremioEnrichedMainAPI(
                KitsunekkoHook.KitsunekkoEnrichedMainAPI(
                    Anime47Hook.EnrichedMainAPI(blankStremioX())
                )
            )
        )

        assertTrue("4-layer chain mainUrl blank", chain.mainUrl.isBlank())
        assertEquals("4-layer chain name delegates", "StremioX", chain.name)

        for ((implName, prioritize) in implementations) {
            val anilist = torraStreamAnime()
            val ordered = prioritize(listOf(chain, anilist))
            assertTrue("[$implName] 4-layer chain last", ordered.last() === chain)
            assertTrue("[$implName] TorraStream-Anime resolves first", ordered.first() === anilist)
        }
    }
}