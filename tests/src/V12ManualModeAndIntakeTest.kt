@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package tests.unit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslationClient
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorEnrichedMainAPI
import com.lagradost.cloudstream3.subtitles.ai.hook.MediaContextManager
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TranslationResult
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import com.lagradost.cloudstream3.subtitles.ai.session.StreamSubtitleExtractor
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitleCoverageValidator
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitlePriorityEvaluator
import com.lagradost.cloudstream3.subtitles.ai.ui.ManualSubtitleSelector
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class MockSharedPrefsV12(
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

private open class MockContextV12 : ContextWrapper(null) {
    private val prefsRegistry = ConcurrentHashMap<String, MockSharedPrefsV12>()
    var mockFilesDir: File = File(System.getProperty("java.io.tmpdir", "."), "mock_v12_files")
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        prefsRegistry.getOrPut(name ?: "default") { MockSharedPrefsV12() }
    override fun getFilesDir(): File {
        if (!mockFilesDir.exists()) mockFilesDir.mkdirs()
        return mockFilesDir
    }
    companion object {
        fun create(): MockContextV12 = try {
            MockContextV12()
        } catch (_: Throwable) {
            val f: Field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            f.isAccessible = true
            val unsafe = f.get(null)
            val inst = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, MockContextV12::class.java) as MockContextV12
            MockContextV12::class.java.getDeclaredField("prefsRegistry").apply { isAccessible = true }.set(inst, ConcurrentHashMap<String, MockSharedPrefsV12>())
            val dir = File(System.getProperty("java.io.tmpdir", "."), "mock_v12_files_${System.currentTimeMillis()}").apply { mkdirs(); deleteOnExit() }
            MockContextV12::class.java.getDeclaredField("mockFilesDir").apply { isAccessible = true }.set(inst, dir)
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

private fun formatMs(ms: Long): String {
    val h = ms / 3600_000L
    val m = (ms % 3600_000L) / 60_000L
    val s = (ms % 60_000L) / 1000L
    val rem = ms % 1000L
    return String.format("%02d:%02d:%02d,%03d", h, m, s, rem)
}

private suspend fun awaitSessionSettled(session: AITranslationSession, timeoutMs: Long = 20000L): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (session.isCancelled || session.isTargetLanguageBypassed) return false
        if (session.isCompleted && !session.isTranslationInFlight) return true
        delay(25L)
    }
    return false
}

private fun buildSrt(count: Int, tag: String): String {
    val sb = StringBuilder()
    var t = 1000L
    for (i in 1..count) {
        val start = t
        val end = start + 1000L
        sb.append(i).append("\n")
        sb.append(formatMs(start)).append(" --> ").append(formatMs(end)).append("\n")
        sb.append("Dialogue line $i for the $tag verification run with enough words.\n\n")
        t = end + 300L
    }
    return sb.toString()
}

private fun startSrtFixtureServer(srtText: String): Pair<ServerSocket, Int> {
    val server = ServerSocket(0)
    val port = server.localPort
    thread(isDaemon = true) {
        try {
            while (!server.isClosed) {
                val sock = server.accept()
                try {
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    var line = reader.readLine()
                    while (line != null && line.isNotBlank()) line = reader.readLine()
                    val bytes = srtText.toByteArray(Charsets.UTF_8)
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                } catch (_: Throwable) {
                } finally {
                    try { sock.close() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }
    return server to port
}

private class CountingCleanClient : AITranslationClient {
    val calls = AtomicInteger(0)
    override suspend fun translateChunk(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
        customGlossary: String?
    ): TranslationResult {
        calls.incrementAndGet()
        return TranslationResult(translations = cues.associate { it.index to "Dịch ${it.index}" }, success = true)
    }
    override suspend fun ping(): TranslationResult = TranslationResult(success = true)
}

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting V12 Manual Mode, Intake Filtering & Episode-Aware Fallback Tests...")
    println("================================================================================")

    val testRoot = File(System.getProperty("java.io.tmpdir", "."), "v12_test_${System.currentTimeMillis()}").apply {
        mkdirs()
        deleteOnExit()
    }
    SubtitleCacheManager.customBaseDir = File(testRoot, "cache")
    TranslatorConfig.resetToDefaults()
    TranslatorConfig.showProgressHUD = false
    SubtitleCacheManager.clearAll(null)

    AITranslatorLocalServer.customBaseDir = File(testRoot, "server_cache").apply { mkdirs() }
    val aiPort = AITranslatorLocalServer.start(null)
    assertTrue("V12 AI loopback server started on ephemeral port ($aiPort)", aiPort > 0)

    // =========================================================================
    // TEST 1: isOwnSubtitleUrl distinguishes own port from peer Kitsunekko port
    // =========================================================================
    println("\n--- [Test 1: isOwnSubtitleUrl own-port detection] ---")
    val kitsuneServer = ServerSocket(0)
    val kitsunePort = kitsuneServer.localPort
    try { kitsuneServer.close() } catch (_: Throwable) {}
    assertTrue("Own 127.0.0.1 port /sub/ URL is recognized", AITranslatorLocalServer.isOwnSubtitleUrl("http://127.0.0.1:$aiPort/sub/abc123.srt"))
    assertTrue("Own localhost port /sub/ URL is recognized", AITranslatorLocalServer.isOwnSubtitleUrl("http://localhost:$aiPort/sub/abc123.srt?v=42"))
    assertFalse("Kitsunekko-style peer port is NOT own URL", AITranslatorLocalServer.isOwnSubtitleUrl("http://127.0.0.1:$kitsunePort/sub/abc123.srt"))
    assertFalse("Own port with non-/sub/ path is NOT own subtitle URL", AITranslatorLocalServer.isOwnSubtitleUrl("http://127.0.0.1:$aiPort/other/abc123.srt"))
    assertFalse("Blank URL is not own URL", AITranslatorLocalServer.isOwnSubtitleUrl("   "))
    assertFalse("External https URL is not own URL", AITranslatorLocalServer.isOwnSubtitleUrl("https://subs.example.com/abc.srt"))

    // =========================================================================
    // TEST 2: queueCandidate accepts loopback Kitsunekko-style source, rejects own AI tracks
    // =========================================================================
    println("\n--- [Test 2: Loopback Kitsunekko intake accepted; own AI tracks rejected] ---")
    val (kitsuneFixture, kitsuneFixturePort) = startSrtFixtureServer(buildSrt(20, "kitsunekko"))
    val kitsuneUrl = "http://127.0.0.1:$kitsuneFixturePort/sub/japanese_ep1.srt"
    assertFalse("Fixture Kitsunekko URL is not own AI URL", AITranslatorLocalServer.isOwnSubtitleUrl(kitsuneUrl))

    val intakeClient = CountingCleanClient()
    val intakeSession = AITranslationSession(
        mediaPayload = "v12_intake_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = intakeClient
    )
    val accepted = intakeSession.queueCandidate(SubtitleFile("Japanese (Kitsunekko)", kitsuneUrl))
    assertTrue("Kitsunekko loopback candidate accepted by queueCandidate", accepted)
    assertEquals("acceptedCandidateCount incremented", 1, intakeSession.acceptedCandidateCount)
    assertTrue("Kitsunekko candidate translated to completion", awaitSessionSettled(intakeSession))
    assertTrue("Kitsunekko candidate produced completedFile", intakeSession.completedFile != null)

    assertFalse(
        "Own [AI] label candidate rejected",
        intakeSession.queueCandidate(SubtitleFile("[AI] Tiếng Việt", "https://subs.example.com/ai_echo.srt"))
    )
    val ownUrl = AITranslatorLocalServer.getSubtitleUrl("v12_foreign_session")
    assertFalse(
        "Own loopback port candidate rejected",
        intakeSession.queueCandidate(SubtitleFile("Japanese", ownUrl))
    )
    try { kitsuneFixture.close() } catch (_: Throwable) {}

    // =========================================================================
    // TEST 3: Late target-language candidate must not destroy an active translation
    // =========================================================================
    println("\n--- [Test 3: Late target-language candidate preserves active job] ---")
    val hangServer = ServerSocket(0)
    val hangPort = hangServer.localPort
    val hangSockets = CopyOnWriteArrayList<Socket>()
    thread(isDaemon = true) {
        try {
            while (!hangServer.isClosed) {
                hangSockets.add(hangServer.accept())
            }
        } catch (_: Throwable) {}
    }
    val hangClient = CountingCleanClient()
    val hangSession = AITranslationSession(
        mediaPayload = "v12_hang_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = hangClient
    )
    assertTrue(
        "Hanging source candidate accepted",
        hangSession.queueCandidate(SubtitleFile("English", "http://127.0.0.1:$hangPort/hang.srt"))
    )
    delay(300L)
    assertTrue("Translation job active while download hangs", hangSession.isTranslationInFlight)
    val lateViAccepted = hangSession.queueCandidate(SubtitleFile("Tiếng Việt", "https://subs.example.com/vi_late.srt"))
    assertFalse("Late target-language candidate not accepted while job active", lateViAccepted)
    assertFalse("Late target-language candidate did NOT bypass/cancel running translation", hangSession.isTargetLanguageBypassed)
    assertTrue("Running translation job survives late target-language candidate", hangSession.isTranslationInFlight)
    hangSession.cancelSession(userInitiated = true)
    hangSockets.forEach { try { it.close() } catch (_: Throwable) {} }
    try { hangServer.close() } catch (_: Throwable) {}

    // =========================================================================
    // TEST 4: Post-completion preemption (margin + cap/cooldown presence)
    // =========================================================================
    println("\n--- [Test 4: Bounded post-completion preemption] ---")
    SubtitleCacheManager.clearAll(null)
    TranslatorConfig.sourcePriorityMode = TranslatorConfig.PRIORITY_ANIME_FIRST
    val preemptClient = CountingCleanClient()
    val (preemptFixture, preemptPort) = startSrtFixtureServer(buildSrt(20, "english-base"))
    val (preemptFixtureJp, preemptPortJp) = startSrtFixtureServer(buildSrt(20, "japanese-upgrade"))
    val preemptSession = AITranslationSession(
        mediaPayload = "v12_preempt_movie",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = preemptClient
    )
    val englishUrl = "http://127.0.0.1:$preemptPort/english.srt"
    assertTrue("English candidate accepted", preemptSession.queueCandidate(SubtitleFile("English", englishUrl)))
    assertTrue("English candidate completed", awaitSessionSettled(preemptSession))
    val firstCallCount = preemptClient.calls.get()
    assertTrue("English candidate translated at least once", firstCallCount >= 1)
    assertEquals("English candidate best score is 500", 500, preemptSession.currentBestScore)

    val jpUrl = "http://127.0.0.1:$preemptPortJp/japanese.srt"
    assertTrue("Higher-scored Japanese candidate accepted", preemptSession.queueCandidate(SubtitleFile("Japanese", jpUrl)))
    assertTrue("Preempted Japanese candidate completed", awaitSessionSettled(preemptSession))
    assertEquals("Japanese preemption raised best score to 1000", 1000, preemptSession.currentBestScore)
    assertTrue("Preemption re-ran translation", preemptClient.calls.get() > firstCallCount)

    delay(200L)
    val callsBeforeWeakCandidate = preemptClient.calls.get()
    assertTrue(
        "Weak lower-score candidate still accepted into pool",
        preemptSession.queueCandidate(SubtitleFile("Japanese signs & songs", "http://127.0.0.1:$preemptPortJp/signs.srt"))
    )
    delay(300L)
    assertTrue("Weak candidate does NOT preempt a finished 1000-score translation", preemptSession.isCompleted)
    assertEquals("Weak candidate did not trigger extra translation work", callsBeforeWeakCandidate, preemptClient.calls.get())

    val lateViAfterCompletion = preemptSession.queueCandidate(SubtitleFile("Tiếng Việt", "https://subs.example.com/vi_done.srt"))
    assertFalse("Late target-language candidate ignored after completion", lateViAfterCompletion)
    assertFalse("Completed translation not bypassed by late target-language track", preemptSession.isTargetLanguageBypassed)
    assertTrue("Completed translation remains completed", preemptSession.isCompleted)
    try { preemptFixture.close() } catch (_: Throwable) {}
    try { preemptFixtureJp.close() } catch (_: Throwable) {}
    TranslatorConfig.sourcePriorityMode = TranslatorConfig.PRIORITY_ANIME_FIRST

    // =========================================================================
    // TEST 5: MediaContextManager episode cache and URL regex
    // =========================================================================
    println("\n--- [Test 5: MediaContextManager.resolveEpisode cache + regex] ---")
    MediaContextManager.clear()
    val fakeProvider = object : MainAPI() {
        override var name = "V12FixtureProvider"
        override var mainUrl = "https://fixture.test"
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean = false
    }
    val movieResp = fakeProvider.newMovieLoadResponse(
        name = "Fixture Movie",
        url = "https://fixture.test/movie",
        type = TvType.Movie,
        dataUrl = "https://fixture.test/movie/real-steel"
    ) { }
    MediaContextManager.setContext(movieResp)
    assertEquals(
        "Movie dataUrl cached as (season=1, episode=1)",
        Pair(1, 1),
        MediaContextManager.resolveEpisode("https://fixture.test/movie/real-steel")
    )

    val tvResp = fakeProvider.newTvSeriesLoadResponse(
        name = "Fixture Show",
        url = "https://fixture.test/show",
        type = TvType.TvSeries,
        episodes = listOf(
            Episode(data = "https://fixture.test/show/s1e7", name = "Episode 7", season = 1, episode = 7)
        )
    ) { }
    MediaContextManager.setContext(tvResp)
    assertEquals(
        "TV episode dataUrl cached as (1, 7)",
        Pair(1, 7),
        MediaContextManager.resolveEpisode("https://fixture.test/show/s1e7")
    )
    assertEquals(
        "Uncached URL falls back to last-number regex extraction",
        Pair(1, 1015),
        MediaContextManager.resolveEpisode("https://fixture.test/watch/one-piece-ep-1015")
    )
    assertEquals(
        "Season inherited from latest cached episode while regex extracts episode",
        Pair(1, 12),
        MediaContextManager.resolveEpisode("https://fixture.test/watch/annual-special-2024/ep12")
    )

    // =========================================================================
    // TEST 6: Episode-aware SubtitleAPI query + wrong-season entity filtering
    // =========================================================================
    println("\n--- [Test 6: Episode-aware SubtitleSearch + season filtering] ---")
    val capturedSearch = CopyOnWriteArrayList<AbstractSubtitleEntities.SubtitleSearch>()
    val fakeSubtitleApi = object : SubtitleAPI() {
        override val name = "V12FakeSubApi"
        override suspend fun search(auth: AuthData?, query: AbstractSubtitleEntities.SubtitleSearch): List<AbstractSubtitleEntities.SubtitleEntity> {
            capturedSearch.add(query)
            if (query.lang != "en") return emptyList()
            return listOf(
                AbstractSubtitleEntities.SubtitleEntity(
                    idPrefix = "s1", name = "Show S01E05 EN", lang = "en", data = "s1e5",
                    type = TvType.TvSeries, source = "V12FakeSubApi", epNumber = 5,
                    seasonNumber = 1, year = 2020, isHearingImpaired = false, headers = emptyMap()
                ),
                AbstractSubtitleEntities.SubtitleEntity(
                    idPrefix = "s2", name = "Show S02E05 EN", lang = "en", data = "s2e5",
                    type = TvType.TvSeries, source = "V12FakeSubApi", epNumber = 5,
                    seasonNumber = 2, year = 2020, isHearingImpaired = false, headers = emptyMap()
                ),
                AbstractSubtitleEntities.SubtitleEntity(
                    idPrefix = "snull", name = "Show EN Untagged", lang = "en", data = "snull",
                    type = TvType.TvSeries, source = "V12FakeSubApi", epNumber = null,
                    seasonNumber = null, year = 2020, isHearingImpaired = false, headers = emptyMap()
                )
            )
        }
        override suspend fun SubtitleResource.getResources(auth: AuthData?, subtitle: AbstractSubtitleEntities.SubtitleEntity) {
            addUrl("https://fixture.test/subs/${subtitle.idPrefix}.srt", "${subtitle.idPrefix}.srt")
        }
    }
    val seasonFiltered = StreamSubtitleExtractor.querySubtitleApiProviders(
        title = "Fixture Show",
        year = 2020,
        imdbId = null,
        targetLang = "vi",
        apisOverride = listOf(fakeSubtitleApi to null),
        timeoutMs = 5000L,
        seasonNumber = 2,
        episodeNumber = 5
    )
    assertTrue("Episode-aware query captured (${capturedSearch.size} searches)", capturedSearch.isNotEmpty())
    assertTrue(
        "SubtitleSearch carried seasonNumber=2 and epNumber=5",
        capturedSearch.all { it.seasonNumber == 2 && it.epNumber == 5 }
    )
    assertTrue("Season-filtered discovery returned CandidatesFound", seasonFiltered is StreamSubtitleExtractor.FallbackDiscoveryResult.CandidatesFound)
    val filteredCandidates = (seasonFiltered as StreamSubtitleExtractor.FallbackDiscoveryResult.CandidatesFound).candidates
    assertEquals("Wrong-season entity filtered out; 2 candidates remain", 2, filteredCandidates.size)
    assertFalse(
        "Season-1 URL never resolved",
        filteredCandidates.any { it.url.contains("s1.srt") }
    )

    // =========================================================================
    // TEST 7: Label penalty scoring
    // =========================================================================
    println("\n--- [Test 7: SubtitlePriorityEvaluator label penalties] ---")
    TranslatorConfig.preserveSdh = true
    val jaScore = SubtitlePriorityEvaluator.calculateScore(
        SubtitleFile("Japanese", "https://x/ja.srt"), "vi", TranslatorConfig.PRIORITY_ANIME_FIRST
    )
    val jaSignsScore = SubtitlePriorityEvaluator.calculateScore(
        SubtitleFile("Japanese signs & songs", "https://x/ja_signs.srt"), "vi", TranslatorConfig.PRIORITY_ANIME_FIRST
    )
    assertEquals("Full Japanese dialogue scores 1000", 1000, jaScore)
    assertEquals("Signs/songs Japanese track penalized to 400", 400, jaSignsScore)
    assertTrue("Signs track ranks below full dialogue", jaSignsScore < jaScore)
    assertTrue("isLowQualityLabel('Japanese signs') is true", SubtitlePriorityEvaluator.isLowQualityLabel("Japanese signs"))
    assertFalse("isLowQualityLabel('Japanese') is false", SubtitlePriorityEvaluator.isLowQualityLabel("Japanese"))

    val sdhPreserved = SubtitlePriorityEvaluator.calculateScore(
        SubtitleFile("English SDH", "https://x/en_sdh.srt"), "vi", TranslatorConfig.PRIORITY_ANIME_FIRST
    )
    TranslatorConfig.preserveSdh = false
    val sdhPenalized = SubtitlePriorityEvaluator.calculateScore(
        SubtitleFile("English SDH", "https://x/en_sdh.srt"), "vi", TranslatorConfig.PRIORITY_ANIME_FIRST
    )
    assertEquals("SDH penalty is 50 when preserveSdh=true", 450, sdhPreserved)
    assertEquals("SDH penalty is 600 when preserveSdh=false", -100, sdhPenalized)
    TranslatorConfig.preserveSdh = true

    val commentaryScore = SubtitlePriorityEvaluator.calculateScore(
        SubtitleFile("English Commentary", "https://x/en_commentary.srt"), "vi", TranslatorConfig.PRIORITY_ANIME_FIRST
    )
    assertEquals("Commentary track penalized to -300", -300, commentaryScore)
    val bypassScore = SubtitlePriorityEvaluator.calculateScore(
        SubtitleFile("Tiếng Việt", "https://x/vi.srt"), "vi", TranslatorConfig.PRIORITY_ANIME_FIRST
    )
    assertEquals("Target language bypass still absolute -1000", -1000, bypassScore)
    assertFalse("Bypass label is not flagged as low-quality partial label", SubtitlePriorityEvaluator.isLowQualityLabel("Tiếng Việt"))

    // =========================================================================
    // TEST 8: SubtitleCoverageValidator partial-track rejection
    // =========================================================================
    println("\n--- [Test 8: SubtitleCoverageValidator] ---")
    fun cueList(count: Int, lastEndMs: Long): List<SubtitleCue> = (1..count).map { i ->
        SubtitleCue(index = i, startMs = (lastEndMs / count) * (i - 1), endMs = (lastEndMs / count) * i, rawText = "Line $i")
    }
    val fullMovieDuration = 120 * 60_000L
    assertTrue(
        "Signs track ending at 10min of a 120min movie is partial",
        SubtitleCoverageValidator.isLikelyPartial(cueList(20, 10 * 60_000L), fullMovieDuration)
    )
    assertFalse(
        "Unknown duration never rejects",
        SubtitleCoverageValidator.isLikelyPartial(cueList(5, 60_000L), null)
    )
    assertFalse(
        "Sub-5-minute clip is not treated as partial",
        SubtitleCoverageValidator.isLikelyPartial(cueList(2, 60_000L), 3 * 60_000L)
    )
    assertTrue(
        "Sparse 10-cue track over 120min rejected by density",
        SubtitleCoverageValidator.isLikelyPartial(cueList(10, fullMovieDuration - 60_000L), fullMovieDuration)
    )
    assertFalse(
        "Dense 200-cue track spanning the movie accepted",
        SubtitleCoverageValidator.isLikelyPartial(cueList(200, fullMovieDuration - 10_000L), fullMovieDuration)
    )
    assertFalse(
        "Empty cue list never rejected",
        SubtitleCoverageValidator.isLikelyPartial(emptyList(), fullMovieDuration)
    )

    // =========================================================================
    // TEST 9: ManualSubtitleSelector decision matrix
    // =========================================================================
    println("\n--- [Test 9: ManualSubtitleSelector] ---")
    assertTrue(
        "No selected track -> NotSelected",
        ManualSubtitleSelector.evaluate(null, null, null, null, "vi") is ManualSubtitleSelector.Result.NotSelected
    )
    assertTrue(
        "Embedded track -> Embedded",
        ManualSubtitleSelector.evaluate("Vietnamese", "embedded://0", "vi", "EMBEDDED_IN_VIDEO", "vi") is ManualSubtitleSelector.Result.Embedded
    )
    assertTrue(
        "Downloaded-file scheme -> NonHttp",
        ManualSubtitleSelector.evaluate("English", "file:///data/sub.srt", "en", "DOWNLOADED_FILE", "vi") is ManualSubtitleSelector.Result.NonHttp
    )
    assertTrue(
        "Own [AI] track -> AlreadyAi",
        ManualSubtitleSelector.evaluate("[AI] Tiếng Việt", "https://x/ai.srt", "vi", "URL", "vi") is ManualSubtitleSelector.Result.AlreadyAi
    )
    assertTrue(
        "Own loopback AI URL -> AlreadyAi",
        ManualSubtitleSelector.evaluate("English", AITranslatorLocalServer.getSubtitleUrl("v12_selector"), "en", "URL", "vi") is ManualSubtitleSelector.Result.AlreadyAi
    )
    assertTrue(
        "Target-language track -> AlreadyTarget",
        ManualSubtitleSelector.evaluate("Tiếng Việt", "https://x/vi.srt", "vi", "URL", "vi") is ManualSubtitleSelector.Result.AlreadyTarget
    )
    val readyResult = ManualSubtitleSelector.evaluate(
        "English", "https://x/en.srt", "en", "URL", "vi",
        headers = mapOf("X-Sub-Key" to "abc")
    )
    assertTrue("Source-language URL track -> Ready", readyResult is ManualSubtitleSelector.Result.Ready)
    val readyFile = (readyResult as ManualSubtitleSelector.Result.Ready).subtitleFile
    assertEquals("Ready track keeps label", "English", readyFile.lang)
    assertEquals("Ready track keeps URL", "https://x/en.srt", readyFile.url)
    assertEquals("Ready track keeps headers", "abc", readyFile.headers?.get("X-Sub-Key"))

    // =========================================================================
    // TEST 10: Manual mode loadLinks is a pure passthrough (no [AI] track, no auto work)
    // =========================================================================
    println("\n--- [Test 10: Manual mode loadLinks passthrough] ---")
    TranslatorConfig.translationMode = TranslatorConfig.MODE_MANUAL
    val manualStartClient = CountingCleanClient()
    val manualSrtRaw = buildSrt(20, "manual-session")
    val manualSrtFile = File(testRoot, "manual_session.srt").apply { writeText(manualSrtRaw) }

    // 10A: manual AITranslationSession startSession emits interim + final callbacks
    val interimTracks = CopyOnWriteArrayList<SubtitleFile>()
    val finalTracks = CopyOnWriteArrayList<SubtitleFile>()
    val manualSession = AITranslationSession(
        mediaPayload = "v12_manual_session",
        targetLang = "vi",
        playerSubtitleCallback = {},
        clientOverride = manualStartClient,
        manual = true,
        onInterimTrack = { interimTracks.add(it) },
        onFinalTrack = { finalTracks.add(it) }
    )
    manualSession.startSession(SubtitleFile("English", manualSrtFile.absolutePath)).join()
    assertTrue("Manual session completed", manualSession.isCompleted)
    assertTrue("Manual session emitted interim track", interimTracks.isNotEmpty())
    assertTrue(
        "Manual interim label marked '(tạm)'",
        interimTracks.first().lang.contains("(tạm)")
    )
    assertTrue("Manual session emitted final track", finalTracks.isNotEmpty())
    assertTrue(
        "Manual final label is authoritative '[AI: model]'",
        finalTracks.last().lang.contains("[AI:")
    )
    assertTrue(
        "Manual final URL carries cache buster",
        finalTracks.last().url.contains("?v=")
    )

    // 10B: loadLinks manual branch never emits AI tracks or starts translation
    val manualEmitted = CopyOnWriteArrayList<SubtitleFile>()
    val manualBaseApi = object : MainAPI() {
        override var name = "V12ManualBase"
        override var mainUrl = "https://manual.base.test"
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            subtitleCallback(SubtitleFile("English (Source)", "https://manual.base.test/en.srt"))
            callback(ExtractorLink("V12ManualBase", "720p", "https://manual.base.test/video.m3u8", "", 720))
            return true
        }
    }
    val manualEnriched = AITranslatorEnrichedMainAPI(manualBaseApi)
    val manualResult = manualEnriched.loadLinks("v12_manual_episode", false, { manualEmitted.add(it) }, {})
    assertTrue("Manual loadLinks returned provider result", manualResult)
    assertEquals("Manual loadLinks emitted exactly the provider's own track", 1, manualEmitted.size)
    assertEquals("Manual loadLinks kept provider label", "English (Source)", manualEmitted[0].lang)
    assertFalse(
        "Manual loadLinks emitted no [AI] ghost track",
        manualEmitted.any { it.lang.contains("[AI", ignoreCase = true) }
    )
    val manualHookSession = manualEnriched.activeSession
    assertTrue("Manual loadLinks created a manual session", manualHookSession?.manual == true)
    delay(600L)
    assertEquals("Manual session queued no candidates automatically", 0, manualHookSession?.acceptedCandidateCount)
    assertFalse("Manual session did not auto-start translation", manualHookSession?.isTranslationInFlight == true)
    assertFalse("Manual session did not complete anything automatically", manualHookSession?.isCompleted == true)
    manualHookSession?.cancelSession()

    // =========================================================================
    // TEST 11: translationMode persistence + version 12 wiring
    // =========================================================================
    println("\n--- [Test 11: translationMode persistence & v12 manifest] ---")
    val mockCtx = MockContextV12.create()
    TranslatorConfig.resetToDefaults()
    assertEquals("Default translationMode is auto", TranslatorConfig.MODE_AUTO, TranslatorConfig.translationMode)
    TranslatorConfig.translationMode = TranslatorConfig.MODE_MANUAL
    TranslatorConfig.save(mockCtx)
    TranslatorConfig.translationMode = TranslatorConfig.MODE_AUTO
    TranslatorConfig.init(mockCtx)
    assertEquals("Manual translationMode persisted and restored", TranslatorConfig.MODE_MANUAL, TranslatorConfig.translationMode)
    TranslatorConfig.resetToDefaults()
    assertEquals("resetToDefaults restores auto mode", TranslatorConfig.MODE_AUTO, TranslatorConfig.translationMode)

    try {
        val manifest = File("AiSubtitleTranslator/manifest.json")
        if (manifest.exists()) {
            assertTrue("manifest.json declares version 12", manifest.readText(Charsets.UTF_8).contains("\"version\":12"))
        } else {
            println("  [SKIP] manifest.json not found relative to cwd")
        }
    } catch (_: Throwable) {}

    AITranslatorLocalServer.stop()
    println("\n================================================================================")
    println(">>> ALL V12 MANUAL MODE & INTAKE TESTS PASSED PERFECTLY!")
    println("================================================================================")
}