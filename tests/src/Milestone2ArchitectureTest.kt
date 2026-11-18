package tests.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.client.AITranslatorPromptBuilder
import com.lagradost.cloudstream3.subtitles.ai.client.PacingGovernor
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.models.*
import com.lagradost.cloudstream3.subtitles.ai.parser.AITranslationParser
import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleParser
import com.lagradost.cloudstream3.subtitles.ai.profile.AniListCastResolver
import com.lagradost.cloudstream3.subtitles.ai.profile.CharacterProfileManager
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import com.lagradost.cloudstream3.subtitles.ai.session.DialogueContextWindow
import kotlinx.coroutines.runBlocking
import java.io.File

fun assertTrue(msg: String, condition: Boolean) {
    if (!condition) {
        System.err.println("  [FAIL] $msg")
        throw AssertionError("Assertion failed: $msg")
    }
    println("  [PASS] $msg")
}

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
    val contextWindow = DialogueContextWindow(lookbehindSize = 3, lookaheadSize = 2, maxLookbehindGapMs = 30000L)

    val cue1 = SubtitleCue(index = 1, startMs = 1000L, endMs = 4000L, rawText = "Hello", maskedText = "Hello", speaker = "A")
    val cue2 = SubtitleCue(index = 2, startMs = 5000L, endMs = 8000L, rawText = "Hi there", maskedText = "Hi there", speaker = "B")
    val cue3 = SubtitleCue(index = 3, startMs = 9000L, endMs = 12000L, rawText = "How are you?", maskedText = "How are you?", speaker = "A")

    contextWindow.recordFinalizedCues(listOf(cue1, cue2, cue3), mapOf(1 to "Xin chào", 2 to "Chào bạn", 3 to "Bạn khỏe không?"))

    // Lookbehind retrieval under normal continuous playback (gap < 30s, index gap == 1)
    val targetCuesContinuous = listOf(SubtitleCue(index = 4, startMs = 13000L, endMs = 16000L, rawText = "I am fine", maskedText = "I am fine", speaker = "B"))
    val lookbehind = contextWindow.getLookbehind(targetCuesContinuous)
    assertEquals("Lookbehind count matches lookbehindSize", 3, lookbehind.size)
    assertEquals("Lookbehind most recent item is Cue 3", 3, lookbehind.last().index)
    assertEquals("Lookbehind translated text preserved", "Bạn khỏe không?", lookbehind.last().translatedText)

    // Temporal Continuity Guard Trigger: Seek Jump > 30s
    val targetCuesSeekTime = listOf(SubtitleCue(index = 5, startMs = 80000L, endMs = 84000L, rawText = "Much later...", maskedText = "Much later..."))
    val lookbehindSeekTime = contextWindow.getLookbehind(targetCuesSeekTime)
    assertEquals("Temporal guard flushes history on time jump > 30s", 0, lookbehindSeekTime.size)

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
    // TEST 9: Bootstrap Discovered Characters Parser
    // -------------------------------------------------------------------------
    println("\n--- [Test 9: Bootstrap Discovered Characters Parser] ---")
    val bootstrapJson = """
        {
          "translations": [
            {"id": 1, "text": "Xin chào sếp."},
            {"id": 2, "text": "Chào Elena."}
          ],
          "discovered_characters": [
            {"name": "Arthur Vance", "gender": "male", "role": "Managing Director", "age_group": "adult"},
            {"name": "Elena Rostova", "gender": "female", "role": "Senior Analyst", "age_group": "adult"}
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

    // -------------------------------------------------------------------------
    // TEST 10: Configuration Keys Persistence & Defaults
    // -------------------------------------------------------------------------
    println("\n--- [Test 10: TranslatorConfig Keys & Defaults] ---")
    TranslatorConfig.resetToDefaults(null)
    assertEquals("enableCharacterProfiling default", true, TranslatorConfig.enableCharacterProfiling)
    assertEquals("contextWindowSize default", 6, TranslatorConfig.contextWindowSize)
    assertEquals("dynamicScenePauseMs default", 1800L, TranslatorConfig.dynamicScenePauseMs)
    assertEquals("maxChunkCues default", 28, TranslatorConfig.maxChunkCues)

    // -------------------------------------------------------------------------
    // TEST 11: AniList Title Sanitizer & Vietnamese Pronoun Rule Engine
    // -------------------------------------------------------------------------
    println("\n--- [Test 11: AniList Title Sanitizer & Pronoun Rules] ---")
    val rawTorrent1 = "[Judas] Kyouran Reijou Nia Liston - 07 [1080p][HEVC x265 10bit][Multi-Subs].mkv"
    val cleaned1 = AniListCastResolver.cleanAnimeTitle(rawTorrent1)
    assertEquals("Cleaned Nia Liston title", "Kyouran Reijou Nia Liston", cleaned1)

    val rawTorrent2 = "[SubsPlease] Sousou no Frieren - S01E12 (1080p) [720p].mp4"
    val cleaned2 = AniListCastResolver.cleanAnimeTitle(rawTorrent2)
    assertEquals("Cleaned Frieren title", "Sousou no Frieren", cleaned2)

    val rawTorrent3 = "Sekai Saikyou no Majo Episode 05 - 1080p.mkv"
    val cleaned3 = AniListCastResolver.cleanAnimeTitle(rawTorrent3)
    assertEquals("Cleaned Witch title", "Sekai Saikyou no Majo", cleaned3)

    // Verify Vietnamese pronoun mappings
    val pFemaleMain = AniListCastResolver.inferVietnamesePronounHint("Female", "MAIN", "Nia Liston")
    assertEquals("Female MAIN maps to tieu thu", "tiểu thư / cô ấy", pFemaleMain)

    val pFemaleSupporting = AniListCastResolver.inferVietnamesePronounHint("Female", "SUPPORTING", "Lynette Blanc")
    assertEquals("Female SUPPORTING maps to em/hau gai", "em / hầu gái / cô ấy", pFemaleSupporting)

    val pMaidAttendant = AniListCastResolver.inferVietnamesePronounHint("Female", "SUPPORTING", "Lynokis Funk (Maid)")
    assertEquals("Maid attendant maps to em/hau gai", "em / hầu gái / cô ấy", pMaidAttendant)

    val pMaleMain = AniListCastResolver.inferVietnamesePronounHint("Male", "MAIN", "Arthur Pendragon")
    assertEquals("Male MAIN maps to thieu gia", "thiếu gia / anh ấy", pMaleMain)

    val pMaleSupporting = AniListCastResolver.inferVietnamesePronounHint("Male", "SUPPORTING", "Neil Liston")
    assertEquals("Male SUPPORTING maps to cau ay", "cậu ấy / anh ấy", pMaleSupporting)

    val pUnknown = AniListCastResolver.inferVietnamesePronounHint("unknown", "BACKGROUND", "Guard A")
    assertEquals("Unknown/Neutral maps to ho/nguoi nay", "họ / người này", pUnknown)

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
            CharacterEntity(name = "Nia Liston", gender = "Female", role = "MAIN", viPronounHint = "tiểu thư / cô ấy", isEphemeral = false),
            CharacterEntity(name = "Neil Liston", gender = "Male", role = "SUPPORTING", viPronounHint = "cậu ấy / anh ấy", isEphemeral = false)
        )
    )
    CharacterProfileManager.saveProfile(null, "Kyouran Reijou Nia Liston", persistentCast, isEphemeral = false)

    // Verify persistent cast is on disk and RAM
    val loadedPersistent = CharacterProfileManager.getProfile(null, "Kyouran Reijou Nia Liston")
    assertEquals("Persistent cast contains 2 characters", 2, loadedPersistent?.characters?.size)

    // Simulate Chunk 3 discovering a dynamic temporary speaker with isEphemeral = true
    val dynamicDiscovered = CharacterEntity(
        name = "Bandit Leader",
        gender = "Male",
        role = "Minor Antagonist",
        viPronounHint = "họ / người này",
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

    println("\n================================================================================")
    println(">>> ALL 12 MILESTONE 2 TEST SUITES PASSED PERFECTLY!")
    println("================================================================================")
}
