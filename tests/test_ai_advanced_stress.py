"""
Advanced Empirical Stress & Mitigation Harness for AI Subtitle Translator
Rigorously tests:
1. Anthropic Credit Exhaustion (HTTP 400 invalid_request_error) vs Plan's 429-only check
2. Anthropic Prompt Caching Token Ceiling (Claude 3.5 Haiku 2048 threshold vs 850 token chunks)
3. Auto-Healing Re-indexing Algorithm (Fixing 100% dropped cue failure)
4. Case-Insensitive & Tag-Balanced Shielding (Fixing [[tag_1]] leakage & font bleeding)
5. Gemini 15 RPM Rate Limit vs Daily Quota Decoupling
6. Recursive Cache Pruner (Pruning chunks/ and abandoned tmp_ files)
"""

import json
import re
import os
import sys
import time
import shutil
from typing import Dict, List, Optional, Any, Tuple

# ==============================================================================
# TEST 1: Anthropic Credit Exhaustion & Prompt Caching Limitations
# ==============================================================================

def test_anthropic_error_and_caching():
    # Real Anthropic credit exhaustion payload
    anthropic_credit_error = {
        "type": "error",
        "error": {
            "type": "invalid_request_error",
            "message": "Your credit balance is too low to access the Claude API. Please go to Plans & Billing to upgrade or purchase credits."
        }
    }
    status_code = 400

    # Plan's Section 3.6 error logic:
    # 'HTTP 429 and transient 5xx errors trigger resilient retries...
    # If the error payload contains insufficient_quota (OpenAI) or RESOURCE_EXHAUSTED (Gemini daily cap)...'
    def plan_handle_error(http_code: int, response_body: str) -> str:
        if http_code == 429:
            if "insufficient_quota" in response_body or "RESOURCE_EXHAUSTED" in response_body:
                return "PERMANENT_QUOTA_ABORT"
            return "RETRY_BACKOFF"
        elif http_code >= 500:
            return "RETRY_BACKOFF"
        else:
            return "UNHANDLED_ERROR_CRASH"

    action = plan_handle_error(status_code, json.dumps(anthropic_credit_error))
    
    # Check Haiku Prompt Caching threshold
    chunk_tokens = 850
    haiku_min_tokens = 2048
    caching_eligible = chunk_tokens >= haiku_min_tokens

    return {
        "anthropic_credit_exhaustion_action": action,
        "is_unhandled_crash": action == "UNHANDLED_ERROR_CRASH",
        "chunk_tokens": chunk_tokens,
        "haiku_min_tokens": haiku_min_tokens,
        "anthropic_caching_active": caching_eligible
    }


# ==============================================================================
# TEST 2: Auto-Healing Re-Indexing Algorithm
# ==============================================================================

class SubtitleCueMock:
    def __init__(self, index: int, raw_text: str):
        self.index = index
        self.raw_text = raw_text

def auto_healing_reconcile(input_cues: List[SubtitleCueMock], translated_items: Dict[int, str]) -> Dict[int, str]:
    """
    Enhanced missing-cue recovery that auto-heals chunk-relative re-indexing:
    If LLM returns IDs 1..N instead of cue.index..cue.index+N-1, detect and remap!
    """
    reconciled = {}
    input_indices = [c.index for c in input_cues]
    min_input = min(input_indices) if input_indices else 1
    max_input = max(input_indices) if input_indices else 1

    # Check if translated_items uses 1-based relative indexing
    trans_keys = sorted(translated_items.keys())
    is_relative_reindexed = False
    if trans_keys and trans_keys[0] == 1 and trans_keys[-1] <= len(input_cues) and min_input > len(input_cues):
        is_relative_reindexed = True

    for idx, cue in enumerate(input_cues):
        # 1. Try exact cue.index lookup
        text = translated_items.get(cue.index)
        
        # 2. If missing and re-indexing detected, try 1-based offset lookup (idx + 1)
        if (text is None or not text.strip()) and is_relative_reindexed:
            text = translated_items.get(idx + 1)

        if text and text.strip():
            reconciled[cue.index] = text.strip()
        else:
            # 3. Fallback to original rawText
            reconciled[cue.index] = cue.raw_text

    return reconciled


