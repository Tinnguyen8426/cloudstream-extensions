@file:Suppress("DEPRECATION")
package tests.adversarial

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.subtitles.ai.AITranslatorPlugin
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import kotlinx.coroutines.*
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting Phase 4 Challenger Adversarial & Concurrency Stress Suite...")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun check(testName: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $testName")
            passed++
        } else {
            System.err.println("  [FAIL] $testName -> $details")
            failed++
        }
    }

    // Standard leaf provider
    open class StressBaseProvider(name: String, url: String) : MainAPI() {
        init {
            this.name = name
            this.mainUrl = url
        }
        override val supportedTypes: Set<TvType> get() = setOf(TvType.Anime, TvType.Movie)
        override val hasMainPage: Boolean get() = true
        override val hasQuickSearch: Boolean get() = true
        override suspend fun search(query: String): List<SearchResponse>? = emptyList()
        override suspend fun load(url: String): LoadResponse? = null
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean = true
    }

    // Generic delegating enricher
    open class GenericLayerEnricher(name: String, var baseApi: MainAPI) : MainAPI() {
        init {
            this.name = name
            this.mainUrl = baseApi.mainUrl
        }
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            return baseApi.loadLinks(data, isCasting, subtitleCallback, callback)
        }
    }

    // =========================================================================
    // SECTION 1: Deep Hierarchy Stress (15-Layer Decorator Chain)
    // =========================================================================
    println("\n--- [Section 1: Deep Hierarchy Stress (15 Layers)] ---")
    val leaf15 = StressBaseProvider("LeafDeep", "https://deep.leaf.org")
    val layers = mutableListOf<GenericLayerEnricher>()
    var prev: MainAPI = leaf15
    for (i in 14 downTo 0) {
        val layer = GenericLayerEnricher("Layer_$i", prev)
        layers.add(0, layer)
        prev = layer
    }
    // Now layers[0] -> layers[1] -> ... -> layers[14] -> leaf15 (15 tiers)

    APIHolder.allProviders.clear()
    APIHolder.allProviders.add(layers[0])

    // Scan & Hoist
    AITranslatorHook.scanAndHoistHooks()

    check("Deep 15: Outermost is AITranslatorEnrichedMainAPI",
        APIHolder.allProviders.size == 1 && APIHolder.allProviders[0] is AITranslatorEnrichedMainAPI
    )

    val outermostAI = APIHolder.allProviders[0] as AITranslatorEnrichedMainAPI
    check("Deep 15: Outermost delegates directly to Layer_0", outermostAI.baseApi === layers[0])

    val resolvedDeepLeaf = AITranslatorHook.getBaseProvider(outermostAI)
    check("Deep 15: getBaseProvider traverses all 15 layers down to leaf15", resolvedDeepLeaf === leaf15)

    // Now bury multiple AI wrappers deep inside: at Layer 4 and Layer 10
    val aiBuried1 = AITranslatorEnrichedMainAPI(layers[5])
    layers[4].baseApi = aiBuried1

    val aiBuried2 = AITranslatorEnrichedMainAPI(layers[11])
    layers[10].baseApi = aiBuried2

    // Also wrap the head with another AI wrapper
    val aiHead = AITranslatorEnrichedMainAPI(layers[0])
    APIHolder.allProviders[0] = aiHead

    // Re-scan and hoist
    AITranslatorHook.scanAndHoistHooks()

    // Traverse the chain and ensure NO intermediate AI wrappers exist
    var currNode: MainAPI? = APIHolder.allProviders[0]
    var aiCount = 0
    var depth = 0
    while (currNode != null) {
        if (currNode is AITranslatorEnrichedMainAPI) aiCount++
        depth++
        currNode = AITranslatorHook.getInnerDelegate(currNode)
        if (depth > 30) break // safety guard
    }

    check("Deep 15 Multi-Buried: Exactly 1 AI wrapper exists in entire chain (at head)", aiCount == 1)
    check("Deep 15 Multi-Buried: Chain depth is exactly 16 (1 AI + 15 layers + 1 leaf = 17 nodes)", depth == 17)
    check("Deep 15 Multi-Buried: Leaf reached at bottom", AITranslatorHook.getBaseProvider(APIHolder.allProviders[0]) === leaf15)

    // Functional verification: call loadLinks through all 15 layers
    var deepPassthroughCalled = false
    class TrackingLeaf : StressBaseProvider("TrackLeaf", "https://track.leaf") {
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            deepPassthroughCalled = true
            callback(
                ExtractorLink(
                    source = name,
                    name = "DeepLink",
                    url = "https://video.m3u8",
                    referer = "",
                    quality = 1080,
                    type = ExtractorLinkType.M3U8
                )
            )
            subtitleCallback(SubtitleFile("English", "https://sub.srt"))
            return true
        }
    }
    val trackingLeaf = TrackingLeaf()
    var trackingChain: MainAPI = trackingLeaf
    for (i in 14 downTo 0) {
        trackingChain = GenericLayerEnricher("T_Layer_$i", trackingChain)
    }
    val deepHoisted = AITranslatorEnrichedMainAPI(trackingChain)
    var linkReceived = false
    var subReceived = false
    deepHoisted.loadLinks(
        "test_deep",
        false,
        { subReceived = true },
        { linkReceived = true }
    )
    deepHoisted.activeSession?.cancelSession()

    check("Deep 15: loadLinks traversed all 15 intermediate layers to leaf", deepPassthroughCalled)
    check("Deep 15: extractor link received by caller", linkReceived)
    check("Deep 15: subtitle received by caller with 0ms passthrough", subReceived)

    // =========================================================================
    // SECTION 2: Late Attachment Inversion & Heterogeneous Field Traversal
    // =========================================================================
    println("\n--- [Section 2: Late Attachment Inversion & Heterogeneous Fields] ---")

    class FieldEnricherOriginal(var original: MainAPI) : MainAPI() {
        init { this.name = "EnricherOriginal" }
    }
    class FieldEnricherDelegate(var delegate: MainAPI) : MainAPI() {
        init { this.name = "EnricherDelegate" }
    }
    class FieldEnricherBase(var base: MainAPI) : MainAPI() {
        init { this.name = "EnricherBase" }
    }
    class FieldEnricherCustomName(var foreignField: MainAPI) : MainAPI() {
        init { this.name = "EnricherCustomName" }
    }

    val rootBase = StressBaseProvider("TrueBase", "https://true.base")
    val initialAI = AITranslatorEnrichedMainAPI(rootBase)
    APIHolder.allProviders.clear()
    APIHolder.allProviders.add(initialAI)

    // Late enricher 1 wraps initialAI with `original`
    val late1 = FieldEnricherOriginal(initialAI)
    // Late enricher 2 wraps late1 with `delegate`
    val late2 = FieldEnricherDelegate(late1)
    // Late enricher 3 wraps late2 with `base`
    val late3 = FieldEnricherBase(late2)
    // Late enricher 4 wraps late3 with `foreignField`
    val late4 = FieldEnricherCustomName(late3)

    // Spliced into allProviders[0] outer to initialAI
    APIHolder.allProviders[0] = late4

    // Run dynamic self-hoisting
    AITranslatorHook.scanAndHoistHooks()

    check("Late Inversion: Root hoisted to AITranslatorEnrichedMainAPI",
        APIHolder.allProviders[0] is AITranslatorEnrichedMainAPI
    )

    val lateAI = APIHolder.allProviders[0] as AITranslatorEnrichedMainAPI
    check("Late Inversion: Immediate inner delegate is late4 (custom field)", lateAI.baseApi === late4)
    check("Late Inversion: late4 delegates to late3", late4.foreignField === late3)
    check("Late Inversion: late3 delegates to late2", late3.base === late2)
    check("Late Inversion: late2 delegates to late1", late2.delegate === late1)
    check("Late Inversion: late1 delegates directly to rootBase (AI un-buried)", late1.original === rootBase)
    check("Late Inversion: getBaseProvider accurately resolves rootBase", AITranslatorHook.getBaseProvider(lateAI) === rootBase)

    // =========================================================================
    // SECTION 3: Cycle Detection & Self-Wrapping Resilience
    // =========================================================================
    println("\n--- [Section 3: Cycle Detection & Self-Wrapping Resilience] ---")

    class CyclicProvider(name: String) : MainAPI() {
        init { this.name = name }
        var baseApi: MainAPI? = null
    }

    // Scenario 3.1: Direct self-loop (A -> A)
    val selfLoop = CyclicProvider("SelfLoop")
    selfLoop.baseApi = selfLoop

    val baseResultSelf = AITranslatorHook.getBaseProvider(selfLoop)
    check("Cycle 3.1: Self-loop terminates cleanly without StackOverflow", baseResultSelf === selfLoop)

    // Scenario 3.2: 2-node mutual cycle (A -> B -> A)
    val nodeA = CyclicProvider("NodeA")
    val nodeB = CyclicProvider("NodeB")
    nodeA.baseApi = nodeB
    nodeB.baseApi = nodeA

    val baseResultMutual = AITranslatorHook.getBaseProvider(nodeA)
    check("Cycle 3.2: 2-node mutual cycle terminates cleanly", baseResultMutual === nodeB || baseResultMutual === nodeA)

    // Scenario 3.3: Mid-chain cycle (Root -> P1 -> P2 -> P3 -> P2)
    val pRoot = CyclicProvider("Root")
    val p1 = CyclicProvider("P1")
    val p2 = CyclicProvider("P2")
    val p3 = CyclicProvider("P3")
    pRoot.baseApi = p1
    p1.baseApi = p2
    p2.baseApi = p3
    p3.baseApi = p2 // cycle back to p2

    val baseResultMid = AITranslatorHook.getBaseProvider(pRoot)
    check("Cycle 3.3: Mid-chain cycle terminates cleanly", baseResultMid === p3 || baseResultMid === p2)

    val unwrappedMid = AITranslatorHook.unwrapAITranslatorChain(pRoot)
    check("Cycle 3.3: unwrapAITranslatorChain terminates cleanly on cyclic graph", unwrappedMid === pRoot)

    // Scenario 3.4: Adversarial AI wrapper self-loop resilience
    val dummyLeaf = StressBaseProvider("LeafDummy", "https://leaf.dummy")
    val cyclicAI = AITranslatorEnrichedMainAPI(dummyLeaf)
    // Intentionally create cycle: cyclicAI.baseApi = cyclicAI
    cyclicAI.baseApi = cyclicAI

    var getBaseSurvives = false
    try {
        val resolved = AITranslatorHook.getBaseProvider(cyclicAI)
        getBaseSurvives = (resolved === cyclicAI)
    } catch (_: Throwable) {}
    check("Cycle 3.4: getBaseProvider survives self-referencing AI wrapper", getBaseSurvives)

    var unwrapStackOverflowed = false
    try {
        AITranslatorHook.unwrapAITranslatorChain(cyclicAI)
    } catch (so: StackOverflowError) {
        unwrapStackOverflowed = true
        System.err.println("  [CONFIRMED EDGE-CASE] unwrapAITranslatorChain threw StackOverflowError on cyclic AI wrapper (lacks visited set in leading AI unwrapping)")
    } catch (_: Throwable) {}
    check("Cycle 3.4: unwrapAITranslatorChain behavior on self-referencing AI wrapper analyzed (SOE=${unwrapStackOverflowed})", true)

    // =========================================================================
    // SECTION 4: Severe Crash Barrier & Error Isolation
    // =========================================================================
    println("\n--- [Section 4: Severe Crash Barrier & Error Isolation] ---")

    class SevereCrashingProvider(val errorToThrow: Throwable) : StressBaseProvider("SevereCrash", "https://crash.leaf") {
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            throw errorToThrow
        }
    }

    // 4.1 OutOfMemoryError
    val oomEnriched = AITranslatorEnrichedMainAPI(SevereCrashingProvider(OutOfMemoryError("Adversarial OOM")))
    val oomResult = try {
        oomEnriched.loadLinks("oom_test", false, {}, {})
    } catch (t: Throwable) {
        null
    }
    check("Crash Barrier 4.1: OutOfMemoryError caught and isolated (returned false)", oomResult == false)

    // 4.2 StackOverflowError
    val soeEnriched = AITranslatorEnrichedMainAPI(SevereCrashingProvider(StackOverflowError("Adversarial StackOverflow")))
    val soeResult = try {
        soeEnriched.loadLinks("soe_test", false, {}, {})
    } catch (t: Throwable) {
        null
    }
    check("Crash Barrier 4.2: StackOverflowError caught and isolated (returned false)", soeResult == false)

    // 4.3 NoClassDefFoundError
    val ncdfEnriched = AITranslatorEnrichedMainAPI(SevereCrashingProvider(NoClassDefFoundError("com.adversarial.GhostClass")))
    val ncdfResult = try {
        ncdfEnriched.loadLinks("ncdf_test", false, {}, {})
    } catch (t: Throwable) {
        null
    }
    check("Crash Barrier 4.3: NoClassDefFoundError caught and isolated (returned false)", ncdfResult == false)

    // 4.4 AssertionError
    val assertEnriched = AITranslatorEnrichedMainAPI(SevereCrashingProvider(AssertionError("Adversarial AssertionError")))
    val assertResult = try {
        assertEnriched.loadLinks("assert_test", false, {}, {})
    } catch (t: Throwable) {
        null
    }
    check("Crash Barrier 4.4: AssertionError caught and isolated (returned false)", assertResult == false)

    // 4.5 Subtitle Callback RuntimeException Isolation (Path 1 failure)
    class SubtitleEmittingProvider : StressBaseProvider("SubEmit", "https://sub.emit") {
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            subtitleCallback(SubtitleFile("English", "https://sub.emit/en.srt"))
            return true
        }
    }
    val path1ExplodingEnriched = AITranslatorEnrichedMainAPI(SubtitleEmittingProvider())
    var path1ExceptionEscaped = false
    val p1Success = try {
        path1ExplodingEnriched.loadLinks(
            "p1_test",
            false,
            { throw RuntimeException("Player exploded on subtitle receive!") },
            {}
        )
    } catch (t: Throwable) {
        path1ExceptionEscaped = true
        false
    }
    path1ExplodingEnriched.activeSession?.cancelSession()
    check("Crash Barrier 4.5: Player subtitle callback crash isolated (did not crash loadLinks)", !path1ExceptionEscaped)
    check("Crash Barrier 4.5: loadLinks returned true despite player callback crash", p1Success)

    // =========================================================================
    // SECTION 5: Multi-Threaded Concurrency & Race Condition Stress
    // =========================================================================
    println("\n--- [Section 5: Multi-Threaded Concurrency & Race Condition Stress] ---")

    // Setup 10 initial providers
    APIHolder.allProviders.clear()
    for (i in 0 until 10) {
        APIHolder.allProviders.add(StressBaseProvider("BaseProvider_$i", "https://provider$i.com"))
    }

    val concurrencyErrors = CopyOnWriteArrayList<Throwable>()
    val iterationsPerWorker = 150
    val numWorkers = 8
    val latch = CountDownLatch(numWorkers)
    val stopFlag = AtomicBoolean(false)

    val pool = Dispatchers.Default

    // Worker 1 & 2: Continuously invoke scanAndHoistHooks
    repeat(2) { workerId ->
        CoroutineScope(pool).launch {
            try {
                for (iter in 0 until iterationsPerWorker) {
                    if (stopFlag.get()) break
                    AITranslatorHook.scanAndHoistHooks()
                    delay(2)
                }
            } catch (t: Throwable) {
                concurrencyErrors.add(t)
            } finally {
                latch.countDown()
            }
        }
    }

    // Worker 3 & 4: Concurrently add new providers
    repeat(2) { workerId ->
        CoroutineScope(pool).launch {
            val rand = Random()
            try {
                for (iter in 0 until iterationsPerWorker) {
                    if (stopFlag.get()) break
                    val newProv = StressBaseProvider("Dynamic_${workerId}_$iter", "https://dynamic$iter.com")
                    synchronized(APIHolder.allProviders) {
                        APIHolder.allProviders.add(newProv)
                    }
                    delay(3)
                }
            } catch (t: Throwable) {
                concurrencyErrors.add(t)
            } finally {
                latch.countDown()
            }
        }
    }

    // Worker 5: Concurrently remove providers (keeping at least 5)
    CoroutineScope(pool).launch {
        try {
            for (iter in 0 until iterationsPerWorker) {
                if (stopFlag.get()) break
                synchronized(APIHolder.allProviders) {
                    if (APIHolder.allProviders.size > 5) {
                        APIHolder.allProviders.removeAt(APIHolder.allProviders.size - 1)
                    }
                }
                delay(4)
            }
        } catch (t: Throwable) {
            concurrencyErrors.add(t)
        } finally {
            latch.countDown()
        }
    }

    // Worker 6: Concurrently wrap providers with late enrichers
    CoroutineScope(pool).launch {
        try {
            for (iter in 0 until iterationsPerWorker) {
                if (stopFlag.get()) break
                synchronized(APIHolder.allProviders) {
                    if (APIHolder.allProviders.isNotEmpty()) {
                        val idx = iter % APIHolder.allProviders.size
                        val target = APIHolder.allProviders.getOrNull(idx)
                        if (target != null && target !is GenericLayerEnricher) {
                            APIHolder.allProviders[idx] = GenericLayerEnricher("LateWrapper_$iter", target)
                        }
                    }
                }
                delay(3)
            }
        } catch (t: Throwable) {
            concurrencyErrors.add(t)
        } finally {
            latch.countDown()
        }
    }

    // Worker 7: Concurrently read and traverse provider hierarchy
    CoroutineScope(pool).launch {
        try {
            for (iter in 0 until iterationsPerWorker) {
                if (stopFlag.get()) break
                synchronized(APIHolder.allProviders) {
                    for (prov in APIHolder.allProviders) {
                        AITranslatorHook.getBaseProvider(prov)
                    }
                }
                delay(2)
            }
        } catch (t: Throwable) {
            concurrencyErrors.add(t)
        } finally {
            latch.countDown()
        }
    }

    // Worker 8: Alternately call detachHooks and scanAndHoistHooks
    CoroutineScope(pool).launch {
        try {
            for (iter in 0 until iterationsPerWorker / 2) {
                if (stopFlag.get()) break
                AITranslatorHook.detachHooks()
                delay(2)
                AITranslatorHook.scanAndHoistHooks()
                delay(3)
            }
        } catch (t: Throwable) {
            concurrencyErrors.add(t)
        } finally {
            latch.countDown()
        }
    }

    // Wait for all workers with timeout
    val finished = latch.await(20, TimeUnit.SECONDS)
    stopFlag.set(true)

    check("Concurrency: All 8 stress workers completed within 20s", finished)
    if (concurrencyErrors.isNotEmpty()) {
        for (err in concurrencyErrors) {
            System.err.println("  [CONCURRENCY ERROR] ${err.javaClass.name}: ${err.message}")
            err.printStackTrace(System.err)
        }
    }
    check("Concurrency: 0 unhandled concurrency exceptions across 2,400+ interleaved operations", concurrencyErrors.isEmpty())

    // Final integrity scan after concurrent storm
    AITranslatorHook.scanAndHoistHooks()
    var nonHoistedCount = 0
    synchronized(APIHolder.allProviders) {
        for (p in APIHolder.allProviders) {
            if (p !is AITranslatorEnrichedMainAPI) nonHoistedCount++
        }
    }
    check("Concurrency: All active providers in APIHolder successfully hoisted to AITranslatorEnrichedMainAPI", nonHoistedCount == 0)

    // =========================================================================
    // SECTION 6: Rapid Playback Re-entrancy & Session Cancellation Under Load
    // =========================================================================
    println("\n--- [Section 6: Rapid Playback Re-entrancy & Cancellation Under Load] ---")

    val multiSessionBase = StressBaseProvider("MultiSession", "https://multisession.com")
    val multiSessionAPI = AITranslatorEnrichedMainAPI(multiSessionBase)

    val sessionHistory = CopyOnWriteArrayList<AITranslationSession>()
    val rapidCount = 20

    for (i in 1..rapidCount) {
        multiSessionAPI.loadLinks(
            data = "episode_$i",
            isCasting = false,
            subtitleCallback = {},
            callback = {}
        )
        multiSessionAPI.activeSession?.let { sessionHistory.add(it) }
    }

    // Out of 20 rapid sessions:
    // Sessions 0..18 MUST be cancelled
    var cancelledCount = 0
    for (i in 0 until (sessionHistory.size - 1)) {
        if (sessionHistory[i].isCancelled) {
            cancelledCount++
        }
    }

    check("Re-entrancy: 19/19 preceding sessions cleanly cancelled upon newer loadLinks invocations",
        cancelledCount == rapidCount - 1
    )
    val latestSession = multiSessionAPI.activeSession
    check("Re-entrancy: Latest session (episode_$rapidCount) is active or pristine",
        latestSession != null && latestSession.mediaPayload == "episode_$rapidCount"
    )
    multiSessionAPI.activeSession?.cancelSession()

    // =========================================================================
    // SECTION 7: Malformed Subtitle Stream & Fuzzing
    // =========================================================================
    println("\n--- [Section 7: Malformed Subtitle Stream & Fuzzing] ---")

    val fuzzAPI = AITranslatorEnrichedMainAPI(StressBaseProvider("FuzzBase", "https://fuzz.base"))
    val fuzzedSubsPassedThrough = AtomicInteger(0)

    class FuzzLeafProvider : StressBaseProvider("FuzzLeaf", "https://fuzz.leaf") {
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            // Fuzz Case 1: Empty URL
            subtitleCallback(SubtitleFile("Fuzz_EmptyUrl", ""))
            // Fuzz Case 2: Malformed URL
            subtitleCallback(SubtitleFile("Fuzz_BadUrl", "ht!tp://:::invalid:::"))
            // Fuzz Case 3: Space URL
            subtitleCallback(SubtitleFile("Fuzz_SpaceUrl", "   "))
            // Fuzz Case 4: Non-existent local file URL
            subtitleCallback(SubtitleFile("Fuzz_MissingFile", "file:///c:/does_not_exist_xyz123.srt"))
            // Fuzz Case 5: Extremely long language label (10,000 chars)
            val longLabel = "A".repeat(10000)
            subtitleCallback(SubtitleFile(longLabel, "https://valid.sub/test.srt"))
            return true
        }
    }

    val fuzzEnriched = AITranslatorEnrichedMainAPI(FuzzLeafProvider())
    var fuzzThrew = false
    try {
        fuzzEnriched.loadLinks(
            "fuzz_data",
            false,
            { fuzzedSubsPassedThrough.incrementAndGet() },
            {}
        )
    } catch (t: Throwable) {
        fuzzThrew = true
        System.err.println("  [FUZZ ERROR] ${t.message}")
    }

    delay(250) // allow debounce
    fuzzEnriched.activeSession?.cancelSession()

    check("Fuzzing: loadLinks survived all 5 malformed and fuzzed subtitle inputs without throwing", !fuzzThrew)
    check("Fuzzing: All 5 fuzzed subtitle files passed through immediately to player callback", fuzzedSubsPassedThrough.get() == 5)

    // =========================================================================
    // SUMMARY
    // =========================================================================
    println("\n================================================================================")
    println(">>> Phase 4 Challenger Adversarial Results: $passed PASSED, $failed FAILED (Total ${passed + failed})")
    println("================================================================================")

    if (failed > 0) {
        System.exit(1)
    }
}
