package tests.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslatorPromptBuilder
import com.lagradost.cloudstream3.subtitles.ai.client.AnthropicTranslatorClient
import com.lagradost.cloudstream3.subtitles.ai.client.GeminiTranslatorClient
import com.lagradost.cloudstream3.subtitles.ai.client.OpenAITranslatorClient
import com.lagradost.cloudstream3.subtitles.ai.client.PacingGovernor
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.*
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.profile.AniListCastResolver
import com.lagradost.cloudstream3.subtitles.ai.profile.CharacterProfileManager
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import com.lagradost.cloudstream3.subtitles.ai.session.DialogueContextWindow
import com.lagradost.cloudstream3.subtitles.ai.session.SubtitleReconciler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

fun assertTrue(msg: String, condition: Boolean) {
    if (!condition) {
        System.err.println("  [FAIL] $msg")
        throw AssertionError("Assertion failed: $msg")
    }
    println("  [PASS] $msg")
}

fun assertFalse(msg: String, condition: Boolean) = assertTrue(msg, !condition)

fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        System.err.println("  [FAIL] $msg - Expected '$expected', but got '$actual'")
        throw AssertionError("Assertion failed: $msg - Expected '$expected', but got '$actual'")
    }
    println("  [PASS] $msg")
}

fun main() = runBlocking {
    println("================================================================================")
    println(">>> Starting Milestone 2 Kotlin Architecture & Character Addressing Test Suite...")
    println("================================================================================")

    // -------------------------------------------------------------------------
    // TEST 1: Data Models & Jackson Serialization
    // -------------------------------------------------------------------------
    println("\n--- [Test 1: Subtitle Models & Polymorphic Serialization] ---")
    val mapper = ObjectMapper().registerKotlinModule()

    val cue = SubtitleCue(
        index = 1,
        cueId = "c1",
        startMs = 1000L,
        endMs = 4000L,
        cueSettings = "align:start",
        rawText = "Hello Arthur",
        maskedText = "Hello Arthur",
        tagMap = emptyMap(),
        speaker = "Elena",
        speakerGender = "female"
    )
    assertEquals("SubtitleCue speaker field", "Elena", cue.speaker)
    assertEquals("SubtitleCue speakerGender field", "female", cue.speakerGender)

    val charEntity = CharacterEntity(
        name = "Arthur",
        aliases = listOf("Art", "Director Vance"),
        gender = "male",
        ageGroup = "adult",
        role = "Senior Managing Director",
        personaVariants = PersonaVariants(public_pronoun = "tôi", combat_pronoun = "tao")
    )
    val rel = CharacterRelationship(
        pair = listOf("Arthur", "Elena"),
        type = "workplace",
        isEphemeral = false,
        addressMatrix = mapOf("vi" to mapOf("Arthur_to_Elena" to "chị", "Elena_to_Arthur" to "anh"))
    )
    val registry = TwoTierCharacterRegistry(
        mediaTitle = "Tech Titans Season 1",
        characters = listOf(charEntity),
        relationships = listOf(rel)
    )

    val regJson = mapper.writeValueAsString(registry)
    assertTrue("Registry serialized contains Arthur", regJson.contains("Arthur"))
    assertTrue("Registry serialized contains Director Vance", regJson.contains("Director Vance"))

    val deserializedReg = mapper.readValue(regJson, TwoTierCharacterRegistry::class.java)
    assertEquals("Deserialized title matches", "Tech Titans Season 1", deserializedReg.mediaTitle)
    assertEquals("Deserialized character name matches", "Arthur", deserializedReg.characters.first().name)
    assertEquals("Deserialized relationship type matches", "workplace", deserializedReg.relationships.first().type)

    // -------------------------------------------------------------------------
    // TEST 2: Subtitle Parser Speaker Diarization (ASS Name/Style, SRT, WebVTT)
    // -------------------------------------------------------------------------
    println("\n--- [Test 2: Speaker Diarization Extraction] ---")

    // Subtest 2.1: ASS Dialogue with explicit Name
    val assWithName = """
        [Script Info]
        Title: ASS Diarization
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:04.00,Default,Arthur,0,0,0,,Good morning everyone.
    """.trimIndent()
    val assNameCues = SubtitleParser.parse(assWithName)
    assertEquals("Extracted 1 ASS cue with Name", 1, assNameCues.size)
    assertEquals("Speaker resolved from Name column", "Arthur", assNameCues[0].speaker)

    // Subtest 2.2: ASS Dialogue with empty Name, fallback to non-Default Style
    val assWithStyleFallback = """
        [Script Info]
        Title: ASS Style Fallback
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:05.00,0:00:08.00,Elena,,0,0,0,,Here is the financial report.
        Dialogue: 0,0:00:09.00,0:00:12.00,Default,,0,0,0,,Just regular narration.
    """.trimIndent()
    val assStyleCues = SubtitleParser.parse(assWithStyleFallback)
    assertEquals("Extracted 2 ASS cues", 2, assStyleCues.size)
    assertEquals("Speaker resolved from Style column fallback", "Elena", assStyleCues[0].speaker)
    assertEquals("Default style does not become speaker", null, assStyleCues[1].speaker)

    // Subtest 2.3: WebVTT <v SpeakerName> voice tags
    val vttWithVoice = """
        WEBVTT

        00:00:01.000 --> 00:00:04.000
        <v Roger>Hello <font color="#ff0000">World</font>!

        00:00:05.000 --> 00:00:08.000
        <v.loud Dr. Claire Morgan>Step away from the console.</v>
    """.trimIndent()
    val vttCues = SubtitleParser.parse(vttWithVoice)
    assertEquals("Extracted 2 VTT cues", 2, vttCues.size)
    assertEquals("VTT Cue 1 speaker extracted", "Roger", vttCues[0].speaker)
    assertTrue("VTT Cue 1 voice tag stripped from rawText", !vttCues[0].rawText.contains("<v Roger>"))
    assertTrue("VTT Cue 1 font tag still shielded", vttCues[0].maskedText.contains("[[TAG_1]]"))
    assertEquals("VTT Cue 2 speaker extracted from class voice tag", "Dr. Claire Morgan", vttCues[1].speaker)
    assertEquals("VTT Cue 2 dialogue cleaned", "Step away from the console.", vttCues[1].rawText)

    // Subtest 2.4: SRT [Speaker]: and Speaker: prefixes
    val srtWithPrefixes = """
        1
        00:00:01,000 --> 00:00:04,000
        [Arthur]: We need to finalize the quarterly review.

        2
        00:00:05,000 --> 00:00:08,000
        Elena: The numbers are ready for your inspection.
    """.trimIndent()
    val srtCues = SubtitleParser.parse(srtWithPrefixes)
    assertEquals("Extracted 2 SRT cues", 2, srtCues.size)
    assertEquals("SRT Bracket speaker extracted", "Arthur", srtCues[0].speaker)
    assertEquals("SRT Bracket dialogue cleaned", "We need to finalize the quarterly review.", srtCues[0].rawText)
    assertEquals("SRT Colon speaker extracted", "Elena", srtCues[1].speaker)
    assertEquals("SRT Colon dialogue cleaned", "The numbers are ready for your inspection.", srtCues[1].rawText)

    // -------------------------------------------------------------------------
    // TEST 3: Dialogue Context Window & Temporal Continuity Guard
    // -------------------------------------------------------------------------
    println("\n--- [Test 3: Dialogue Context Window & Temporal Guard] ---")
    val contextWindow = DialogueContextWindow(lookbehindSize = 3, lookaheadSize = 2, maxLookbehindGapMs = 15000L)

    val cue1 = SubtitleCue(index = 1, startMs = 1000L, endMs = 4000L, rawText = "Hello", maskedText = "Hello", speaker = "A")
    val cue2 = SubtitleCue(index = 2, startMs = 5000L, endMs = 8000L, rawText = "Hi there", maskedText = "Hi there", speaker = "B")
    val cue3 = SubtitleCue(index = 3, startMs = 9000L, endMs = 12000L, rawText = "How are you?", maskedText = "How are you?", speaker = "A")

    contextWindow.recordFinalizedCues(listOf(cue1, cue2, cue3), mapOf(1 to "Xin chào", 2 to "Chào bạn", 3 to "Bạn khỏe không?"))

    // Lookbehind retrieval under normal continuous playback (gap < 15s, index gap == 1)
    val targetCuesContinuous = listOf(SubtitleCue(index = 4, startMs = 13000L, endMs = 16000L, rawText = "I am fine", maskedText = "I am fine", speaker = "B"))
    val lookbehind = contextWindow.getLookbehind(targetCuesContinuous)
    assertEquals("Lookbehind count matches lookbehindSize", 3, lookbehind.size)
    assertEquals("Lookbehind most recent item is Cue 3", 3, lookbehind.last().index)
    assertEquals("Lookbehind translated text preserved", "Bạn khỏe không?", lookbehind.last().translatedText)

    // Temporal Continuity Guard Trigger: Seek Jump > 15s
    val targetCuesSeekTime = listOf(SubtitleCue(index = 5, startMs = 80000L, endMs = 84000L, rawText = "Much later...", maskedText = "Much later..."))
    val lookbehindSeekTime = contextWindow.getLookbehind(targetCuesSeekTime)
    assertEquals("Temporal guard flushes history on time jump > 15s", 0, lookbehindSeekTime.size)

    // Re-populate and test index jump guard (> 5 cues ahead)
    contextWindow.recordFinalizedCues(listOf(cue1), mapOf(1 to "Xin chào"))
    val targetCuesIndexJump = listOf(SubtitleCue(index = 10, startMs = 3000L, endMs = 6000L, rawText = "Skipped forward", maskedText = "Skipped forward"))
    val lookbehindIndexJump = contextWindow.getLookbehind(targetCuesIndexJump)
    assertEquals("Temporal guard flushes history on index jump > 5", 0, lookbehindIndexJump.size)

    // -------------------------------------------------------------------------
    // TEST 4: Active-Speaker Pruning & Token Budget Cap (<= 250 Tokens)
    // -------------------------------------------------------------------------
    println("\n--- [Test 4: Active-Speaker Pruning & Token Cap] ---")
    val charsList = mutableListOf<CharacterEntity>()
    val relsList = mutableListOf<CharacterRelationship>()

    // Create a large cast of 15 characters
    for (i in 1..15) {
        charsList.add(
            CharacterEntity(
                name = "Character_$i",
                aliases = listOf("Alias_${i}_A", "Alias_${i}_B", "Nickname_$i"),
                gender = if (i % 2 == 0) "female" else "male",
                role = "Supporting Role $i"
            )
        )
    }
    // Create multiple relationships between characters
    for (i in 1 until 15) {
        relsList.add(
            CharacterRelationship(
                pair = listOf("Character_$i", "Character_${i + 1}"),
                type = "peers",
                addressMatrix = mapOf("vi" to mapOf("call" to "bạn"))
            )
        )
    }
    val hugeRegistry = TwoTierCharacterRegistry(
        mediaTitle = "Epic Saga",
        characters = charsList,
        relationships = relsList
    )

    val activeCue = SubtitleCue(index = 20, startMs = 1000L, endMs = 4000L, rawText = "Look, Character_5 is here!", maskedText = "", speaker = "Character_4")
    val pruned = contextWindow.pruneRegistry(hugeRegistry, listOf(activeCue))

    assertTrue("Pruned registry contains active speaker Character_4", pruned.characters.any { it.name.equals("Character_4", true) })
    assertTrue("Pruned registry contains mentioned Character_5", pruned.characters.any { it.name.equals("Character_5", true) })
    assertTrue("Pruned registry contains lead Character_1", pruned.characters.any { it.name.equals("Character_1", true) })
    assertTrue("Pruned registry eliminates unmentioned non-lead Character_15", !pruned.characters.any { it.name.equals("Character_15", true) })

    // Verify orphan relationship elimination (all characters in relationship pair must be in pruned characters)
    val prunedCharNames = pruned.characters.map { it.name.lowercase() }.toSet()
    for (relItem in pruned.relationships) {
        assertTrue(
            "No orphan relationships in pruned output: ${relItem.pair}",
            relItem.pair.all { it.lowercase() in prunedCharNames }
        )
    }

    // Verify hard token budget <= 250 tokens
    val estimatedTokens = mapper.writeValueAsString(pruned).length / 4.0
    assertTrue("Pruned registry strictly satisfies token budget <= 250.0 (got $estimatedTokens tokens)", estimatedTokens <= 250.0)

    // -------------------------------------------------------------------------
    // TEST 5: Character Profile Manager Two-Tier Storage & Isolation
    // -------------------------------------------------------------------------
    println("\n--- [Test 5: Two-Tier Character Profile Manager Isolation] ---")
    val tempDir = File.createTempFile("profile_test_", "").apply { delete(); mkdirs() }
    tempDir.deleteOnExit()
    CharacterProfileManager.customBaseDir = tempDir

    val coreReg = TwoTierCharacterRegistry(
        mediaTitle = "Breaking Bad",
        characters = listOf(
            CharacterEntity(name = "Walter White", gender = "male", role = "Chemistry Teacher"),
            CharacterEntity(name = "Jesse Pinkman", gender = "male", role = "Former Student")
        ),
        relationships = listOf(
            CharacterRelationship(pair = listOf("Walter White", "Jesse Pinkman"), type = "master_disciple")
        )
    )

    // Save core profile to persistent disk
    CharacterProfileManager.saveProfile(null, "Breaking Bad", coreReg, isEphemeral = false)
    val loadedCore = CharacterProfileManager.getProfile(null, "Breaking Bad")
    assertEquals("Persistent core profile retrieved", "Breaking Bad", loadedCore?.mediaTitle)
    assertEquals("Persistent core characters count", 2, loadedCore?.characters?.size)

    // Simulate hostile escalation during episode 1 (ephemeral RAM override)
    val ephemeralReg = coreReg.copy(
        relationships = listOf(
            CharacterRelationship(pair = listOf("Walter White", "Jesse Pinkman"), type = "hostile", isEphemeral = true)
        )
    )
    CharacterProfileManager.saveProfile(null, "Breaking Bad", ephemeralReg, isEphemeral = true)
    val activeEp1 = CharacterProfileManager.getProfile(null, "Breaking Bad")
    assertEquals("Active episode sees ephemeral hostile override", "hostile", activeEp1?.relationships?.first()?.type)

    // Simulate episode change: resetEpisodicState()
    CharacterProfileManager.resetEpisodicState("Breaking Bad")
    val activeEp2 = CharacterProfileManager.getProfile(null, "Breaking Bad")
    assertEquals("Episode 2 reverts cleanly to persistent master_disciple profile (zero contamination)", "master_disciple", activeEp2?.relationships?.first()?.type)

    // -------------------------------------------------------------------------
    // TEST 6: Coroutine Pacing Governor Monotonic Rate Limiting
    // -------------------------------------------------------------------------
    println("\n--- [Test 6: Coroutine PacingGovernor Rate Limiting] ---")
    val governor = PacingGovernor(minIntervalMs = 100L) // 100ms test interval
    val t0 = PacingGovernor.getMonotonicTimeMs()
    governor.pace()
    val t1 = PacingGovernor.getMonotonicTimeMs()
    governor.pace()
    val t2 = PacingGovernor.getMonotonicTimeMs()

    assertTrue("Monotonic time is non-negative and non-decreasing", t1 >= t0 && t2 >= t1)
    assertTrue("Second pace call was delayed by at least minIntervalMs (elapsed: ${t2 - t1}ms)", (t2 - t1) >= 90L)

    // -------------------------------------------------------------------------
    // TEST 7: Dynamic Pause-Aware Chunking & Stichomythia Cap
    // -------------------------------------------------------------------------
    println("\n--- [Test 7: Dynamic Pause-Aware Chunking & Stichomythia Cap] ---")
    val dynamicCues = mutableListOf<SubtitleCue>()
    var currentTime = 1000L

    // Generate 35 continuous rapid-fire cues (stichomythia) with no pauses > 1800ms
    for (i in 1..35) {
        dynamicCues.add(
            SubtitleCue(
                index = i,
                startMs = currentTime,
                endMs = currentTime + 1000L,
                rawText = "Fast cue number $i."
            )
        )
        currentTime += 1200L // only 200ms pause
    }

    val stichomythiaChunks = AITranslationSession.splitIntoDynamicChunks(dynamicCues, minCues = 18, maxCues = 28, pauseThresholdMs = 1800L)
    assertEquals("Stichomythia split into 2 chunks", 2, stichomythiaChunks.size)
    assertEquals("Chunk 1 strictly capped at maxChunkCues = 28", 28, stichomythiaChunks[0].size)
    assertEquals("Chunk 2 contains remaining 7 cues", 7, stichomythiaChunks[1].size)

    // Generate cues with natural pause of 2500ms at cue 20
    val sceneCues = mutableListOf<SubtitleCue>()
    currentTime = 1000L
    for (i in 1..30) {
        val pauseAfter = if (i == 20) 2500L else 300L
        sceneCues.add(
            SubtitleCue(
                index = i,
                startMs = currentTime,
                endMs = currentTime + 1000L,
                rawText = "Scene cue $i."
            )
        )
        currentTime += (1000L + pauseAfter)
    }

    val sceneChunks = AITranslationSession.splitIntoDynamicChunks(sceneCues, minCues = 18, maxCues = 28, pauseThresholdMs = 1800L)
    assertEquals("Scene split at natural pause into 2 chunks", 2, sceneChunks.size)
    assertEquals("Chunk 1 cleanly cut at cue 20 due to pause", 20, sceneChunks[0].size)
    assertEquals("Chunk 2 contains remaining 10 cues", 10, sceneChunks[1].size)

    // -------------------------------------------------------------------------
    // TEST 8: Multilingual Universal Prompts & Schema Detection
    // -------------------------------------------------------------------------
    println("\n--- [Test 8: Multilingual Prompts & Schema Detection] ---")
    val promptVi = AITranslatorPromptBuilder.buildSystemPrompt("vi", registry, null)
    assertTrue("Vietnamese prompt includes kinship directives", promptVi.contains("Thứ bậc dòng tộc"))
    assertTrue("Vietnamese prompt includes Dyadic Reciprocity", promptVi.contains("Bất biến Nghịch đảo Xưng hô"))
    assertTrue("Vietnamese prompt includes Character Roster", promptVi.contains("Arthur"))
    assertTrue("Vietnamese prompt includes School/peer rules", promptVi.contains("Bạn bè học đường"))
    assertTrue("Vietnamese prompt includes Internal Monologue rules", promptVi.contains("Độc thoại nội tâm"))
    assertTrue("Vietnamese prompt includes relaxed tôi - bạn rules", promptVi.contains("Quy tắc 'tôi - bạn'"))
    assertTrue("Prompt disambiguation override has NO character roster reference", !promptVi.contains("or character roster"))
    assertTrue("Vietnamese prompt includes 3-Tier Hierarchy of Truth", promptVi.contains("3-TIER HIERARCHY OF TRUTH") && promptVi.contains("Tier 1"))
    assertTrue("Vietnamese prompt includes Pragmatic Dialogue vs Monologue matrix", promptVi.contains("Ma trận Nhận diện Hành vi Ngôn ngữ"))
    assertTrue("Vietnamese prompt includes Observational Thoughts directive", promptVi.contains("Suy nghĩ quan sát về người khác"))
    assertTrue("Vietnamese prompt includes Universal Hierarchy Lock", promptVi.contains("Khóa thứ bậc hai chiều"))
    assertTrue("Prompt output schema enforces lean {id, text} and forbids 'act'/'role'", promptVi.contains("{\"translations\":[{\"id\":<int>,\"text\":\"<string>\"}]}") && promptVi.contains("DO NOT output 'act', 'role'"))

    val schoolContext = MediaContext(title = "Horimiya", tags = listOf("Romance", "School", "Comedy"))
    val schoolPrompt = AITranslatorPromptBuilder.buildSystemPrompt("vi", registry, null, schoolContext)
    assertTrue("School genre prompt includes school directives", schoolPrompt.contains("Bối cảnh học đường"))

    val periodContext = MediaContext(title = "Kenshin", tags = listOf("Action", "Historical", "Samurai"))
    val periodPrompt = AITranslatorPromptBuilder.buildSystemPrompt("vi", registry, null, periodContext)
    assertTrue("Period genre prompt includes wuxia directives", periodPrompt.contains("Bối cảnh cổ trang"))

    val promptJa = AITranslatorPromptBuilder.getLanguageDirectives("ja")
    assertTrue("Japanese directives include Zero Anaphora and Benefactives", promptJa.contains("Zero Anaphora") && promptJa.contains("-te ageru"))
    assertTrue("Japanese directives include Uchi/Soto Keigo", promptJa.contains("Uchi/Soto") && promptJa.contains("Kenjougo"))

    val promptPl = AITranslatorPromptBuilder.getLanguageDirectives("pl")
    assertTrue("Polish directives include Pan/Pani 3rd person singular rule", promptPl.contains("THIRD PERSON SINGULAR") && promptPl.contains("Pani to widziała"))

    val promptFr = AITranslatorPromptBuilder.getLanguageDirectives("fr")
    assertTrue("French directives include COD vs COI agreement", promptFr.contains("COD") && promptFr.contains("Elle s'est lavé les mains"))

    val promptDe = AITranslatorPromptBuilder.getLanguageDirectives("de")
    assertTrue("German directives include predicate adjective invariance", promptDe.contains("REMAIN UNINFLECTED") && promptDe.contains("Sie ist müde"))

    // Bootstrap check
    val isBootstrap0 = AITranslatorPromptBuilder.isBootstrap(emptyList(), null)
    assertTrue("Chunk 0 with empty lookbehind and null registry is Bootstrap", isBootstrap0)
    val isBootstrap1 = AITranslatorPromptBuilder.isBootstrap(listOf(ContextCue(1, "A", "Hi", "Chào")), registry)
    assertTrue("Chunk 1 with lookbehind is NOT Bootstrap", !isBootstrap1)

    // -------------------------------------------------------------------------
    // TEST 9: Bootstrap Discovered Characters & Relationships Parser
    // -------------------------------------------------------------------------
    println("\n--- [Test 9: Bootstrap Discovered Characters & Relationships Parser] ---")
    val bootstrapJson = """
        {
          "translations": [
            {"id": 1, "text": "Xin chào sếp."},
            {"id": 2, "text": "Chào Elena."}
          ],
          "discovered_characters": [
            {"name": "Arthur Vance", "gender": "male", "role": "Managing Director", "age_group": "adult"},
            {"name": "Elena Rostova", "gender": "female", "role": "Senior Analyst", "age_group": "adult"}
          ],
          "discovered_relationships": [
            {
              "speaker": "Arthur Vance",
              "target": "Elena Rostova",
              "relation": "workplace",
              "address": "anh - em"
            }
          ]
        }
    """.trimIndent()

    val parsedTrans = AITranslationParser.parseTranslations(bootstrapJson)
    assertEquals("Parsed translations count", 2, parsedTrans.size)
    assertEquals("Cue 1 translation", "Xin chào sếp.", parsedTrans[1])

    val discoveredChars = AITranslationParser.parseDiscoveredCharacters(bootstrapJson)
    assertEquals("Parsed discovered characters count", 2, discoveredChars.size)
    assertEquals("Discovered character 1 name", "Arthur Vance", discoveredChars[0].name)
    assertEquals("Discovered character 1 gender", "male", discoveredChars[0].gender)
    assertEquals("Discovered character 2 name", "Elena Rostova", discoveredChars[1].name)
    assertEquals("Discovered character 2 gender", "female", discoveredChars[1].gender)

    val discoveredRels = AITranslationSession.parseDiscoveredRelationships(bootstrapJson)
    assertEquals("Discovered relationships count", 1, discoveredRels.size)
    assertEquals("Relationship speaker", "Arthur Vance", discoveredRels[0].addressMatrix["speaker"])
    assertEquals("Relationship target", "Elena Rostova", discoveredRels[0].addressMatrix["target"])
    assertEquals("Relationship address", "anh - em", discoveredRels[0].addressMatrix["address"])
    assertEquals("Relationship pair", listOf("Arthur Vance", "Elena Rostova"), discoveredRels[0].pair)

    // Subtest 9.2: SubtitleItem Deserialization & Parser with act and role
    val actRoleJson = """
        {
          "translations": [
            {"id": 1, "act": "dialogue", "role": "junior", "text": "Dạ em chào sếp ạ."},
            {"id": 2, "act": "monologue", "role": "solo", "text": "Mình phải cố gắng hoàn thành việc này."}
          ]
        }
    """.trimIndent()
    val parsedActRole = AITranslationParser.parseTranslations(actRoleJson)
    assertEquals("Parsed translations with act/role count", 2, parsedActRole.size)
    assertEquals("Cue 1 with act/role", "Dạ em chào sếp ạ.", parsedActRole[1])
    assertEquals("Cue 2 with act/role", "Mình phải cố gắng hoàn thành việc này.", parsedActRole[2])

    val itemTestJson = """{"id": 1, "act": "dialogue", "role": "senior", "text": "Chào em."}"""
    val subItem = mapper.readValue(itemTestJson, SubtitleItem::class.java)
    assertEquals("SubtitleItem id parsed", 1, subItem.id)
    assertEquals("SubtitleItem act parsed", "dialogue", subItem.act)
    assertEquals("SubtitleItem role parsed", "senior", subItem.role)
    assertEquals("SubtitleItem text parsed", "Chào em.", subItem.text)

    // -------------------------------------------------------------------------
    // TEST 10: Configuration Keys Persistence & Defaults
    // -------------------------------------------------------------------------
    println("\n--- [Test 10: TranslatorConfig Keys & Defaults] ---")
    TranslatorConfig.resetToDefaults(null)
    assertEquals("enableCharacterProfiling default", true, TranslatorConfig.enableCharacterProfiling)
    assertEquals("contextWindowSize default", 12, TranslatorConfig.contextWindowSize)
    assertEquals("dynamicScenePauseMs default", 1800L, TranslatorConfig.dynamicScenePauseMs)
    assertEquals("maxChunkCues default", 45, TranslatorConfig.maxChunkCues)
    assertEquals("highSpeedMode default", false, TranslatorConfig.highSpeedMode)

    // -------------------------------------------------------------------------
    // TEST 11: AniList Title Sanitizer & Fact-Only Character Entity Model
    // -------------------------------------------------------------------------
    println("\n--- [Test 11: AniList Title Sanitizer & Fact-Only Character Entity Model] ---")
    val rawTorrent1 = "[Judas] Kyouran Reijou Nia Liston - 07 [1080p][HEVC x265 10bit][Multi-Subs].mkv"
    val cleaned1 = AniListCastResolver.cleanAnimeTitle(rawTorrent1)
    assertEquals("Cleaned Nia Liston title", "Kyouran Reijou Nia Liston", cleaned1)

    val rawTorrent2 = "[SubsPlease] Sousou no Frieren - S01E12 (1080p) [720p].mp4"
    val cleaned2 = AniListCastResolver.cleanAnimeTitle(rawTorrent2)
    assertEquals("Cleaned Frieren title", "Sousou no Frieren", cleaned2)

    val rawTorrent3 = "Sekai Saikyou no Majo Episode 05 - 1080p.mkv"
    val cleaned3 = AniListCastResolver.cleanAnimeTitle(rawTorrent3)
    assertEquals("Cleaned Witch title", "Sekai Saikyou no Majo", cleaned3)

    // Verify Ground-Truth Fact Character Entity Model (viPronounHint is null, no dead labels)
    val entityFemaleMain = CharacterEntity(name = "Nia Liston", gender = "Female", role = "MAIN")
    assertEquals("Female MAIN name", "Nia Liston", entityFemaleMain.name)
    assertEquals("Female MAIN gender", "Female", entityFemaleMain.gender)
    assertEquals("Female MAIN role", "MAIN", entityFemaleMain.role)
    assertEquals("viPronounHint is null by default", null, entityFemaleMain.viPronounHint)

    val pFemaleMain = AniListCastResolver.inferVietnamesePronounHint("Female", "MAIN", "Nia Liston")
    assertEquals("Female MAIN maps to null", null, pFemaleMain)

    val pFemaleSupporting = AniListCastResolver.inferVietnamesePronounHint("Female", "SUPPORTING", "Lynette Blanc")
    assertEquals("Female SUPPORTING maps to null", null, pFemaleSupporting)

    val pMaidAttendant = AniListCastResolver.inferVietnamesePronounHint("Female", "SUPPORTING", "Lynokis Funk (Maid)")
    assertEquals("Maid attendant maps to null", null, pMaidAttendant)

    val pMaleMain = AniListCastResolver.inferVietnamesePronounHint("Male", "MAIN", "Arthur Pendragon")
    assertEquals("Male MAIN maps to null", null, pMaleMain)

    val pMaleSupporting = AniListCastResolver.inferVietnamesePronounHint("Male", "SUPPORTING", "Neil Liston")
    assertEquals("Male SUPPORTING maps to null", null, pMaleSupporting)

    val pUnknown = AniListCastResolver.inferVietnamesePronounHint("unknown", "BACKGROUND", "Guard A")
    assertEquals("Unknown/Neutral maps to null", null, pUnknown)

    // -------------------------------------------------------------------------
    // TEST 12: Dynamic Discovery Ephemeral RAM Scoping & Disk Isolation
    // -------------------------------------------------------------------------
    println("\n--- [Test 12: Ephemeral RAM Scoping & Disk Isolation] ---")
    val testBaseDir = File.createTempFile("m2_profile_test_", "").apply { delete(); mkdirs() }
    testBaseDir.deleteOnExit()
    CharacterProfileManager.customBaseDir = testBaseDir

    val persistentCast = TwoTierCharacterRegistry(
        mediaTitle = "Kyouran Reijou Nia Liston",
        characters = listOf(
            CharacterEntity(name = "Nia Liston", gender = "Female", role = "MAIN", viPronounHint = null, isEphemeral = false),
            CharacterEntity(name = "Neil Liston", gender = "Male", role = "SUPPORTING", viPronounHint = null, isEphemeral = false)
        )
    )
    CharacterProfileManager.saveProfile(null, "Kyouran Reijou Nia Liston", persistentCast, isEphemeral = false)

    // Verify persistent cast is on disk and RAM
    val loadedPersistent = CharacterProfileManager.getProfile(null, "Kyouran Reijou Nia Liston")
    assertEquals("Persistent cast contains 2 characters", 2, loadedPersistent?.characters?.size)
    assertEquals("Nia viPronounHint is null", null, loadedPersistent?.characters?.first()?.viPronounHint)

    // Simulate Chunk 3 discovering a dynamic temporary speaker with isEphemeral = true
    val dynamicDiscovered = CharacterEntity(
        name = "Bandit Leader",
        gender = "Male",
        role = "Minor Antagonist",
        viPronounHint = null,
        isEphemeral = true
    )
    val episodicCast = persistentCast.copy(
        characters = persistentCast.characters + dynamicDiscovered
    )
    CharacterProfileManager.saveProfile(null, "Kyouran Reijou Nia Liston", episodicCast, isEphemeral = true)

    // Active episode RAM should see 3 characters
    val activeEpisodic = CharacterProfileManager.getProfile(null, "Kyouran Reijou Nia Liston")
    assertEquals("Active episodic RAM sees 3 characters", 3, activeEpisodic?.characters?.size)
    assertTrue("Active episodic RAM has Bandit Leader", activeEpisodic?.characters?.any { it.name == "Bandit Leader" } == true)

    // Verify disk storage does not contain ephemeral character
    val diskDir = File(testBaseDir, "ai_profiles")
    val diskFiles = diskDir.listFiles() ?: emptyArray()
    assertTrue("Disk profile file exists", diskFiles.isNotEmpty())
    val diskContent = diskFiles.first().readText(Charsets.UTF_8)
    assertTrue("Disk JSON does NOT contain Bandit Leader", !diskContent.contains("Bandit Leader"))

    // Simulate episode end / new episode: reset episodic state
    CharacterProfileManager.resetEpisodicState("Kyouran Reijou Nia Liston")
    val postReset = CharacterProfileManager.getProfile(null, "Kyouran Reijou Nia Liston")
    assertEquals("Post-reset profile reverts to persistent 2 characters", 2, postReset?.characters?.size)
    assertTrue("Post-reset does NOT contain Bandit Leader", postReset?.characters?.none { it.name == "Bandit Leader" } == true)

    // -------------------------------------------------------------------------
    // TEST 13: Japanese Yakuwarigo Pragmatics, Kitsunekko Parser Healing & Interjection Recovery
    // -------------------------------------------------------------------------
    println("\n--- [Test 13: Japanese Yakuwarigo Pragmatics & Kitsunekko Parser Healing] ---")

    // 13.1. SubtitleParser flexible timecodes: fullwidth whitespace, arrows ->, ~, centiseconds
    val srtGluedContent = """
        1
        00:01:23,45　->　00:01:25,60
        俺の勝ちだぜ！

        2
        00:01:25,800 ~ 00:01:25,800
        「うん。」
    """.trimIndent()
    val parsedSrtGlued = SubtitleParser.parse(srtGluedContent)
    assertEquals("Extracted 2 cues from glued/flexible SRT", 2, parsedSrtGlued.size)
    assertEquals("Cue 1 startMs", 83450L, parsedSrtGlued[0].startMs)
    assertEquals("Cue 1 endMs", 85600L, parsedSrtGlued[0].endMs)
    assertEquals("Cue 2 auto-healed +1500ms when endMs <= startMs", 85800L + 1500L, parsedSrtGlued[1].endMs)

    // 13.2. SubtitleParser ASS auto-healing & vector drawing stripping
    val assContent = """
        [Script Info]
        Title: Kitsunekko Test

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:01:10.00,0:01:10.00,Default,,0,0,0,,{\pos(192,250)}わしの番じゃ。
        Dialogue: 0,0:01:12.00,0:01:15.50,Default,,0,0,0,,{\p1}m 0 0 l 100 0 l 100 100{\p0}{\an8}待って！
    """.trimIndent()
    val parsedAss = SubtitleParser.parse(assContent)
    assertEquals("Extracted 2 ASS cues", 2, parsedAss.size)
    assertEquals("ASS Cue 1 auto-healed +1500ms", 70000L + 1500L, parsedAss[0].endMs)
    assertTrue("ASS Cue 1 preserves pos tag shielded", parsedAss[0].maskedText.contains("[[TAG_1]]"))
    assertTrue("ASS Cue 1 raw dialogue retained", parsedAss[0].rawText.contains("わしの番じゃ。"))
    assertEquals("ASS Cue 2 stripped vector drawing", "{\\an8}待って！", parsedAss[1].rawText)
    assertTrue("ASS Cue 2 preserves an8 tag shielded", parsedAss[1].maskedText.contains("[[TAG_1]]"))

    // 13.3. AITranslatorPromptBuilder Japanese script detection
    assertTrue("isJapaneseScript detects Hiragana", AITranslatorPromptBuilder.isJapaneseScript("こんにちは"))
    assertTrue("isJapaneseScript detects Katakana", AITranslatorPromptBuilder.isJapaneseScript("テスト"))
    assertTrue("isJapaneseScript detects Kanji", AITranslatorPromptBuilder.isJapaneseScript("日本語"))
    assertFalse("isJapaneseScript returns false for English", AITranslatorPromptBuilder.isJapaneseScript("Hello world"))
    assertFalse("isJapaneseScript returns false for null/empty", AITranslatorPromptBuilder.isJapaneseScript(""))

    // 13.4. Yakuwarigo linguistic directives in System Prompt
    val promptJaSource = AITranslatorPromptBuilder.buildSystemPrompt("vi", null, null, sourceLang = "ja")
    assertTrue("Prompt contains Section 2.1 header", promptJaSource.contains("### 2.1. SOURCE LANGUAGE (JAPANESE) YAKUWARIGO & RETENTION DIRECTIVES:"))
    assertTrue("Prompt contains Mandatory 1-to-1 Cue Retention", promptJaSource.contains("MANDATORY 1-TO-1 CUE RETENTION"))
    assertTrue("Prompt contains Ore pronoun mapping", promptJaSource.contains("俺 (Ore)"))
    assertTrue("Prompt contains Boku pronoun mapping", promptJaSource.contains("僕 (Boku)"))
    assertTrue("Prompt contains Washi elder mapping", promptJaSource.contains("わし (Washi)"))
    assertTrue("Prompt contains Atashi female mapping", promptJaSource.contains("あたし (Atashi)"))
    assertTrue("Prompt contains sentence endings da ze / ssu", promptJaSource.contains("〜だぜ") && promptJaSource.contains("〜っす"))
    assertTrue("Prompt contains Japanese Internal Monologue markers", promptJaSource.contains("〜だろうか") && promptJaSource.contains("〜のか"))

    // Auto-detection via sampleText
    val promptDetectedJa = AITranslatorPromptBuilder.buildSystemPrompt("vi", null, null, sampleText = "わしの番じゃ。")
    assertTrue("Prompt injected Yakuwarigo via sampleText Japanese detection", promptDetectedJa.contains("YAKUWARIGO LINGUISTIC MATRIX"))

    // Non-Japanese sourceLang should omit Section 2.1
    val promptEnSource = AITranslatorPromptBuilder.buildSystemPrompt("vi", null, null, sourceLang = "en", sampleText = "Good morning.")
    assertFalse("English prompt omits Section 2.1", promptEnSource.contains("2.1. SOURCE LANGUAGE (JAPANESE) YAKUWARIGO"))

    // 13.5. SubtitleReconciler Micro-Healing of Japanese Interjections
    assertEquals("healJapaneseInterjection un", "Ừ", SubtitleReconciler.healJapaneseInterjection("うん"))
    assertEquals("healJapaneseInterjection with quotes", "Vâng", SubtitleReconciler.healJapaneseInterjection("「はい」"))
    assertEquals("healJapaneseInterjection with Japanese ideographic period", "Vâng", SubtitleReconciler.healJapaneseInterjection("はい。"))
    assertEquals("healJapaneseInterjection with punctuation and quotes", "Hả?", SubtitleReconciler.healJapaneseInterjection("「えっ！？」"))
    assertEquals("healJapaneseInterjection uso", "Không thể nào...", SubtitleReconciler.healJapaneseInterjection("嘘。"))
    assertEquals("healJapaneseInterjection arigatou", "Cảm ơn.", SubtitleReconciler.healJapaneseInterjection("ありがとう"))
    assertEquals("healJapaneseInterjection gomen", "Xin lỗi.", SubtitleReconciler.healJapaneseInterjection("ごめん"))
    assertEquals("healJapaneseInterjection non-interjection returns null", null, SubtitleReconciler.healJapaneseInterjection("こんにちは世界"))

    // SubtitleReconciler autoHealingReconcile recovering dropped interjection
    val testCues = listOf(
        SubtitleCue(index = 1, startMs = 1000L, endMs = 2000L, rawText = "俺の番だ！"),
        SubtitleCue(index = 2, startMs = 2100L, endMs = 3000L, rawText = "「うん。」"),
        SubtitleCue(index = 3, startMs = 3100L, endMs = 4000L, rawText = "行くぞ！")
    )
    val llmOmittedOutput = mapOf(
        1 to "Đến lượt tao rồi!",
        // Cue 2 was dropped by LLM
        3 to "Đi thôi!"
    )
    val healedResult = SubtitleReconciler.autoHealingReconcile(testCues, llmOmittedOutput)
    assertEquals("Healed result has all 3 cues", 3, healedResult.size)
    assertEquals("Cue 1 translation", "Đến lượt tao rồi!", healedResult[1])
    assertEquals("Cue 2 micro-healed interjection", "Ừ", healedResult[2])
    assertEquals("Cue 3 translation", "Đi thôi!", healedResult[3])

    // -------------------------------------------------------------------------
    // TEST 14: M1 v11 Rich Input Context, Lean Clients, Cancellable OkHttp & Film Bible
    // -------------------------------------------------------------------------
    println("\n--- [Test 14: M1 v11 Rich Context, Lean Clients, Cancellation & Film Bible] ---")

    // 14.1 Gemini: omitted thinkingConfig, 8192 maxOutputTokens, {id,text} schema, non-thought parts filter, single-attempt 429 RPM, generateJsonPrompt
    val capturedGeminiBody = AtomicReference<String>("")
    val mockGeminiThinkClient = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        val buf = Buffer()
        req.body?.writeTo(buf)
        capturedGeminiBody.set(buf.readUtf8())
        val respJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"thought": true, "text": "Let me analyze the kinship relationship first..."},
                      {"text": "{\"translations\":[{\"id\":1,\"text\":\"Đừng ra đó, bố ơi.\"}]}"}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(respJson.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val geminiV11 = GeminiTranslatorClient(apiKey = "test-gem", httpClient = mockGeminiThinkClient)
    val gemRes = geminiV11.translateChunkWithSceneDigest(
        cues = listOf(SubtitleCue(index = 1, startMs = 1000L, endMs = 3000L, rawText = "Don't go out there, Dad.")),
        targetLang = "vi",
        rollingSceneDigest = "Charlie and Max in the workshop"
    )
    assertTrue("Gemini v11 filters thought=true part and parses translation", gemRes.isSuccess && gemRes.translations[1] == "Đừng ra đó, bố ơi.")
    val gemReqTree = mapper.readTree(capturedGeminiBody.get())
    val gemGenCfg = gemReqTree.get("generationConfig")
    assertFalse("Gemini omits thinkingConfig", capturedGeminiBody.get().contains("thinkingConfig") || capturedGeminiBody.get().contains("thinkingBudget") || capturedGeminiBody.get().contains("thinkingLevel"))
    assertEquals("Gemini maxOutputTokens is 8192", 8192, gemGenCfg?.get("maxOutputTokens")?.asInt())
    val gemSchemaProps = gemGenCfg?.get("responseSchema")?.get("properties")?.get("translations")?.get("items")?.get("properties")
    assertTrue("Gemini schema has id & text only (no act/role)", gemSchemaProps?.has("id") == true && gemSchemaProps.has("text") && !gemSchemaProps.has("act") && !gemSchemaProps.has("role"))
    val gemUserText = gemReqTree.get("contents")?.get(0)?.get("parts")?.get(0)?.get("text")?.asText() ?: ""
    assertTrue("Gemini passes rollingSceneDigest in user payload", gemUserText.contains("[ROLLING_SCENE_DIGEST]") && gemUserText.contains("Charlie and Max"))

    // 14.1b Gemini single-attempt on 429 RPM (no nested internal retry)
    val rpmCalls = AtomicInteger(0)
    val mockGeminiRpmClient = OkHttpClient.Builder().addInterceptor { chain ->
        rpmCalls.incrementAndGet()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .body("""{"error":{"code":429,"message":"Quota exceeded for GenerateRequestsPerMinute"}}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
    val geminiRpmRes = GeminiTranslatorClient("k", httpClient = mockGeminiRpmClient)
        .translateChunk(listOf(SubtitleCue(index = 1, startMs = 0L, endMs = 1000L, rawText = "Hi")), targetLang = "vi")
    assertEquals("Gemini executes single attempt per translateChunk call", 1, rpmCalls.get())
    assertTrue("Gemini 429 RPM returns success=false, retryable=true, isQuotaExhausted=false", !geminiRpmRes.isSuccess && geminiRpmRes.retryable && !geminiRpmRes.isQuotaExhausted)

    // 14.1c Gemini generateJsonPrompt (Pass 0)
    val mockGeminiJsonPromptClient = OkHttpClient.Builder().addInterceptor { chain ->
        val json = """{"candidates":[{"content":{"parts":[{"thought":true,"text":"thinking"},{"text":"{\"tone_guide\":\"Gritty boxing drama\"}"}]}}]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val jsonPromptOut = GeminiTranslatorClient("k", httpClient = mockGeminiJsonPromptClient).generateJsonPrompt("sys", "usr", timeoutSeconds = 5L)
    assertEquals("Gemini generateJsonPrompt filters thought and returns raw JSON", """{"tone_guide":"Gritty boxing drama"}""", jsonPromptOut)

    // 14.2 OpenAI: lean {id, text} schema, max_tokens 8192, generateJsonPrompt
    val capturedOpenAiBody = AtomicReference<String>("")
    val mockOpenAiV11Client = OkHttpClient.Builder().addInterceptor { chain ->
        val buf = Buffer()
        chain.request().body?.writeTo(buf)
        capturedOpenAiBody.set(buf.readUtf8())
        val respJson = """{"choices":[{"message":{"content":"{\"translations\":[{\"id\":1,\"text\":\"Ký hợp đồng tối nay.\"}]}"}}]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respJson.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val openAiV11 = OpenAITranslatorClient("sk-test", httpClient = mockOpenAiV11Client)
    val oaiRes = openAiV11.translateChunk(listOf(SubtitleCue(index = 1, startMs = 0L, endMs = 1000L, rawText = "Sign the deal tonight.")), targetLang = "vi")
    assertTrue("OpenAI v11 parses lean translation", oaiRes.isSuccess && oaiRes.translations[1] == "Ký hợp đồng tối nay.")
    val oaiTree = mapper.readTree(capturedOpenAiBody.get())
    assertEquals("OpenAI max_tokens is 8192", 8192, oaiTree.get("max_tokens")?.asInt())
    val oaiProps = oaiTree.get("response_format")?.get("json_schema")?.get("schema")?.get("properties")?.get("translations")?.get("items")?.get("properties")
    assertTrue("OpenAI schema has id & text only (no act/role)", oaiProps?.has("id") == true && oaiProps.has("text") && !oaiProps.has("act") && !oaiProps.has("role"))
    assertFalse("OpenAI schema omits discovered_characters", oaiTree.get("response_format")?.get("json_schema")?.get("schema")?.get("properties")?.has("discovered_characters") == true)

    // 14.3 Anthropic: unified prefill, max_tokens 8192, conditional cache_control, both ] and ]} reconstruction, isCreditExhaustion
    val capturedAnthropicOfficial = AtomicReference<String>("")
    val mockAnthropicOfficial = OkHttpClient.Builder().addInterceptor { chain ->
        val buf = Buffer()
        chain.request().body?.writeTo(buf)
        capturedAnthropicOfficial.set(buf.readUtf8())
        val respJson = """{"content":[{"type":"text","text":"{\"id\":1,\"text\":\"Nói nhỏ thôi.\"}]}"}]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respJson.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val anthRes = AnthropicTranslatorClient("ant-key", httpClient = mockAnthropicOfficial)
        .translateChunk(listOf(SubtitleCue(index = 1, startMs = 0L, endMs = 1000L, rawText = "Keep your voice down.")), targetLang = "vi")
    assertTrue("Anthropic unified prefill + ]} continuation parses", anthRes.isSuccess && anthRes.translations[1] == "Nói nhỏ thôi.")
    val anthTree = mapper.readTree(capturedAnthropicOfficial.get())
    assertEquals("Anthropic max_tokens is 8192", 8192, anthTree.get("max_tokens")?.asInt())
    assertTrue("Anthropic official URL uses cache_control array system", anthTree.get("system")?.isArray == true && anthTree.get("system")?.get(0)?.has("cache_control") == true)
    assertEquals("Anthropic assistant prefill is unified", "{\"translations\": [", anthTree.get("messages")?.get(1)?.get("content")?.asText())
    assertTrue("AnthropicTranslatorClient.isCreditExhaustion restored", AnthropicTranslatorClient.isCreditExhaustion(400, """{"error":{"message":"Your credit balance is too low"}}"""))

    val capturedAnthropicProxy = AtomicReference<String>("")
    val mockAnthropicProxy = OkHttpClient.Builder().addInterceptor { chain ->
        val buf = Buffer()
        chain.request().body?.writeTo(buf)
        capturedAnthropicProxy.set(buf.readUtf8())
        val respJson = """{"content":[{"type":"text","text":"{\"id\":1,\"text\":\"Chào anh\"}]"}]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respJson.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val anthProxyRes = AnthropicTranslatorClient("ant-key", baseUrl = "https://proxy.example.com", httpClient = mockAnthropicProxy)
        .translateChunk(listOf(SubtitleCue(index = 1, startMs = 0L, endMs = 1000L, rawText = "Hi")), targetLang = "vi")
    assertTrue("Anthropic custom proxy plain string system & ] continuation parses", anthProxyRes.isSuccess && anthProxyRes.translations[1] == "Chào anh")
    assertTrue("Anthropic custom proxy sends plain string system", mapper.readTree(capturedAnthropicProxy.get()).get("system")?.isTextual == true)

    // 14.4 0ms Cancellable OkHttp socket interruption via suspendCancellableCoroutine
    val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val acceptedLatch = CountDownLatch(1)
    val serverThread = Thread {
        try {
            val sock = serverSocket.accept()
            acceptedLatch.countDown()
            Thread.sleep(5000)
            sock.close()
        } catch (_: Throwable) {}
    }.apply { isDaemon = true; start() }

    val longTimeoutClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()
    val cancellableGemini = GeminiTranslatorClient(
        apiKey = "k",
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}",
        httpClient = longTimeoutClient
    )
    val sawCancellation = AtomicBoolean(false)
    val job = launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            cancellableGemini.translateChunk(listOf(SubtitleCue(index = 1, startMs = 0L, endMs = 1000L, rawText = "Hold socket")), targetLang = "vi")
        } catch (ce: CancellationException) {
            sawCancellation.set(true)
            throw ce
        }
    }
    var waitedMs = 0L
    while (!acceptedLatch.await(10, TimeUnit.MILLISECONDS) && waitedMs < 3000L) {
        delay(10)
        waitedMs += 10
    }
    assertTrue("Mock server accepted socket connection before cancel", acceptedLatch.count == 0L)
    delay(40)
    val tCancelStart = System.currentTimeMillis()
    job.cancelAndJoin()
    val cancelElapsed = System.currentTimeMillis() - tCancelStart
    try { serverSocket.close() } catch (_: Throwable) {}
    serverThread.interrupt()
    assertTrue("0ms OkHttp cancellation completed rapidly (< 500ms, took ${cancelElapsed}ms)", cancelElapsed < 500L)
    assertTrue("CancellationException propagated cleanly without being swallowed", sawCancellation.get())

    // 14.4b OkHttp callTimeout returns null / retryable TranslationResult (NOT CancellationException) + null content handling
    val timeoutSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val timeoutServerThread = Thread {
        while (!timeoutSocket.isClosed) {
            try {
                val sock = timeoutSocket.accept()
                Thread {
                    try {
                        Thread.sleep(5000)
                        sock.close()
                    } catch (_: Throwable) {}
                }.apply { isDaemon = true; start() }
            } catch (_: Throwable) {
                break
            }
        }
    }.apply { isDaemon = true; start() }
    val stalledBaseUrl = "http://127.0.0.1:${timeoutSocket.localPort}"

    var gemTimeoutThrewCancel = false
    val gemTimeoutRes = try {
        GeminiTranslatorClient("k", baseUrl = stalledBaseUrl).generateJsonPrompt("sys", "usr", timeoutSeconds = 1L)
    } catch (_: CancellationException) {
        gemTimeoutThrewCancel = true
        "THREW"
    }
    assertTrue("Gemini generateJsonPrompt callTimeout returns null without CancellationException", !gemTimeoutThrewCancel && gemTimeoutRes == null)

    var oaiTimeoutThrewCancel = false
    val oaiTimeoutRes = try {
        OpenAITranslatorClient("sk", baseUrl = stalledBaseUrl).generateJsonPrompt("sys", "usr", timeoutSeconds = 1L)
    } catch (_: CancellationException) {
        oaiTimeoutThrewCancel = true
        "THREW"
    }
    assertTrue("OpenAI generateJsonPrompt callTimeout returns null without CancellationException", !oaiTimeoutThrewCancel && oaiTimeoutRes == null)

    var anthTimeoutThrewCancel = false
    val anthTimeoutRes = try {
        AnthropicTranslatorClient("ant", baseUrl = stalledBaseUrl).generateJsonPrompt("sys", "usr", timeoutSeconds = 1L)
    } catch (_: CancellationException) {
        anthTimeoutThrewCancel = true
        "THREW"
    }
    assertTrue("Anthropic generateJsonPrompt callTimeout returns null without CancellationException", !anthTimeoutThrewCancel && anthTimeoutRes == null)

    val shortCallClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(1, TimeUnit.SECONDS)
        .build()
    var gemChunkThrewCancel = false
    val gemChunkTimeoutRes = try {
        GeminiTranslatorClient("k", baseUrl = stalledBaseUrl, httpClient = shortCallClient)
            .translateChunk(listOf(SubtitleCue(index = 1, startMs = 0L, endMs = 1000L, rawText = "Timeout test")), targetLang = "vi")
    } catch (_: CancellationException) {
        gemChunkThrewCancel = true
        null
    }
    try { timeoutSocket.close() } catch (_: Throwable) {}
    timeoutServerThread.interrupt()
    assertTrue(
        "Gemini translateChunk callTimeout returns success=false, retryable=true without CancellationException",
        !gemChunkThrewCancel && gemChunkTimeoutRes != null && !gemChunkTimeoutRes.isSuccess && gemChunkTimeoutRes.retryable
    )

    val openAiNullContentClient = OkHttpClient.Builder().addInterceptor { chain ->
        val respJson = """{"choices":[{"message":{"content":null,"refusal":"Sorry"}}]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respJson.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val oaiNullContentOut = OpenAITranslatorClient("sk", httpClient = openAiNullContentClient)
        .generateJsonPrompt("sys", "usr", timeoutSeconds = 5L)
    assertEquals("OpenAI generateJsonPrompt on content:null returns null", null, oaiNullContentOut)

    val anthropicNullTextClient = OkHttpClient.Builder().addInterceptor { chain ->
        val respJson = """{"content":[{"type":"text","text":null}]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respJson.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val anthNullTextOut = AnthropicTranslatorClient("ant", httpClient = anthropicNullTextClient)
        .generateJsonPrompt("sys", "usr", timeoutSeconds = 5L)
    assertEquals("Anthropic generateJsonPrompt on text:null returns null", null, anthNullTextOut)

    val geminiEmptyCandidatesClient = OkHttpClient.Builder().addInterceptor { chain ->
        val respJson = """{"candidates":[]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respJson.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val gemEmptyClient = GeminiTranslatorClient("gem", httpClient = geminiEmptyCandidatesClient)
    val gemEmptyPromptOut = gemEmptyClient.generateJsonPrompt("sys", "usr", timeoutSeconds = 5L)
    assertEquals("Gemini generateJsonPrompt on candidates:[] returns null", null, gemEmptyPromptOut)
    val gemEmptyChunkOut = gemEmptyClient.translateChunk(
        listOf(SubtitleCue(index = 1, startMs = 0L, endMs = 1000L, rawText = "Hello")),
        targetLang = "vi"
    )
    assertTrue(
        "Gemini translateChunk on candidates:[] yields empty translations without rawResponse=null or envelope",
        gemEmptyChunkOut.translations.isEmpty() && gemEmptyChunkOut.rawResponse == "" && gemEmptyChunkOut.rawResponse != "null"
    )

    val geminiNullTextClient = OkHttpClient.Builder().addInterceptor { chain ->
        val respJson = """{"candidates":[{"content":{"parts":[{"text":null}]}}]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respJson.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val gemNullTextClient = GeminiTranslatorClient("gem", httpClient = geminiNullTextClient)
    val gemNullTextPromptOut = gemNullTextClient.generateJsonPrompt("sys", "usr", timeoutSeconds = 5L)
    assertEquals("Gemini generateJsonPrompt on parts[].text:null returns null", null, gemNullTextPromptOut)
    val gemNullTextChunkOut = gemNullTextClient.translateChunk(
        listOf(SubtitleCue(index = 1, startMs = 0L, endMs = 1000L, rawText = "Hello")),
        targetLang = "vi"
    )
    assertTrue(
        "Gemini translateChunk on parts[].text:null yields empty translations without rawResponse=null",
        gemNullTextChunkOut.translations.isEmpty() && gemNullTextChunkOut.rawResponse == "" && gemNullTextChunkOut.rawResponse != "null"
    )

    // 14.5 PacingGovernor highSpeedMode = true bypasses delay
    val fastGov = PacingGovernor(minIntervalMs = 5000L, highSpeedMode = true)
    val tf0 = PacingGovernor.getMonotonicTimeMs()
    fastGov.pace()
    fastGov.pace()
    val tf1 = PacingGovernor.getMonotonicTimeMs()
    assertTrue("PacingGovernor highSpeedMode bypasses 5000ms pacing (${tf1 - tf0}ms)", (tf1 - tf0) < 200L)

    // 14.6 AITranslationParser.parseFilmBible + truncated JSON safety invariant
    val filmBibleRaw = """
        Here is the film bible:
        ```json
        {
          "characters": [
            {"name": "Charlie Kenton", "aliases": ["Charlie"], "gender": "male", "age_group": "adult", "role": "Former boxer"},
            {"name": "Max Kenton", "aliases": ["Max"], "gender": "male", "age_group": "child", "role": "Charlie's son"}
          ],
          "relationships": [
            {"pair": ["Charlie Kenton", "Max Kenton"], "type": "family", "address_matrix": {"vi": {"Charlie_to_Max": "bố - con"}}}
          ],
          "auto_glossary": {"shadow-box": "đấm gió", "sparring bot": "robot tập đấu"},
          "tone_guide": "Gritty sports father-son drama"
        }
        ```
    """.trimIndent()
    val parsedBible = AITranslationParser.parseFilmBible(filmBibleRaw)
    assertTrue("parseFilmBible extracted 2 characters & 1 relationship", parsedBible != null && parsedBible.characters.size == 2 && parsedBible.relationships.size == 1)
    assertEquals("parseFilmBible auto_glossary", "đấm gió", parsedBible?.autoGlossary?.get("shadow-box"))
    assertEquals("parseFilmBible tone_guide", "Gritty sports father-son drama", parsedBible?.toneGuide)
    val bibleReg = parsedBible!!.toRegistry("Real Steel")
    assertEquals("GlobalFilmBible.toRegistry mediaTitle", "Real Steel", bibleReg.mediaTitle)
    assertTrue("Truncated JSON still returns emptyMap()", AITranslationParser.parseTranslations("""[{"id": 1, "text": "Incomplete""").isEmpty())

    val proseBracesFilmBible = """
        Using template {characters, relationships}:
        {"characters":[{"name":"Charlie Kenton","gender":"male"}],"tone_guide":"Gritty drama"}
    """.trimIndent()
    val parsedProseBraces = AITranslationParser.parseFilmBible(proseBracesFilmBible)
    assertTrue(
        "parseFilmBible parses JSON preceded by prose containing {braces}",
        parsedProseBraces != null && parsedProseBraces.characters.firstOrNull()?.name == "Charlie Kenton" && parsedProseBraces.toneGuide == "Gritty drama"
    )

    val emptyNullBible = AITranslationParser.parseFilmBible("""{"characters":[],"relationships":[],"auto_glossary":{},"tone_guide":null}""")
    assertEquals("parseFilmBible with empty fields and tone_guide:null returns null", null, emptyNullBible)

    val nullFieldsBible = AITranslationParser.parseFilmBible(
        """{"characters":[{"name":"Max","aliases":[null,"M"],"gender":null,"age_group":null,"role":null},{"name":null}],"relationships":[{"pair":[null,null],"type":null},{"pair":["Charlie","Max"],"type":null,"address_matrix":{"vi":{"Charlie_to_Max":"bố - con","bad":null}}}],"auto_glossary":{"term":null,"ring":"sàn đấu"},"tone_guide":null}"""
    )
    assertTrue("parseFilmBible preserves defaults & ignores null entries", nullFieldsBible != null &&
        nullFieldsBible.characters.size == 1 &&
        nullFieldsBible.characters[0].name == "Max" &&
        nullFieldsBible.characters[0].aliases == listOf("M") &&
        nullFieldsBible.characters[0].gender == "unknown" &&
        nullFieldsBible.characters[0].ageGroup == "adult" &&
        nullFieldsBible.characters[0].role == "" &&
        nullFieldsBible.relationships.size == 1 &&
        nullFieldsBible.relationships[0].pair == listOf("Charlie", "Max") &&
        nullFieldsBible.relationships[0].type == "peers" &&
        nullFieldsBible.autoGlossary == mapOf("ring" to "sàn đấu") &&
        nullFieldsBible.toneGuide == ""
    )
    val viMatrix = nullFieldsBible?.relationships?.get(0)?.addressMatrix?.get("vi") as? Map<*, *>
    assertTrue("parseFilmBible addressMatrix ignores null entries", viMatrix != null && viMatrix["Charlie_to_Max"] == "bố - con" && !viMatrix.containsKey("bad"))

    val parsedNullCue = AITranslationParser.parseTranslations("""{"translations":[{"id":1,"text":null},{"id":2,"text":"Chào"}]}""")
    assertEquals("parseTranslations omits text:null cue instead of literal null", mapOf(2 to "Chào"), parsedNullCue)

    println("\n================================================================================")
    println(">>> ALL 14 MILESTONE 2 TEST SUITES PASSED PERFECTLY!")
    println("================================================================================")
}
