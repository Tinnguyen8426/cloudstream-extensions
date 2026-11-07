"""
Empirical Adversarial Stress Test Suite for AI Subtitle Translator Plan
Focus Areas:
1. AI Provider Schemas & Payload Compliance (OpenAI, Gemini REST v1beta, Anthropic Messages)
2. Prompt Engineering, ID Mapping & Line Desynchronization Resilience
3. Token Economics, Rate Limiting, HTTP 429 & LRU Cache Eviction
"""

import json
import re
import os
import sys
import time
import shutil
import hashlib
from typing import Dict, List, Optional, Any, Tuple

# ==============================================================================
# SUITE 1: AI Provider Schemas & Payload Compliance
# ==============================================================================

def test_gemini_url_builder():
    """
    Test buildGeminiUrl algorithm from Section 3.2:
    fun buildGeminiUrl(baseUrl: String?, model: String): String {
        val cleanBase = (baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://generativelanguage.googleapis.com").removeSuffix("/")
        val cleanModel = model.trim().ifEmpty { "gemini-1.5-flash" }
        return if (cleanBase.endsWith("/v1beta")) {
            "$cleanBase/models/$cleanModel:generateContent"
        } else {
            "$cleanBase/v1beta/models/$cleanModel:generateContent"
        }
    }
    """
    def build_gemini_url(base_url: Optional[str], model: str) -> str:
        clean_base = (base_url.strip() if base_url and base_url.strip() else "https://generativelanguage.googleapis.com").rstrip("/")
        clean_model = model.strip() if model.strip() else "gemini-1.5-flash"
        if clean_base.endswith("/v1beta"):
            return f"{clean_base}/models/{clean_model}:generateContent"
        else:
            return f"{clean_base}/v1beta/models/{clean_model}:generateContent"

    # Standard case
    url1 = build_gemini_url(None, "gemini-1.5-flash")
    assert url1 == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    # Edge case 1: User provides base URL ending with /v1beta
    url2 = build_gemini_url("https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-flash")
    assert url2 == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    # Edge case 2 (DEFECT): User enters model as 'models/gemini-1.5-flash' (standard in Google SDKs)
    url3 = build_gemini_url(None, "models/gemini-1.5-flash")
    # This produces double models/ -> models/models/gemini-1.5-flash:generateContent (404 on API!)
    has_double_models = "/models/models/" in url3

    # Edge case 3: User enters base URL with trailing slash 'https://generativelanguage.googleapis.com/v1beta/'
    url4 = build_gemini_url("https://generativelanguage.googleapis.com/v1beta/", "gemini-1.5-flash")
    assert url4 == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    return {
        "standard_url": url1,
        "models_prefix_vulnerability": has_double_models,
        "double_models_url": url3
    }


def test_openai_url_normalizer():
    """
    Test normalizeOpenAiUrl from Section 3.1:
    fun normalizeOpenAiUrl(baseUrl: String?): String {
        val clean = (baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1").removeSuffix("/")
        return when {
            clean.endswith("/chat/completions") -> clean
            clean.endswith("/v1") -> "$clean/chat/completions"
            clean.contains("deepseek.com") && !clean.endswith("/v1") -> "$clean/chat/completions"
            else -> "$clean/chat/completions"
        }
    }
    """
    def normalize_openai_url(base_url: Optional[str]) -> str:
        clean = (base_url.strip() if base_url and base_url.strip() else "https://api.openai.com/v1").rstrip("/")
        if clean.endswith("/chat/completions"):
            return clean
        elif clean.endswith("/v1"):
            return f"{clean}/chat/completions"
        elif "deepseek.com" in clean and not clean.endswith("/v1"):
            return f"{clean}/chat/completions"
        else:
            return f"{clean}/chat/completions"

    # OpenAI standard
    u1 = normalize_openai_url("https://api.openai.com/v1")
    assert u1 == "https://api.openai.com/v1/chat/completions"

    # OpenRouter
    u2 = normalize_openai_url("https://openrouter.ai/api/v1")
    assert u2 == "https://openrouter.ai/api/v1/chat/completions"

    # DeepSeek without /v1
    u3 = normalize_openai_url("https://api.deepseek.com")
    assert u3 == "https://api.deepseek.com/chat/completions"

    # Groq without /v1: User enters https://api.groq.com/openai
    u4 = normalize_openai_url("https://api.groq.com/openai")
    # Will become https://api.groq.com/openai/chat/completions (Groq requires /openai/v1/chat/completions!)
    groq_missing_v1 = u4 != "https://api.groq.com/openai/v1/chat/completions"

    return {
        "openai_standard": u1,
        "openrouter_standard": u2,
        "deepseek_clean": u3,
        "groq_missing_v1": groq_missing_v1,
        "groq_actual_output": u4
    }


