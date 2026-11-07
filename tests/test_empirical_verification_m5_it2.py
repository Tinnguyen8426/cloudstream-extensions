"""
Empirical verification script for m5_challenger_2_it2
Directly testing the algorithms and logic defined in AI_SUBTITLE_TRANSLATOR_PLAN.md:
1. Gemini 15 RPM Rate Limit vs Quota Decoupling
2. Line Desynchronization Auto-Healing (autoHealingReconcile)
3. Universal Polymorphic Deserializer (AITranslationParser)
4. Anthropic endpoint and credit handling
5. Recursive Cache Pruning (pruneCache)
"""

import json
import re
import os
import shutil
import time

def test_1_gemini_decoupling():
    """Verify Section 3.6 Gemini Rate Limit vs Quota Decoupling"""
    def handle_gemini_error(code: int, error_body: str, attempt: int):
        if code == 429:
            is_daily_quota = "perday" in error_body.lower() or "generaterequestsperday" in error_body.lower()
            if is_daily_quota:
                # Triggers circuit breaker and fallback
                return {"action": "CIRCUIT_BREAKER_FALLBACK", "retry": False}
            # Transient 15 RPM rate limit: exponential backoff
            backoff = (5000 * (1 << min(attempt, 3)))
            return {"action": "TRANSIENT_BACKOFF_RETRY", "retry": True, "backoff_ms": backoff}
        return {"action": "OTHER", "retry": False}

    daily_payload = json.dumps({
        "error": {
            "code": 429,
            "message": "Resource has been exhausted (e.g. check quota).",
            "status": "RESOURCE_EXHAUSTED",
            "details": [{"violations": [{"description": "Quota exceeded for GenerateRequestsPerDay"}]}]
        }
    })

    rpm_payload = json.dumps({
        "error": {
            "code": 429,
            "message": "Resource has been exhausted (e.g. check quota).",
            "status": "RESOURCE_EXHAUSTED",
            "details": [{"violations": [{"description": "Quota exceeded for GenerateRequestsPerMinute"}]}]
        }
    })

    generic_429 = json.dumps({
        "error": {
            "code": 429,
            "message": "Resource has been exhausted (e.g. check quota).",
            "status": "RESOURCE_EXHAUSTED"
        }
    })

    res_daily = handle_gemini_error(429, daily_payload, 0)
    res_rpm = handle_gemini_error(429, rpm_payload, 0)
    res_generic = handle_gemini_error(429, generic_429, 0)

    assert res_daily["retry"] is False and res_daily["action"] == "CIRCUIT_BREAKER_FALLBACK", "Daily quota should trigger circuit breaker"
    assert res_rpm["retry"] is True and res_rpm["action"] == "TRANSIENT_BACKOFF_RETRY", "15 RPM limit should trigger retry"
    assert res_generic["retry"] is True and res_generic["action"] == "TRANSIENT_BACKOFF_RETRY", "Generic 429 without PerDay should retry"

    return {
        "pass": True,
        "daily_action": res_daily["action"],
        "rpm_action": res_rpm["action"],
        "generic_action": res_generic["action"]
    }

