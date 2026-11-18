package com.lagradost.cloudstream3.subtitles.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.ContextCue
import com.lagradost.cloudstream3.subtitles.ai.models.MediaContext
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleCue
import com.lagradost.cloudstream3.subtitles.ai.models.TwoTierCharacterRegistry
import java.util.Locale

/**
 * Builds universal prompt payloads and linguistic grammatical directives
 * across target language families (Vietnamese kinship, East Asian keigo/benefactives,
 * Romance COD/COI, Slavic Pan/Pani and gendered past tenses).
 */
object AITranslatorPromptBuilder {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    fun isBootstrap(lookbehind: List<ContextCue>, characterRegistry: TwoTierCharacterRegistry?): Boolean {
        return lookbehind.isEmpty() && (characterRegistry == null || characterRegistry.characters.isEmpty())
    }

    fun getLanguageDirectives(targetLang: String): String {
        return when (targetLang.lowercase(Locale.ROOT).trim()) {
            "vi" -> """
                - Xưng hô nhân vật: Áp dụng nghiêm ngặt danh từ thân tộc chuẩn xác theo giới tính, tuổi tác và tôn ti trong bảng CHARACTER ROSTER (anh/em, chị/em, chú/cháu, bác/cháu, cô/cháu, ông/cháu, bố/con).
                - Thứ bậc dòng tộc thắng tuổi tác sinh học: Trong quan hệ gia tộc, vai vế huyết thống ghi đè tuổi sinh học ("chú ít tuổi hơn cháu" vẫn phải dịch xưng hô "chú - cháu", tuyệt đối không dùng "anh - em").
                - Xung đột công sở vs Tuổi tác: Khi sếp nam trẻ tuổi nói chuyện với nhân viên nữ lớn tuổi hơn, sếp tự xưng "tôi/em" và gọi nhân viên là "chị" (cấm trịch thượng dùng "anh - em" và cấm lố bịch dùng "chú - cháu").
                - Bất biến Nghịch đảo Xưng hô (Dyadic Reciprocity): Nếu nhân vật A xưng 'anh' gọi 'em', nhân vật B bắt buộc phải xưng 'em' gọi 'anh'. Tuyệt đối không để nhân vật tự xưng 'tôi' khi đang đối thoại thân mật với người thân/người yêu.
                - Xưng hô nhóm (Group Address): Khi phát ngôn hướng tới tập thể ("you guys", "all of you"), dùng danh từ tập thể phù hợp: "các anh", "các chị", "các em", "mọi người", cấm dùng "các bạn" máy móc.
                - Tránh xa lối dịch máy vô cảm: Cấm sử dụng cặp xưng hô cứng nhắc 'tôi - bạn' trừ trường hợp hai người lớn xa lạ giao tiếp xã giao lần đầu.
                - Biến chuyển cảm xúc: Chỉ chuyển sang 'mày - tao' khi hai nhân vật thân thiết từ nhỏ hoặc khi xảy ra xung đột giận dữ nảy lửa.
            """.trimIndent()

            "ja" -> """
                - Apply Zero Anaphora with MANDATORY Benefactive Auxiliaries: Do NOT translate English 'you'/'I' into 'anata'/'watashi' when context is clear. When dropping pronouns in action/request sentences, ALWAYS attach directional benefactive verbs (-te ageru for giving help, -te kureru / -te morau for receiving help) to prevent ambiguous or inverted agency.
                - Uchi/Soto (In-Group vs Out-Group) Keigo Invariant: When speaking to an external client/outsider (Soto) about an internal superior/boss (Uchi), you MUST humble the boss: drop all honorific titles (-sama, -san, Buchou) and use Kenjougo (humble verbs, e.g. "Tanaka wa seki o hazushite orimasu"). NEVER use Sonkeigo for in-group members toward outsiders.
                - Use 1st-person pronoun tailored to character persona (ore, boku, watashi, atashi) and respect persona variants in combat vs daily life.
                - Select honorifics (-san, -kun, -chan, -sama) and politeness level (keigo, teineigo, kudaketa) reflecting social hierarchy.
            """.trimIndent()

            "ko" -> """
                - Maintain strict honorific levels (Hasipsioche vs Haeyoche vs Banmal) according to seniority and relationships.
                - NEVER use 'dangsin' for 'you' in casual speech. Replace with relational titles (Oppa, Hyung, Noona, Unnie, Seonsaengnim).
            """.trimIndent()

            "zh" -> """
                - Use polite 'Nín' (您) vs informal 'Nǐ' (你) based on hierarchy. Use appropriate kinship and vocational titles.
            """.trimIndent()

            "fr" -> """
                - T-V Distinction: Strictly respect formal vs informal pronouns (tu vs vous) as designated in the roster.
                - French Past Participle Agreement with Reflexive Verbs:
                  * AGREE ONLY if the reflexive pronoun is a Direct Object (COD): "Elle s'est lavée".
                  * DO NOT AGREE if the reflexive pronoun is an Indirect Object (COI) followed by a direct object: "Elle s'est lavé les mains" (lavé, NOT lavée).
                  * With 'avoir', agree with preceding direct object only: "Les fleurs qu'elle a cueillies".
            """.trimIndent()

            "de" -> """
                - T-V Distinction: Respect formal vs informal address (du / ihr vs Sie).
                - German Predicate Adjective INVARIANCE:
                  * Predicate adjectives following linking verbs (sein, werden, bleiben) REMAIN UNINFLECTED regardless of subject gender/number: "Sie ist müde", "Er ist müde", "Ich bin bereit". NEVER add gender endings to predicate adjectives.
                  * Feminine occupational nouns MUST take suffix -in: Ärztin, Lehrerin.
            """.trimIndent()

            "es" -> """
                - T-V Distinction: Respect formal vs informal address (tú vs usted, vosotros vs ustedes according to regional setting).
            """.trimIndent()

            "ru" -> """
                - Russian Gendered Past Tense & Short Adjectives:
                  * Conjugate past-tense verbs according to speaker/listener gender: suffix -л for male, -ла for female ("Я видел" vs "Я видела").
                  * Predicative short adjectives & modals must agree with gender: "Я должен" (male) vs "Я должна" (female); "Я готов" vs "Я готова".
            """.trimIndent()

            "pl" -> """
                - Polish Pan / Pani 3rd-Person Agreement MANDATE:
                  * When addressing a character formally using honorifics 'Pan' (sir) or 'Pani' (madam), the accompanying verb MUST be conjugated in the THIRD PERSON SINGULAR, NOT the 2nd person!
                    Example (formal female): "Czy Pani to widziała?" (3rd person singular fem). NEVER use 2nd-person "widziałaś" with Pani.
            """.trimIndent()

            else -> """
                - Maintain natural spoken conversational dialogue suited for cinematic subtitles.
                - Ensure consistent character pronouns, register, and respectful/informal tone throughout the scene.
            """.trimIndent()
        }
    }

