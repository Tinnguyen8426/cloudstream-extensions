package com.lagradost.cloudstream3.subtitles.ai.parser

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.subtitles.ai.models.CharacterEntity
import com.lagradost.cloudstream3.subtitles.ai.models.CharacterRelationship
import com.lagradost.cloudstream3.subtitles.ai.models.GlobalFilmBible
import com.lagradost.cloudstream3.subtitles.ai.models.SubtitleItem

/**
 * Universal Polymorphic JSON Deserializer for AI subtitle translation responses.
 *
 * Robustly handles disparate schema structures emitted across AI providers:
 * 1. Root JSON Arrays: `[{"id": 1, "text": "..."}]` (Gemini, Anthropic prefill)
 * 2. Wrapped JSON Objects: `{"translations": [{"id": 1, "text": "..."}]}` (OpenAI Structured Outputs)
 * 3. Arbitrary Wrapper Objects: `{"subtitles": [...]}`, `{"items": [...]}`
 * 4. Anthropic Continuation: `{"id": 1, ...}]` (missing leading bracket due to assistant prefill)
 * 5. Markdown Code Fences: ````json ... ```` surrounded by conversational preamble/postamble
 * 6. Bracketed JSON Array embedded in conversational text
 * 7. Tagged Line Delimiters: `<<<ID>>>\nText` (fallback format)
 */
object AITranslationParser {

    private val mapper: ObjectMapper = ObjectMapper().apply {
        registerKotlinModule()
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
    }

    private val FENCE_REGEX = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
    private val ARRAY_REGEX = Regex("""(\[\s*\{[\s\S]*\}\s*\])""")
    private val ANTHROPIC_CONTINUATION_REGEX = Regex("""(\{\s*"id"[\s\S]*\])""")
    private val DELIMITER_REGEX = Regex("""<<<(\d+)>>>\s*([\s\S]*?)(?=(?:<<<\d+>>>|$))""")

    /**
     * Strips markdown code fences (```json ... ``` or ``` ... ```) and extracts
     * the enclosed payload. Handles conversational leading and trailing remarks.
     */
    fun stripMarkdownFences(raw: String): String {
        val clean = raw.trim()
        val match = FENCE_REGEX.find(clean)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        if (clean.startsWith("```")) {
            var s = clean.substringAfter("\n")
            if (s.endsWith("```")) {
                s = s.substringBeforeLast("```").trim()
            }
            return s
        }
        return clean
    }

    /**
     * Deserializes arbitrary AI response formats into a map of subtitle Cue ID -> Translated Text.
     */
    fun parseTranslations(rawResponse: String): Map<Int, String> {
        val cleanFences = stripMarkdownFences(rawResponse)
        var candidate = cleanFences.trim()

        // Handle Anthropic assistant prefill continuation where Claude begins output immediately after '['
        if (!candidate.startsWith("[") && !candidate.startsWith("{\"translations\"") &&
            candidate.endsWith("]}") && candidate.contains("\"id\"")
        ) {
            val firstBrace = candidate.indexOf('{')
            if (firstBrace >= 0) {
                candidate = "{\"translations\": [" + candidate.substring(firstBrace)
            }
        } else if (!candidate.startsWith("[") && candidate.endsWith("]") && (candidate.startsWith("{") || candidate.contains("\"id\""))) {
            val firstBrace = candidate.indexOf('{')
            if (firstBrace >= 0) {
                candidate = "[" + candidate.substring(firstBrace)
            }
        }

        val result = HashMap<Int, String>()

        // Tier 1: Direct Jackson tree traversal on candidate payload
        try {
            val rootNode = mapper.readTree(candidate)
            val arrayNode = extractArrayNode(rootNode)

            if (arrayNode != null && arrayNode.isArray) {
                populateItems(arrayNode, result)
                if (result.isNotEmpty()) return result
            }
        } catch (_: Throwable) {
            // Fall through to regex extraction
        }

        // Tier 2: Extract embedded JSON array from conversational text via regex
        val arrayMatch = ARRAY_REGEX.find(rawResponse)
        if (arrayMatch != null) {
            try {
                val arrayNode = mapper.readTree(arrayMatch.groupValues[1])
                if (arrayNode != null && arrayNode.isArray) {
                    populateItems(arrayNode, result)
                    if (result.isNotEmpty()) return result
                }
            } catch (_: Throwable) {}
        }

        // Tier 2b: Extract Anthropic continuation embedded in conversational text
        val anthropicMatch = ANTHROPIC_CONTINUATION_REGEX.find(rawResponse)
        if (anthropicMatch != null) {
            try {
                val reconstructed = "[" + anthropicMatch.groupValues[1]
                val arrayNode = mapper.readTree(reconstructed)
                if (arrayNode != null && arrayNode.isArray) {
                    populateItems(arrayNode, result)
                    if (result.isNotEmpty()) return result
                }
            } catch (_: Throwable) {}
        }

        // Tier 3: Delimiter fallback parser (<<<ID>>>\nText)
        val delimiterMatches = DELIMITER_REGEX.findAll(rawResponse).toList()
        for (m in delimiterMatches) {
            val id = m.groupValues[1].toIntOrNull() ?: continue
            val text = m.groupValues[2].trim()
            if (text.isNotEmpty()) {
                result[id] = text
            }
        }

        return result
    }