def test_2_auto_healing_reconcile():
    """Verify Section 3.5 autoHealingReconcile"""
    class MockCue:
        def __init__(self, index, raw_text):
            self.index = index
            self.raw_text = raw_text

    def auto_healing_reconcile(input_cues, translated_items):
        reconciled = {}
        min_input_id = min((c.index for c in input_cues), default=1)
        trans_keys = sorted(translated_items.keys())

        # Auto-detect 1-based relative re-indexing (keys 1..N while input starts > N)
        is_relative = (
            len(trans_keys) > 0 and
            trans_keys[0] == 1 and
            trans_keys[-1] <= len(input_cues) and
            min_input_id > len(input_cues)
        )

        for offset, cue in enumerate(input_cues):
            text = translated_items.get(cue.index)
            if (text is None or not text.strip()) and is_relative:
                # Map by 1-based offset: offset 0 corresponds to item ID 1
                text = translated_items.get(offset + 1)
            
            reconciled[cue.index] = text.strip() if text and text.strip() else cue.raw_text
        return reconciled

    # Scenario 1: Chunk 2 (cues 31..60), LLM outputs relative 1..30
    cues_chunk2 = [MockCue(i, f"English {i}") for i in range(31, 61)]
    llm_relative = {i: f"Vietnamese {i+30}" for i in range(1, 31)}
    result_rel = auto_healing_reconcile(cues_chunk2, llm_relative)
    recovered_rel = sum(1 for i in range(31, 61) if result_rel[i] == f"Vietnamese {i}")

    # Scenario 2: Chunk 1 (cues 1..30), LLM outputs 1..30
    cues_chunk1 = [MockCue(i, f"English {i}") for i in range(1, 31)]
    llm_normal1 = {i: f"Vietnamese {i}" for i in range(1, 31)}
    result_norm1 = auto_healing_reconcile(cues_chunk1, llm_normal1)
    recovered_norm1 = sum(1 for i in range(1, 31) if result_norm1[i] == f"Vietnamese {i}")

    # Scenario 3: Chunk 2 (cues 31..60), LLM outputs absolute 31..60
    llm_normal2 = {i: f"Vietnamese {i}" for i in range(31, 61)}
    result_norm2 = auto_healing_reconcile(cues_chunk2, llm_normal2)
    recovered_norm2 = sum(1 for i in range(31, 61) if result_norm2[i] == f"Vietnamese {i}")

    # Scenario 4: Partial drop in relative mode (LLM outputs 1..28 of 30)
    llm_relative_drop = {i: f"Vietnamese {i+30}" for i in range(1, 29)}
    result_rel_drop = auto_healing_reconcile(cues_chunk2, llm_relative_drop)
    recovered_rel_drop = sum(1 for i in range(31, 59) if result_rel_drop[i] == f"Vietnamese {i}")
    fallback_cues = [result_rel_drop[59], result_rel_drop[60]]

    assert recovered_rel == 30, f"Expected 30 recovered, got {recovered_rel}"
    assert recovered_norm1 == 30, f"Expected 30 recovered, got {recovered_norm1}"
    assert recovered_norm2 == 30, f"Expected 30 recovered, got {recovered_norm2}"
    assert recovered_rel_drop == 28, f"Expected 28 recovered, got {recovered_rel_drop}"
    assert fallback_cues == ["English 59", "English 60"], f"Expected fallback, got {fallback_cues}"

    return {
        "pass": True,
        "recovered_relative_30": recovered_rel,
        "recovered_normal_chunk1": recovered_norm1,
        "recovered_normal_chunk2": recovered_norm2,
        "partial_drop_fallback_safe": fallback_cues == ["English 59", "English 60"]
    }

def test_3_universal_polymorphic_parser():
    """Verify Section 3.4 AITranslationParser"""
    delimiter_regex = re.compile(r'<<<(\d+)>>>\s*([\s\S]*?)(?=(?:<<<\d+>>>|$))')

    def strip_markdown_fences(raw: str) -> str:
        clean = raw.strip()
        if clean.startswith("```"):
            clean = clean.split("\n", 1)[1] if "\n" in clean else clean[3:]
            if clean.endswith("```"):
                clean = clean.rsplit("```", 1)[0].strip()
        return clean

    def parse_translations(raw_response: str):
        clean = strip_markdown_fences(raw_response)
        result = {}

        try:
            tree = json.loads(clean)
            array_node = None
            if isinstance(tree, list):
                array_node = tree
            elif isinstance(tree, dict):
                if "translations" in tree and isinstance(tree["translations"], list):
                    array_node = tree["translations"]
                else:
                    for v in tree.values():
                        if isinstance(v, list):
                            array_node = v
                            break
            if array_node is not None:
                for item in array_node:
                    if isinstance(item, dict) and "id" in item and "text" in item:
                        result[int(item["id"])] = str(item["text"])
                return result
        except Exception:
            pass

        # Delimiter fallback
        matches = delimiter_regex.findall(clean)
        for m in matches:
            cid = int(m[0])
            ctext = m[1].strip()
            result[cid] = ctext

        return result

    # Test format A: Root array
    raw_array = '[{"id": 1, "text": "Xin chao"}, {"id": 2, "text": "The gioi"}]'
    res_a = parse_translations(raw_array)
    assert res_a == {1: "Xin chao", 2: "The gioi"}, f"Format A failed: {res_a}"

    # Test format B: Root object with "translations"
    raw_obj = '{"translations": [{"id": 1, "text": "Xin chao"}, {"id": 2, "text": "The gioi"}]}'
    res_b = parse_translations(raw_obj)
    assert res_b == {1: "Xin chao", 2: "The gioi"}, f"Format B failed: {res_b}"

    # Test format C: Root object with arbitrary array key
    raw_other_obj = '{"results": [{"id": 1, "text": "Xin chao"}, {"id": 2, "text": "The gioi"}]}'
    res_c = parse_translations(raw_other_obj)
    assert res_c == {1: "Xin chao", 2: "The gioi"}, f"Format C failed: {res_c}"

    # Test format D: Delimiter format
    raw_delim = '<<<1>>>\nXin chao\n<<<2>>>\nThe gioi\n'
    res_d = parse_translations(raw_delim)
    assert res_d == {1: "Xin chao", 2: "The gioi"}, f"Format D failed: {res_d}"

    # Test format E: Markdown fence
    raw_fenced = '```json\n[{"id": 1, "text": "Xin chao"}, {"id": 2, "text": "The gioi"}]\n```'
    res_e = parse_translations(raw_fenced)
    assert res_e == {1: "Xin chao", 2: "The gioi"}, f"Format E failed: {res_e}"

    return {
        "pass": True,
        "root_array_parsed": res_a == {1: "Xin chao", 2: "The gioi"},
        "object_translations_parsed": res_b == {1: "Xin chao", 2: "The gioi"},
        "arbitrary_object_parsed": res_c == {1: "Xin chao", 2: "The gioi"},
        "delimiter_parsed": res_d == {1: "Xin chao", 2: "The gioi"},
        "fenced_parsed": res_e == {1: "Xin chao", 2: "The gioi"}
    }