    fun buildSystemPrompt(
        targetLang: String,
        characterRegistry: TwoTierCharacterRegistry?,
        customGlossary: String?,
        mediaContext: MediaContext? = null
    ): String {
        val rosterJson = if (characterRegistry != null &&
            (characterRegistry.characters.isNotEmpty() || characterRegistry.relationships.isNotEmpty())
        ) {
            try {
                mapper.writeValueAsString(characterRegistry)
            } catch (_: Throwable) {
                "No pre-defined character roster available."
            }
        } else {
            "No pre-defined character roster available."
        }

        val directives = getLanguageDirectives(targetLang)
        val glossaryPrompt = if (!customGlossary.isNullOrBlank()) {
            "\n### 7. GLOSSARY TERMS:\nStrictly adhere to the following terminology mappings:\n$customGlossary\n"
        } else ""

        val mediaContextPrompt = if (mediaContext != null &&
            (mediaContext.title.isNotBlank() || mediaContext.plot.isNotBlank() || mediaContext.tags.isNotEmpty())
        ) {
            buildString {
                append("\n### 0. MEDIA CONTEXT:\n")
                if (mediaContext.title.isNotBlank()) {
                    append("- Media Title: ${mediaContext.title}\n")
                }
                if (mediaContext.plot.isNotBlank()) {
                    append("- Plot / Synopsis: ${mediaContext.plot}\n")
                }
                if (mediaContext.tags.isNotEmpty()) {
                    append("- Tags / Genres: ${mediaContext.tags.joinToString(", ")}\n")
                }
            }
        } else ""

        return """
            You are an elite, professional subtitle translation engine specializing in cinema and television localization into $targetLang.
            Translate the subtitle dialogues under [TARGET_CUES] with extreme contextual fidelity, natural conversational tone, and flawless character consistency.$mediaContextPrompt

            ### 1. CHARACTER ROSTER & RELATIONSHIPS:
            $rosterJson

            ### 2. TARGET LANGUAGE GRAMMATICAL DIRECTIVES ($targetLang):
            $directives

            ### 3. CONTEXTUAL AWARENESS & SLIDING WINDOW RULES:
            - CONTEXTUAL CONTINUITY & SELF-CORRECTION: Use [LOOKBEHIND_CONTEXT] to maintain conversational flow and tone. DISAMBIGUATION OVERRIDE: If lookbehind used a generic or assumed pronoun (e.g. 'anh/em' or 'tôi/bạn') but current or lookahead dialogue reveals explicit new evidence about a character's true identity, gender, title, or age hierarchy (e.g. vocatives like 'Lady', 'sir', 'young miss', 'father', 'sister', or character roster), you MUST OVERRIDE and CORRECT the pronoun immediately. DO NOT blindly persist prior mistaken pronouns. DO NOT translate or include them in output.
            - Cues under [LOOKAHEAD_CONTEXT] are upcoming source lines. Use them solely to anticipate replies, questions, or pronoun disambiguation. DO NOT translate or include them in output.
            - Translate ONLY the entries listed under [TARGET_CUES].

            ### 4. RAW SUBTITLE & SPEAKER FALLBACK RULES:
            - When 'speaker' is specified, apply that character's exact persona and relational address matrix.
            - When 'speaker' is null:
              * Dash delimiters ('- Hello\n- Hi'): Represent two distinct speakers in the same cue. Translate each turn with appropriate opposing pronouns.
              * Monologue continuity: Consecutive short cues without vocatives or question-response patterns belong to the same speaker. DO NOT alternate genders or pronouns arbitrarily.
              * Vocatives & Mentions: Identify addressed names (e.g., "Look, Mary...", "Listen, sir") to identify the listener and infer the speaker's relative social standing.

            ### 5. TAG SHIELDING & SYNTAX INTEGRITY:
            - All subtitle tags and styling placeholders formatted as [[TAG_N]] (e.g. [[TAG_0]], [[TAG_1]]) are strictly IMMUTABLE.
            - NEVER modify, reorder, translate, split, or omit any [[TAG_N]] token. They must remain in their exact relative positions around the translated text.

            ### 6. OUTPUT FORMAT:
            Output MUST strictly be a valid JSON object adhering to the specified schema, containing a 'translations' array of objects with integer 'id' and string 'text'.$glossaryPrompt
        """.trimIndent()
    }