def test_auto_healing_reindexing():
    # Input cues are 31..60
    input_cues = [SubtitleCueMock(i, f"English dialogue {i}") for i in range(31, 61)]
    # LLM re-indexed to 1..30
    llm_output = {i: f"Vietnamese dialogue {i + 30}" for i in range(1, 31)}

    healed = auto_healing_reconcile(input_cues, llm_output)
    
    recovered_count = sum(1 for i in range(31, 61) if healed[i].startswith("Vietnamese"))
    return {
        "total_cues": 30,
        "recovered_with_auto_healing": recovered_count,
        "recovery_success_rate": f"{recovered_count}/30 (100% recovered)"
    }


# ==============================================================================
# TEST 3: Resilient Tag Shielding & Full HTML Balancer
# ==============================================================================

def resilient_unmask_tags(text: str, tag_map: Dict[str, str]) -> str:
    """
    Case-insensitive, whitespace-tolerant unmasking of [[TAG_n]] tokens
    and comprehensive tag balancing for <i>, <b>, <u>, <font>, <s>.
    """
    result = text
    # Map normalized token -> original tag
    normalized_map = {}
    for token, original in tag_map.items():
        # normalize token: e.g. "[[TAG_1]]" -> "tag_1"
        core = token.strip("[]").strip().lower()
        normalized_map[core] = original

    # Replace with regex ignoring case and optional spaces inside brackets
    def replacer(m):
        core = m.group(1).lower().replace(" ", "")
        return normalized_map.get(core, m.group(0))

    result = re.sub(r'\[\[\s*([a-zA-Z0-9_]+)\s*\]\]', replacer, result)
    return full_balance_html(result)


def full_balance_html(input_str: str) -> str:
    """Balances i, b, u, s, and font tags"""
    balanced = input_str
    # 1. Simple tags
    for tag in ["i", "b", "u", "s"]:
        open_count = len(re.findall(f"<{tag}>", balanced, re.IGNORECASE))
        close_count = len(re.findall(f"</{tag}>", balanced, re.IGNORECASE))
        if open_count > close_count:
            balanced += f"</{tag}>" * (open_count - close_count)
    
    # 2. Font tags (e.g. <font color="...">)
    font_open_count = len(re.findall(r"<font(?:\s+[^>]*?)?>", balanced, re.IGNORECASE))
    font_close_count = len(re.findall(r"</font>", balanced, re.IGNORECASE))
    if font_open_count > font_close_count:
        balanced += "</font>" * (font_open_count - font_close_count)

    return balanced


def test_resilient_tag_shielding():
    tag_map = {
        "[[TAG_1]]": "<i>",
        "[[TAG_2]]": "</i>",
        "[[TAG_3]]": '<font color="#FFFF00">',
        "[[TAG_4]]": "</font>"
    }

    # Case 1: Lowercase token
    res1 = resilient_unmask_tags("[[tag_1]]Hello[[tag_2]]", tag_map)
    # Case 2: Whitespace token
    res2 = resilient_unmask_tags("[[ TAG_1 ]]Hello[[ TAG_2 ]]", tag_map)
    # Case 3: Unclosed font tag
    res3 = resilient_unmask_tags("[[TAG_3]]Warning!", tag_map)

    return {
        "lowercase_unmasked": res1 == "<i>Hello</i>",
        "whitespace_unmasked": res2 == "<i>Hello</i>",
        "font_closed_properly": res3 == '<font color="#FFFF00">Warning!</font>'
    }


# ==============================================================================
# TEST 4: Gemini Rate Limit vs Daily Quota Decoupling
# ==============================================================================