def test_openai_json_mode_and_response_format():
    """
    OpenAI JSON mode requires:
    1. Prompt must contain the string 'json' or 'JSON'.
    2. Model response MUST be a root JSON object { ... }, NOT a root JSON array [ ... ].
    Let's test whether the schema definition and prompts in the plan have a mismatch.
    """
    # Section 3.1: line 670 asks for a JSON object with key 'translations':
    prompt_3_1 = "Output a JSON object with key 'translations' containing an array of objects with integer 'id' and string 'text'."
    # Section 3.5: line 1000 asks for a root JSON array:
    prompt_3_5 = "Respond ONLY with a valid JSON array of objects with keys 'id' (integer) and 'text' (string)."

    # Let's verify Jackson deserialization behavior on both formats
    obj_payload = '{"translations": [{"id": 1, "text": "Xin chao"}]}'
    arr_payload = '[{"id": 1, "text": "Xin chao"}]'

    # Can a List parser parse obj_payload directly?
    def parse_as_list(json_str: str) -> Tuple[bool, Optional[str]]:
        try:
            data = json.loads(json_str)
            if not isinstance(data, list):
                return False, f"Expected list, got {type(data).__name__}"
            return True, None
        except Exception as e:
            return False, str(e)

    def parse_as_object(json_str: str) -> Tuple[bool, Optional[str]]:
        try:
            data = json.loads(json_str)
            if not isinstance(data, dict) or "translations" not in data:
                return False, f"Expected dict with 'translations', got {type(data).__name__}"
            return True, None
        except Exception as e:
            return False, str(e)

    list_ok_on_arr, _ = parse_as_list(arr_payload)
    list_ok_on_obj, err_list_on_obj = parse_as_list(obj_payload)

    obj_ok_on_obj, _ = parse_as_object(obj_payload)
    obj_ok_on_arr, err_obj_on_arr = parse_as_object(arr_payload)

    return {
        "prompt_conflict": prompt_3_1 != prompt_3_5,
        "list_parser_on_object_fails": not list_ok_on_obj,
        "list_parser_error": err_list_on_obj,
        "object_parser_on_array_fails": not obj_ok_on_arr,
        "object_parser_error": err_obj_on_arr
    }


def test_markdown_code_fence_stripper():
    """
    Test extraction of JSON when an LLM wraps the response in markdown code blocks:
    ```json ... ``` or conversational text.
    """
    raw_responses = [
        # Case 1: Pure array
        '[{"id": 1, "text": "Hello"}]',
        # Case 2: Markdown fenced json
        '```json\n[{"id": 1, "text": "Hello"}]\n```',
        # Case 3: Markdown fenced without 'json'
        '```\n[{"id": 1, "text": "Hello"}]\n```',
        # Case 4: Conversational prefix + fence
        'Sure, here is the translation:\n```json\n[{"id": 1, "text": "Hello"}]\n```\nHope that helps!',
        # Case 5: Anthropic prefill continuation (raw content from Anthropic starts without '[')
        '{"id": 1, "text": "Hello"}]',
        # Case 6: Delimiter fallback
        '<<<1>>>\nHello\n<<<2>>>\nWorld'
    ]

    def extract_json_cues(raw: str, is_anthropic_prefill: bool = False) -> List[Dict[str, Any]]:
        text = raw.strip()
        if is_anthropic_prefill and not text.startswith("["):
            text = "[" + text

        # Strip markdown fences
        fence_match = re.search(r'```(?:json)?\s*([\s\S]*?)\s*```', text)
        if fence_match:
            candidate = fence_match.group(1).strip()
        else:
            candidate = text

        # Try parsing JSON array directly
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, list):
                return parsed
            if isinstance(parsed, dict) and "translations" in parsed:
                return parsed["translations"]
        except Exception:
            pass

        # Try regex search for bracketed array [ ... ]
        array_match = re.search(r'(\[\s*\{[\s\S]*\}\s*\])', text)
        if array_match:
            try:
                parsed = json.loads(array_match.group(1))
                if isinstance(parsed, list):
                    return parsed
            except Exception:
                pass

        # Fallback to tagged delimiter <<<ID>>> text
        delimiter_cues = []
        delim_pattern = re.compile(r'<<<(\d+)>>>\s*([\s\S]*?)(?=(?:<<<\d+>>>|$))')
        for match in delim_pattern.finditer(text):
            cue_id = int(match.group(1))
            cue_text = match.group(2).strip()
            delimiter_cues.append({"id": cue_id, "text": cue_text})

        return delimiter_cues

    results = []
    for idx, resp in enumerate(raw_responses):
        is_anthropic = (idx == 4)
        extracted = extract_json_cues(resp, is_anthropic_prefill=is_anthropic)
        results.append({
            "case": idx + 1,
            "success": len(extracted) > 0,
            "count": len(extracted),
            "item_1_id": extracted[0]["id"] if extracted else None
        })

    return results


