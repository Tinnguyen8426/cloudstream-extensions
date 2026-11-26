@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package tests.unit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslationClient
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.hook.MediaContextManager
import com.lagradost.cloudstream3.subtitles.ai.models.*
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.profile.CharacterProfileManager
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import com.lagradost.cloudstream3.subtitles.ai.session.StreamSubtitleExtractor
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitleReconciler
import com.lagradost.cloudstream3.subtitles.ai.ui.AITranslatorSettingsDialog
import com.lagradost.cloudstream3.subtitles.ai.ui.TranslationProgressHUD
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private class MockSharedPrefsM2(
    private val store: MutableMap<String, Any?> = ConcurrentHashMap()
) : SharedPreferences {
    override fun getAll(): Map<String, *> = HashMap(store)
    override fun getString(key: String?, defValue: String?): String? = store[key]?.toString() ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (store[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = when (val v = store[key]) {
        is Int -> v
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: defValue
        else -> defValue
    }
    override fun getLong(key: String?, defValue: Long): Long = when (val v = store[key]) {
        is Long -> v
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: defValue
        else -> defValue
    }
    override fun getFloat(key: String?, defValue: Float): Float = when (val v = store[key]) {
        is Float -> v
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull() ?: defValue
        else -> defValue
    }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = when (val v = store[key]) {
        is Boolean -> v
        is String -> v.toBoolean()
        else -> defValue
    }
    override fun contains(key: String?): Boolean = store.containsKey(key)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false
        override fun putString(k: String?, v: String?) = apply { if (k != null) pending[k] = v }
        override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { if (k != null) pending[k] = v }
        override fun putInt(k: String?, v: Int) = apply { if (k != null) pending[k] = v }
        override fun putLong(k: String?, v: Long) = apply { if (k != null) pending[k] = v }
        override fun putFloat(k: String?, v: Float) = apply { if (k != null) pending[k] = v }
        override fun putBoolean(k: String?, v: Boolean) = apply { if (k != null) pending[k] = v }
        override fun remove(k: String?) = apply { if (k != null) removed.add(k) }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) store.clear()
            removed.forEach { store.remove(it) }
            pending.forEach { (k, v) -> if (v == null) store.remove(k) else store[k] = v }
        }
    }
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private open class MockContextM2 : ContextWrapper(null) {
    private val prefsRegistry = ConcurrentHashMap<String, MockSharedPrefsM2>()
    var mockFilesDir: File = File(System.getProperty("java.io.tmpdir", "."), "mock_m2_files")
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        prefsRegistry.getOrPut(name ?: "default") { MockSharedPrefsM2() }
    override fun getFilesDir(): File {
        if (!mockFilesDir.exists()) mockFilesDir.mkdirs()
        return mockFilesDir
    }
    companion object {
        fun create(): MockContextM2 = try {
            MockContextM2()
        } catch (_: Throwable) {
            val f: Field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            f.isAccessible = true
            val unsafe = f.get(null)
            val inst = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, MockContextM2::class.java) as MockContextM2
            MockContextM2::class.java.getDeclaredField("prefsRegistry").apply { isAccessible = true }.set(inst, ConcurrentHashMap<String, MockSharedPrefsM2>())
            val dir = File(System.getProperty("java.io.tmpdir", "."), "mock_m2_files_${System.currentTimeMillis()}").apply { mkdirs(); deleteOnExit() }
            MockContextM2::class.java.getDeclaredField("mockFilesDir").apply { isAccessible = true }.set(inst, dir)
            inst
        }
    }
}

private fun assertTrue(msg: String, condition: Boolean) {
    if (!condition) {
        System.err.println("  [FAIL] $msg")
        throw AssertionError("Assertion failed: $msg")
    }
    println("  [PASS] $msg")
}

private fun assertFalse(msg: String, condition: Boolean) = assertTrue(msg, !condition)

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        System.err.println("  [FAIL] $msg - Expected '$expected', but got '$actual'")
        throw AssertionError("Assertion failed: $msg - Expected '$expected', but got '$actual'")
    }
    println("  [PASS] $msg")
}

private fun buildSrt(count: Int, gapAfterCue: Map<Int, Long> = emptyMap()): String {
    val sb = StringBuilder()
    var t = 1000L
    for (i in 1..count) {
        val start = t
        val end = start + 1000L
        sb.append(i).append("\n")
        sb.append(formatMs(start)).append(" --> ").append(formatMs(end)).append("\n")
        sb.append("Dialogue line number $i with enough words to verify translation.\n\n")
        t = end + (gapAfterCue[i] ?: 300L)
    }
    return sb.toString()
}