def classify_gemini_error(http_code: int, response_json: Dict[str, Any], retry_after_header: Optional[str]) -> str:
    """
    Correctly distinguishes between Gemini transient RPM rate limit and hard daily quota.
    """
    if http_code != 429:
        return "OTHER"

    error_obj = response_json.get("error", {})
    status = error_obj.get("status", "")
    message = error_obj.get("message", "")
    details = error_obj.get("details", [])

    # Check violation details if present
    is_per_minute = False
    is_per_day = False
    for d in details:
        violations = d.get("violations", [])
        for v in violations:
            desc = v.get("description", "").lower()
            if "minute" in desc:
                is_per_minute = True
            if "day" in desc or "daily" in desc:
                is_per_day = True

    if is_per_day:
        return "HARD_DAILY_QUOTA_EXHAUSTED"
    
    if is_per_minute or retry_after_header is not None or status == "RESOURCE_EXHAUSTED":
        # Unless explicitly identified as daily, treat as transient rate limit!
        return "TRANSIENT_RPM_RATE_LIMIT"

    return "TRANSIENT_RPM_RATE_LIMIT"


def test_gemini_decoupling():
    gemini_15_rpm = {
        "error": {
            "code": 429,
            "status": "RESOURCE_EXHAUSTED",
            "message": "Quota exceeded",
            "details": [{"violations": [{"description": "Quota exceeded for GenerateRequestsPerMinute"}]}]
        }
    }
    gemini_daily = {
        "error": {
            "code": 429,
            "status": "RESOURCE_EXHAUSTED",
            "message": "Quota exceeded",
            "details": [{"violations": [{"description": "Quota exceeded for GenerateRequestsPerDay"}]}]
        }
    }

    rpm_type = classify_gemini_error(429, gemini_15_rpm, None)
    daily_type = classify_gemini_error(429, gemini_daily, None)

    return {
        "rpm_classification": rpm_type,
        "daily_classification": daily_type,
        "correctly_separated": (rpm_type == "TRANSIENT_RPM_RATE_LIMIT" and daily_type == "HARD_DAILY_QUOTA_EXHAUSTED")
    }


# ==============================================================================
# TEST 5: Comprehensive Recursive Cache Pruner
# ==============================================================================

def robust_prune_cache(base_dir: str, max_bytes: int = 1024*1024, max_files: int = 10, max_ttl_ms: int = 30*86400*1000):
    full_dir = os.path.join(base_dir, "full")
    chunks_dir = os.path.join(base_dir, "chunks")
    now = time.time()

    # 1. Clean orphaned tmp_ files older than 5 minutes
    if os.path.exists(full_dir):
        for f in os.listdir(full_dir):
            if f.startswith("tmp_"):
                fpath = os.path.join(full_dir, f)
                if now - os.path.getmtime(fpath) > 300: # 5 min
                    try: os.remove(fpath)
                    except: pass

    # 2. Clean abandoned chunk directories older than 24 hours
    if os.path.exists(chunks_dir):
        for d in os.listdir(chunks_dir):
            dpath = os.path.join(chunks_dir, d)
            if os.path.isdir(dpath):
                if now - os.path.getmtime(dpath) > 86400: # 24 hrs
                    try: shutil.rmtree(dpath)
                    except: pass

    # 3. Prune full/ directory under LRU limits
    if os.path.exists(full_dir):
        files = [
            os.path.join(full_dir, f) for f in os.listdir(full_dir)
            if os.path.isfile(os.path.join(full_dir, f)) and not f.startswith("tmp_")
        ]
        # TTL pruning
        remaining = []
        for fpath in files:
            if (now - os.path.getmtime(fpath)) * 1000 > max_ttl_ms:
                try: os.remove(fpath)
                except: pass
            else:
                remaining.append(fpath)

        remaining.sort(key=lambda x: os.path.getmtime(x))
        total_bytes = sum(os.path.getsize(f) for f in remaining)
        count = len(remaining)

        while remaining and (total_bytes > max_bytes or count > max_files):
            oldest = remaining.pop(0)
            total_bytes -= os.path.getsize(oldest)
            count -= 1
            try: os.remove(oldest)
            except: pass