# ==============================================================================
# SUITE 2: Prompt Engineering & Line Desynchronization Resilience
# ==============================================================================

class MockCue:
    def __init__(self, index: int, raw_text: str, start_ms: int, end_ms: int):
        self.index = index
        self.raw_text = raw_text
        self.start_ms = start_ms
        self.end_ms = end_ms

def reconcile_chunk_items(input_cues: List[MockCue], translated_items: Dict[int, str]) -> Dict[int, str]:
    """
    Exact implementation of Section 3.5:
    fun reconcileChunkItems(
        inputCues: List<SubtitleCue>,
        translatedItems: Map<Int, String>
    ): Map<Int, String> {
        val reconciled = HashMap<Int, String>(inputCues.size)
        for (cue in inputCues) {
            val text = translatedItems[cue.index]?.trim()
            if (!text.isNullOrBlank()) {
                reconciled[cue.index] = text
            } else {
                // Missing line recovered from original source dialogue
                reconciled[cue.index] = cue.rawText
            }
        }
        return reconciled
    }
    """
    reconciled = {}
    for cue in input_cues:
        text = translated_items.get(cue.index)
        if text and text.strip():
            reconciled[cue.index] = text.strip()
        else:
            reconciled[cue.index] = cue.raw_text
    return reconciled


def test_line_desync_scenarios():
    """
    Stress-test line desynchronization and missing-line recovery across 5 adversarial failure modes:
    A. Normal 1-to-1 match (baseline)
    B. Dropped line (model drops cue 17)
    C. Re-indexing defect (model returns IDs 1..30 instead of global IDs 31..60)
    D. Line merge (model merges cue 10 and 11 into cue 10, omitting 11)
    E. Hallucinated extra IDs (model hallucinates cue 999 and previous context cue 30)
    """
    # Chunk 2: Cues 31 to 60
    cues_chunk_2 = [
        MockCue(i, f"Original English line {i}", (i - 1) * 2000, i * 2000)
        for i in range(31, 61)
    ]

    # Scenario A: Baseline 100% match
    trans_a = {i: f"Translated line {i}" for i in range(31, 61)}
    rec_a = reconcile_chunk_items(cues_chunk_2, trans_a)
    assert len(rec_a) == 30
    assert all(rec_a[i] == f"Translated line {i}" for i in range(31, 61))

    # Scenario B: Model drops cue 45
    trans_b = {i: f"Translated line {i}" for i in range(31, 61) if i != 45}
    rec_b = reconcile_chunk_items(cues_chunk_2, trans_b)
    assert len(rec_b) == 30
    assert rec_b[45] == "Original English line 45"  # Fallback to source
    assert rec_b[46] == "Translated line 46"       # No desynchronization!

    # Scenario C: CRITICAL FLAW - Model outputs chunk-relative IDs 1..30 instead of 31..60!
    trans_c = {i: f"Translated line {i}" for i in range(1, 31)}
    rec_c = reconcile_chunk_items(cues_chunk_2, trans_c)
    assert len(rec_c) == 30
    # Every cue from 31 to 60 failed lookup in translated_items because translated_items has keys 1..30!
    dropped_count = sum(1 for i in range(31, 61) if rec_c[i].startswith("Original English"))
    assert dropped_count == 30, "All 30 cues should have fallen back due to ID mismatch!"

    # Scenario D: Model merges cue 40 & 41 into cue 40
    trans_d = {i: f"Translated line {i}" for i in range(31, 61) if i != 41}
    trans_d[40] = "Translated line 40 and line 41 merged together"
    rec_d = reconcile_chunk_items(cues_chunk_2, trans_d)
    assert rec_d[40] == "Translated line 40 and line 41 merged together"
    assert rec_d[41] == "Original English line 41"  # English line 41 still appears!

    # Scenario E: Model hallucinates cue 30 (from previous context) and cue 999
    trans_e = {i: f"Translated line {i}" for i in range(31, 61)}
    trans_e[30] = "Context line 30 translated"
    trans_e[999] = "Hallucinated line 999"
    rec_e = reconcile_chunk_items(cues_chunk_2, trans_e)
    assert len(rec_e) == 30
    assert 30 not in rec_e
    assert 999 not in rec_e

    return {
        "baseline_pass": True,
        "dropped_cue_fallback_pass": True,
        "reindexing_failure_rate": f"{dropped_count}/30 (100% failure)",
        "merged_cue_duplicate_behavior": "Cue 40 contains merged text, Cue 41 contains untranslated source",
        "hallucination_filtered": True
    }


