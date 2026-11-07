package com.lagradost.cloudstream3.subtitles.ai.parser

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
        if (!candidate.startsWith("[") && candidate.endsWith("]") && (candidate.startsWith("{") || candidate.contains("\"id\""))) {
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
                    val id = elem.get("id")?.asInt() ?: (i + 1)
                    val text = elem.get("text")?.asText()?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        result[id] = text
                    }
                }
            }
        }
    }
}