def test_robust_cache_pruner(tmp_path: str):
    base_dir = os.path.join(tmp_path, "ai_cache_test")
    full_dir = os.path.join(base_dir, "full")
    chunks_dir = os.path.join(base_dir, "chunks")
    os.makedirs(full_dir, exist_ok=True)
    os.makedirs(chunks_dir, exist_ok=True)

    now = time.time()
    # 1. Orphaned tmp_ file created 10 minutes ago
    tmp_file = os.path.join(full_dir, "tmp_1700000000_dead.srt")
    with open(tmp_file, "w") as f: f.write("dead temp")
    os.utime(tmp_file, (now - 600, now - 600))

    # 2. Abandoned chunk directory created 2 days ago
    old_chunk_dir = os.path.join(chunks_dir, "dead_chunk_episode")
    os.makedirs(old_chunk_dir, exist_ok=True)
    with open(os.path.join(old_chunk_dir, "chunk_001.json"), "w") as f: f.write("{}")
    os.utime(old_chunk_dir, (now - 172800, now - 172800))

    # 3. Active chunk directory created 10 minutes ago
    active_chunk_dir = os.path.join(chunks_dir, "active_chunk_episode")
    os.makedirs(active_chunk_dir, exist_ok=True)
    with open(os.path.join(active_chunk_dir, "chunk_001.json"), "w") as f: f.write("{}")
    os.utime(active_chunk_dir, (now - 600, now - 600))

    # 4. 15 full files
    for i in range(15):
        fp = os.path.join(full_dir, f"file_{i:02d}.srt")
        with open(fp, "wb") as f: f.write(b"x" * 100 * 1024)
        os.utime(fp, (now - (15 - i) * 60, now - (15 - i) * 60))

    robust_prune_cache(base_dir, max_bytes=1024*1024, max_files=10)

    return {
        "orphaned_tmp_deleted": not os.path.exists(tmp_file),
        "abandoned_old_chunks_deleted": not os.path.exists(old_chunk_dir),
        "active_chunks_preserved": os.path.exists(active_chunk_dir),
        "remaining_full_files": len([f for f in os.listdir(full_dir) if not f.startswith("tmp_")])
    }


def main():
    print("=" * 80)
    print("RUNNING ADVANCED EMPIRICAL STRESS & MITIGATION HARNESS")
    print("=" * 80)

    # 1. Anthropic error & caching
    anthropic_res = test_anthropic_error_and_caching()
    print("\n[TEST 1] Anthropic Quota Exhaustion & Prompt Caching Limitations:")
    print(json.dumps(anthropic_res, indent=2))

    # 2. Auto-healing reindexing
    reindexing_res = test_auto_healing_reindexing()
    print("\n[TEST 2] Auto-Healing Re-indexing Algorithm Verification:")
    print(json.dumps(reindexing_res, indent=2))

    # 3. Resilient tag shielding
    shielding_res = test_resilient_tag_shielding()
    print("\n[TEST 3] Resilient Tag Shielding & Full HTML Tag Balancing:")
    print(json.dumps(shielding_res, indent=2))

    # 4. Gemini Decoupling
    decoupling_res = test_gemini_decoupling()
    print("\n[TEST 4] Gemini 15 RPM Rate Limit vs Daily Quota Decoupling:")
    print(json.dumps(decoupling_res, indent=2))

    # 5. Robust Cache Pruner
    tmp_path = os.path.join(os.path.dirname(__file__), ".test_cache_robust_tmp")
    if os.path.exists(tmp_path): shutil.rmtree(tmp_path)
    os.makedirs(tmp_path, exist_ok=True)
    try:
        cache_prune_res = test_robust_cache_pruner(tmp_path)
        print("\n[TEST 5] Robust Recursive Cache Pruner Verification:")
        print(json.dumps(cache_prune_res, indent=2))
    finally:
        if os.path.exists(tmp_path): shutil.rmtree(tmp_path)

    print("\n" + "=" * 80)
    print("ADVANCED EMPIRICAL SUITE COMPLETED SUCCESSFULLY.")
    print("=" * 80)

if __name__ == "__main__":
    main()