def test_tag_shielding_and_html_balancing():
    """
    Test tag shielding:
    1. Case variation: [[TAG_1]] -> [[tag_1]] or [[TAG 1]]
    2. HTML tag balancing: <i>, <b>, <u>, and unclosed <font color="...">
    """
    tag_map = {
        "[[TAG_1]]": "<i>",
        "[[TAG_2]]": "</i>",
        "[[TAG_3]]": '<font color="#FF0000">',
        "[[TAG_4]]": "</font>"
    }

    def unmask_tags(text: str, tags: Dict[str, str]) -> str:
        res = text
        for token, original in tags.items():
            res = res.replace(token, original)
        return balance_html_tags(res)

    def balance_html_tags(text: str) -> str:
        """From Section 2.5: val tags = listOf('i', 'b', 'u')"""
        tags = ["i", "b", "u"]
        balanced = text
        for tag in tags:
            open_count = len(re.findall(f"<{tag}>", balanced, re.IGNORECASE))
            close_count = len(re.findall(f"</{tag}>", balanced, re.IGNORECASE))
            if open_count > close_count:
                balanced += f"</{tag}>" * (open_count - close_count)
        return balanced

    # Case 1: Exact match unmasking
    text_normal = "[[TAG_1]]Xin chao[[TAG_2]]"
    unmasked_normal = unmask_tags(text_normal, tag_map)
    assert unmasked_normal == "<i>Xin chao</i>"

    # Case 2: Model lowered casing [[tag_1]]
    text_lower = "[[tag_1]]Xin chao[[tag_2]]"
    unmasked_lower = unmask_tags(text_lower, tag_map)
    # Token was NOT replaced because replace() is case-sensitive!
    tag_leakage_lower = "[[tag_1]]" in unmasked_lower

    # Case 3: Missing closing </i> tag
    text_unclosed_i = "[[TAG_1]]Xin chao ban"
    unmasked_i = unmask_tags(text_unclosed_i, tag_map)
    assert unmasked_i == "<i>Xin chao ban</i>"  # Balancer fixed </i>

    # Case 4: Unclosed <font color="#FF0000">
    text_unclosed_font = "[[TAG_3]]Chu y nguy hiem"
    unmasked_font = unmask_tags(text_unclosed_font, tag_map)
    # <font> is NOT in listOf("i", "b", "u")!
    # Therefore it is NOT closed, bleeding color to subsequent cues!
    font_not_balanced = not unmasked_font.endswith("</font>")

    return {
        "exact_tag_unmask_pass": True,
        "lowercase_tag_leakage": tag_leakage_lower,
        "i_tag_balanced": True,
        "font_tag_unbalanced_leak": font_not_balanced
    }


# ==============================================================================
# SUITE 3: Token Economics, Rate Limiting & LRU Caching Resilience
# ==============================================================================