private fun formatMs(ms: Long): String {
    val h = ms / 3600_000L
    val m = (ms % 3600_000L) / 60_000L
    val s = (ms % 60_000L) / 1000L
    val rem = ms % 1000L
    return String.format("%02d:%02d:%02d,%03d", h, m, s, rem)
}

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting Milestone 2 Session, Bisect, Cancel & Source Fallback Test Suite...")
    println("================================================================================")

    val testRoot = File(System.getProperty("java.io.tmpdir", "."), "m2_session_test_${System.currentTimeMillis()}").apply {
        mkdirs()
        deleteOnExit()
    }
    SubtitleCacheManager.customBaseDir = File(testRoot, "cache")
    CharacterProfileManager.customBaseDir = File(testRoot, "profiles")
    TranslatorConfig.resetToDefaults()
    TranslatorConfig.showProgressHUD = false

    // =========================================================================
    // TEST 1: Pass 0 Global Film Bible Pre-Scan & Cinemeta "Genres: ..." Trigger
    // =========================================================================
    println("\n--- [Test 1: Pass 0 Global Film Bible Pre-Scan & Cinemeta Trigger] ---")
    SubtitleCacheManager.clearAll(null)
    CharacterProfileManager.customBaseDir = File(testRoot, "profiles_t1").apply { mkdirs() }

    val realSteelTitle = "Real Steel"
    // Pre-seed Cinemeta-like profile (characters + toneGuide = "Genres: Action, Drama, Sci-Fi", empty relationships/autoGlossary)
    CharacterProfileManager.saveProfile(
        null,
        realSteelTitle,
        TwoTierCharacterRegistry(
            mediaTitle = realSteelTitle,
            characters = listOf(
                CharacterEntity(name = "Charlie Kenton", role = "Played by Hugh Jackman"),
                CharacterEntity(name = "Max Kenton", role = "Played by Dakota Goyo")
            ),
            relationships = emptyList(),
            autoGlossary = emptyMap(),
            toneGuide = "Genres: Action, Drama, Sci-Fi"
        ),
        isEphemeral = false
    )

    val pass0Calls = AtomicInteger(0)
    val observedChunkRegistries = CopyOnWriteArrayList<TwoTierCharacterRegistry?>()
    val pass0Client = object : AITranslationClient {
        override suspend fun generateJsonPrompt(systemPrompt: String, userPrompt: String, timeoutSeconds: Long): String? {
            pass0Calls.incrementAndGet()
            assertEquals("Pass 0 timeout is 12s", 12L, timeoutSeconds)
            assertTrue("Pass 0 prompt includes Media Metadata", userPrompt.contains("Real Steel"))
            return """
                {
                  "characters": [
                    {"name": "Charlie Kenton", "gender": "male", "age_group": "adult", "role": "Ex-boxer father"},
                    {"name": "Max Kenton", "gender": "male", "age_group": "child", "role": "Charlie's son"},
                    {"name": "Bailey Tallet", "gender": "female", "age_group": "adult", "role": "Gym owner"}
                  ],
                  "relationships": [
                    {"pair": ["Charlie Kenton", "Max Kenton"], "type": "family", "address_matrix": {"vi": {"A_to_B": "bố - con", "B_to_A": "con - bố"}}}
                  ],
                  "auto_glossary": {"sparring bot": "robot tập đấu", "shadow-box": "đấm gió"},
                  "tone_guide": "Gritty sports father-son drama; sharp boxing banter."
                }
            """.trimIndent()
        }

        override suspend fun translateChunkWithSceneDigest(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?,
            lookbehind: List<ContextCue>,
            lookahead: List<ContextCue>,
            characterRegistry: TwoTierCharacterRegistry?,
            mediaContext: MediaContext?,
            rollingSceneDigest: String?
        ): TranslationResult {
            observedChunkRegistries.add(characterRegistry)
            return TranslationResult(
                translations = cues.associate { it.index to "Bản dịch ${it.index}" },
                success = true
            )
        }

        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val srt45File = File(testRoot, "real_steel_45.srt").apply { writeText(buildSrt(45)) }
    val realSteelCtx = MediaContext(
        title = realSteelTitle,
        plot = "A washed-up fighter teams up with his estranged son to build a robot boxer.",
        mediaType = "Movie",
        year = 2011,
        genres = listOf("Action", "Drama", "Sci-Fi"),
        characterActors = mapOf("Hugh Jackman" to "Charlie Kenton", "Dakota Goyo" to "Max Kenton")
    )
    val emitted1 = CopyOnWriteArrayList<SubtitleFile>()
    val session1 = AITranslationSession(
        mediaPayload = "real_steel_ep1",
        targetLang = "vi",
        playerSubtitleCallback = { emitted1.add(it) },
        clientOverride = pass0Client,
        mediaContext = realSteelCtx
    )
    session1.startSession(SubtitleFile("English", srt45File.absolutePath)).join()

    assertTrue("Session 1 completed", session1.isCompleted)
    assertEquals("Pass 0 triggered once despite Cinemeta 'Genres: ...' toneGuide", 1, pass0Calls.get())
    val firstReg = observedChunkRegistries.firstOrNull()
    assertTrue("Chunk 0 received Film Bible relationship Charlie<->Max", firstReg?.relationships?.any { it.pair.contains("Charlie Kenton") && it.pair.contains("Max Kenton") } == true)
    assertEquals("Chunk 0 received Film Bible autoGlossary", "robot tập đấu", firstReg?.autoGlossary?.get("sparring bot"))
    assertTrue("Chunk 0 received Film Bible Bailey Tallet", firstReg?.characters?.any { it.name == "Bailey Tallet" } == true)

    // Case 1B: Cached rich Bible skips Pass 0 on subsequent run
    SubtitleCacheManager.clearAll(null)
    val session1b = AITranslationSession(
        mediaPayload = "real_steel_ep1_b",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = pass0Client,
        mediaContext = realSteelCtx
    )
    session1b.startSession(SubtitleFile("English", srt45File.absolutePath)).join()
    assertEquals("Pass 0 NOT re-run once rich Bible is cached", 1, pass0Calls.get())

    // Case 1C: cues < 40 does not run Pass 0; Case 1D: throwing generateJsonPrompt is non-blocking
    SubtitleCacheManager.clearAll(null)
    val shortFile = File(testRoot, "short_20.srt").apply { writeText(buildSrt(20)) }
    val throwPass0Calls = AtomicInteger(0)
    val throwingPass0Client = object : AITranslationClient {
        override suspend fun generateJsonPrompt(systemPrompt: String, userPrompt: String, timeoutSeconds: Long): String? {
            throwPass0Calls.incrementAndGet()
            throw RuntimeException("Simulated Pass 0 12s timeout")
        }
        override suspend fun translateChunk(cues: List<SubtitleCue>, sourceLang: String?, targetLang: String, customGlossary: String?): TranslationResult {
            return TranslationResult(translations = cues.associate { it.index to "Bản dịch ${it.index}" }, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }
    val session1c = AITranslationSession(
        mediaPayload = "short_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = throwingPass0Client,
        mediaContext = MediaContext(title = "Uncached Short Film", mediaType = "Movie")
    )
    session1c.startSession(SubtitleFile("English", shortFile.absolutePath)).join()
    assertEquals("Pass 0 skipped when cues < 40", 0, throwPass0Calls.get())
    assertTrue("Short movie completed", session1c.isCompleted)

    val longUncachedFile = File(testRoot, "long_45_uncached.srt").apply { writeText(buildSrt(45)) }
    val session1d = AITranslationSession(
        mediaPayload = "long_uncached",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = throwingPass0Client,
        mediaContext = MediaContext(title = "Uncached Long Film", mediaType = "Movie")
    )
    session1d.startSession(SubtitleFile("English", longUncachedFile.absolutePath)).join()
    assertEquals("Pass 0 attempted once for >= 40 uncached cues", 1, throwPass0Calls.get())
    assertTrue("Pass 0 exception is non-blocking; session still completes", session1d.isCompleted)

    // =========================================================================
    // TEST 2: Progressive Chunking (12-15 / 25-45), 12/8 Window, 1200/20 Roster, Rolling Digest
    // =========================================================================
    println("\n--- [Test 2: Progressive Chunking, 12/8 Window, 1200/20 Roster & Rolling Digest] ---")
    SubtitleCacheManager.clearAll(null)
    val cues100 = SubtitleParser.parse(buildSrt(100))
    val progChunks = AITranslationSession.splitIntoProgressiveChunks(cues100)
    assertTrue("Chunk 0 size is 12..15 (got ${progChunks[0].size})", progChunks[0].size in 12..15)
    assertTrue("Chunks 1..N are <= 45 (got ${progChunks.drop(1).map { it.size }})", progChunks.drop(1).all { it.size <= 45 })
    assertEquals("Total cues preserved across progressive chunks", 100, progChunks.sumOf { it.size })

    // Verify 12/8 lookbehind/lookahead, (1200.0, 20) pruning, and rollingSceneDigest propagation
    val largeChars = (1..10).map { CharacterEntity(name = "Char$it", role = "Supporting role $it", gender = "male") }
    val largeRels = (1..9).map {
        CharacterRelationship(pair = listOf("Char$it", "Char${it + 1}"), type = "friends", addressMatrix = mapOf("vi" to mapOf("call" to "tôi - cậu")))
    }
    CharacterProfileManager.saveProfile(
        null,
        "LargeCastFilm",
        TwoTierCharacterRegistry(mediaTitle = "LargeCastFilm", characters = largeChars, relationships = largeRels, toneGuide = "Modern cinema."),
        isEphemeral = false
    )
    val observedLookbehind = CopyOnWriteArrayList<Int>()
    val observedLookahead = CopyOnWriteArrayList<Int>()
    val observedDigests = CopyOnWriteArrayList<String?>()
    val observedRosterSizes = CopyOnWriteArrayList<Int>()

    val spyClient = object : AITranslationClient {
        override suspend fun translateChunkWithSceneDigest(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?,
            lookbehind: List<ContextCue>,
            lookahead: List<ContextCue>,
            characterRegistry: TwoTierCharacterRegistry?,
            mediaContext: MediaContext?,
            rollingSceneDigest: String?
        ): TranslationResult {
            observedLookbehind.add(lookbehind.size)
            observedLookahead.add(lookahead.size)
            observedDigests.add(rollingSceneDigest)
            observedRosterSizes.add(characterRegistry?.characters?.size ?: 0)
            return TranslationResult(translations = cues.associate { it.index to "Dịch câu ${it.index}" }, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val srt60File = File(testRoot, "cast_60.srt").apply {
        val sb = StringBuilder()
        var t = 1000L
        for (i in 1..60) {
            sb.append("$i\n${formatMs(t)} --> ${formatMs(t + 1000L)}\nChar${((i - 1) % 10) + 1}: Dialogue line number $i for testing.\n\n")
            t += 1300L
        }
        writeText(sb.toString())
    }
    val session2 = AITranslationSession(
        mediaPayload = "large_cast_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = spyClient,
        mediaContext = MediaContext(title = "LargeCastFilm", mediaType = "Movie")
    )
    session2.startSession(SubtitleFile("English", srt60File.absolutePath)).join()
    assertTrue("Session 2 completed", session2.isCompleted)
    assertTrue("Chunk 0 had lookahead == 8 (got ${observedLookahead.firstOrNull()})", observedLookahead.firstOrNull() == 8)
    assertTrue("Chunk 1 received 12 lookbehind cues (got ${observedLookbehind.getOrNull(1)})", observedLookbehind.getOrNull(1) == 12)
    assertTrue("Chunk 1 received non-blank rollingSceneDigest", !observedDigests.getOrNull(1).isNullOrBlank())
    assertTrue("Runtime pruneRegistry expanded beyond 6 chars (got ${observedRosterSizes.maxOrNull()})", (observedRosterSizes.maxOrNull() ?: 0) > 6)

    // =========================================================================
    // TEST 3: Adaptive Bisect Split-Retry & isUntranslatedCue
    // =========================================================================
    println("\n--- [Test 3: Adaptive Bisect Split-Retry & isUntranslatedCue] ---")
    assertTrue("Blank text is untranslated", AITranslationSession.isUntranslatedCue("Hello world today", ""))
    assertTrue("Long identical English line is untranslated", AITranslationSession.isUntranslatedCue("This is a long untranslated English sentence.", "This is a long untranslated English sentence."))
    assertFalse("MockAIClient 'Bản dịch của ...' is translated", AITranslationSession.isUntranslatedCue("Hello world today", "Bản dịch của Hello world today"))
    assertFalse("Short interjection/name <= 12 chars & < 3 words is spared", AITranslationSession.isUntranslatedCue("Max!", "Max!"))

    SubtitleCacheManager.clearAll(null)
    val bisectSizes = CopyOnWriteArrayList<Int>()
    val bisectDigests = CopyOnWriteArrayList<Pair<Int, String?>>()
    val bisectLookbehinds = CopyOnWriteArrayList<Pair<Int, Int>>()

    val bisectClient = object : AITranslationClient {
        override suspend fun translateChunkWithSceneDigest(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?,
            lookbehind: List<ContextCue>,
            lookahead: List<ContextCue>,
            characterRegistry: TwoTierCharacterRegistry?,
            mediaContext: MediaContext?,
            rollingSceneDigest: String?
        ): TranslationResult {
            bisectSizes.add(cues.size)
            bisectDigests.add(cues.first().index to rollingSceneDigest)
            bisectLookbehinds.add(cues.first().index to lookbehind.size)
            // Fail any chunk >= 16 cues (e.g. Chunk 1 of 30 cues) to force bisect 30 -> 15 + 15
            if (cues.size >= 16) {
                return TranslationResult(success = false, retryable = false, error = "Truncated large chunk")
            }
            return TranslationResult(translations = cues.associate { it.index to "Dịch chuẩn ${it.index}" }, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    // 45 cues -> Chunk 0 = 15 cues (succeeds), Chunk 1 = 30 cues (bisects into 15 + 15!)
    val bisectFile = File(testRoot, "bisect_45.srt").apply { writeText(buildSrt(45)) }
    val session3 = AITranslationSession(
        mediaPayload = "bisect_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = bisectClient
    )
    session3.startSession(SubtitleFile("English", bisectFile.absolutePath)).join()
    assertTrue("Bisect session completed", session3.isCompleted)
    assertTrue("Bisect observed 30-cue chunk attempt", bisectSizes.contains(30))
    assertTrue("Bisect split 30 -> 15 + 15 (observed >= 3 calls of size 15: ${bisectSizes.count { it == 15 }})", bisectSizes.count { it == 15 } >= 3)
    // Right half starts at index 31; it must receive lookbehind from left half (cues 16..30) and non-blank digest
    val rightHalfLb = bisectLookbehinds.firstOrNull { it.first == 31 }?.second ?: 0
    val rightHalfDigest = bisectDigests.firstOrNull { it.first == 31 }?.second
    assertTrue("Bisect right half (cue 31) received sequential lookbehind from left half (got $rightHalfLb)", rightHalfLb > 0)
    assertTrue("Bisect right half (cue 31) received rollingSceneDigest from left half", !rightHalfDigest.isNullOrBlank())

    // =========================================================================
    // TEST 4: Tier 1 Full Cache Protection & Poisoned Cache Self-Healing
    // =========================================================================
    println("\n--- [Test 4: Tier 1 Full Cache Protection & Self-Healing] ---")
    SubtitleCacheManager.clearAll(null)
    val partialRaw = buildSrt(20) // Chunk 0 = 15 cues, Chunk 1 = 5 cues (< 16, cannot bisect)
    val partialFile = File(testRoot, "partial_20.srt").apply { writeText(partialRaw) }
    val partialClient = object : AITranslationClient {
        override suspend fun translateChunk(cues: List<SubtitleCue>, sourceLang: String?, targetLang: String, customGlossary: String?): TranslationResult {
            // Chunk starting at 1 succeeds; Chunk starting at 16 returns untranslated raw text
            return if (cues.first().index == 1) {
                TranslationResult(translations = cues.associate { it.index to "Dịch đúng ${it.index}" }, success = true)
            } else {
                TranslationResult(translations = cues.associate { it.index to cues.first { c -> c.index == it.index }.rawText }, success = true)
            }
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    val session4 = AITranslationSession(
        mediaPayload = "partial_cache_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = partialClient
    )
    session4.startSession(SubtitleFile("English", partialFile.absolutePath)).join()
    val expectedKey4 = SubtitleCacheManager.buildCacheKey(
        content = partialRaw,
        targetLang = "vi",
        provider = TranslatorConfig.provider,
        model = TranslatorConfig.getEffectiveModel(),
        glossary = TranslatorConfig.customGlossary
    )
    assertTrue("Partial session still emitted playable _interim file", session4.completedFile?.name?.contains("_interim") == true)
    assertEquals("Tier 1 Full Cache MUST NOT be poisoned when cues remain untranslated", null, SubtitleCacheManager.getFullFile(null, expectedKey4))
    val tier2Loaded = SubtitleCacheManager.loadChunks(null, expectedKey4)
    assertEquals("Tier 2 retains only the 15 clean translated cues (not the 5 untranslated cues)", 15, tier2Loaded.size)

    // Self-healing test: manually poison Tier 1 cache with untranslated cues and verify re-translation
    SubtitleCacheManager.saveFullFile(null, expectedKey4, partialRaw)
    assertTrue("Poisoned Tier 1 file exists before session", SubtitleCacheManager.getFullFile(null, expectedKey4) != null)
    val cleanClient4 = object : AITranslationClient {
        override suspend fun translateChunk(cues: List<SubtitleCue>, sourceLang: String?, targetLang: String, customGlossary: String?): TranslationResult {
            return TranslationResult(translations = cues.associate { it.index to "Dịch sạch ${it.index}" }, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }
    val session4b = AITranslationSession(
        mediaPayload = "partial_cache_movie_heal",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = cleanClient4
    )
    session4b.startSession(SubtitleFile("English", partialFile.absolutePath)).join()
    val healedText = SubtitleCacheManager.getFullFile(null, expectedKey4)?.readText(Charsets.UTF_8).orEmpty()
    assertTrue("Poisoned Tier 1 file was purged and replaced with clean translations", healedText.contains("Dịch sạch 16"))

    // =========================================================================
    // TEST 5: highSpeedMode Scene Parallelism (>= 15s Gap)
    // =========================================================================
    println("\n--- [Test 5: highSpeedMode Scene Parallelism (>= 15s Gap)] ---")
    SubtitleCacheManager.clearAll(null)
    TranslatorConfig.highSpeedMode = true
    val activeWorkers = AtomicInteger(0)
    val maxConcurrentWorkers = AtomicInteger(0)
    val parallelClient = object : AITranslationClient {
        override suspend fun translateChunk(cues: List<SubtitleCue>, sourceLang: String?, targetLang: String, customGlossary: String?): TranslationResult {
            val cur = activeWorkers.incrementAndGet()
            while (true) {
                val prev = maxConcurrentWorkers.get()
                if (cur <= prev || maxConcurrentWorkers.compareAndSet(prev, cur)) break
            }
            delay(150L)
            activeWorkers.decrementAndGet()
            return TranslationResult(translations = cues.associate { it.index to "Song song ${it.index}" }, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }

    // 45 cues with 20_000ms gap right after cue 15 (splits Chunk 0 and Chunk 1 into 2 scene groups!)
    val parallelSrtFile = File(testRoot, "parallel_45.srt").apply {
        writeText(buildSrt(45, gapAfterCue = mapOf(15 to 20_000L)))
    }
    val session5 = AITranslationSession(
        mediaPayload = "parallel_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = parallelClient
    )
    session5.startSession(SubtitleFile("English", parallelSrtFile.absolutePath)).join()
    TranslatorConfig.highSpeedMode = false
    assertTrue("highSpeedMode session completed", session5.isCompleted)
    assertTrue("highSpeedMode ran >= 2 independent scene groups concurrently (maxConcurrent=${maxConcurrentWorkers.get()})", maxConcurrentWorkers.get() >= 2)

    // =========================================================================
    // TEST 6: Cancel HUD + 0ms Call Cancel + LocalServer 404 & Latch Release
    // =========================================================================
    println("\n--- [Test 6: Cancel Flow, 0ms OkHttp Abort & LocalServer 404] ---")
    SubtitleCacheManager.clearAll(null)
    val hasOnCancel = TranslationProgressHUD::class.java.methods.any { m ->
        m.name == "show" && m.parameterTypes.size == 8
    }
    assertTrue("TranslationProgressHUD.show exposes 8-param overload with onCancel", hasOnCancel)

    // Spin up a hanging HTTP server to test 0ms OkHttp abort when cancelSession is invoked
    val hangServer = ServerSocket(0)
    val hangPort = hangServer.localPort
    val acceptedSocketRef = AtomicReference<Socket?>(null)
    val hangAcceptedLatch = CountDownLatch(1)
    thread(isDaemon = true) {
        try {
            val s = hangServer.accept()
            acceptedSocketRef.set(s)
            hangAcceptedLatch.countDown()
            Thread.sleep(10_000L)
        } catch (_: Throwable) {}
    }

    AITranslatorLocalServer.customBaseDir = File(testRoot, "server_cache").apply { mkdirs() }
    val srvPort = AITranslatorLocalServer.start(null)
    val session6 = AITranslationSession(
        mediaPayload = "cancel_test_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = cleanClient4
    )
    AITranslatorLocalServer.registerSession(session6.sessionKey, session6)

    // Launch a blocking HTTP GET against AITranslatorLocalServer while session is active
    val serverResponseCode = AtomicInteger(-1)
    val serverLatch = CountDownLatch(1)
    thread(isDaemon = true) {
        try {
            val conn = URL("http://127.0.0.1:$srvPort/sub/${session6.sessionKey}.srt").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            serverResponseCode.set(conn.responseCode)
            conn.disconnect()
        } catch (_: Throwable) {} finally {
            serverLatch.countDown()
        }
    }

    // Start subtitle download against hanging server
    val job6 = session6.startSession(SubtitleFile("English", "http://127.0.0.1:$hangPort/hang.srt"))
    hangAcceptedLatch.await(3, TimeUnit.SECONDS)
    delay(150L)

    val cancelStartMs = System.currentTimeMillis()
    session6.cancelSession(userInitiated = true)
    job6.join()
    val cancelElapsedMs = System.currentTimeMillis() - cancelStartMs

    assertTrue("session6.isCancelled is true", session6.isCancelled)
    assertTrue("OkHttp download + session aborted rapidly (< 1500ms, got ${cancelElapsedMs}ms)", cancelElapsedMs < 1500L)
    assertTrue("AITranslatorLocalServer unblocked immediately", serverLatch.await(2, TimeUnit.SECONDS))
    assertEquals("AITranslatorLocalServer returned HTTP 404 on cancelled session", 404, serverResponseCode.get())
    try { acceptedSocketRef.get()?.close() } catch (_: Throwable) {}
    try { hangServer.close() } catch (_: Throwable) {}

    // =========================================================================
    // TEST 7: MediaContext Extraction & ExtractorLink 0ms Passthrough + Referer Fallback
    // =========================================================================
    println("\n--- [Test 7: MediaContext Extraction & ExtractorLink Referer Fallback] ---")
    SubtitleCacheManager.clearAll(null)
    MediaContextManager.clear()
    val refServer = ServerSocket(0)
    val refPort = refServer.localPort
    val refThread = thread(isDaemon = true) {
        try {
            val sock = refServer.accept()
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (line != null && line.isNotBlank()) {
                val p = line.split(":", limit = 2)
                if (p.size == 2) headers[p[0].trim().lowercase()] = p[1].trim()
                line = reader.readLine()
            }
            val out = sock.getOutputStream()
            if (headers["referer"] == "https://video-cdn.test/embed" && headers["x-stream-auth"] == "v11") {
                val body = "1\n00:00:01,000 --> 00:00:04,000\nReferer protected subtitle dialogue.\n\n"
                val bytes = body.toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                out.write(bytes)
            } else {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
            out.flush()
            sock.close()
            refServer.close()
        } catch (_: Throwable) {}
    }

    val videoCallbackOrder = CopyOnWriteArrayList<String>()
    val subCallbackEmitted = CopyOnWriteArrayList<SubtitleFile>()
    val fakeLeafApi = object : MainAPI() {
        override var name = "MockStreamProvider"
        override var mainUrl = "https://video-cdn.test"
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            callback(
                ExtractorLink(
                    source = "MockStreamProvider",
                    name = "1080p",
                    url = "https://video-cdn.test/master.m3u8",
                    referer = "https://video-cdn.test/embed",
                    quality = 1080,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf("X-Stream-Auth" to "v11")
                )
            )
            // Subtitle emitted WITHOUT headers — must inherit from captured ExtractorLink!
            subtitleCallback(SubtitleFile("English", "http://127.0.0.1:$refPort/sub.srt"))
            return true
        }
    }

    val movieResp = fakeLeafApi.newMovieLoadResponse(
        name = "Real Steel",
        url = "https://example.com/real-steel",
        type = TvType.Movie,
        dataUrl = "https://example.com/real-steel"
    ) {
        year = 2011
        plot = "Robot boxing movie"
        tags = listOf("Action", "Sci-Fi")
        actors = listOf(ActorData(Actor("Hugh Jackman"), roleString = "Charlie Kenton"))
        with(LoadResponse.Companion) {
            addImdbId("tt0433035")
        }
    }
    MediaContextManager.setContext(movieResp)
    val capturedCtx = MediaContextManager.currentContext
    assertEquals("MediaContext.mediaType populated", "Movie", capturedCtx?.mediaType)
    assertEquals("MediaContext.year populated", 2011, capturedCtx?.year)
    assertEquals("MediaContext.genres populated", listOf("Action", "Sci-Fi"), capturedCtx?.genres)
    assertEquals("MediaContext.characterActors populated", "Charlie Kenton", capturedCtx?.characterActors?.get("Hugh Jackman"))
    assertEquals("MediaContextManager.currentImdbId extracted", "tt0433035", MediaContextManager.currentImdbId)

    val enrichedApi = AITranslatorEnrichedMainAPI(fakeLeafApi)
    TranslatorConfig.provider = TranslatorConfig.PROVIDER_CUSTOM
    TranslatorConfig.customBaseUrl = "http://127.0.0.1:1"
    val enrichedOk = enrichedApi.loadLinks(
        data = "ref_test_ep",
        isCasting = false,
        subtitleCallback = { subCallbackEmitted.add(it) },
        callback = { videoCallbackOrder.add(it.url) }
    )
    assertTrue("Enriched loadLinks succeeded", enrichedOk)
    assertEquals("ExtractorLink passed through at 0ms", listOf("https://video-cdn.test/master.m3u8"), videoCallbackOrder.toList())
    assertTrue("Pre-announced [AI] track emitted", subCallbackEmitted.any { it.lang.startsWith("[AI]") })
    enrichedApi.activeSession?.cancelSession()
    TranslatorConfig.provider = TranslatorConfig.PROVIDER_GEMINI
    TranslatorConfig.customBaseUrl = ""

    // Verify end-to-end 403->200 referer fallback download using recorded ExtractorLink
    val refSession = AITranslationSession(
        mediaPayload = "ref_session_e2e",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = cleanClient4
    )
    refSession.recordExtractorLink(
        ExtractorLink(
            source = "MockStreamProvider",
            name = "1080p",
            url = "https://video-cdn.test/master.m3u8",
            referer = "https://video-cdn.test/embed",
            quality = 1080,
            type = ExtractorLinkType.M3U8,
            headers = mapOf("X-Stream-Auth" to "v11")
        )
    )
    refSession.startSession(SubtitleFile("English", "http://127.0.0.1:$refPort/sub.srt")).join()
    refThread.join(2000)
    assertTrue("Subtitle without headers succeeded via ExtractorLink Referer/headers fallback", refSession.isCompleted)

    // =========================================================================
    // TEST 8: StreamSubtitleExtractor Tier A (HLS/DASH/VTT Unroll/Bypass) & Tier B
    // =========================================================================
    println("\n--- [Test 8: StreamSubtitleExtractor Tier A & Tier B] ---")
    // 8A: HLS Master Playlist parsing & relative URI resolution
    val hlsMaster = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,LANGUAGE="en",URI="subs/en/index.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Vietnamese",DEFAULT=NO,LANGUAGE="vi",URI="https://cdn.example.com/vi.vtt"
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,SUBTITLES="subs"
        video/1080p.m3u8
    """.trimIndent()
    val hlsSubs = StreamSubtitleExtractor.parseHlsSubtitles(hlsMaster, "https://cdn.example.com/hls/master.m3u8")
    assertEquals("Parsed 2 HLS subtitle tracks", 2, hlsSubs.size)
    assertEquals("Resolved relative HLS subtitle URI", "https://cdn.example.com/hls/subs/en/index.m3u8", hlsSubs[0].url)
    assertEquals("Preserved absolute HLS subtitle URI", "https://cdn.example.com/vi.vtt", hlsSubs[1].url)

    // 8B: DASH MPD pure-Regex parsing
    val dashMpd = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
          <Period>
            <AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="v1"/></AdaptationSet>
            <AdaptationSet contentType="text" mimeType="text/vtt" lang="en">
              <Label>English CC</Label>
              <Representation id="t1"><BaseURL>subs/dash_en.vtt</BaseURL></Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()
    val dashSubs = StreamSubtitleExtractor.parseDashSubtitles(dashMpd, "https://dash.example.com/stream/manifest.mpd")
    assertEquals("Parsed 1 DASH text track", 1, dashSubs.size)
    assertEquals("Resolved DASH relative BaseURL", "https://dash.example.com/stream/subs/dash_en.vtt", dashSubs[0].url)
    assertTrue("DASH track label contains 'en'", dashSubs[0].lang.contains("en", ignoreCase = true))

    // 8C: HLS VTT segment unroller (strips per-segment WEBVTT/X-TIMESTAMP-MAP, deduplicates boundary cue)
    val seg1 = """
        WEBVTT
        X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000
        Kind: captions
        Language: en

        00:00:01.000 --> 00:00:04.000
        First segment dialogue.

        00:00:04.500 --> 00:00:07.000
        Boundary overlapping cue.
    """.trimIndent()
    val seg2 = """
        WEBVTT
        X-TIMESTAMP-MAP=MPEGTS:1800000,LOCAL:00:00:00.000

        00:00:04.500 --> 00:00:07.000
        Boundary overlapping cue.

        00:00:08.000 --> 00:00:11.000
        Second segment dialogue.
    """.trimIndent()
    val vttM3u8Server = ServerSocket(0)
    val vttPort = vttM3u8Server.localPort
    thread(isDaemon = true) {
        try {
            repeat(3) {
                val sock = vttM3u8Server.accept()
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val reqLine = reader.readLine() ?: ""
                var l = reader.readLine()
                while (l != null && l.isNotBlank()) l = reader.readLine()
                val body = when {
                    reqLine.contains("sub_list.m3u8") -> "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:5.0,\nseg1.vtt\n#EXTINF:5.0,\nseg2.vtt\n#EXT-X-ENDLIST\n"
                    reqLine.contains("seg1.vtt") -> seg1
                    reqLine.contains("seg2.vtt") -> seg2
                    else -> ""
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                out.write(bytes)
                out.flush()
                sock.close()
            }
            vttM3u8Server.close()
        } catch (_: Throwable) {}
    }

    SubtitleCacheManager.clearAll(null)
    val vttSession = AITranslationSession(
        mediaPayload = "hls_vtt_unroll_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = cleanClient4
    )
    vttSession.startSession(SubtitleFile("English", "http://127.0.0.1:$vttPort/sub_list.m3u8")).join()
    assertTrue("HLS VTT .m3u8 playlist unrolled and translated cleanly", vttSession.isCompleted)
    val unrolledSrtText = vttSession.completedFile?.readText(Charsets.UTF_8).orEmpty()
    val unrolledCues = SubtitleParser.parse(unrolledSrtText)
    assertEquals("Unrolled + deduplicated VTT produced exactly 3 cues (not 4)", 3, unrolledCues.size)
    assertFalse("No X-TIMESTAMP-MAP header leaked into translated cue", unrolledSrtText.contains("X-TIMESTAMP-MAP"))

    // Encrypted HLS subtitle playlist rejection
    val encryptedResult = StreamSubtitleExtractor.unrollHlsVttPlaylist(
        playlistText = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:5.0,\nseg1.vtt",
        playlistUrl = "https://example.com/sub.m3u8",
        fetchBytes = { _, _ -> null }
    )
    assertEquals("Encrypted HLS subtitle playlist cleanly rejected as null", null, encryptedResult)

    // 8C2: P15e Regression — Variant (#EXT-X-STREAM-INF) -> media .m3u8 -> .vtt via AITranslationSession.startSession
    val variantServer = ServerSocket(0)
    val variantPort = variantServer.localPort
    val variantRequestedPaths = CopyOnWriteArrayList<String>()
    thread(isDaemon = true) {
        try {
            while (!variantServer.isClosed) {
                val sock = variantServer.accept()
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val reqLine = reader.readLine() ?: ""
                var l = reader.readLine()
                while (l != null && l.isNotBlank()) l = reader.readLine()
                val reqPath = reqLine.split(" ").getOrNull(1).orEmpty()
                variantRequestedPaths.add(reqPath)
                val (status, body) = when {
                    reqPath.contains("sub_master.m3u8") -> "200 OK" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\nsub_en.m3u8\n"
                    reqPath.contains("sub_en.m3u8") -> "200 OK" to "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:5.0,\ns1.vtt\n#EXTINF:5.0,\ns2.vtt\n#EXT-X-ENDLIST\n"
                    reqPath.contains("s1.vtt") -> "200 OK" to "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nVariant segment one.\n\n"
                    reqPath.contains("s2.vtt") -> "200 OK" to "WEBVTT\n\n00:00:04.000 --> 00:00:06.000\nVariant segment two.\n\n"
                    else -> "404 Not Found" to ""
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                out.write(bytes)
                out.flush()
                sock.close()
            }
        } catch (_: Throwable) {}
    }
    SubtitleCacheManager.clearAll(null)
    val variantSession = AITranslationSession(
        mediaPayload = "hls_variant_p15e_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = cleanClient4
    )
    variantSession.startSession(SubtitleFile("en", "http://127.0.0.1:$variantPort/sub_master.m3u8")).join()
    try { variantServer.close() } catch (_: Throwable) {}
    assertTrue("P15e: Variant #EXT-X-STREAM-INF -> media .m3u8 -> .vtt session completed", variantSession.isCompleted)
    assertFalse("P15e: No /WEBVTT 404 request was made", variantRequestedPaths.any { it.contains("WEBVTT") })
    val variantSrtText = variantSession.completedFile?.readText(Charsets.UTF_8).orEmpty()
    val variantCues = SubtitleParser.parse(variantSrtText)
    assertEquals("P15e: Variant HLS playlist translated all 2 cues", 2, variantCues.size)

    // Direct guard tests in StreamSubtitleExtractor.unrollHlsVttPlaylist (WEBVTT & non-HLS rejection)
    var guardFetchCalls = 0
    val directVttBytes = StreamSubtitleExtractor.unrollHlsVttPlaylist(
        playlistText = "WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nAlready unrolled VTT\n",
        playlistUrl = "https://example.com/sub.m3u8",
        fetchBytes = { _, _ -> guardFetchCalls++; null }
    )
    assertTrue("Direct WEBVTT payload returned without fetching cue lines as URLs", directVttBytes != null && guardFetchCalls == 0)
    val nonHlsBytes = StreamSubtitleExtractor.unrollHlsVttPlaylist(
        playlistText = "<html><body>Not an HLS playlist</body></html>",
        playlistUrl = "https://example.com/error.m3u8",
        fetchBytes = { _, _ -> guardFetchCalls++; null }
    )
    assertEquals("Non-HLS/non-VTT payload cleanly rejected as null without fetching lines", null, nonHlsBytes)
    assertEquals("Non-HLS guard made 0 segment fetch attempts", 0, guardFetchCalls)

    // 8C3: Blank-lineless adjacent VTT cues edge case in extractVttCueBlocks
    val blankLinelessVtt = """
        WEBVTT

        00:00:01.000 --> 00:00:02.500
        Cue one without trailing blank line
        00:00:02.600 --> 00:00:04.000
        Cue two immediately following
        00:00:04.500 --> 00:00:06.000
        Cue three immediately following
    """.trimIndent()
    val blankLinelessUnrolled = StreamSubtitleExtractor.unrollHlsVttPlaylist(
        playlistText = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:6.0,\nseg_noblank.vtt\n#EXT-X-ENDLIST",
        playlistUrl = "https://example.com/noblank.m3u8",
        fetchBytes = { _, _ -> blankLinelessVtt.toByteArray(Charsets.UTF_8) }
    )
    assertTrue("Blank-lineless VTT unrolled cleanly", blankLinelessUnrolled != null)
    val blankLinelessCues = SubtitleParser.parse(String(blankLinelessUnrolled!!, Charsets.UTF_8))
    assertEquals("Blank-lineless adjacent VTT cues preserved all 3 cues", 3, blankLinelessCues.size)
    assertTrue("Blank-lineless cue 2 text intact", blankLinelessCues[1].rawText.contains("Cue two immediately following"))
    assertTrue("Blank-lineless cue 3 text intact", blankLinelessCues[2].rawText.contains("Cue three immediately following"))

    // 8D: Tier A Target Language Bypass via local HLS master playlist (and top-3 + timeout check)
    val hlsBypassServer = ServerSocket(0)
    val hlsBypassPort = hlsBypassServer.localPort
    thread(isDaemon = true) {
        try {
            val sock = hlsBypassServer.accept()
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            var l = reader.readLine()
            while (l != null && l.isNotBlank()) l = reader.readLine()
            val bytes = hlsMaster.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
            sock.close()
            hlsBypassServer.close()
        } catch (_: Throwable) {}
    }
    val tierADiscovery = StreamSubtitleExtractor.discoverFallbackSubtitles(
        capturedLinks = listOf(
            ExtractorLink("Test", "Master", "http://127.0.0.1:$hlsBypassPort/master.m3u8", "", 1080, ExtractorLinkType.M3U8)
        ),
        targetLang = "vi",
        title = "Test Movie"
    )
    assertTrue("Tier A detected Vietnamese track in HLS manifest and triggered Bypass", tierADiscovery is StreamSubtitleExtractor.FallbackDiscoveryResult.Bypassed)

    // Top-3 link cap check: 4 links (4th link should NEVER be fetched)
    val linkHitCounts = AtomicInteger(0)
    val capServer = ServerSocket(0)
    val capPort = capServer.localPort
    thread(isDaemon = true) {
        try {
            while (!capServer.isClosed) {
                val sock = capServer.accept()
                linkHitCounts.incrementAndGet()
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\n#EXTM3U".toByteArray())
                out.flush()
                sock.close()
            }
        } catch (_: Throwable) {}
    }
    val fourLinks = (1..4).map { q ->
        ExtractorLink("Test", "Stream$q", "http://127.0.0.1:$capPort/stream_$q.m3u8", "", q * 100, ExtractorLinkType.M3U8)
    }
    StreamSubtitleExtractor.discoverFallbackSubtitles(
        capturedLinks = fourLinks,
        targetLang = "vi",
        title = null
    )
    try { capServer.close() } catch (_: Throwable) {}
    assertEquals("Tier A scans at most 3 priority links", 3, linkHitCounts.get())

    // 8E: Tier B1 Pure Java Reflection on PlayerGeneratorViewModel (EMBEDDED_IN_VIDEO bypass)
    val mockEmbeddedVm = object {
        fun getCurrentSubtitles(): Any = object {
            fun getValue(): Any = object {
                fun getValue(): Set<SubtitleData> = setOf(
                    SubtitleData(
                        originalName = "Vietnamese [Embedded]",
                        nameSuffix = "",
                        url = "embedded://track/0",
                        origin = SubtitleOrigin.EMBEDDED_IN_VIDEO,
                        mimeType = "text/vtt",
                        headers = emptyMap(),
                        languageCode = "vi"
                    )
                )
            }
        }
    }
    val embeddedMatched = StreamSubtitleExtractor.hasEmbeddedTargetLanguageTrack("vi", mockEmbeddedVm)
    assertEquals("Tier B1 reflection detected EMBEDDED_IN_VIDEO 'vi' track", "vi", embeddedMatched)

    // 8F: Tier B2 AccountManager SubtitleAPI fallback (search + resource -> SubtitleFile)
    val mockSubtitleApi = object : SubtitleAPI() {
        override val name = "MockCoreSubApi"
        override suspend fun search(auth: AuthData?, query: AbstractSubtitleEntities.SubtitleSearch): List<AbstractSubtitleEntities.SubtitleEntity> {
            assertTrue("SubtitleSearch.lang is non-null", query.lang != null)
            return if (query.lang == "en") {
                listOf(
                    AbstractSubtitleEntities.SubtitleEntity(
                        idPrefix = "mock",
                        name = "Real Steel (2011) EN",
                        lang = "en",
                        data = "sub_123",
                        type = TvType.Movie,
                        source = "MockCoreSubApi",
                        epNumber = null,
                        seasonNumber = null,
                        year = 2011,
                        isHearingImpaired = false,
                        headers = mapOf("X-Sub-Key" to "abc")
                    )
                )
            } else emptyList()
        }

        override suspend fun SubtitleResource.getResources(auth: AuthData?, subtitle: AbstractSubtitleEntities.SubtitleEntity) {
            addUrl("https://subs.example.com/real_steel_en.srt", "Real Steel EN")
        }
    }
    val tierB2Res = StreamSubtitleExtractor.querySubtitleApiProviders(
        title = "Real Steel",
        year = 2011,
        imdbId = "tt0433035",
        targetLang = "vi",
        apisOverride = listOf(mockSubtitleApi to null)
    )
    assertTrue("Tier B2 resolved SubtitleAPI candidate", tierB2Res is StreamSubtitleExtractor.FallbackDiscoveryResult.CandidatesFound)
    val b2Files = (tierB2Res as StreamSubtitleExtractor.FallbackDiscoveryResult.CandidatesFound).candidates
    assertEquals("Tier B2 returned 1 SubtitleFile", 1, b2Files.size)
    assertEquals("Tier B2 preserved URL", "https://subs.example.com/real_steel_en.srt", b2Files[0].url)
    assertEquals("Tier B2 preserved headers", "abc", b2Files[0].headers?.get("X-Sub-Key"))

    // =========================================================================
    // TEST 9: AITranslatorSettingsDialog v11 Defaults & highSpeedMode Persistence
    // =========================================================================
    println("\n--- [Test 9: Settings v11 Defaults & highSpeedMode Persistence] ---")
    val mockCtx = MockContextM2.create()
    TranslatorConfig.resetToDefaults()
    assertEquals("Default contextWindowSize is 12", 12, TranslatorConfig.contextWindowSize)
    assertEquals("Default maxChunkCues is 45", 45, TranslatorConfig.maxChunkCues)
    assertFalse("Default highSpeedMode is false", TranslatorConfig.highSpeedMode)

    TranslatorConfig.highSpeedMode = true
    TranslatorConfig.save(mockCtx)
    TranslatorConfig.highSpeedMode = false
    TranslatorConfig.init(mockCtx)
    assertTrue("key_high_speed_mode persisted and restored", TranslatorConfig.highSpeedMode)

    val dialogBuilder = try {
        AITranslatorSettingsDialog.buildDialog(mockCtx)
    } catch (_: Throwable) {
        null // Android stub AlertDialog.Builder on desktop JVM may throw Stub!
    }
    assertTrue("AITranslatorSettingsDialog exposes buildDialog without XML inflation", AITranslatorSettingsDialog::class.java.methods.any { it.name == "buildDialog" })
    if (dialogBuilder != null) {
        println("  [PASS] AITranslatorSettingsDialog.buildDialog instantiated cleanly")
    }

    // =========================================================================
    // TEST 10: Tier 5 Adversarial Regression Tests (Gaps 1-4)
    // =========================================================================
    println("\n--- [Test 10: Tier 5 Adversarial Regression Tests] ---")

    // 10A: Gap 1 - Runtime onSeek reprioritizes pending sequential chunks
    TranslatorConfig.resetToDefaults()
    TranslatorConfig.showProgressHUD = false
    TranslatorConfig.highSpeedMode = false
    SubtitleCacheManager.clearAll(null)

    val srt105Text = buildSrt(105)
    val cues105 = SubtitleParser.parse(srt105Text)
    val seekFile = File(testRoot, "tier5_seek_105.srt").apply { writeText(srt105Text) }
    val chunks105 = AITranslationSession.splitIntoProgressiveChunks(cues105)
    assertTrue("Tier 5 105-cue SRT produces >= 3 chunks (got ${chunks105.size})", chunks105.size >= 3)
    val chunk2StartMs = chunks105[2].first().startMs
    val executedFirstIndices = CopyOnWriteArrayList<Int>()
    lateinit var seekSessionRef: AITranslationSession
    val seekClient = object : AITranslationClient {
        override suspend fun translateChunkWithSceneDigest(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?,
            lookbehind: List<ContextCue>,
            lookahead: List<ContextCue>,
            characterRegistry: TwoTierCharacterRegistry?,
            mediaContext: MediaContext?,
            rollingSceneDigest: String?
        ): TranslationResult {
            executedFirstIndices.add(cues.first().index)
            if (cues.first().index == chunks105[0].first().index) {
                // Seek directly into Chunk 2 while Chunk 0 is executing
                seekSessionRef.onSeek(chunk2StartMs + 200L)
            }
            return TranslationResult(translations = cues.associate { it.index to "Dịch seek ${it.index}" }, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }
    val seekSession = AITranslationSession(
        mediaPayload = "tier5_runtime_seek_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = seekClient
    )
    seekSessionRef = seekSession
    seekSession.startSession(SubtitleFile("English", seekFile.absolutePath)).join()
    assertTrue("Tier 5 runtime onSeek session completed", seekSession.isCompleted)
    assertEquals("Tier 5 Chunk 0 ran first", chunks105[0].first().index, executedFirstIndices.getOrNull(0))
    assertEquals("Tier 5 Chunk 2 reprioritized immediately after onSeek", chunks105[2].first().index, executedFirstIndices.getOrNull(1))
    assertEquals("Tier 5 Chunk 1 ran after Chunk 2", chunks105[1].first().index, executedFirstIndices.getOrNull(2))

    // 10B: Gap 2 - Async English (10ms) + Tiếng Việt (40ms) Late Bypass & LocalServer < 500ms 404 (no 60s hang, no ghost [AI])
    SubtitleCacheManager.clearAll(null)
    val lateBypassPort = AITranslatorLocalServer.start(null)
    val lateBypassSession = AITranslationSession(
        mediaPayload = "tier5_late_bypass_movie",
        targetLang = "vi",
        playerSubtitleCallback = {}
    )
    // Simulate pre-announcement on English before late Vietnamese arrives
    lateBypassSession.preAnnounceIfNeeded(SubtitleFile("English", "https://example.com/tier5_en.srt"))
    val ghostUrl = AITranslatorLocalServer.getSubtitleUrl(lateBypassSession.sessionKey)
    val ghostHttpCode = AtomicInteger(-1)
    val ghostHttpElapsedMs = AtomicInteger(-1)
    val ghostLatch = CountDownLatch(1)
    thread(isDaemon = true) {
        val t0 = System.currentTimeMillis()
        try {
            val conn = URL(ghostUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            ghostHttpCode.set(conn.responseCode)
            conn.disconnect()
        } catch (_: Throwable) {} finally {
            ghostHttpElapsedMs.set((System.currentTimeMillis() - t0).toInt())
            ghostLatch.countDown()
        }
    }
    delay(10L)
    lateBypassSession.queueCandidate(SubtitleFile("English", "https://example.com/tier5_en.srt"))
    delay(40L)
    lateBypassSession.queueCandidate(SubtitleFile("Tiếng Việt", "https://example.com/tier5_vi.srt"))
    assertTrue("Tier 5 late bypass unblocked AITranslatorLocalServer promptly", ghostLatch.await(1500, TimeUnit.MILLISECONDS))
    assertEquals("Tier 5 late bypass returned HTTP 404 on pre-announced URL", 404, ghostHttpCode.get())
    assertTrue("Tier 5 late bypass HTTP 404 completed in < 500ms (got ${ghostHttpElapsedMs.get()}ms)", ghostHttpElapsedMs.get() in 0..500)
    assertTrue("Tier 5 late bypass marked session bypassed", lateBypassSession.isTargetLanguageBypassed)
    assertFalse("Tier 5 awaitCompletion returns false immediately on bypassed session", lateBypassSession.awaitCompletion(2000L))

    // Also verify AITranslatorEnrichedMainAPI async race emits 0 ghost [AI] tracks
    val raceEmitted = CopyOnWriteArrayList<SubtitleFile>()
    val asyncRaceBaseApi = object : MainAPI() {
        override var name = "AsyncRaceMock"
        override var mainUrl = "https://async.race.test"
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            CoroutineScope(Dispatchers.IO).launch {
                delay(10L)
                subtitleCallback(SubtitleFile("English (Async)", "https://async.race.test/en.srt"))
            }
            CoroutineScope(Dispatchers.IO).launch {
                delay(40L)
                subtitleCallback(SubtitleFile("Tiếng Việt (Async)", "https://async.race.test/vi.srt"))
            }
            return true
        }
    }
    val asyncRaceEnriched = AITranslatorEnrichedMainAPI(asyncRaceBaseApi)
    asyncRaceEnriched.loadLinks("tier5_async_race", false, { raceEmitted.add(it) }, {})
    delay(280L)
    assertEquals("Tier 5 async race emitted exact 2 tracks without ghost [AI] track", 2, raceEmitted.size)
    assertTrue("Tier 5 async race session marked bypassed", asyncRaceEnriched.activeSession?.isTargetLanguageBypassed == true)
    asyncRaceEnriched.activeSession?.cancelSession()

    // 10C: Gap 3 - Dropped long cues (7/40) & dropped short cues (15/40 "Come on!") bisect & Tier 1 cache protection
    assertFalse("Pure SFX '[Music]' hasDialogueLetters == false", AITranslationSession.hasDialogueLetters("[Music]"))
    assertFalse("Pure punctuation '...' hasDialogueLetters == false", AITranslationSession.hasDialogueLetters("..."))
    assertTrue("Short dialogue 'Come on!' hasDialogueLetters == true", AITranslationSession.hasDialogueLetters("Come on!"))

    // 10C-1: 55 cues (Chunk 0=15, Chunk 1=40); LLM drops 7/40 long cues on 40-cue chunk, succeeds on bisected 20+20
    SubtitleCacheManager.clearAll(null)
    val raw55Long = buildSrt(55)
    val file55Long = File(testRoot, "tier5_drop_long_55.srt").apply { writeText(raw55Long) }
    val observedSizesLong = CopyOnWriteArrayList<Int>()
    val dropLongClient = object : AITranslationClient {
        override suspend fun translateChunkWithSceneDigest(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?,
            lookbehind: List<ContextCue>,
            lookahead: List<ContextCue>,
            characterRegistry: TwoTierCharacterRegistry?,
            mediaContext: MediaContext?,
            rollingSceneDigest: String?
        ): TranslationResult {
            observedSizesLong.add(cues.size)
            val kept = if (cues.size == 40) cues.dropLast(7) else cues
            return TranslationResult(translations = kept.associate { it.index to "Dịch dài ${it.index}" }, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }
    val dropLongSession = AITranslationSession(
        mediaPayload = "tier5_drop_long_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = dropLongClient
    )
    dropLongSession.startSession(SubtitleFile("English", file55Long.absolutePath)).join()
    assertTrue("Tier 5 (7/40 dropped long cues) triggered bisect (observed 40 and 20: $observedSizesLong)", observedSizesLong.contains(40) && observedSizesLong.contains(20))
    val key55Long = SubtitleCacheManager.buildCacheKey(raw55Long, "vi", TranslatorConfig.provider, TranslatorConfig.getEffectiveModel(), TranslatorConfig.customGlossary)
    assertTrue("Tier 5 recovered long-cue session wrote clean Tier 1 Full Cache", SubtitleCacheManager.getFullFile(null, key55Long) != null)

    // 10C-2: 55 cues where cues 16..30 (15/40 in Chunk 1) are short "Come on!" and persistently dropped by LLM
    SubtitleCacheManager.clearAll(null)
    val sbShort55 = StringBuilder()
    var tShort = 1000L
    for (i in 1..55) {
        val line = if (i in 16..30) "Come on!" else "Long dialogue sentence number $i for testing."
        sbShort55.append("$i\n${formatMs(tShort)} --> ${formatMs(tShort + 1000L)}\n$line\n\n")
        tShort += 1300L
    }
    val raw55Short = sbShort55.toString()
    val file55Short = File(testRoot, "tier5_drop_short_55.srt").apply { writeText(raw55Short) }
    val observedSizesShort = CopyOnWriteArrayList<Int>()
    val dropShortClient = object : AITranslationClient {
        override suspend fun translateChunkWithSceneDigest(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?,
            lookbehind: List<ContextCue>,
            lookahead: List<ContextCue>,
            characterRegistry: TwoTierCharacterRegistry?,
            mediaContext: MediaContext?,
            rollingSceneDigest: String?
        ): TranslationResult {
            observedSizesShort.add(cues.size)
            val map = cues.filter { it.rawText != "Come on!" }.associate { it.index to "Dịch chuẩn ${it.index}" }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }
    val dropShortSession = AITranslationSession(
        mediaPayload = "tier5_drop_short_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = dropShortClient
    )
    dropShortSession.startSession(SubtitleFile("English", file55Short.absolutePath)).join()
    assertTrue("Tier 5 (15/40 dropped 'Come on!') bisected 40 -> 20 -> 10 ($observedSizesShort)", observedSizesShort.contains(40) && observedSizesShort.contains(20) && observedSizesShort.contains(10))
    assertTrue("Tier 5 unrecovered dropped 'Come on!' emitted _interim SRT", dropShortSession.completedFile?.name?.contains("_interim") == true)
    val key55Short = SubtitleCacheManager.buildCacheKey(raw55Short, "vi", TranslatorConfig.provider, TranslatorConfig.getEffectiveModel(), TranslatorConfig.customGlossary)
    assertEquals("Tier 5 Tier 1 Full Cache MUST NOT be poisoned by dropped 'Come on!' cues", null, SubtitleCacheManager.getFullFile(null, key55Short))
    val tier2Short = SubtitleCacheManager.loadChunks(null, key55Short)
    assertFalse("Tier 5 Tier 2 chunk cache excludes dropped 'Come on!' cue 16", tier2Short.containsKey(16))

    // 10D: Gap 4 - Tier B2 multi-provider scan (Provider1_EnOnly + Provider2_HasVi -> Bypassed)
    val provider1EnOnly = object : SubtitleAPI() {
        override val name = "Provider1_EnOnly"
        override suspend fun search(auth: AuthData?, query: AbstractSubtitleEntities.SubtitleSearch): List<AbstractSubtitleEntities.SubtitleEntity> {
            return if (query.lang == "en") {
                listOf(
                    AbstractSubtitleEntities.SubtitleEntity(
                        idPrefix = "p1", name = "Movie EN", lang = "en", data = "p1_en",
                        type = TvType.Movie, source = "Provider1_EnOnly", epNumber = null,
                        seasonNumber = null, year = 2011, isHearingImpaired = false, headers = emptyMap()
                    )
                )
            } else emptyList()
        }
        override suspend fun SubtitleResource.getResources(auth: AuthData?, subtitle: AbstractSubtitleEntities.SubtitleEntity) {
            addUrl("https://p1.example.com/en.srt", "Movie EN")
        }
    }
    val provider2HasVi = object : SubtitleAPI() {
        override val name = "Provider2_HasVi"
        override suspend fun search(auth: AuthData?, query: AbstractSubtitleEntities.SubtitleSearch): List<AbstractSubtitleEntities.SubtitleEntity> {
            return if (query.lang == "vi") {
                listOf(
                    AbstractSubtitleEntities.SubtitleEntity(
                        idPrefix = "p2", name = "Movie VI", lang = "vi", data = "p2_vi",
                        type = TvType.Movie, source = "Provider2_HasVi", epNumber = null,
                        seasonNumber = null, year = 2011, isHearingImpaired = false, headers = emptyMap()
                    )
                )
            } else emptyList()
        }
        override suspend fun SubtitleResource.getResources(auth: AuthData?, subtitle: AbstractSubtitleEntities.SubtitleEntity) {
            addUrl("https://p2.example.com/vi.srt", "Movie VI")
        }
    }
    val multiProviderRes = StreamSubtitleExtractor.querySubtitleApiProviders(
        title = "Real Steel",
        year = 2011,
        imdbId = "tt0433035",
        targetLang = "vi",
        apisOverride = listOf(provider1EnOnly to null, provider2HasVi to null)
    )
    assertTrue("Tier 5 Tier B2 scans all providers for targetLang and returns Bypassed", multiProviderRes is StreamSubtitleExtractor.FallbackDiscoveryResult.Bypassed)
    assertEquals("Tier 5 Tier B2 matchedLang is 'vi'", "vi", (multiProviderRes as StreamSubtitleExtractor.FallbackDiscoveryResult.Bypassed).matchedLang)

    // 10E: Gap B - Slow loadLinks (>200ms: EN at 10ms, VI at 260ms) emits 0 ghost [AI] tracks and marks bypassed
    val slowEmitted = CopyOnWriteArrayList<SubtitleFile>()
    val slowLoadLinksBaseApi = object : MainAPI() {
        override var name = "SlowLoadLinksMock"
        override var mainUrl = "https://slow.loadlinks.test"
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            delay(10L)
            subtitleCallback(SubtitleFile("English (Host1)", "https://slow.loadlinks.test/en.srt"))
            delay(250L)
            subtitleCallback(SubtitleFile("Tiếng Việt (Host2)", "https://slow.loadlinks.test/vi.srt"))
            delay(20L)
            return true
        }
    }
    val slowEnriched = AITranslatorEnrichedMainAPI(slowLoadLinksBaseApi)
    val slowOk = slowEnriched.loadLinks("tier5_slow_loadlinks", false, { slowEmitted.add(it) }, {})
    delay(120L)
    assertTrue("Tier 5 slow loadLinks returned true", slowOk)
    assertEquals("Tier 5 slow loadLinks emitted exact 2 tracks without ghost [AI] track", 2, slowEmitted.size)
    assertFalse("Tier 5 slow loadLinks emitted zero [AI] tracks", slowEmitted.any { it.lang.contains("[AI", ignoreCase = true) })
    assertTrue("Tier 5 slow loadLinks marked session bypassed", slowEnriched.activeSession?.isTargetLanguageBypassed == true)
    slowEnriched.activeSession?.cancelSession()

    // 10F: Gap C - SubtitleReconciler 1-based relative re-indexing when cue #1 is dropped (2..40 on 16..55)
    val cues16To55 = (16..55).map { idx ->
        SubtitleCue(index = idx, startMs = idx * 1000L, endMs = idx * 1000L + 500L, rawText = "Raw $idx")
    }
    val relDrop1Map = (2..40).associateWith { k -> "Trans_rel_$k" }
    val relDrop1Result = SubtitleReconciler.autoHealingReconcileDetailed(cues16To55, relDrop1Map)
    assertEquals("Tier 5 relative 2..40 on 16..55 drops only cue 16", setOf(16), relDrop1Result.droppedCueIds)
    assertEquals("Tier 5 cue 16 falls back to Raw 16", "Raw 16", relDrop1Result.translations[16])
    for (k in 2..40) {
        assertEquals("Tier 5 relative key $k aligned to global cue ${15 + k}", "Trans_rel_$k", relDrop1Result.translations[15 + k])
    }

    // Verify global partial 16..40 on 16..55 is NOT misdetected as relative
    val globalPartialMap = (16..40).associateWith { k -> "Trans_global_$k" }
    val globalPartialResult = SubtitleReconciler.autoHealingReconcileDetailed(cues16To55, globalPartialMap)
    assertEquals("Tier 5 global partial 16..40 drops 41..55", (41..55).toSet(), globalPartialResult.droppedCueIds)
    assertEquals("Tier 5 global cue 16 aligned directly", "Trans_global_16", globalPartialResult.translations[16])
    assertEquals("Tier 5 global cue 40 aligned directly", "Trans_global_40", globalPartialResult.translations[40])
    assertEquals("Tier 5 global cue 41 falls back to Raw 41", "Raw 41", globalPartialResult.translations[41])

    // 10G: Gap D - Proper-noun, Tag-Stripping & Non-Alphabetic Markup Guard in isUntranslatedCue & hasDialogueLetters
    assertFalse("10G: Styled proper noun '<i>Charlie Kenton!</i>' is NOT untranslated", AITranslationSession.isUntranslatedCue("<i>Charlie Kenton!</i>", "<i>Charlie Kenton!</i>"))
    assertFalse("10G: Plain proper noun 'Charlie Kenton!' is NOT untranslated", AITranslationSession.isUntranslatedCue("Charlie Kenton!", "Charlie Kenton!"))
    assertFalse("10G: ASS styled proper noun '{\\an8}Tak Mashido' is NOT untranslated", AITranslationSession.isUntranslatedCue("{\\an8}Tak Mashido", "{\\an8}Tak Mashido"))
    assertFalse("10G: Font tag punctuation '<font color=\"#ffffff\">...</font>' is NOT untranslated", AITranslationSession.isUntranslatedCue("<font color=\"#ffffff\">...</font>", "<font color=\"#ffffff\">...</font>"))
    assertFalse("10G: ASS punctuation '{\\an8}...' is NOT untranslated", AITranslationSession.isUntranslatedCue("{\\an8}...", "{\\an8}..."))
    assertFalse("10G: ASS punctuation '{\\an8}...' hasDialogueLetters == false", AITranslationSession.hasDialogueLetters("{\\an8}..."))
    assertFalse("10G: Font punctuation '<font color=\"#ffffff\">...</font>' hasDialogueLetters == false", AITranslationSession.hasDialogueLetters("<font color=\"#ffffff\">...</font>"))
    assertTrue("10G: ASS proper noun '{\\an8}Tak Mashido' hasDialogueLetters == true", AITranslationSession.hasDialogueLetters("{\\an8}Tak Mashido"))
    assertTrue("10G: Title-case English sentence 'Destroy Evil Robots!' IS untranslated", AITranslationSession.isUntranslatedCue("Destroy Evil Robots!", "Destroy Evil Robots!"))
    assertTrue("10G: Title-case English sentence 'Check System Status!' IS untranslated", AITranslationSession.isUntranslatedCue("Check System Status!", "Check System Status!"))

    // 10G-1: 55-cue SRT where Chunk 1 (40 cues) contains styled proper nouns & tag-only punctuation kept verbatim
    TranslatorConfig.resetToDefaults()
    TranslatorConfig.showProgressHUD = false
    TranslatorConfig.highSpeedMode = false
    SubtitleCacheManager.clearAll(null)

    val sb55GapD = StringBuilder()
    var tGapD = 1000L
    for (i in 1..55) {
        val line = when (i) {
            20 -> "<i>Charlie Kenton!</i>"
            25 -> "{\\an8}Tak Mashido"
            30 -> "<font color=\"#ffffff\">...</font>"
            else -> "Dialogue line number $i for proper noun verification."
        }
        sb55GapD.append("$i\n${formatMs(tGapD)} --> ${formatMs(tGapD + 1000L)}\n$line\n\n")
        tGapD += 1300L
    }
    val raw55GapD = sb55GapD.toString()
    val file55GapD = File(testRoot, "tier5_gapd_55.srt").apply { writeText(raw55GapD) }
    val gapDCallSizes = CopyOnWriteArrayList<Int>()
    val gapDClient = object : AITranslationClient {
        override suspend fun translateChunkWithSceneDigest(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?,
            lookbehind: List<ContextCue>,
            lookahead: List<ContextCue>,
            characterRegistry: TwoTierCharacterRegistry?,
            mediaContext: MediaContext?,
            rollingSceneDigest: String?
        ): TranslationResult {
            gapDCallSizes.add(cues.size)
            val map = cues.associate { cue ->
                val text = if (cue.index in setOf(20, 25, 30)) cue.maskedText else "Dịch chuẩn ${cue.index}"
                cue.index to text
            }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }
    val gapDSession = AITranslationSession(
        mediaPayload = "tier5_gapd_55_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = gapDClient
    )
    gapDSession.startSession(SubtitleFile("English", file55GapD.absolutePath)).join()
    assertTrue("10G-1: Session completed", gapDSession.isCompleted)
    assertEquals("10G-1: CALL_SIZES == [15, 40] (2 calls, 0 bisects)", listOf(15, 40), gapDCallSizes.toList())
    assertFalse("10G-1: Emitted full SRT (not _interim)", gapDSession.completedFile?.name?.contains("_interim") == true)
    val key55GapD = SubtitleCacheManager.buildCacheKey(raw55GapD, "vi", TranslatorConfig.provider, TranslatorConfig.getEffectiveModel(), TranslatorConfig.customGlossary)
    assertTrue("10G-1: Tier 1 Full Cache IS written", SubtitleCacheManager.getFullFile(null, key55GapD) != null)
    assertTrue("10G-1: Tier 2 chunks cleared after Tier 1 save", SubtitleCacheManager.loadChunks(null, key55GapD).isEmpty() && !SubtitleCacheManager.hasChunks(null, key55GapD))

    // 10G-2: 36-cue SRT containing Title-Case English sentences kept verbatim MUST trigger Adaptive Bisect
    SubtitleCacheManager.clearAll(null)
    val sb36GapD = StringBuilder()
    var t36 = 1000L
    for (i in 1..36) {
        val line = when (i) {
            20 -> "Destroy Evil Robots!"
            25 -> "Check System Status!"
            else -> "Dialogue line number $i for bisect verification."
        }
        sb36GapD.append("$i\n${formatMs(t36)} --> ${formatMs(t36 + 1000L)}\n$line\n\n")
        t36 += 1300L
    }
    val raw36GapD = sb36GapD.toString()
    val file36GapD = File(testRoot, "tier5_gapd_36.srt").apply { writeText(raw36GapD) }
    val seenSizes36 = CopyOnWriteArrayList<Int>()
    val bisectTitleCaseClient = object : AITranslationClient {
        override suspend fun translateChunkWithSceneDigest(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
            customGlossary: String?,
            lookbehind: List<ContextCue>,
            lookahead: List<ContextCue>,
            characterRegistry: TwoTierCharacterRegistry?,
            mediaContext: MediaContext?,
            rollingSceneDigest: String?
        ): TranslationResult {
            seenSizes36.add(cues.size)
            val map = cues.associate { cue ->
                val text = if (cue.index in setOf(20, 25)) cue.rawText else "Dịch chuẩn ${cue.index}"
                cue.index to text
            }
            return TranslationResult(translations = map, success = true)
        }
        override suspend fun ping(): TranslationResult = TranslationResult(success = true)
    }
    val gapDBisectSession = AITranslationSession(
        mediaPayload = "tier5_gapd_36_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = bisectTitleCaseClient
    )
    gapDBisectSession.startSession(SubtitleFile("English", file36GapD.absolutePath)).join()
    assertTrue("10G-2: Session completed", gapDBisectSession.isCompleted)
    assertTrue("10G-2: Chunk 1 (size 21) attempted ($seenSizes36)", seenSizes36.contains(21))
    assertTrue("10G-2: Adaptive Bisect triggered on Title-Case English sentences (seenSizes.any { it < 21 } & bisected < 21: $seenSizes36)", seenSizes36.any { it < 21 } && seenSizes36.any { it == 10 || it == 11 })
    val key36GapD = SubtitleCacheManager.buildCacheKey(raw36GapD, "vi", TranslatorConfig.provider, TranslatorConfig.getEffectiveModel(), TranslatorConfig.customGlossary)
    assertEquals("10G-2: Tier 1 Full Cache NOT poisoned by untranslated Title-Case sentences", null, SubtitleCacheManager.getFullFile(null, key36GapD))

    AITranslatorLocalServer.stop()
    println("\n================================================================================")
    println(">>> ALL MILESTONE 2 SESSION & FALLBACK TESTS PASSED PERFECTLY!")
    println("================================================================================")
}