def test_4_anthropic_spec():
    """Verify Section 3.3 Anthropic endpoint and credit error handling"""
    def build_anthropic_url(base_url):
        clean = (base_url.strip() if base_url and base_url.strip() else "https://api.anthropic.com/v1/messages")
        return clean if clean.endswith("/messages") else (clean + "/messages").replace("//messages", "/messages")

    u1 = build_anthropic_url(None)
    u2 = build_anthropic_url("")
    u3 = build_anthropic_url("https://api.anthropic.com/v1")
    u4 = build_anthropic_url("https://custom-proxy.internal/v1/messages")

    assert u1 == "https://api.anthropic.com/v1/messages", f"Failed default: {u1}"
    assert u2 == "https://api.anthropic.com/v1/messages", f"Failed blank: {u2}"
    assert u3 == "https://api.anthropic.com/v1/messages", f"Failed /v1: {u3}"
    assert u4 == "https://custom-proxy.internal/v1/messages", f"Failed custom: {u4}"

    # Credit handling
    def handle_anthropic_error(code: int, error_body: str):
        if code == 400 and "credit balance is too low" in error_body.lower():
            return "HARD_QUOTA_EXHAUSTION"
        elif code == 429:
            return "RATE_LIMIT_RETRY"
        return "UNKNOWN_ERROR"

    err_body = '{"type": "error", "error": {"type": "invalid_request_error", "message": "Your credit balance is too low to access the Claude API. Please go to Plans & Billing to upgrade or purchase credits."}}'
    res_err = handle_anthropic_error(400, err_body)
    assert res_err == "HARD_QUOTA_EXHAUSTION", f"Credit error not recognized: {res_err}"

    return {
        "pass": True,
        "default_url": u1,
        "appended_url": u3,
        "credit_exhaustion_detected": res_err == "HARD_QUOTA_EXHAUSTION"
    }