def test_gemini_rate_limit_vs_quota_conflation():
    """
    Test Section 3.6 rate limit and quota exhaustion logic:
    'If the error payload contains insufficient_quota (OpenAI) or RESOURCE_EXHAUSTED (Gemini daily cap),
    the client aborts retries immediately, flags the provider in-memory for 15 minutes,
    notifies the user via Toast, and gracefully falls back to the original untranslated subtitle stream.'

    We simulate:
    - Gemini Free Tier 15 RPM rate limit (temporary limit reached on chunk 16).
    - Gemini API error payload for 15 RPM.
    """
    gemini_15_rpm_error = {
        "error": {
            "code": 429,
            "message": "Resource has been exhausted (e.g. check quota).",
            "status": "RESOURCE_EXHAUSTED",
            "details": [
                {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                        {
                            "subject": "project:12345",
                            "description": "Quota exceeded for quota metric 'GenerateRequestsPerMinute' and limit 'RequestsPerMinutePerProject' of service 'generativelanguage.googleapis.com'"
                        }
                    ]
                }
            ]
        }
    }

    gemini_daily_cap_error = {
        "error": {
            "code": 429,
            "message": "Resource has been exhausted (e.g. check quota).",
            "status": "RESOURCE_EXHAUSTED",
            "details": [
                {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                        {
                            "subject": "project:12345",
                            "description": "Quota exceeded for quota metric 'GenerateRequestsPerDay' and limit 'RequestsPerDayPerProject' of service 'generativelanguage.googleapis.com'"
                        }
                    ]
                }
            ]
        }
    }

    def evaluate_gemini_error_handling(error_dict: Dict[str, Any]) -> str:
        error_json_str = json.dumps(error_dict)
        # Plan's condition from Section 3.6:
        if "RESOURCE_EXHAUSTED" in error_json_str:
            # Plan action: abort retries immediately, flag in-memory for 15m, fall back to untranslated
            return "ABORT_AND_BAN_15M"
        return "RETRY_WITH_BACKOFF"

    action_rpm = evaluate_gemini_error_handling(gemini_15_rpm_error)
    action_daily = evaluate_gemini_error_handling(gemini_daily_cap_error)

    # Both return ABORT_AND_BAN_15M!
    premature_abort_on_rpm = (action_rpm == "ABORT_AND_BAN_15M")

    return {
        "action_on_15_rpm": action_rpm,
        "action_on_daily_cap": action_daily,
        "premature_abort_on_transient_15_rpm": premature_abort_on_rpm
    }