    /**
     * Polymorphic delegator alias.
     */
    fun parse(rawResponse: String): Map<Int, String> = parseTranslations(rawResponse)

    private fun JsonNode?.textOrNull(): String? =
        this?.takeIf { !it.isNull && it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonNode?.nonNullText(): String? =
        this?.takeIf { !it.isNull && it.isTextual }?.asText()

    private fun sanitizeAddressMatrixMap(node: JsonNode): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (!node.isObject) return out
        val fields = node.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            val key = entry.key?.trim() ?: ""
            if (key.isEmpty()) continue
            val value = entry.value ?: continue
            if (value.isNull) continue
            when {
                value.isTextual -> {
                    val text = value.asText().trim()
                    if (text.isNotEmpty()) out[key] = text
                }
                value.isObject -> {
                    val nested = sanitizeAddressMatrixMap(value)
                    if (nested.isNotEmpty()) out[key] = nested
                }
                value.isArray -> {
                    val list = value.mapNotNull { it.textOrNull() }
                    if (list.isNotEmpty()) out[key] = list
                }
                value.isBoolean -> out[key] = value.asBoolean()
                value.isNumber -> out[key] = value.numberValue()
            }
        }
        return out
    }

    /**
     * Extracts discovered characters from the response (Bootstrap translation in Chunk 0).
     */
    fun parseDiscoveredCharacters(rawResponse: String): List<CharacterEntity> {
        val cleanFences = stripMarkdownFences(rawResponse).trim()
        val result = mutableListOf<CharacterEntity>()

        try {
            val rootNode = mapper.readTree(cleanFences)
            val charNode = when {
                rootNode.has("discovered_characters") && rootNode.get("discovered_characters").isArray ->
                    rootNode.get("discovered_characters")
                rootNode.has("discoveredCharacters") && rootNode.get("discoveredCharacters").isArray ->
                    rootNode.get("discoveredCharacters")
                else -> null
            }

            if (charNode != null && charNode.isArray) {
                for (i in 0 until charNode.size()) {
                    val elem = charNode.get(i)
                    if (elem != null && elem.isObject) {
                        val name = elem.get("name").textOrNull() ?: ""
                        if (name.isNotEmpty()) {
                            val gender = elem.get("gender").textOrNull() ?: "unknown"
                            val role = elem.get("role").textOrNull() ?: ""
                            val ageGroup = elem.get("ageGroup").textOrNull()
                                ?: elem.get("age_group").textOrNull() ?: "adult"
                            result.add(
                                CharacterEntity(
                                    name = name,
                                    gender = gender,
                                    role = role,
                                    ageGroup = ageGroup
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return result
    }

    /**
     * Parses a GlobalFilmBible JSON response returned by Pass 0 Pre-Scan.
     * Supports both snake_case and camelCase keys and tolerates conversational wrappers.
     */
    fun parseFilmBible(rawResponse: String): GlobalFilmBible? {
        if (rawResponse.isBlank()) return null
        val clean = stripMarkdownFences(rawResponse).trim()
        if (clean.isEmpty()) return null

        val directNode: JsonNode? = try {
            mapper.readTree(clean)?.takeIf { it.isObject && hasFilmBibleKeys(it) }
        } catch (_: Throwable) {
            null
        }
        val rootNode: JsonNode = directNode ?: extractFilmBibleJsonNode(clean) ?: return null

        if (!rootNode.isObject) return null

        val characters = mutableListOf<CharacterEntity>()
        val charsNode = rootNode.get("characters")
        if (charsNode != null && charsNode.isArray) {
            for (elem in charsNode) {
                if (elem == null || !elem.isObject) continue
                val name = elem.get("name").textOrNull() ?: ""
                if (name.isEmpty()) continue
                val aliases = mutableListOf<String>()
                val aliasesNode = elem.get("aliases")
                if (aliasesNode != null && aliasesNode.isArray) {
                    for (a in aliasesNode) {
                        val aStr = a.textOrNull()
                        if (aStr != null) aliases.add(aStr)
                    }
                }
                val gender = elem.get("gender").textOrNull() ?: "unknown"
                val ageGroup = elem.get("ageGroup").textOrNull()
                    ?: elem.get("age_group").textOrNull()
                    ?: "adult"
                val role = elem.get("role").textOrNull() ?: ""
                characters.add(
                    CharacterEntity(
                        name = name,
                        aliases = aliases,
                        gender = gender,
                        ageGroup = ageGroup,
                        role = role,
                        isEphemeral = false
                    )
                )
            }
        }

        val relationships = mutableListOf<CharacterRelationship>()
        val relsNode = rootNode.get("relationships")
        if (relsNode != null && relsNode.isArray) {
            for (elem in relsNode) {
                if (elem == null || !elem.isObject) continue
                val pair = mutableListOf<String>()
                val pairNode = elem.get("pair")
                if (pairNode != null && pairNode.isArray) {
                    for (p in pairNode) {
                        val ps = p.textOrNull()
                        if (ps != null) pair.add(ps)
                    }
                }
                val speaker = elem.get("speaker").textOrNull()
                val target = elem.get("target").textOrNull()
                if (pair.isEmpty() && speaker != null && target != null) {
                    pair.add(speaker)
                    pair.add(target)
                }
                if (pair.size < 2) continue

                val relType = elem.get("type").textOrNull()
                    ?: elem.get("relation").textOrNull()
                    ?: "peers"

                val addressMatrix = LinkedHashMap<String, Any>()
                val matrixNode = elem.get("addressMatrix") ?: elem.get("address_matrix")
                if (matrixNode != null && matrixNode.isObject) {
                    addressMatrix.putAll(sanitizeAddressMatrixMap(matrixNode))
                } else {
                    val addressVal = elem.get("address")
                    if (addressVal != null) {
                        if (addressVal.isObject) {
                            addressMatrix.putAll(sanitizeAddressMatrixMap(addressVal))
                        } else {
                            addressVal.textOrNull()?.let { addressMatrix["address"] = it }
                        }
                    }
                }

                relationships.add(
                    CharacterRelationship(
                        pair = pair,
                        type = relType,
                        isEphemeral = false,
                        addressMatrix = addressMatrix
                    )
                )
            }
        }

        val autoGlossary = LinkedHashMap<String, String>()
        val glossaryNode = rootNode.get("auto_glossary")
            ?: rootNode.get("autoGlossary")
            ?: rootNode.get("glossary")
        if (glossaryNode != null && glossaryNode.isObject) {
            val fields = glossaryNode.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                val k = entry.key?.trim() ?: ""
                val v = entry.value.textOrNull()
                if (k.isNotEmpty() && v != null) {
                    autoGlossary[k] = v
                }
            }
        }

        val toneGuide = rootNode.get("tone_guide").textOrNull()
            ?: rootNode.get("toneGuide").textOrNull()
            ?: ""

        if (characters.isEmpty() && relationships.isEmpty() && autoGlossary.isEmpty() && toneGuide.isBlank()) {
            return null
        }

        return GlobalFilmBible(
            characters = characters,
            relationships = relationships,
            autoGlossary = autoGlossary,
            toneGuide = toneGuide
        )
    }

    private fun hasFilmBibleKeys(node: JsonNode): Boolean {
        return node.has("characters") ||
            node.has("relationships") ||
            node.has("auto_glossary") ||
            node.has("autoGlossary") ||
            node.has("glossary") ||
            node.has("tone_guide") ||
            node.has("toneGuide")
    }

    private fun extractFilmBibleJsonNode(text: String): JsonNode? {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val start = text.indexOf('{', searchFrom)
            if (start < 0) return null

            var depth = 0
            var inString = false
            var escapeNext = false
            var end = -1
            for (i in start until text.length) {
                val c = text[i]
                if (escapeNext) {
                    escapeNext = false
                    continue
                }
                if (c == '\\' && inString) {
                    escapeNext = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
            }

            if (end > start) {
                val candidate = text.substring(start, end + 1)
                try {
                    val node = mapper.readTree(candidate)
                    if (node != null && node.isObject && hasFilmBibleKeys(node)) {
                        return node
                    }
                } catch (_: Throwable) {}
            }
            searchFrom = start + 1
        }
        return null
    }

    /**
     * Inspects a JsonNode to locate the primary subtitle array node.
     */
    private fun extractArrayNode(node: JsonNode?): JsonNode? {
        if (node == null) return null
        if (node.isArray) return node

        if (node.isObject) {
            // Check standard OpenAI structured output key
            if (node.has("translations") && node.get("translations").isArray) {
                return node.get("translations")
            }
            // Check common alternative wrapper keys
            val wellKnownKeys = listOf("data", "subtitles", "items", "cues", "results")
            for (key in wellKnownKeys) {
                if (node.has(key) && node.get(key).isArray) {
                    return node.get(key)
                }
            }
            // Otherwise search all child fields for the first array node
            return node.elements().asSequence().firstOrNull { it.isArray }
        }

        return null
    }

    /**
     * Reads SubtitleItem objects from a JsonNode array and populates the result map.
     */
    private fun populateItems(arrayNode: JsonNode, result: HashMap<Int, String>) {
        try {
            val items: List<SubtitleItem> = mapper.convertValue(
                arrayNode,
                object : TypeReference<List<SubtitleItem>>() {}
            )
            for (item in items) {
                val text = item.text.trim()
                if (text.isNotEmpty()) {
                    result[item.id] = text
                }
            }
        } catch (_: Throwable) {
            // If strict TypeReference conversion fails, parse manually from array elements
            for (i in 0 until arrayNode.size()) {
                val elem = arrayNode.get(i)
                if (elem != null && elem.isObject) {
                    val id = elem.get("id")?.takeIf { !it.isNull }?.asInt() ?: (i + 1)
                    val text = elem.get("text").nonNullText()?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        result[id] = text
                    }
                }
            }
        }
    }
}