def test_5_recursive_cache_pruner(tmp_path: str):
    """Verify Section 5.3 SubtitleCacheManager recursive pruning"""
    base_dir = os.path.join(tmp_path, "sub_cache")
    full_dir = os.path.join(base_dir, "full")
    chunks_dir = os.path.join(base_dir, "chunks")
    os.makedirs(full_dir, exist_ok=True)
    os.makedirs(chunks_dir, exist_ok=True)

    TMP_EXPIRY_MS = 5 * 60 * 1000
    CHUNK_EXPIRY_MS = 24 * 3600 * 1000
    MAX_TTL_MS = 30 * 86400 * 1000
    MAX_BYTES = 1024 * 1024 # 1 MB
    MAX_FILES = 10

    now_ms = int(time.time() * 1000)

    # 1. Stale tmp file (> 5 min)
    stale_tmp = os.path.join(full_dir, "tmp_old.srt")
    with open(stale_tmp, "w") as f: f.write("old tmp")
    os.utime(stale_tmp, ((now_ms - TMP_EXPIRY_MS - 10000)/1000, (now_ms - TMP_EXPIRY_MS - 10000)/1000))

    # 2. Fresh tmp file (< 5 min)
    fresh_tmp = os.path.join(full_dir, "tmp_fresh.srt")
    with open(fresh_tmp, "w") as f: f.write("fresh tmp")
    os.utime(fresh_tmp, ((now_ms - 60000)/1000, (now_ms - 60000)/1000))

    # 3. Abandoned chunk dir (> 24 hr)
    abandoned_chunk_dir = os.path.join(chunks_dir, "abandoned_show")
    os.makedirs(abandoned_chunk_dir, exist_ok=True)
    with open(os.path.join(abandoned_chunk_dir, "chunk_0001.json"), "w") as f: f.write("{}")
    os.utime(abandoned_chunk_dir, ((now_ms - CHUNK_EXPIRY_MS - 10000)/1000, (now_ms - CHUNK_EXPIRY_MS - 10000)/1000))

    # 4. Active chunk dir (< 24 hr)
    active_chunk_dir = os.path.join(chunks_dir, "active_show")
    os.makedirs(active_chunk_dir, exist_ok=True)
    with open(os.path.join(active_chunk_dir, "chunk_0001.json"), "w") as f: f.write("{}")
    os.utime(active_chunk_dir, ((now_ms - 3600000)/1000, (now_ms - 3600000)/1000))

    # 5. 15 full files of 100KB each (total 1.5MB > 1MB, count 15 > 10)
    for i in range(15):
        fp = os.path.join(full_dir, f"full_{i:02d}.srt")
        with open(fp, "wb") as f: f.write(b"0" * 100 * 1024)
        mtime = (now_ms - (15 - i) * 60000) / 1000
        os.utime(fp, (mtime, mtime))

    # Plan's pruneCache algorithm (Kotlin converted to Python):
    now = int(time.time() * 1000)
    # Step 1: Prune orphaned tmp_ older than 5 min
    for f in os.listdir(full_dir):
        fp = os.path.join(full_dir, f)
        if os.path.isfile(fp) and f.startswith("tmp_"):
            if now - int(os.path.getmtime(fp) * 1000) > TMP_EXPIRY_MS:
                os.remove(fp)

    # Step 2: Prune abandoned chunks older than 24h
    for d in os.listdir(chunks_dir):
        dp = os.path.join(chunks_dir, d)
        if os.path.isdir(dp):
            if now - int(os.path.getmtime(dp) * 1000) > CHUNK_EXPIRY_MS:
                shutil.rmtree(dp)

    # Step 3: Prune full/ directory under LRU limits
    files = [
        os.path.join(full_dir, f) for f in os.listdir(full_dir)
        if os.path.isfile(os.path.join(full_dir, f)) and not f.startswith("tmp_")
    ]
    # TTL
    remaining = []
    for fp in files:
        if now - int(os.path.getmtime(fp) * 1000) > MAX_TTL_MS:
            os.remove(fp)
        else:
            remaining.append(fp)

    remaining.sort(key=lambda x: os.path.getmtime(x))
    total_bytes = sum(os.path.getsize(f) for f in remaining)
    file_count = len(remaining)

    while remaining and (total_bytes > MAX_BYTES or file_count > MAX_FILES):
        oldest = remaining.pop(0)
        total_bytes -= os.path.getsize(oldest)
        file_count -= 1
        os.remove(oldest)

    # Assertions
    assert not os.path.exists(stale_tmp), "Stale tmp file should be deleted"
    assert os.path.exists(fresh_tmp), "Fresh tmp file should be preserved"
    assert not os.path.exists(abandoned_chunk_dir), "Abandoned chunks should be deleted"
    assert os.path.exists(active_chunk_dir), "Active chunks should be preserved"
    assert len(remaining) == 10, f"Expected 10 files remaining, got {len(remaining)}"
    assert total_bytes <= MAX_BYTES, f"Expected total bytes <= {MAX_BYTES}, got {total_bytes}"

    return {
        "pass": True,
        "stale_tmp_deleted": not os.path.exists(stale_tmp),
        "fresh_tmp_preserved": os.path.exists(fresh_tmp),
        "abandoned_chunks_deleted": not os.path.exists(abandoned_chunk_dir),
        "active_chunks_preserved": os.path.exists(active_chunk_dir),
        "remaining_full_files": len(remaining),
        "total_bytes": total_bytes
    }