def test_lru_cache_and_disk_leak(tmp_path: str):
    """
    Test SubtitleCacheManager from Section 5.3:
    1. Enforce 50MB / 200 files eviction.
    2. Check if chunks/ directory is pruned.
    3. Check if abandoned tmp_*.srt files are pruned.
    """
    base_dir = os.path.join(tmp_path, "ai_sub_cache")
    full_dir = os.path.join(base_dir, "full")
    chunks_dir = os.path.join(base_dir, "chunks")
    os.makedirs(full_dir, exist_ok=True)
    os.makedirs(chunks_dir, exist_ok=True)

    max_bytes = 1024 * 1024 # 1 MB for mock test
    max_files = 10          # 10 files for mock test

    # 1. Populate fullDir with 15 files of 100 KB each (total 1.5 MB > 1.0 MB, 15 > 10 files)
    now = time.time()
    for i in range(15):
        fname = f"hash_{i:03d}.srt"
        fpath = os.path.join(full_dir, fname)
        with open(fpath, "wb") as f:
            f.write(b"x" * 100 * 1024)
        # Stagger last modified
        os.utime(fpath, (now - (15 - i) * 60, now - (15 - i) * 60))

    # Also create an abandoned tmp_ file
    tmp_file = os.path.join(full_dir, "tmp_1700000000_corrupted.srt")
    with open(tmp_file, "wb") as f:
        f.write(b"abandoned temp data" * 1000)

    # Also create abandoned chunks in chunks/ directory (from interrupted episodes)
    for i in range(5):
        chunk_episode_dir = os.path.join(chunks_dir, f"interrupted_hash_{i:03d}")
        os.makedirs(chunk_episode_dir, exist_ok=True)
        with open(os.path.join(chunk_episode_dir, "chunk_001.json"), "w") as f:
            f.write('{"cues": []}')
        with open(os.path.join(chunk_episode_dir, "chunk_002.json"), "w") as f:
            f.write('{"cues": []}')

    # Execute pruneCache algorithm from Section 5.3:
    def prune_cache():
        # Exact logic from SubtitleCacheManager.kt:
        # val files = fullDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") }?.toMutableList() ?: return
        all_entries = os.listdir(full_dir)
        files = [
            os.path.join(full_dir, f) for f in all_entries
            if os.path.isfile(os.path.join(full_dir, f)) and not f.startswith("tmp_")
        ]
        # Sort by mtime
        files.sort(key=lambda x: os.path.getmtime(x))
        total_bytes = sum(os.path.getsize(f) for f in files)
        file_count = len(files)

        while files and (total_bytes > max_bytes or file_count > max_files):
            oldest = files.pop(0)
            total_bytes -= os.path.getsize(oldest)
            file_count -= 1
            os.remove(oldest)

    prune_cache()

    remaining_full_files = [f for f in os.listdir(full_dir) if not f.startswith("tmp_")]
    tmp_file_exists = os.path.exists(tmp_file)
    remaining_chunk_dirs = os.listdir(chunks_dir)

    return {
        "pruned_full_files_count": len(remaining_full_files),
        "full_files_cap_enforced": len(remaining_full_files) <= max_files,
        "tmp_file_survived_unpruned": tmp_file_exists,
        "chunk_dirs_count": len(remaining_chunk_dirs),
        "chunk_dirs_completely_ignored_by_pruner": len(remaining_chunk_dirs) == 5
    }


def main():
    print("=" * 80)
    print("RUNNING EMPIRICAL ADVERSARIAL STRESS TEST SUITE")
    print("=" * 80)

    # 1. AI Provider Schemas
    gemini_url_results = test_gemini_url_builder()
    print("\n[TEST 1.1] Gemini URL Construction:")
    print(json.dumps(gemini_url_results, indent=2))

    openai_url_results = test_openai_url_normalizer()
    print("\n[TEST 1.2] OpenAI URL Normalization:")
    print(json.dumps(openai_url_results, indent=2))

    json_mode_results = test_openai_json_mode_and_response_format()
    print("\n[TEST 1.3] OpenAI JSON Mode vs System Prompt Mismatch:")
    print(json.dumps(json_mode_results, indent=2))

    fence_results = test_markdown_code_fence_stripper()
    print("\n[TEST 1.4] Markdown Code Fence & JSON Extraction Resilience:")
    print(json.dumps(fence_results, indent=2))

    # 2. Line Desynchronization & Cues
    desync_results = test_line_desync_scenarios()
    print("\n[TEST 2.1] Subtitle Line Desync & Missing-Line Recovery Scenarios:")
    print(json.dumps(desync_results, indent=2))

    tag_results = test_tag_shielding_and_html_balancing()
    print("\n[TEST 2.2] Tag Shielding & HTML Balancing Defect Analysis:")
    print(json.dumps(tag_results, indent=2))

    # 3. Rate Limits & Caching
    gemini_quota_results = test_gemini_rate_limit_vs_quota_conflation()
    print("\n[TEST 3.1] Gemini 15 RPM vs Daily Quota Conflation Bug:")
    print(json.dumps(gemini_quota_results, indent=2))

    tmp_cache_test_dir = os.path.join(os.path.dirname(__file__), ".test_cache_tmp")
    if os.path.exists(tmp_cache_test_dir):
        shutil.rmtree(tmp_cache_test_dir)
    os.makedirs(tmp_cache_test_dir, exist_ok=True)
    try:
        cache_results = test_lru_cache_and_disk_leak(tmp_cache_test_dir)
        print("\n[TEST 3.2] LRU Cache Eviction & Unbounded Storage Leak:")
        print(json.dumps(cache_results, indent=2))
    finally:
        if os.path.exists(tmp_cache_test_dir):
            shutil.rmtree(tmp_cache_test_dir)

    print("\n" + "=" * 80)
    print("ALL EMPIRICAL TESTS COMPLETED.")
    print("=" * 80)

if __name__ == "__main__":
    main()
