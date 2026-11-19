package tests.unit

import com.lagradost.cloudstream3.subtitles.ai.client.AITranslatorPromptBuilder
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import com.lagradost.cloudstream3.subtitles.ai.session.DialogueContextWindow

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

fun main() {
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
    // TEST 3: DialogueContextWindow 30000ms Scene-Cut Eviction
    // -------------------------------------------------------------------------
    println("\n--- [Test 3: DialogueContextWindow 30000ms Scene-Cut Eviction] ---")
    val defaultWindow = DialogueContextWindow()
    assertEquals("Default maxLookbehindGapMs is 30000L", 30000L, defaultWindow.maxLookbehindGapMs)

    val cue1 = SubtitleCue(index = 1, startMs = 1000L, endMs = 3000L, rawText = "Line 1")
    defaultWindow.recordFinalizedCues(listOf(cue1), mapOf(1 to "Dòng 1"))

    // Within-scene target: starts at 7000L (gap = 4000L <= 30000L)
    val withinSceneTarget = listOf(SubtitleCue(index = 2, startMs = 7000L, endMs = 9000L, rawText = "Line 2"))
    val lbWithin = defaultWindow.getLookbehind(withinSceneTarget)
    assertEquals("Lookbehind preserved for gaps <= 30000ms", 1, lbWithin.size)
    assertEquals("Lookbehind item index matches cue 1", 1, lbWithin.first().index)

    // Record cue 2
    defaultWindow.recordFinalizedCues(withinSceneTarget, mapOf(2 to "Dòng 2"))

    // Scene-cut target: starts at 40000L (gap = 31000L > 30000L)
    val sceneCutTarget = listOf(SubtitleCue(index = 3, startMs = 40000L, endMs = 42000L, rawText = "Line 3"))
    val lbSceneCut = defaultWindow.getLookbehind(sceneCutTarget)
    assertEquals("Lookbehind flushed on scene cut gap > 30000ms", 0, lbSceneCut.size)
    assertEquals("Session generation ID incremented after flush", 1L, defaultWindow.sessionGenerationId)

    println("\n================================================================================")
    println(">>> ALL MILESTONE 1 KOTLIN TESTS PASSED PERFECTLY!")
    println("================================================================================")
}