def test_6_gemini_url_builder():
    """Verify Section 3.2 Gemini URL Builder with models/ prefix stripping"""
    def build_gemini_url(base_url, model):
        clean_base = (base_url.strip() if base_url and base_url.strip() else "https://generativelanguage.googleapis.com").rstrip("/")
        clean_model = model.strip()
        if clean_model.startswith("models/"):
            clean_model = clean_model[len("models/"):]
        if not clean_model:
            clean_model = "gemini-1.5-flash"
        if clean_base.endswith("/v1beta"):
            return f"{clean_base}/models/{clean_model}:generateContent"
        else:
            return f"{clean_base}/v1beta/models/{clean_model}:generateContent"

    u1 = build_gemini_url(None, "gemini-1.5-flash")
    u2 = build_gemini_url(None, "models/gemini-1.5-flash")
    u3 = build_gemini_url("https://generativelanguage.googleapis.com/v1beta", "models/gemini-1.5-pro")

    assert u1 == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    assert u2 == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    assert u3 == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent"

    return {
        "pass": True,
        "standard_url": u1,
        "prefix_stripped_url": u2,
        "custom_v1beta_url": u3
    }

def test_7_tag_shielding_and_balancing():
    """Verify Section 2.4 & 2.5 Resilient Tag Unmasking & HTML Balancer"""
    tag_token_regex = re.compile(r'\[\[\s*([a-zA-Z0-9_]+)\s*\]\]')

    def balance_html_tags(s):
        balanced = s
        for tag in ["i", "b", "u", "s"]:
            o = len(re.findall(f"<{tag}>", balanced, re.IGNORECASE))
            c = len(re.findall(f"</{tag}>", balanced, re.IGNORECASE))
            if o > c:
                balanced += f"</{tag}>" * (o - c)
        fo = len(re.findall(r"<font(?:\s+[^>]*?)?>", balanced, re.IGNORECASE))
        fc = len(re.findall(r"</font>", balanced, re.IGNORECASE))
        if fo > fc:
            balanced += "</font>" * (fo - fc)
        return balanced

    def unmask_tags(text, tag_map):
        norm = {}
        for tok, orig in tag_map.items():
            core = tok.strip("[]").strip().lower()
            norm[core] = orig
        def repl(m):
            core = m.group(1).lower()
            return norm.get(core, m.group(0))
        unmasked = tag_token_regex.sub(repl, text)
        return balance_html_tags(unmasked)

    tag_map = {
        "[[TAG_1]]": "<i>",
        "[[TAG_2]]": "</i>",
        "[[TAG_3]]": '<font color="#FF0000">',
        "[[TAG_4]]": "</font>"
    }

    # Case 1: lowercase and spaces inside token
    res1 = unmask_tags("[[ tag_1 ]]Hello[[ tag_2 ]]", tag_map)
    assert res1 == "<i>Hello</i>", f"Failed case 1: {res1}"

    # Case 2: unclosed font tag
    res2 = unmask_tags("[[TAG_3]]Warning!", tag_map)
    assert res2 == '<font color="#FF0000">Warning!</font>', f"Failed case 2: {res2}"

    return {
        "pass": True,
        "lowercase_unmasked": res1 == "<i>Hello</i>",
        "font_closed_properly": res2 == '<font color="#FF0000">Warning!</font>'
    }

if __name__ == "__main__":
    print("Executing Empirical Verification Suite for M5 Challenger Iteration 2...")
    r1 = test_1_gemini_decoupling()
    print("Test 1 (Gemini Decoupling):", json.dumps(r1, indent=2))

    r2 = test_2_auto_healing_reconcile()
    print("Test 2 (Auto Healing Reconcile):", json.dumps(r2, indent=2))

    r3 = test_3_universal_polymorphic_parser()
    print("Test 3 (AITranslationParser):", json.dumps(r3, indent=2))

    r4 = test_4_anthropic_spec()
    print("Test 4 (Anthropic Spec):", json.dumps(r4, indent=2))

    test_tmp = os.path.join(os.path.dirname(__file__), ".m5_it2_cache_tmp")
    if os.path.exists(test_tmp): shutil.rmtree(test_tmp)
    os.makedirs(test_tmp, exist_ok=True)
    try:
        r5 = test_5_recursive_cache_pruner(test_tmp)
        print("Test 5 (Recursive Pruning):", json.dumps(r5, indent=2))
    finally:
        if os.path.exists(test_tmp): shutil.rmtree(test_tmp)

    r6 = test_6_gemini_url_builder()
    print("Test 6 (Gemini URL Builder):", json.dumps(r6, indent=2))

    r7 = test_7_tag_shielding_and_balancing()
    print("Test 7 (Tag Shielding & HTML Balancer):", json.dumps(r7, indent=2))

    print("\nALL 7 EMPIRICAL VERIFICATION TESTS PASSED SUCCESSFULLY.")