    fun buildUserPayload(
        cues: List<SubtitleCue>,
        lookbehind: List<ContextCue>,
        lookahead: List<ContextCue>
    ): String {
        val sb = StringBuilder()

        if (lookbehind.isNotEmpty()) {
            sb.append("[LOOKBEHIND_CONTEXT]\n")
            sb.append("(Previous dialogue lines with finalized translations - READ ONLY, do not translate or include in output):\n")
            val lbList = lookbehind.map { cue ->
                val map = mutableMapOf<String, Any?>("id" to cue.index)
                if (cue.speaker != null) map["speaker"] = cue.speaker
                map["src"] = cue.sourceText
                map["translated"] = cue.translatedText ?: ""
                map
            }
            sb.append(mapper.writeValueAsString(lbList))
            sb.append("\n\n")
        }

        sb.append("[TARGET_CUES]\n")
        sb.append("(Translate ONLY these dialogue entries into the target language):\n")
        val targetList = cues.map { cue ->
            val map = mutableMapOf<String, Any?>("id" to cue.index)
            if (cue.speaker != null) map["speaker"] = cue.speaker
            map["text"] = if (cue.maskedText.isNotBlank()) cue.maskedText else cue.rawText
            map
        }
        sb.append(mapper.writeValueAsString(targetList))

        if (lookahead.isNotEmpty()) {
            sb.append("\n\n[LOOKAHEAD_CONTEXT]\n")
            sb.append("(Upcoming dialogue lines for anticipation - READ ONLY, do not translate or include in output):\n")
            val laList = lookahead.map { cue ->
                val map = mutableMapOf<String, Any?>("id" to cue.index)
                if (cue.speaker != null) map["speaker"] = cue.speaker
                map["src"] = cue.sourceText
                map
            }
            sb.append(mapper.writeValueAsString(laList))
        }

        return sb.toString()
    }
}
