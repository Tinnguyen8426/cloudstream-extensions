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

    fun isJapaneseScript(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        for (i in 0 until text.length) {
            val c = text[i]
            if ((c in '\u3040'..'\u309F') || // Hiragana
                (c in '\u30A0'..'\u30FF') || // Katakana
                (c in '\u31F0'..'\u31FF') || // Katakana Phonetic Extensions
                (c in '\u4E00'..'\u9FAF')    // CJK Unified Ideographs (Kanji)
            ) {
                return true
            }
        }
        return false
    }

    fun getJapaneseSourceDirectives(): String {
        return """
            - MANDATORY 1-TO-1 CUE RETENTION:
              * You MUST return exactly one translation object for every single input cue ID.
              * Even for one-word Japanese interjections (e.g. 'はい', 'うん', 'えっ？', 'あ…', '何？', '嘘'), NEVER omit, drop, or merge them with neighboring cues.
              * The count of output translations MUST strictly equal the count of input cues. Dropping cues is strictly forbidden.
            - YAKUWARIGO LINGUISTIC MATRIX (役割語 - Role Language) & PRONOUN LOCKING:
              1. First-Person Pronouns (一人称) -> Speaker Persona & Social Identity:
                 * 俺 (Ore): Male, confident, rugged/casual, everyday informal -> translate as 'anh' / 'tớ' / 'tao' / 'ta'.
                 * 僕 (Boku): Male youth/boy, gentle, polite, student -> translate as 'em' / 'con' / 'tớ' / 'cháu' / 'mình'.
                 * 私 (Watashi): Standard formal, polite adult female or professional male -> translate as 'tôi' / 'em' / 'chị' / 'mình'.
                 * あたし (Atashi) / うち (Uchi): Young female, lively/casual or cute -> translate as 'em' / 'tớ' / 'mình'.
                 * わし (Washi): Elder man (rōjin / grandfather) -> translate as 'ông' / 'lão' / 'bác' / 'ta'.
                 * 我 / 吾輩 / 我輩 (Ware / Wagahai): Powerful being, monster, arrogant noble, mad scientist -> translate as 'ta' / 'bổn tọa'.
                 * 拙者 (Sessha): Samurai, ninja, traditional warrior -> translate as 'tại hạ'.
                 * 妾 (Warawa): Aristocratic lady, queen, empress -> translate as 'thiếp' / 'bổn cung'.
                 * 小生 (Shousei): Humble male scholar/intellectual -> translate as 'tôi' / 'kẻ hèn này'.
              2. Second-Person Pronouns (二人称) -> Listener Standing & Dyadic Relation:
                 * お前 (Omae): Superior to subordinate, or male friend to close male friend -> translate as 'em' / 'mày' / 'cậu'.
                 * あんた (Anta): Familiar casual, family member, close friend -> translate as 'anh' / 'em' / 'cậu' / 'mày'.
                 * 君 (Kimi): Male speaker to equal or junior (gentle, friendly, affectionate) -> translate as 'em' / 'cậu' / 'bạn'.
                 * 貴様 (Kisama) / 手前 (Temee): Hostile, disdainful, enraged in combat -> translate as 'mày' / 'ngươi' / 'thằng kia'.
                 * あなた (Anata): Polite social address; OR affectionate wife-to-husband ('darling') -> translate as 'anh' (if wife to husband) or 'bạn' / 'cậu' / 'ông/bà'.
                 * お主 (Onushi) / 貴殿 (Kiden): Elder/military traditional address -> translate as 'ngươi' / 'các hạ' / 'thí chủ'.
              3. Sentence Endings & Copula (語尾・終助詞) -> Persona Stereotypes:
                 * 〜だぜ / 〜ぜ / 〜ぞ: Shounen male hero, bold, direct. Never a woman or elderly person.
                 * 〜わ / 〜わよ / 〜かしら / 〜のよ: Feminine gentle, young lady or mature woman. Never a rough male.
                 * 〜じゃ / 〜のじゃ / 〜おる / 〜わい: Elder speech (Rōjin-go - 老人語), wise old man or mentor.
                 * 〜ですわ / 〜ますの / 〜あそばせ: Aristocrat / high-class lady (Ojou-sama-go), elegant nobility.
                 * 〜でござる / 〜であります: Samurai, ninja, or military personnel.
                 * 〜っす / 〜す: Junior/subordinate to senior (Kouhai-go) -> STRICTLY LOCK AS JUNIOR ('em' / 'cháu').
                 * 〜なのだ / 〜のだ: Mascot, innocent / energetic character -> cute informal pronouns.
              4. Politeness & Hierarchy (Keigo vs Tamekuchi):
                 * Polite/Honorific (です/ます, いらっしゃる, おっしゃる): Junior speaking to senior, or respectful social distance.
                 * Humble (Kenjougo: 申す, 参る, いただく): Speaker lowers self -> STRICTLY LOCK AS JUNIOR ('em' / 'cháu').
                 * Casual (タメ口 - Tamekuchi: だ, dictionary form, 〜ね): Equals, peers, or senior speaking down to junior.
              5. Japanese Internal Monologue Markers:
                 * Sentences ending in non-interactive questions (〜だろうか, 〜のか, 〜か, 〜かしら), plain dictionary forms without keigo, or introspective exclamations (〜とはな, 〜まさか, 〜なんてこった) indicate Internal Monologue.
                 * Treat as Internal Monologue: use 'mình', 'ta', 'tôi' or omit pronouns entirely; NEVER use 'anh' or 'em'.
        """.trimIndent()
    }

    fun getLanguageDirectives(targetLang: String): String {
        return when (targetLang.lowercase(Locale.ROOT).trim()) {
            "vi" -> """
                - Xưng hô nhân vật: Áp dụng danh từ thân tộc chuẩn xác theo giới tính, tuổi tác và vai vế thực tế trong bảng CHARACTER ROSTER (anh/em, chị/em, chú/cháu, bác/cháu, cô/cháu, ông/cháu, bố/con).
                - Bạn bè học đường / cùng trang lứa: Ưu tiên xưng hô 'tớ - cậu', 'mình - bạn', gọi tên trực tiếp, hoặc 'mày - tao' khi thân mật suồng sã. Tuyệt đối KHÔNG áp đặt 'anh - em' lên bạn học cùng trang lứa.
                - Ma trận Nhận diện Hành vi Ngôn ngữ (Dialogue vs Monologue):
                  * Phân biệt Spoken Dialogue vs Internal Monologue thuần túy bằng Hướng phát ngôn (Direction of Address).
                  * Lời thoại đối đáp (Spoken Dialogue): Hướng tới người nghe có mặt trong cảnh (chứa 'you', đại từ ngôi 2, từ hô ngữ, câu hỏi/chào hỏi trực tiếp, mệnh lệnh/yêu cầu).
                  * Độc thoại nội tâm (Internal Monologue): Suy nghĩ thầm kín trong đầu, không có người nghe trực tiếp (chứa động từ nhận thức 'I think/wonder/feel', câu tự vấn/rhetorical questions).
                - Quy tắc xưng hô Độc thoại nội tâm: Khi nhân vật tự suy nghĩ một mình (không có người nghe trực tiếp), dùng 'mình', 'ta', 'tôi' hoặc lược bỏ đại từ nhân xưng. Tuyệt đối KHÔNG tự xưng 'anh / em' khi độc thoại một mình.
                - Suy nghĩ quan sát về người khác (Observational Thoughts): Khi độc thoại nội tâm là suy nghĩ quan sát, nhận định về người khác ("She is late", "What is he doing?"), BẮT BUỘC giữ nguyên ngôi thứ 3 ('cô ấy', 'anh ấy', 'cậu ấy', 'họ'...), tuyệt đối KHÔNG chuyển thành ngôi thứ 2 xưng hô đối thoại ('em', 'anh', 'cậu').
                - Khóa thứ bậc hai chiều (Universal Hierarchy Lock): Trong hai lượt thoại đối đáp kế cận giữa 2 nhân vật, cặp vai vế xưng hô (senior <-> junior, ví dụ anh/em hoặc chú/cháu) phải giữ nguyên bất biến, không đảo lộn vai xưng hô giữa câu hỏi và câu đáp.
                - Phân định theo từng lượt thoại (Per-Cue Decision): Ngữ khí và vai vế xưng hô (đối thoại hay độc thoại, trên hay dưới) được đánh giá linh hoạt theo từng lượt thoại (per-cue), không khóa cứng vai trò của nhân vật cho toàn bộ bộ phim.
                - Khóa Thân Tộc Theo Đúng Cặp Nhân Vật (Pair-Scoped Kinship Lock): Mọi đại từ thân tộc ('bố/ba - con', 'mẹ - con', 'ông/bà - cháu', 'chú/cô/bác - cháu', 'vợ - chồng') CHỈ được phép áp dụng khi ĐÚNG 2 thành viên ruột thịt trong cặp quan hệ đó đang trực tiếp nói chuyện với nhau. CẤM kế thừa đại từ thân tộc từ [LOOKBEHIND_CONTEXT] sang cảnh công việc, đối thủ, đám đông, bạn bè hoặc nhân vật người lớn khác; khi gặp [SCENE_SHIFT_CAUTION], gap_s >= 2.5, hoặc đổi chủ đề/giọng điệu, BẮT BUỘC reset ngay về đại từ tình huống ('tôi - anh/cô/ông', 'anh - em', 'tao - mày').
                - Thứ bậc dòng tộc thắng tuổi tác sinh học: Trong quan hệ gia tộc, vai vế huyết thống ghi đè tuổi sinh học ("chú ít tuổi hơn cháu" vẫn phải dịch xưng hô "chú - cháu", tuyệt đối không dùng "anh - em").
                - Xung đột công sở vs Tuổi tác: Khi sếp nam trẻ tuổi nói chuyện với nhân viên nữ lớn tuổi hơn, sếp tự xưng "tôi/em" và gọi nhân viên là "chị" (cấm trịch thượng dùng "anh - em" và cấm lố bịch dùng "chú - cháu").
                - Bất biến Nghịch đảo Xưng hô (Dyadic Reciprocity): Nếu nhân vật A xưng 'anh' gọi 'em', nhân vật B bắt buộc phải xưng 'em' gọi 'anh'. Tuyệt đối không để nhân vật tự xưng 'tôi' khi đang đối thoại thân mật với người thân/người yêu.
                - Xưng hô nhóm (Group Address): Khi phát ngôn hướng tới tập thể ("you guys", "all of you"), dùng danh từ tập thể phù hợp: "các anh", "các chị", "các em", "mọi người", cấm dùng "các bạn" máy móc.
                - Quy tắc 'tôi - bạn': Không lạm dụng 'tôi - bạn' trong giao tiếp thân mật hoặc gia đình; nhưng ĐƯỢC PHÉP dùng khi hai người lớn xa lạ, đồng nghiệp xã giao hoặc trong bối cảnh hành chính/điều tra.
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

    fun getGenreDirectives(mediaContext: MediaContext?, targetLang: String): String {
        if (mediaContext == null || targetLang.lowercase(Locale.ROOT).trim() != "vi") return ""
        val combinedMeta = (mediaContext.tags + mediaContext.genres + listOf(mediaContext.plot, mediaContext.title, mediaContext.mediaType))
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        val isSchool = listOf("school", "học đường", "slice of life", "comedy", "youth", "shoujo", "đời thường").any { combinedMeta.contains(it) }
        val isPeriod = listOf("historical", "cổ trang", "wuxia", "kiếm hiệp", "xianxia", "period", "samurai", "martial arts").any { combinedMeta.contains(it) }
        val isFantasy = listOf("fantasy", "isekai", "magic", "dị giới", "tiên hiệp").any { combinedMeta.contains(it) }
        val isDocumentary = mediaContext.mediaType.equals("Documentary", ignoreCase = true) ||
            listOf("documentary", "tài liệu", "docuseries").any { combinedMeta.contains(it) }
        val isAsianDrama = mediaContext.mediaType.equals("AsianDrama", ignoreCase = true) ||
            listOf("asian drama", "asiandrama", "k-drama", "kdrama", "c-drama", "cdrama", "phim bộ châu á").any { combinedMeta.contains(it) }
        val isModern = listOf("workplace", "công sở", "office", "police", "investigation", "sci-fi", "crime", "hiện đại").any { combinedMeta.contains(it) }
        val isWesternCinema = (
            mediaContext.mediaType.equals("Movie", ignoreCase = true) ||
                mediaContext.mediaType.equals("TvSeries", ignoreCase = true) ||
                listOf("action", "sci-fi", "science fiction", "drama", "sports", "family", "boxing", "thriller", "crime", "hollywood").any { combinedMeta.contains(it) }
            ) && !isSchool && !isPeriod && !isDocumentary && !isAsianDrama

        val sb = StringBuilder()
        if (isSchool) {
            sb.append("- Bối cảnh học đường / đời thường: Nhân vật bè bạn, cùng trang lứa ưu tiên xưng hô 'tớ - cậu', 'mình - bạn', gọi tên, hoặc 'mày - tao' khi thân mật suồng sã. CẤM dùng các danh xưng quý tộc/cổ phong như 'tiểu thư', 'thiếu gia', 'huynh đệ'.\n")
        }
        if (isPeriod) {
            sb.append("- Bối cảnh cổ trang / kiếm hiệp: Áp dụng đại từ cổ phong phù hợp (huynh - đệ, tỷ - muội, công tử, tiểu thư, tiền bối - vãn bối, sư phụ - đồ nhi, ta - ngươi).\n")
        }
        if (isFantasy && !isPeriod) {
            sb.append("- Bối cảnh fantasy / dị giới: Chỉ xưng hô 'tiểu thư / thiếu gia / ngài' khi nhân vật được xác nhận rõ là quý tộc hoặc lãnh chúa trong tác phẩm. Giữa các thành viên tổ đội mạo hiểm giả, dùng 'anh - em', 'cô - cậu', 'ông - tôi' một cách bình đẳng.\n")
        }
        if (isDocumentary) {
            sb.append("- Bối cảnh phim Tài liệu / Thuyết minh (Documentary): Dùng giọng văn thuyết minh khách quan, chuẩn mực ('ông', 'bà', 'họ', 'chúng ta', 'nó', 'người dân'). CẤM dùng xưng hô tình cảm/học đường như 'anh - em', 'tớ - cậu'.\n")
        }
        if (isAsianDrama && !isPeriod) {
            sb.append("- Bối cảnh phim bộ Châu Á hiện đại (AsianDrama): Phân biệt rõ vai vế công sở và gia tộc hiện đại ('anh - em', 'chị - em', 'sếp - tôi/em', 'chú/cô - cháu'), ưu tiên khẩu ngữ tự nhiên, tránh lẫn từ ngữ cổ trang.\n")
        }
        if (isModern && !isSchool && !isPeriod && !isDocumentary) {
            sb.append("- Bối cảnh hiện đại / công sở: Gọi theo chức vụ hoặc 'anh - em', 'chị - em', 'sếp - em', 'tôi - anh/chị'.\n")
        }
        if (isWesternCinema) {
            sb.append("- Bối cảnh phim Điện ảnh Âu Mỹ (Action / Sci-Fi / Drama / Sports / Family): Thoại ngắn gọn, tự nhiên kiểu điện ảnh Việt Nam ('tôi - anh/ông/cô', 'tao - mày' khi xung đột/sàn đấu/quán bar, 'anh - em' cặp đôi, chỉ dùng 'bố/ba - con' khi đúng cặp cha con ruột thịt).\n")
            sb.append("  * Few-Shot Ngắt Xưng Hô Khi Cắt Cảnh (Scene-Cut Reset): Nếu lookbehind là cha con (\"Don't go out there, Dad.\" -> \"Đừng ra đó, bố ơi.\"), nhưng cue hiện tại có gap_s >= 3.0 hoặc [SCENE_SHIFT_CAUTION] chuyển sang đàm phán người lớn (\"Sign the deal tonight.\" -> \"Ký hợp đồng tối nay đi anh.\"), CẤM kéo tiếp 'bố - con'.\n")
            sb.append("  * Few-Shot Khẩu Ngữ Tự Nhiên (Anti-Translationese): Dịch thoát ý tự nhiên, tránh dịch word-by-word cứng nhắc: \"You're kidding me?\" -> \"Nói đùa hả?\" / \"Anh giỡn tôi à?\" (tránh \"Bạn đang đùa với tôi sao?\"); \"Keep your voice down.\" -> \"Nói nhỏ thôi.\" (tránh \"Hãy giữ giọng của bạn nhỏ xuống.\").\n")
        }
        return sb.toString().trimEnd()
    }

    fun buildSystemPrompt(
        targetLang: String,
        characterRegistry: TwoTierCharacterRegistry?,
        customGlossary: String?,
        mediaContext: MediaContext? = null,
        sourceLang: String? = null,
        sampleText: String? = null
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
        val toneGuideLine = if (characterRegistry != null && characterRegistry.toneGuide.isNotBlank()) {
            "\n- Tone & Style Guide: ${characterRegistry.toneGuide.trim()}"
        } else ""

        val directives = getLanguageDirectives(targetLang)
        val genreDirectives = getGenreDirectives(mediaContext, targetLang)
        val fullDirectives = if (genreDirectives.isNotBlank()) "$directives\n$genreDirectives" else directives
        val isJapanese = sourceLang?.lowercase(Locale.ROOT)?.trim() in listOf("ja", "jpn") ||
                isJapaneseScript(sampleText)
        val japanesePrompt = if (isJapanese) {
            "\n\n### 2.1. SOURCE LANGUAGE (JAPANESE) YAKUWARIGO & RETENTION DIRECTIVES:\n${getJapaneseSourceDirectives()}"
        } else ""

        val autoGlossaryLines = characterRegistry?.autoGlossary?.entries
            ?.filter { it.key.isNotBlank() && it.value.isNotBlank() }
            ?.joinToString("\n") { (k, v) -> "- $k -> $v" }
            ?: ""
        val combinedGlossary = listOf(autoGlossaryLines, customGlossary?.trim() ?: "")
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val glossaryPrompt = if (combinedGlossary.isNotBlank()) {
            "\n### 7. GLOSSARY TERMS:\nStrictly adhere to the following terminology mappings:\n$combinedGlossary\n"
        } else ""

        val allTagsGenres = if (mediaContext != null) {
            (mediaContext.tags + mediaContext.genres).distinct().filter { it.isNotBlank() }
        } else emptyList()
        val mediaContextPrompt = if (mediaContext == null) {
            ""
        } else {
            val hasMediaInfo = mediaContext.title.isNotBlank() ||
                mediaContext.plot.isNotBlank() ||
                allTagsGenres.isNotEmpty() ||
                mediaContext.mediaType.isNotBlank() ||
                mediaContext.year != null ||
                mediaContext.characterActors.isNotEmpty() ||
                mediaContext.actors.isNotEmpty()
            if (!hasMediaInfo) "" else buildString {
                append("\n### 0. MEDIA CONTEXT:\n")
                if (mediaContext.title.isNotBlank()) {
                    append("- Media Title: ${mediaContext.title}\n")
                }
                if (mediaContext.mediaType.isNotBlank()) {
                    append("- Media Type: ${mediaContext.mediaType}\n")
                }
                if (mediaContext.year != null) {
                    append("- Release Year: ${mediaContext.year}\n")
                }
                if (mediaContext.plot.isNotBlank()) {
                    append("- Plot / Synopsis: ${mediaContext.plot}\n")
                }
                if (allTagsGenres.isNotEmpty()) {
                    append("- Tags / Genres: ${allTagsGenres.joinToString(", ")}\n")
                }
                if (mediaContext.characterActors.isNotEmpty()) {
                    val castFormatted = mediaContext.characterActors.entries.take(15).joinToString(", ") { (actor, role) ->
                        if (role.isNotBlank()) "$actor (as $role)" else actor
                    }
                    append("- Cast & Characters: $castFormatted\n")
                } else if (mediaContext.actors.isNotEmpty()) {
                    append("- Cast & Characters: ${mediaContext.actors.take(15).joinToString(", ")}\n")
                }
            }
        }

        return """
            You are an elite, professional subtitle translation engine specializing in cinema and television localization into $targetLang.
            Translate the subtitle dialogues under [TARGET_CUES] with extreme contextual fidelity, natural conversational tone, and flawless character consistency.$mediaContextPrompt

            ### 1. CHARACTER ROSTER & RELATIONSHIPS:
            $rosterJson$toneGuideLine

            ### 2. TARGET LANGUAGE GRAMMATICAL DIRECTIVES ($targetLang):
            $fullDirectives$japanesePrompt

            ### 3. CONTEXTUAL AWARENESS & SLIDING WINDOW RULES:
            - 3-TIER HIERARCHY OF TRUTH & ANTI-CASCADING OVERRIDE:
              * Tier 1 (Supreme Truth - Vocatives & Lookahead): Explicit vocatives (e.g. 'Dad', 'Father', 'Sensei', 'Boss', 'Doctor', 'Sir', 'Bro', 'Kid', 'Lady', 'young miss') or kinship revelations in the current cue or [LOOKAHEAD_CONTEXT] OVERRIDE 100% of previous context. You MUST immediately correct pronouns and self-correct from that cue forward. NEVER persist mistaken prior assumptions across dialogue turns.
              * Tier 2 (Situational & Genre Context): Genre conventions (School, Workplace, Historical/Period, Martial Arts) guide default social registers and honorific norms.
              * Tier 3 (Provisional Lookbehind): [LOOKBEHIND_CONTEXT] is a soft, provisional hypothesis for conversational continuity. It applies ONLY in the absence of higher-tier conflicting evidence.
            - CONTEXTUAL CONTINUITY & SELF-CORRECTION: Use [LOOKBEHIND_CONTEXT] to maintain conversational flow and tone. DISAMBIGUATION OVERRIDE: If lookbehind used a generic or assumed pronoun (e.g. 'anh/em' or 'tôi/bạn') but current or lookahead dialogue reveals explicit new evidence about a character's true identity, gender, title, or age hierarchy (e.g. vocatives like 'Lady', 'sir', 'young miss', 'father', 'sister'), you MUST OVERRIDE and CORRECT the pronoun immediately. DO NOT blindly persist prior mistaken pronouns. DO NOT translate or include them in output.
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
            Output MUST strictly be a valid JSON object matching this exact schema:
            {"translations":[{"id":<int>,"text":"<string>"}]}
            - Every input cue ID in [TARGET_CUES] MUST appear exactly once in 'translations'.
            - DO NOT output 'act', 'role', extra keys, markdown code fences, explanations, or commentary.$glossaryPrompt
        """.trimIndent()
    }

    private fun formatGapSec(gapMs: Long): String {
        return String.format(Locale.US, "%.1f", gapMs / 1000.0)
    }

    fun buildUserPayload(
        cues: List<SubtitleCue>,
        lookbehind: List<ContextCue>,
        lookahead: List<ContextCue>,
        rollingSceneDigest: String? = null
    ): String {
        val sb = StringBuilder()

        if (!rollingSceneDigest.isNullOrBlank()) {
            sb.append("[ROLLING_SCENE_DIGEST]\n")
            sb.append(rollingSceneDigest.trim())
            sb.append("\n\n")
        }

        if (lookbehind.isNotEmpty()) {
            sb.append("[LOOKBEHIND_CONTEXT]\n")
            sb.append("(Previous dialogue lines with finalized translations - READ ONLY, do not translate or include in output):\n")
            val lbList = lookbehind.map { cue ->
                val map = mutableMapOf<String, Any?>("id" to cue.index)
                if (cue.speaker != null) map["speaker"] = cue.speaker
                if (cue.gapMs >= 2500L) map["gap_s"] = formatGapSec(cue.gapMs)
                map["src"] = cue.sourceText
                map["translated"] = cue.translatedText ?: ""
                map
            }
            sb.append(mapper.writeValueAsString(lbList))
            sb.append("\n\n")
        }

        if (lookbehind.isNotEmpty() && cues.isNotEmpty()) {
            val transitionGapMs = (cues.first().startMs - lookbehind.last().endMs).coerceAtLeast(0L)
            if (transitionGapMs >= 3000L || cues.first().speaker == null) {
                val speakerState = if (cues.first().speaker == null) "unverified" else "annotated"
                sb.append("[SCENE_SHIFT_CAUTION: Time gap = ${formatGapSec(transitionGapMs)}s, speakers = $speakerState — verify speakers & vocatives in [TARGET_CUES]; DO NOT blindly inherit prior scene kinship pronouns.]\n")
            }
        }

        sb.append("[TARGET_CUES]\n")
        sb.append("(Translate ONLY these dialogue entries into the target language):\n")
        val targetList = cues.mapIndexed { idx, cue ->
            val prevEnd = if (idx == 0) lookbehind.lastOrNull()?.endMs ?: -1L else cues[idx - 1].endMs
            val map = mutableMapOf<String, Any?>("id" to cue.index)
            if (cue.speaker != null) map["speaker"] = cue.speaker
            if (prevEnd >= 0L) {
                val gapMs = cue.startMs - prevEnd
                if (gapMs >= 2500L) map["gap_s"] = formatGapSec(gapMs)
            }
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
                if (cue.gapMs >= 2500L) map["gap_s"] = formatGapSec(cue.gapMs)
                map["src"] = cue.sourceText
                map
            }
            sb.append(mapper.writeValueAsString(laList))
        }

        return sb.toString()
    }

    /**
     * Pass 0 helper: samples up to ~maxSamples representative cues evenly across the film timeline,
     * prioritizing lines containing proper names or family/title vocatives.
     */
    fun sampleRepresentativeCues(cues: List<SubtitleCue>, maxSamples: Int = 165): List<SubtitleCue> {
        if (cues.size <= maxSamples) return cues
        if (maxSamples <= 0) return emptyList()

        val vocativePattern = Regex(
            "(?i)\\b(dad|mom|son|father|mother|boss|mr\\.?|mrs\\.?|ms\\.?|coach|dr\\.?|sensei|sir|uncle|aunt|grandpa|grandma|brother|sister|bro|kid|captain)\\b|-san\\b|-sama\\b|-kun\\b|-chan\\b"
        )
        val properNounPattern = Regex("(?<!^)\\b[A-Z][a-z]{2,}\\b")

        fun scoreCue(cue: SubtitleCue): Int {
            val text = cue.rawText.trim()
            if (text.isEmpty()) return -1
            var score = 0
            if (vocativePattern.containsMatchIn(text)) score += 3
            if (properNounPattern.containsMatchIn(text)) score += 2
            if (!cue.speaker.isNullOrBlank()) score += 1
            return score
        }

        val selected = ArrayList<SubtitleCue>(maxSamples)
        val total = cues.size
        for (bucket in 0 until maxSamples) {
            val startIdx = (bucket * total) / maxSamples
            val endIdx = (((bucket + 1) * total) / maxSamples).coerceAtLeast(startIdx + 1).coerceAtMost(total)
            var bestCue = cues[startIdx]
            var bestScore = Int.MIN_VALUE
            for (idx in startIdx until endIdx) {
                val c = cues[idx]
                val s = scoreCue(c)
                if (s > bestScore) {
                    bestScore = s
                    bestCue = c
                }
            }
            selected.add(bestCue)
        }
        return selected
    }

    /**
     * Pass 0 helper: builds (systemPrompt, userPayload) to extract a GlobalFilmBible JSON.
     */
    fun buildFilmBiblePrompt(
        mediaContext: MediaContext?,
        sampledCues: List<SubtitleCue>,
        targetLang: String
    ): Pair<String, String> {
        val systemPrompt = """
            You are a senior localization director preparing a Global Film Bible for translating a film/series into $targetLang.
            Analyze the synopsis, cast list, and representative dialogue timeline below, and output ONLY a single valid JSON object matching this schema:
            {
              "characters": [{"name": "Full Name", "aliases": ["Nickname"], "gender": "male|female|unknown", "age_group": "child|teen|adult|elder", "role": "Brief role"}],
              "relationships": [{"pair": ["Char A", "Char B"], "type": "family|workplace|rivals|friends|romantic", "address_matrix": {"$targetLang": {"A_to_B": "pronoun", "B_to_A": "pronoun"}}}],
              "auto_glossary": {"domain term": "consistent translation"},
              "tone_guide": "Concise 1-2 sentence style & register guidance"
            }
            Rules:
            - Extract up to ~15 key named characters.
            - List explicit fixed relationship pairs (especially blood/family pairs like father-son vs non-family coworkers/rivals).
            - Output raw JSON ONLY without markdown fences or explanation.
        """.trimIndent()

        val userPrompt = buildString {
            if (mediaContext != null) {
                append("[MEDIA_METADATA]\n")
                if (mediaContext.title.isNotBlank()) append("Title: ${mediaContext.title}\n")
                if (mediaContext.mediaType.isNotBlank()) append("Type: ${mediaContext.mediaType}\n")
                if (mediaContext.year != null) append("Year: ${mediaContext.year}\n")
                val genres = (mediaContext.tags + mediaContext.genres).distinct().filter { it.isNotBlank() }
                if (genres.isNotEmpty()) append("Genres: ${genres.joinToString(", ")}\n")
                if (mediaContext.plot.isNotBlank()) append("Plot: ${mediaContext.plot}\n")
                if (mediaContext.characterActors.isNotEmpty()) {
                    append("Cast: ${mediaContext.characterActors.entries.joinToString(", ") { (a, r) -> "$a -> $r" }}\n")
                } else if (mediaContext.actors.isNotEmpty()) {
                    append("Cast: ${mediaContext.actors.joinToString(", ")}\n")
                }
                append("\n")
            }
            append("[REPRESENTATIVE_DIALOGUE_SAMPLES]\n")
            for (cue in sampledCues) {
                val spk = if (!cue.speaker.isNullOrBlank()) "${cue.speaker}: " else ""
                append("[${cue.index}] $spk${cue.rawText.replace('\n', ' ')}\n")
            }
        }
        return systemPrompt to userPrompt
    }
}
