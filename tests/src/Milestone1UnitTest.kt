package tests.unit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslatorPromptBuilder
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.*
import com.lagradost.cloudstream3.subtitles.ai.profile.AniListCastResolver
import com.lagradost.cloudstream3.subtitles.ai.profile.CharacterProfileManager
import com.lagradost.cloudstream3.subtitles.ai.session.DialogueContextWindow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private fun assertTrue(msg: String, condition: Boolean) {
    if (!condition) {
        System.err.println("  [FAIL] $msg")
        throw AssertionError("Assertion failed: $msg")
    }
    println("  [PASS] $msg")
}

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        System.err.println("  [FAIL] $msg - Expected '$expected', but got '$actual'")
        throw AssertionError("Assertion failed: $msg - Expected '$expected', but got '$actual'")
    }
    println("  [PASS] $msg")
}

private class M1MockSharedPreferences : SharedPreferences {
    private val store = ConcurrentHashMap<String, Any?>()
    override fun getAll(): Map<String, *> = HashMap(store)
    override fun getString(key: String?, defValue: String?): String? = store[key]?.toString() ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (store[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (store[key] as? Number)?.toInt() ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (store[key] as? Number)?.toLong() ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (store[key] as? Number)?.toFloat() ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (store[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = store.containsKey(key)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        override fun putString(k: String?, v: String?) = apply { if (k != null) staged[k] = v }
        override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { if (k != null) staged[k] = v }
        override fun putInt(k: String?, v: Int) = apply { if (k != null) staged[k] = v }
        override fun putLong(k: String?, v: Long) = apply { if (k != null) staged[k] = v }
        override fun putFloat(k: String?, v: Float) = apply { if (k != null) staged[k] = v }
        override fun putBoolean(k: String?, v: Boolean) = apply { if (k != null) staged[k] = v }
        override fun remove(k: String?) = apply { if (k != null) store.remove(k) }
        override fun clear() = apply { store.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            for ((k, v) in staged) {
                if (v == null) store.remove(k) else store[k] = v
            }
            staged.clear()
        }
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private open class M1MockContext : ContextWrapper(null) {
    private val prefs = ConcurrentHashMap<String, M1MockSharedPreferences>()
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        prefs.getOrPut(name ?: "default") { M1MockSharedPreferences() }
    override fun getApplicationContext(): Context = this

    companion object {
        fun create(): M1MockContext {
            return try {
                M1MockContext()
            } catch (_: Throwable) {
                val f: Field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
                f.isAccessible = true
                val unsafe = f.get(null)
                val alloc = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
                val inst = alloc.invoke(unsafe, M1MockContext::class.java) as M1MockContext
                val pf = M1MockContext::class.java.getDeclaredField("prefs")
                pf.isAccessible = true
                pf.set(inst, ConcurrentHashMap<String, M1MockSharedPreferences>())
                inst
            }
        }
    }
}

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting Milestone 1 Core Prompting & Context Window Decoupling Tests...")
    println("================================================================================")

    // -------------------------------------------------------------------------
    // TEST 1: Default System Prompt & DISAMBIGUATION OVERRIDE
    // -------------------------------------------------------------------------
    println("\n--- [Test 1: System Prompt Without MediaContext & Disambiguation Override] ---")
    val defaultPrompt = AITranslatorPromptBuilder.buildSystemPrompt("vi", null, null)

    assertTrue("Prompt omits Section 0 when MediaContext is null", !defaultPrompt.contains("### 0. MEDIA CONTEXT"))
    assertTrue("Prompt contains Section 1 Character Roster", defaultPrompt.contains("### 1. CHARACTER ROSTER & RELATIONSHIPS:"))
    assertTrue(
        "Prompt contains CONTEXTUAL CONTINUITY & SELF-CORRECTION",
        defaultPrompt.contains("CONTEXTUAL CONTINUITY & SELF-CORRECTION: Use [LOOKBEHIND_CONTEXT]")
    )
    assertTrue(
        "Prompt contains DISAMBIGUATION OVERRIDE directive",
        defaultPrompt.contains("DISAMBIGUATION OVERRIDE: If lookbehind used a generic or assumed pronoun")
    )
    assertTrue(
        "Prompt contains directive to never blindly persist prior mistaken pronouns",
        defaultPrompt.contains("DO NOT blindly persist prior mistaken pronouns.")
    )

    // -------------------------------------------------------------------------
    // TEST 2: System Prompt With Injected MediaContext
    // -------------------------------------------------------------------------
    println("\n--- [Test 2: System Prompt With Injected MediaContext] ---")
    val mediaContext = MediaContext(
        title = "Kyouran Reijou Nia Liston",
        plot = "The manic princess Nia Liston engages in fierce battles with martial arts.",
        tags = listOf("Action", "Fantasy", "Martial Arts")
    )
    val enrichedPrompt = AITranslatorPromptBuilder.buildSystemPrompt("vi", null, null, mediaContext)

    assertTrue("Enriched prompt contains Section 0 header", enrichedPrompt.contains("### 0. MEDIA CONTEXT:"))
    assertTrue("Enriched prompt contains Media Title", enrichedPrompt.contains("- Media Title: Kyouran Reijou Nia Liston"))
    assertTrue("Enriched prompt contains Plot", enrichedPrompt.contains("- Plot / Synopsis: The manic princess Nia Liston engages in fierce battles with martial arts."))
    assertTrue("Enriched prompt contains Tags", enrichedPrompt.contains("- Tags / Genres: Action, Fantasy, Martial Arts"))

    val pos0 = enrichedPrompt.indexOf("### 0. MEDIA CONTEXT:")
    val pos1 = enrichedPrompt.indexOf("### 1. CHARACTER ROSTER & RELATIONSHIPS:")
    assertTrue("Section 0 precedes Section 1 in system prompt", pos0 in 0 until pos1)
    assertTrue("Enriched prompt still retains DISAMBIGUATION OVERRIDE", enrichedPrompt.contains("DISAMBIGUATION OVERRIDE"))

    // -------------------------------------------------------------------------
    // TEST 3: DialogueContextWindow 15000ms Scene-Cut Eviction
    // -------------------------------------------------------------------------
    println("\n--- [Test 3: DialogueContextWindow 15000ms Scene-Cut Eviction] ---")
    val defaultWindow = DialogueContextWindow()
    assertEquals("Default maxLookbehindGapMs is 15000L", 15000L, defaultWindow.maxLookbehindGapMs)

    val cue1 = SubtitleCue(index = 1, startMs = 1000L, endMs = 3000L, rawText = "Line 1")
    defaultWindow.recordFinalizedCues(listOf(cue1), mapOf(1 to "Dòng 1"))

    // Within-scene target: starts at 7000L (gap = 4000L <= 15000L)
    val withinSceneTarget = listOf(SubtitleCue(index = 2, startMs = 7000L, endMs = 9000L, rawText = "Line 2"))
    val lbWithin = defaultWindow.getLookbehind(withinSceneTarget)
    assertEquals("Lookbehind preserved for gaps <= 15000ms", 1, lbWithin.size)
    assertEquals("Lookbehind item index matches cue 1", 1, lbWithin.first().index)

    // Record cue 2
    defaultWindow.recordFinalizedCues(withinSceneTarget, mapOf(2 to "Dòng 2"))

    // Scene-cut target: starts at 26000L (gap = 17000L > 15000L)
    val sceneCutTarget = listOf(SubtitleCue(index = 3, startMs = 26000L, endMs = 28000L, rawText = "Line 3"))
    val lbSceneCut = defaultWindow.getLookbehind(sceneCutTarget)
    assertEquals("Lookbehind flushed on scene cut gap > 15000ms", 0, lbSceneCut.size)
    assertEquals("Session generation ID incremented after flush", 1L, defaultWindow.sessionGenerationId)

    // -------------------------------------------------------------------------
    // TEST 4: M1 v11 Models, Backwards-Compat & GlobalFilmBible
    // -------------------------------------------------------------------------
    println("\n--- [Test 4: Models & GlobalFilmBible] ---")
    val mapper = ObjectMapper().registerKotlinModule()
    val mc = MediaContext(
        title = "Real Steel",
        plot = "A boxing promoter reconnects with his son.",
        actors = listOf("Hugh Jackman", "Dakota Goyo"),
        tags = listOf("Action"),
        mediaType = "Movie",
        year = 2011,
        genres = listOf("Sci-Fi", "Sports", "Drama"),
        characterActors = mapOf("Hugh Jackman" to "Charlie Kenton", "Dakota Goyo" to "Max Kenton")
    )
    assertEquals("MediaContext mediaType preserved", "Movie", mc.mediaType)
    assertEquals("MediaContext year preserved", 2011, mc.year)
    assertEquals("MediaContext characterActors preserved", "Charlie Kenton", mc.characterActors["Hugh Jackman"])

    val legacyPosCtxCue = ContextCue(1, "A", "Hi", "Chào")
    assertEquals("Positional 4-arg ContextCue defaults gapMs to 0L", 0L, legacyPosCtxCue.gapMs)

    val itemMinimal = mapper.readValue("""{"id":7,"text":"Đi thôi."}""", SubtitleItem::class.java)
    assertEquals("Minimal SubtitleItem id", 7, itemMinimal.id)
    assertEquals("Minimal SubtitleItem act defaults null", null, itemMinimal.act)
    assertEquals("Minimal SubtitleItem role defaults null", null, itemMinimal.role)

    val bibleJson = """{"characters":[{"name":"Charlie Kenton","gender":"male"}],"auto_glossary":{"shadow-box":"đấm gió"},"tone_guide":"Gritty drama"}"""
    val bible = mapper.readValue(bibleJson, GlobalFilmBible::class.java)
    assertEquals("GlobalFilmBible auto_glossary alias", "đấm gió", bible.autoGlossary["shadow-box"])
    assertEquals("GlobalFilmBible tone_guide alias", "Gritty drama", bible.toneGuide)
    val convertedReg = bible.toRegistry("Real Steel")
    assertEquals("toRegistry preserves mediaTitle & glossary", "Real Steel", convertedReg.mediaTitle)
    assertEquals("toRegistry preserves toneGuide", "Gritty drama", convertedReg.toneGuide)

    // -------------------------------------------------------------------------
    // TEST 5: CharacterProfileManager Legacy JSON & Explicit Null Tolerance
    // -------------------------------------------------------------------------
    println("\n--- [Test 5: CharacterProfileManager Legacy & Null Compatibility] ---")
    val m1TempDir = File.createTempFile("m1_profile_test_", "").apply { delete(); mkdirs() }
    m1TempDir.deleteOnExit()
    CharacterProfileManager.clearAll()
    CharacterProfileManager.customBaseDir = m1TempDir

    val legacyTitle = "Legacy Anime Series"
    val digestBytes = java.security.MessageDigest.getInstance("SHA-256")
        .digest(legacyTitle.trim().lowercase().toByteArray(Charsets.UTF_8))
    val legacyKey = digestBytes.joinToString("") { "%02x".format(it) }
    val aiProfilesDir = File(m1TempDir, "ai_profiles").apply { mkdirs() }
    // Write legacy JSON missing autoGlossary/toneGuide AND another with explicit nulls
    File(aiProfilesDir, "$legacyKey.json").writeText(
        """{"mediaTitle":"Legacy Anime Series","characters":[{"name":"Hero","gender":"male","role":"MAIN"}],"relationships":[],"unknownLegacyField":123}""",
        Charsets.UTF_8
    )
    val loadedLegacy = CharacterProfileManager.getProfile(null, legacyTitle)
    assertTrue("Legacy JSON deserializes cleanly", loadedLegacy != null && loadedLegacy.characters.size == 1)
    assertEquals("Legacy JSON defaults toneGuide to empty", "", loadedLegacy?.toneGuide)
    assertTrue("Legacy JSON defaults autoGlossary to emptyMap", loadedLegacy?.autoGlossary?.isEmpty() == true)

    val nullFieldsTitle = "Null Fields Series"
    val nullKey = java.security.MessageDigest.getInstance("SHA-256")
        .digest(nullFieldsTitle.trim().lowercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    File(aiProfilesDir, "$nullKey.json").writeText(
        """{"mediaTitle":"Null Fields Series","characters":[{"name":"Hero","gender":"male"}],"autoGlossary":null,"toneGuide":null}""",
        Charsets.UTF_8
    )
    val loadedNullFields = CharacterProfileManager.getProfile(null, nullFieldsTitle)
    assertTrue("JSON with explicit null autoGlossary/toneGuide deserializes safely", loadedNullFields != null)

    // Round-trip save + episodic reset preserves autoGlossary & toneGuide
    val richBibleReg = TwoTierCharacterRegistry(
        mediaTitle = "Real Steel",
        characters = listOf(CharacterEntity(name = "Charlie Kenton", gender = "male", role = "Father")),
        autoGlossary = mapOf("sparring bot" to "robot tập đấu"),
        toneGuide = "Gritty cinema"
    )
    CharacterProfileManager.saveProfile(null, "Real Steel", richBibleReg, isEphemeral = false)
    CharacterProfileManager.saveProfile(null, "Real Steel", richBibleReg.copy(characters = richBibleReg.characters + CharacterEntity(name = "Temp", isEphemeral = true)), isEphemeral = true)
    CharacterProfileManager.resetEpisodicState("Real Steel")
    CharacterProfileManager.clearAll()
    val reloadedRich = CharacterProfileManager.getProfile(null, "Real Steel")
    assertEquals("Disk reload preserves autoGlossary", "robot tập đấu", reloadedRich?.autoGlossary?.get("sparring bot"))
    assertEquals("Disk reload preserves toneGuide", "Gritty cinema", reloadedRich?.toneGuide)

    // -------------------------------------------------------------------------
    // TEST 6: TranslatorConfig v11 Defaults & highSpeedMode Persistence
    // -------------------------------------------------------------------------
    println("\n--- [Test 6: TranslatorConfig v11 Defaults & highSpeedMode] ---")
    val mockCtx = M1MockContext.create()
    TranslatorConfig.resetToDefaults(mockCtx)
    assertEquals("TranslatorConfig chunkSize preserved at 30", 30, TranslatorConfig.chunkSize)
    assertEquals("TranslatorConfig contextWindowSize default is 12", 12, TranslatorConfig.contextWindowSize)
    assertEquals("TranslatorConfig maxChunkCues default is 45", 45, TranslatorConfig.maxChunkCues)
    assertEquals("TranslatorConfig highSpeedMode default is false", false, TranslatorConfig.highSpeedMode)

    TranslatorConfig.highSpeedMode = true
    TranslatorConfig.save(mockCtx)
    TranslatorConfig.highSpeedMode = false
    TranslatorConfig.init(mockCtx)
    assertEquals("TranslatorConfig restores key_high_speed_mode=true", true, TranslatorConfig.highSpeedMode)
    TranslatorConfig.resetToDefaults(mockCtx)
    assertEquals("TranslatorConfig resetToDefaults clears highSpeedMode", false, TranslatorConfig.highSpeedMode)

    // -------------------------------------------------------------------------
    // TEST 7: AniListCastResolver.resolveUniversalCast (Live-Action Cinemeta Mock)
    // -------------------------------------------------------------------------
    println("\n--- [Test 7: AniListCastResolver.resolveUniversalCast] ---")
    val test7Dir = File.createTempFile("m1_test7_profile_", "").apply { delete(); mkdirs() }
    test7Dir.deleteOnExit()
    CharacterProfileManager.clearAll()
    CharacterProfileManager.customBaseDir = test7Dir
    val aniListCalls = AtomicInteger(0)
    val cinemetaMockClient = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val bodyStr = when {
            url.contains("anilist.co") -> {
                aniListCalls.incrementAndGet()
                "{}"
            }
            url.contains("/catalog/movie/top/search=") -> """{"metas":[{"id":"tt0433035","imdb_id":"tt0433035","name":"Real Steel","releaseInfo":"2011"}]}"""
            url.contains("/meta/movie/tt0433035.json") -> """{"meta":{"genres":["Action","Drama","Sci-Fi"],"app_extras":{"cast":[{"name":"Evangeline Lilly","character":"Bailey Tallet"}]}}}"""
            else -> "{}"
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bodyStr.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val universalReg = AniListCastResolver.resolveUniversalCast(
        context = null,
        rawTitle = "Real.Steel.2011.1080p.mkv",
        mediaContext = mc,
        httpClient = cinemetaMockClient,
        cinemetaBaseUrl = "https://mock-cinemeta.local"
    )
    assertEquals("Live-Action Movie did NOT call AniList", 0, aniListCalls.get())
    assertTrue("resolveUniversalCast seeded Charlie Kenton from characterActors", universalReg?.characters?.any { it.name == "Charlie Kenton" } == true)
    assertTrue("resolveUniversalCast merged Bailey Tallet from Cinemeta meta", universalReg?.characters?.any { it.name == "Bailey Tallet" } == true)
    assertTrue("resolveUniversalCast captured Cinemeta genres in toneGuide", universalReg?.toneGuide?.contains("Action") == true)

    // 7b: AniListCastResolver.resolveUniversalCast with slow 8s OkHttpClient against stalled ServerSocket finishes <= 4000ms
    val slowSocket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val slowServerThread = Thread {
        while (!slowSocket.isClosed) {
            try {
                val sock = slowSocket.accept()
                Thread {
                    try {
                        Thread.sleep(10000)
                        sock.close()
                    } catch (_: Throwable) {}
                }.apply { isDaemon = true; start() }
            } catch (_: Throwable) {
                break
            }
        }
    }.apply { isDaemon = true; start() }
    val slowClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val slowMc = MediaContext(
        title = "Unseen Stalled Movie",
        mediaType = "Movie",
        characterActors = mapOf("Hugh Jackman" to "Charlie Kenton", "Dakota Goyo" to "Max Kenton")
    )
    val tSlowStart = System.currentTimeMillis()
    val slowReg = AniListCastResolver.resolveUniversalCast(
        context = null,
        rawTitle = "Unseen.Stalled.Movie.2011.mkv",
        mediaContext = slowMc,
        httpClient = slowClient,
        cinemetaBaseUrl = "http://127.0.0.1:${slowSocket.localPort}"
    )
    val slowElapsed = System.currentTimeMillis() - tSlowStart
    try { slowSocket.close() } catch (_: Throwable) {}
    slowServerThread.interrupt()
    assertTrue("resolveUniversalCast with 8s OkHttpClient finishes <= 4000ms (took ${slowElapsed}ms)", slowElapsed <= 4000L)
    assertTrue(
        "resolveUniversalCast timeout fallback preserves seeded characterActors",
        slowReg?.characters?.any { it.name == "Charlie Kenton" } == true && slowReg.characters.any { it.name == "Max Kenton" }
    )

    // 7c: NullNode regression tests for resolveCast and resolveUniversalCast (zero literal "null" strings)
    val nullAniListClient = OkHttpClient.Builder().addInterceptor { chain ->
        val aniListNullJson = """{"data":{"Media":{"characters":{"edges":[
            {"role":"MAIN","node":{"name":{"full":null,"native":"アトム"},"gender":null}},
            {"role":null,"node":{"name":{"full":"Dr. Tenma","native":null},"gender":"Male"}},
            {"role":null,"node":{"name":{"full":null,"native":null},"gender":null}}
        ]}}}}"""
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(aniListNullJson.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val nullAnimeChars = AniListCastResolver.resolveCast("Astro Boy Null Probe", nullAniListClient)
    assertEquals("resolveCast skips both-null names and keeps 2 valid entries", 2, nullAnimeChars.size)
    assertEquals("resolveCast falls back from full:null to native", "アトム", nullAnimeChars[0].name)
    assertTrue("resolveCast does not duplicate native in aliases", nullAnimeChars[0].aliases.isEmpty())
    assertEquals("resolveCast defaults gender:null to unknown", "unknown", nullAnimeChars[0].gender)
    assertEquals("resolveCast preserves role=MAIN", "MAIN", nullAnimeChars[0].role)
    assertEquals("resolveCast preserves full name when native:null", "Dr. Tenma", nullAnimeChars[1].name)
    assertTrue("resolveCast omits native:null from aliases", nullAnimeChars[1].aliases.isEmpty())
    assertEquals("resolveCast preserves gender=Male", "Male", nullAnimeChars[1].gender)
    assertEquals("resolveCast defaults role:null to empty string", "", nullAnimeChars[1].role)
    assertTrue(
        "resolveCast emits zero literal 'null' strings",
        nullAnimeChars.none { c ->
            c.name == "null" || c.gender == "null" || c.role == "null" || c.aliases.any { it == "null" }
        }
    )

    val nullCinemetaClient = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val bodyStr = when {
            url.contains("/catalog/movie/top/search=") && url.contains("Null%20Cast%20Movie") ->
                """{"metas":[{"id":"tt9000001","imdb_id":"tt9000001","name":"Null Cast Movie","year":2011}]}"""
            url.contains("/meta/movie/tt9000001.json") ->
                """{"meta":{"genres":[null,"Sci-Fi"],"app_extras":{"cast":[{"name":"Hugh Jackman","character":null},{"name":null,"character":"Max Kenton"},null]}}}"""
            url.contains("/catalog/movie/top/search=") && url.contains("Null%20Raw%20Cast") ->
                """{"metas":[{"id":"tt9000002","imdb_id":"tt9000002","name":"Null Raw Cast","year":2011}]}"""
            url.contains("/meta/movie/tt9000002.json") ->
                """{"meta":{"genres":[null],"cast":[null,"Dakota Goyo"]}}"""
            else -> "{}"
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bodyStr.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val nullCastReg = AniListCastResolver.resolveUniversalCast(
        context = null,
        rawTitle = "Null Cast Movie",
        mediaContext = MediaContext(title = "Null Cast Movie", mediaType = "Movie", year = 2011),
        httpClient = nullCinemetaClient,
        cinemetaBaseUrl = "https://mock-cinemeta.local"
    )
    assertTrue("resolveUniversalCast returns registry for Null Cast Movie", nullCastReg != null)
    assertEquals(
        "resolveUniversalCast skips character:null and null entries in app_extras.cast",
        listOf("Max Kenton"),
        nullCastReg?.characters?.map { it.name }
    )
    assertEquals("resolveUniversalCast avoids 'Played by null' when actor name is null", "Cast", nullCastReg?.characters?.firstOrNull()?.role)
    assertEquals("resolveUniversalCast filters null genres", "Genres: Sci-Fi", nullCastReg?.toneGuide)
    assertTrue(
        "resolveUniversalCast emits zero literal 'null' strings in characters or toneGuide",
        nullCastReg?.characters?.none { it.name == "null" || it.role == "null" } == true &&
            !(nullCastReg?.toneGuide?.contains("null") ?: true)
    )

    val nullRawCastReg = AniListCastResolver.resolveUniversalCast(
        context = null,
        rawTitle = "Null Raw Cast",
        mediaContext = MediaContext(title = "Null Raw Cast", mediaType = "Movie", year = 2011),
        httpClient = nullCinemetaClient,
        cinemetaBaseUrl = "https://mock-cinemeta.local"
    )
    assertEquals(
        "resolveUniversalCast skips null in fallback cast array",
        listOf("Dakota Goyo"),
        nullRawCastReg?.characters?.map { it.name }
    )
    assertEquals("resolveUniversalCast returns empty toneGuide when all genres are null", "", nullRawCastReg?.toneGuide)

    // -------------------------------------------------------------------------
    // TEST 8: DialogueContextWindow 1200/20 Pruning, gapMs & isSceneShiftCaution
    // -------------------------------------------------------------------------
    println("\n--- [Test 8: DialogueContextWindow 1200/20 & Timing Gaps] ---")
    val win12 = DialogueContextWindow(lookbehindSize = 12, lookaheadSize = 8, maxLookbehindGapMs = 15000L)
    val largeChars = (1..15).map { CharacterEntity(name = "Char_$it", gender = "male", role = "Role $it") }
    val largeRels = (1..14).map { CharacterRelationship(pair = listOf("Char_$it", "Char_${it + 1}"), type = "peers") }
    val largeReg = TwoTierCharacterRegistry(
        mediaTitle = "Real Steel",
        characters = largeChars,
        relationships = largeRels,
        autoGlossary = mapOf("uppercut" to "cú móc ngược"),
        toneGuide = "Action sports drama"
    )
    val targetCues = listOf(
        SubtitleCue(index = 2, startMs = 7000L, endMs = 9000L, rawText = "Char_4, Char_5, Char_6, Char_7, Char_8, Char_9, Char_10 are ready", speaker = "Char_4")
    )
    val defaultPruned = win12.pruneRegistry(largeReg, targetCues)
    assertTrue("Default pruneRegistry stays <= 6 chars", defaultPruned.characters.size <= 6)
    assertTrue("Default pruneRegistry enforces <= 250.0 tokens", mapper.writeValueAsString(defaultPruned).length / 4.0 <= 250.0)

    val expandedPruned = win12.pruneRegistry(largeReg, targetCues, maxTokens = 1200.0, maxCharacters = 20)
    assertTrue("Expanded pruneRegistry(1200, 20) retains > 6 chars", expandedPruned.characters.size > 6)

    val c1 = SubtitleCue(index = 1, startMs = 1000L, endMs = 3000L, rawText = "Hey", speaker = "Charlie")
    val c2 = SubtitleCue(index = 2, startMs = 6500L, endMs = 8000L, rawText = "Sign this.", speaker = null)
    val c3 = SubtitleCue(index = 3, startMs = 11500L, endMs = 13000L, rawText = "Lookahead line", speaker = "Ricky")
    win12.recordFinalizedCues(listOf(c1), mapOf(1 to "Này"))
    val lbGap = win12.getLookbehind(listOf(c2))
    assertEquals("computeTransitionGapMs returns 3500L", 3500L, win12.computeTransitionGapMs(listOf(c2), lbGap))
    assertTrue("isSceneShiftCaution triggers when gap >= 3000L or speaker == null", win12.isSceneShiftCaution(listOf(c2), lbGap))
    val laGap = win12.extractLookahead(listOf(c1, c2, c3), currentTargetEndIndex = 2)
    assertEquals("extractLookahead computes gapMs", 3500L, laGap.first().gapMs)

    // -------------------------------------------------------------------------
    // TEST 9: AITranslatorPromptBuilder Kinship Lock, Multi-Genre, Gaps & Film Bible
    // -------------------------------------------------------------------------
    println("\n--- [Test 9: PromptBuilder Kinship Lock, Multi-Genre & Film Bible] ---")
    val westernPrompt = AITranslatorPromptBuilder.buildSystemPrompt("vi", richBibleReg, "robot -> người máy", mc)
    assertTrue("Pair-Scoped Kinship Lock included", westernPrompt.contains("Pair-Scoped Kinship Lock"))
    assertTrue("Western Cinema Few-Shot included", westernPrompt.contains("Few-Shot Ngắt Xưng Hô Khi Cắt Cảnh") && westernPrompt.contains("Anti-Translationese"))
    assertTrue("autoGlossary merged before customGlossary in Section 7", westernPrompt.contains("sparring bot -> robot tập đấu") && westernPrompt.contains("robot -> người máy"))
    assertTrue("Section 6 enforces {id, text} and forbids act/role", westernPrompt.contains("{\"translations\":[{\"id\":<int>,\"text\":\"<string>\"}]}") && westernPrompt.contains("DO NOT output 'act', 'role'"))

    val docPrompt = AITranslatorPromptBuilder.buildSystemPrompt("vi", null, null, MediaContext(title = "Planet Earth", mediaType = "Documentary"))
    assertTrue("Documentary directives included", docPrompt.contains("Tài liệu / Thuyết minh"))
    val kdramaPrompt = AITranslatorPromptBuilder.buildSystemPrompt("vi", null, null, MediaContext(title = "Vincenzo", mediaType = "AsianDrama"))
    assertTrue("AsianDrama directives included", kdramaPrompt.contains("Châu Á hiện đại"))

    val userPayload = AITranslatorPromptBuilder.buildUserPayload(
        cues = listOf(c2),
        lookbehind = lbGap,
        lookahead = laGap,
        rollingSceneDigest = "Charlie left the gym."
    )
    assertTrue("User payload includes [ROLLING_SCENE_DIGEST]", userPayload.contains("[ROLLING_SCENE_DIGEST]") && userPayload.contains("Charlie left the gym."))
    assertTrue("User payload includes [SCENE_SHIFT_CAUTION]", userPayload.contains("[SCENE_SHIFT_CAUTION:"))
    assertTrue("User payload includes gap_s >= 2.5s", userPayload.contains("\"gap_s\":\"3.5\""))

    val manyCues = (1..400).map { i ->
        val txt = if (i % 25 == 0) "Listen Dad, Charlie is here." else "Regular line $i"
        SubtitleCue(index = i, startMs = i * 1000L, endMs = i * 1000L + 800L, rawText = txt)
    }
    val sampled = AITranslatorPromptBuilder.sampleRepresentativeCues(manyCues, maxSamples = 165)
    assertEquals("sampleRepresentativeCues returns 165 samples", 165, sampled.size)
    assertTrue("sampleRepresentativeCues prioritizes vocative/proper-noun lines", sampled.any { it.rawText.contains("Dad") })
    val (fbSys, fbUser) = AITranslatorPromptBuilder.buildFilmBiblePrompt(mc, sampled, "vi")
    assertTrue("buildFilmBiblePrompt requests GlobalFilmBible schema", fbSys.contains("auto_glossary") && fbSys.contains("tone_guide"))
    assertTrue("buildFilmBiblePrompt embeds metadata and sampled lines", fbUser.contains("Real Steel") && fbUser.contains("[REPRESENTATIVE_DIALOGUE_SAMPLES]"))

    println("\n================================================================================")
    println(">>> ALL MILESTONE 1 KOTLIN TESTS PASSED PERFECTLY!")
    println("================================================================================")
}
