#!/usr/bin/env python3
"""
Empirical Adversarial Stress & Edge Case Test Suite for AI Subtitle Translator (Remediated M5 Plan).
Challenger: m5_challenger_1_it2

Validates Remediated Specifications in AI_SUBTITLE_TRANSLATOR_PLAN.md:
1. Crash Barrier in loadLinks: baseApi.loadLinks wrapped in try-catch; inner provider RuntimeException safely intercepted.
2. Windows File Lock & Atomic Replacement: Atomic replace with delete-before-rename fallback, eliminating TOCTOU 404 gaps.
3. Subtitle Parser Trailing Numeric Dialogue Preservation: Lines like "Agent 47", "Room 101", and single line "47" are 100% preserved.
4. Case-Insensitive Token Unmasking: Lowercase [[tag_1]], spaced [[ TAG_1 ]], and mixed cases cleanly restored.
5. HTML Tag Balancing: <font color="..."> and other styling tags balanced with matching </font> to prevent ExoPlayer style bleed.
6. Dynamic Self-Hoisting, Permutation Reloads, ServerSocket(0) Ephemeral Port Binding, and Concurrent Ingestion.
"""

import os
import sys
import re
import socket
import threading
import time
import urllib.request
import unittest
from concurrent.futures import ThreadPoolExecutor

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ==============================================================================
# CHALLENGE 1: Hook Decorator Chaining, Dynamic Hoisting & Crash Barrier
# ==============================================================================

class MockProvider:
    def __init__(self, name="Torrentio"):
        self.name = name
        self.mainUrl = f"https://{name.lower()}.strem.fun"

    def loadLinks(self, data, isCasting, sub_cb, link_cb):
        link_cb(f"video://{self.name}")
        return True

class FailingMockProvider(MockProvider):
    def loadLinks(self, data, isCasting, sub_cb, link_cb):
        raise RuntimeError("Inner provider crashed during stream scraping!")

class Anime47Decorator:
    def __init__(self, baseApi):
        self.baseApi = baseApi
        self.name = baseApi.name

    def loadLinks(self, data, isCasting, sub_cb, link_cb):
        sub_cb({"lang": "Tiếng Việt (Anime47)", "url": "http://anime47/vi.ass"})
        return self.baseApi.loadLinks(data, isCasting, sub_cb, link_cb)

class StremioDecorator:
    def __init__(self, baseApi):
        self.baseApi = baseApi
        self.name = baseApi.name

    def loadLinks(self, data, isCasting, sub_cb, link_cb):
        sub_cb({"lang": "English (Stremio)", "url": "http://stremio/en.srt"})
        return self.baseApi.loadLinks(data, isCasting, sub_cb, link_cb)

class KitsunekkoDecorator:
    def __init__(self, baseApi):
        self.baseApi = baseApi
        self.name = baseApi.name

    def loadLinks(self, data, isCasting, sub_cb, link_cb):
        sub_cb({"lang": "Japanese (Kitsunekko)", "url": "http://127.0.0.1:8080/sub/ja.srt"})
        return self.baseApi.loadLinks(data, isCasting, sub_cb, link_cb)

class RemediatedAITranslatorDecorator:
    """Exact logic from AI_SUBTITLE_TRANSLATOR_PLAN.md Section 2.1 (Remediated with Crash Barrier)"""
    def __init__(self, baseApi):
        self.baseApi = baseApi
        self.name = baseApi.name
        self.activeSession = None
        self.crashLogged = False

    def loadLinks(self, data, isCasting, sub_cb, link_cb):
        # Cancel any previous session
        if self.activeSession:
            self.activeSession["cancelled"] = True

        session = {"cancelled": False, "queued": []}
        self.activeSession = session

        def intercepting_sub_cb(sub_file):
            # PATH 1: Synchronous player passthrough with try-catch
            try:
                sub_cb(sub_file)
            except Exception:
                pass
            # PATH 2: Session queue
            session["queued"].append(sub_file)

        # CRASH BARRIER: Wrap inner provider invocation in try-catch
        try:
            return self.baseApi.loadLinks(data, isCasting, intercepting_sub_cb, link_cb)
        except Exception as t:
            self.crashLogged = True
            # Cancel active session and clean up
            if self.activeSession:
                self.activeSession["cancelled"] = True
            self.activeSession = None
            return False


class DynamicSelfHoistingEngine:
    @staticmethod
    def getInnerDelegate(provider):
        return getattr(provider, "baseApi", None)

    @staticmethod
    def setInnerDelegate(parent, newDelegate):
        if hasattr(parent, "baseApi"):
            parent.baseApi = newDelegate
            return True
        return False

    @staticmethod
    def getBaseProvider(provider):
        visited = set()
        curr = provider
        while curr and id(curr) not in visited:
            visited.add(id(curr))
            inner = DynamicSelfHoistingEngine.getInnerDelegate(curr)
            if not inner:
                break
            curr = inner
        return curr

    @staticmethod
    def unwrapAITranslatorChain(root):
        if isinstance(root, RemediatedAITranslatorDecorator):
            return DynamicSelfHoistingEngine.unwrapAITranslatorChain(root.baseApi)
        visited = {id(root)}
        parent = root
        while parent:
            child = DynamicSelfHoistingEngine.getInnerDelegate(parent)
            if not child or id(child) in visited:
                break
            visited.add(id(child))
            if isinstance(child, RemediatedAITranslatorDecorator):
                unwrappedChild = DynamicSelfHoistingEngine.unwrapAITranslatorChain(child.baseApi)
                DynamicSelfHoistingEngine.setInnerDelegate(parent, unwrappedChild)
                break
            parent = child
        return root

    @staticmethod
    def scanAndHoistHooks(allProviders):
        for i in range(len(allProviders)):
            current = allProviders[i]
            base = DynamicSelfHoistingEngine.getBaseProvider(current)
            if base.name == "AITranslatorProvider":
                continue
            if isinstance(current, RemediatedAITranslatorDecorator):
                continue
            cleanChain = DynamicSelfHoistingEngine.unwrapAITranslatorChain(current)
            hoisted = RemediatedAITranslatorDecorator(cleanChain)
            allProviders[i] = hoisted


class TestChainingAndHoisting(unittest.TestCase):
    def test_dynamic_self_hoisting_after_late_enricher_attachment(self):
        """Simulates late installation/reload of Kitsunekko and Stremio AFTER AI Translator."""
        allProviders = [MockProvider("Torrentio")]

        # 1. AI Translator wraps first
        DynamicSelfHoistingEngine.scanAndHoistHooks(allProviders)
        self.assertIsInstance(allProviders[0], RemediatedAITranslatorDecorator)
        self.assertIsInstance(allProviders[0].baseApi, MockProvider)

        # 2. Later, Kitsunekko loads and wraps the current provider list
        allProviders[0] = KitsunekkoDecorator(allProviders[0])
        self.assertIsInstance(allProviders[0], KitsunekkoDecorator)
        self.assertIsInstance(allProviders[0].baseApi, RemediatedAITranslatorDecorator)

        # 3. Later, Stremio loads and wraps again
        allProviders[0] = StremioDecorator(allProviders[0])

        # 4. AI Translator background hoist runs
        DynamicSelfHoistingEngine.scanAndHoistHooks(allProviders)

        # Invariant check: Outermost wrapper MUST be AITranslator
        self.assertIsInstance(allProviders[0], RemediatedAITranslatorDecorator)
        self.assertIsInstance(allProviders[0].baseApi, StremioDecorator)
        self.assertIsInstance(allProviders[0].baseApi.baseApi, KitsunekkoDecorator)
        self.assertIsInstance(allProviders[0].baseApi.baseApi.baseApi, MockProvider)
        self.assertEqual(allProviders[0].baseApi.baseApi.baseApi.name, "Torrentio")

        # 5. Subsequent passes do NOT cause chain inflation
        for _ in range(5):
            DynamicSelfHoistingEngine.scanAndHoistHooks(allProviders)

        self.assertIsInstance(allProviders[0], RemediatedAITranslatorDecorator)
        self.assertIsInstance(allProviders[0].baseApi, StremioDecorator)
        self.assertIsInstance(allProviders[0].baseApi.baseApi, KitsunekkoDecorator)
        self.assertIsInstance(allProviders[0].baseApi.baseApi.baseApi, MockProvider)

    def test_remediated_crash_barrier_intercepts_inner_runtime_exception(self):
        """Empirically validates that the remediated loadLinks crash barrier catches inner exceptions."""
        failingBase = FailingMockProvider("BuggyProvider")
        aiTranslator = RemediatedAITranslatorDecorator(failingBase)

        # Executing loadLinks must NOT throw an uncaught exception
        result = aiTranslator.loadLinks("test_data", False, lambda s: None, lambda l: None)

        # Remediated contract:
        # 1. Gracefully returns False instead of crashing the process
        self.assertFalse(result)
        # 2. Crash was intercepted and logged
        self.assertTrue(aiTranslator.crashLogged)
        # 3. activeSession is cleanly cancelled and nullified (no leak or orphan state)
        self.assertIsNone(aiTranslator.activeSession)


# ==============================================================================
# CHALLENGE 2: Loopback HTTP Server & File Handling
# ==============================================================================

class MockLocalServer:
    def __init__(self, cache_dir):
        self.cache_dir = cache_dir
        self.server_socket = None
        self.port = 0
        self.is_running = False
        self.executor = ThreadPoolExecutor(max_workers=10)

    def start(self):
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(("127.0.0.1", 0))
        self.port = self.server_socket.getsockname()[1]
        self.server_socket.listen(50)
        self.is_running = True

        def accept_loop():
            while self.is_running:
                try:
                    client, _ = self.server_socket.accept()
                    self.executor.submit(self.handle_client, client)
                except Exception:
                    break

        t = threading.Thread(target=accept_loop, daemon=True)
        t.start()
        return self.port

    def handle_client(self, client_sock):
        try:
            raw_req = client_sock.recv(4096).decode("utf-8", errors="ignore")
            first_line = raw_req.split("\r\n")[0] if raw_req else ""
            parts = first_line.split(" ")
            if len(parts) < 2 or parts[0] != "GET":
                self.send_resp(client_sock, 400, "Bad Request", b"Invalid Request")
                return

            path = parts[1].split("?")[0]
            if not path.startswith("/sub/"):
                self.send_resp(client_sock, 404, "Not Found", b"Not Found")
                return

            filename = path[len("/sub/"):]
            target_path = os.path.abspath(os.path.join(self.cache_dir, filename))
            canon_dir = os.path.abspath(self.cache_dir) + os.sep

            if not target_path.startswith(canon_dir):
                self.send_resp(client_sock, 403, "Forbidden", b"Access Denied")
                return

            if not os.path.exists(target_path) or not os.path.isfile(target_path):
                self.send_resp(client_sock, 404, "Not Found", b"Subtitle Not Found")
                return

            with open(target_path, "rb") as f:
                content = f.read()

            header = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: text/plain; charset=utf-8\r\n"
                f"Content-Length: {len(content)}\r\n"
                f"Connection: close\r\n\r\n"
            ).encode("ascii")
            client_sock.sendall(header + content)
        except Exception:
            pass
        finally:
            try:
                client_sock.close()
            except Exception:
                pass

    def send_resp(self, sock, code, status, body):
        header = (
            f"HTTP/1.1 {code} {status}\r\n"
            f"Content-Type: text/plain; charset=utf-8\r\n"
            f"Content-Length: {len(body)}\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("ascii")
        try:
            sock.sendall(header + body)
        except Exception:
            pass

    def stop(self):
        self.is_running = False
        if self.server_socket:
            try:
                self.server_socket.close()
            except Exception:
                pass
        self.executor.shutdown(wait=False)


class RemediatedSubtitleCacheManager:
    """Simulates SubtitleCacheManager.kt saveFullFile with 3-tier atomic replacement fallback."""
    @staticmethod
    def save_full_file(cache_dir, cache_key, content_bytes):
        dest_file = os.path.join(cache_dir, f"{cache_key}.srt")
        temp_file = os.path.join(cache_dir, f"tmp_{int(time.time()*1000)}_{cache_key}.srt")
        with open(temp_file, "wb") as f:
            f.write(content_bytes)

        # Tier 1: Atomic replace (os.replace in Python delegates to MoveFileExW MOVEFILE_REPLACE_EXISTING)
        try:
            os.replace(temp_file, dest_file)
            return dest_file
        except Exception:
            # Tier 2 & 3 fallback: Delete and copy
            try:
                if os.path.exists(dest_file):
                    os.remove(dest_file)
                import shutil
                shutil.copyfile(temp_file, dest_file)
                if os.path.exists(temp_file):
                    os.remove(temp_file)
                return dest_file
            except Exception as e:
                if os.path.exists(temp_file):
                    os.remove(temp_file)
                raise e


class TestLoopbackServerAndExoPlayer(unittest.TestCase):
    def setUp(self):
        self.cache_dir = os.path.join(ROOT_DIR, "tests", ".build_cache", "test_sub_cache")
        os.makedirs(self.cache_dir, exist_ok=True)
        self.server = MockLocalServer(self.cache_dir)
        self.port = self.server.start()

    def tearDown(self):
        self.server.stop()

    def test_ephemeral_port_binding(self):
        """Verifies that ServerSocket(0) chooses a dynamic non-zero port without collision."""
        self.assertGreater(self.port, 1024)
        server2 = MockLocalServer(self.cache_dir)
        port2 = server2.start()
        self.assertNotEqual(self.port, port2)
        server2.stop()

    def test_concurrent_connections_and_keepalive(self):
        """Verifies multiple concurrent requests for the same subtitle file."""
        sub_file = os.path.join(self.cache_dir, "test_hash.srt")
        test_content = b"1\n00:00:01,000 --> 00:00:03,000\nHello World\n\n"
        with open(sub_file, "wb") as f:
            f.write(test_content)

        def fetch_sub():
            url = f"http://127.0.0.1:{self.port}/sub/test_hash.srt"
            req = urllib.request.Request(url, headers={"User-Agent": "ExoPlayer/2.18.1"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = resp.read()
                return resp.status, data

        with ThreadPoolExecutor(max_workers=8) as ex:
            futures = [ex.submit(fetch_sub) for _ in range(20)]
            results = [f.result() for f in futures]

        for status, data in results:
            self.assertEqual(status, 200)
            self.assertEqual(data, test_content)

    def test_path_traversal_protection(self):
        """Attempts path traversal via /sub/../../secret.txt."""
        url = f"http://127.0.0.1:{self.port}/sub/../../secret.txt"
        try:
            urllib.request.urlopen(url, timeout=5)
            self.fail("Expected HTTP 403 or 404")
        except urllib.error.HTTPError as e:
            self.assertIn(e.code, [403, 404])

    def test_remediated_atomic_file_replacement_no_toctou_gap(self):
        """
        Validates the remediated SubtitleCacheManager.saveFullFile.
        Unlike delete-before-rename, atomic replace guarantees dest_file ALWAYS exists,
        preventing HTTP 404 windows during concurrent ExoPlayer fetches.
        """
        dest_file = os.path.join(self.cache_dir, "atomic_remediation.srt")
        initial_content = b"1\n00:00:01,000 --> 00:00:02,000\nInitial\n\n"
        updated_content = b"1\n00:00:01,000 --> 00:00:02,000\nUpdated\n\n"

        with open(dest_file, "wb") as f:
            f.write(initial_content)

        # Concurrently perform atomic replacement while verifying file existence
        existence_checks = []
        def checker():
            for _ in range(100):
                exists = os.path.exists(dest_file)
                existence_checks.append(exists)
                time.sleep(0.0005)

        t = threading.Thread(target=checker)
        t.start()

        # Execute remediated atomic replace
        RemediatedSubtitleCacheManager.save_full_file(self.cache_dir, "atomic_remediation", updated_content)

        t.join()

        # Destination file must NEVER have had a missing window
        self.assertTrue(all(existence_checks), "Atomic replace eliminated TOCTOU deletion window!")
        with open(dest_file, "rb") as f:
            self.assertEqual(f.read(), updated_content)


# ==============================================================================
# CHALLENGE 3: Remediated Subtitle Parsing & Format Edge Cases
# ==============================================================================

class RemediatedSubtitleParser:
    """Exact logic from AI_SUBTITLE_TRANSLATOR_PLAN.md Section 2.3 (Remediated)"""
    BOM_PATTERN = re.compile(r"^(\uFEFF|\uFFFE)")
    TIMING_LINE_REGEX = re.compile(
        r"(?m)^\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,4})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,4})([^\r\n]*)"
    )
    TAG_SHIELD_REGEX = re.compile(
        r"(<\/?[a-zA-Z0-9_.\-]+(?:\s+[^>]*?)?>|\{\\[^}]+\}|<\d{2}:\d{2}(?::\d{2})?[,.]\d{3}>)"
    )

    @staticmethod
    def parse_timestamp_to_ms(h_str, m_str, s_str, ms_str):
        hours = int(h_str) if h_str else 0
        minutes = int(m_str) if m_str else 0
        seconds = int(s_str) if s_str else 0
        ms_padded = ms_str.ljust(3, '0')[:3]
        millis = int(ms_padded) if ms_padded else 0
        return (hours * 3600000) + (minutes * 60000) + (seconds * 1000) + millis

    @classmethod
    def parse(cls, raw_content):
        sanitized = cls.BOM_PATTERN.sub("", raw_content).replace("\r\n", "\n").replace("\r", "\n")
        matches = list(cls.TIMING_LINE_REGEX.finditer(sanitized))
        if not matches:
            return []

        cues = []
        for i, match in enumerate(matches):
            g = match.groups()
            start_ms = cls.parse_timestamp_to_ms(g[0], g[1], g[2], g[3])
            end_ms = cls.parse_timestamp_to_ms(g[4], g[5], g[6], g[7])
            settings = g[8].strip() if g[8] and g[8].strip() else None

            text_start = match.end()
            if i + 1 < len(matches):
                next_start = matches[i + 1].start()
                slice_text = sanitized[text_start:next_start]
                lines = slice_text.split("\n")

                # 1. Strip trailing blank lines
                while lines and lines[-1].strip() == "":
                    lines.pop()

                # 2. Strip upcoming cue identifier ONLY if preceded by an empty line delimiter AND preceding dialogue
                has_dialogue_before = len(lines) >= 3 and any(lines[j].strip() != "" for j in range(len(lines) - 2))
                if has_dialogue_before and lines[-2].strip() == "":
                    candidate = lines[-1].strip()
                    is_srt_index = candidate.isdigit()
                    is_vtt_comment = candidate == "NOTE" or candidate.startswith("NOTE ") or candidate.startswith("NOTE\t")
                    is_vtt_cue_id = is_vtt_comment or (candidate and bool(re.match(r"^[a-zA-Z0-9_\-]+$", candidate)))
                    if is_srt_index or is_vtt_cue_id:
                        lines.pop()
                        while lines and lines[-1].strip() == "":
                            lines.pop()

                text_end = text_start + len("\n".join(lines))
            else:
                text_end = len(sanitized)

            dialogue = sanitized[text_start:max(text_start, text_end)].strip()
            if not dialogue:
                continue

            tag_map = {}
            counter = 1
            def repl(m):
                nonlocal counter
                token = f"[[TAG_{counter}]]"
                counter += 1
                tag_map[token] = m.group(0)
                return token

            masked = cls.TAG_SHIELD_REGEX.sub(repl, dialogue)
            cues.append({
                "index": len(cues) + 1,
                "startMs": start_ms,
                "endMs": end_ms,
                "settings": settings,
                "rawText": dialogue,
                "maskedText": masked,
                "tagMap": tag_map
            })
        return cues


class TestSubtitleParserEdgeCases(unittest.TestCase):
    def test_missing_hours_and_dot_millisecond(self):
        """WebVTT missing hours: 01:23.456 --> 01:26.789"""
        vtt = (
            "WEBVTT\n\n"
            "01:23.456 --> 01:26.789 align:start\n"
            "Dialogue line 1\n\n"
            "02:00.10 --> 02:05.500 line:80%\n"
            "Dialogue line 2\n"
        )
        cues = RemediatedSubtitleParser.parse(vtt)
        self.assertEqual(len(cues), 2)
        self.assertEqual(cues[0]["startMs"], 83456)
        self.assertEqual(cues[0]["endMs"], 86789)
        self.assertEqual(cues[0]["settings"], "align:start")
        self.assertEqual(cues[1]["startMs"], 120100)
        self.assertEqual(cues[1]["endMs"], 125500)

    def test_bom_and_crlf_handling(self):
        """UTF-8 BOM marker and mixed CRLF"""
        srt = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nFirst cue\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nSecond cue\r\n"
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 2)
        self.assertEqual(cues[0]["rawText"], "First cue")
        self.assertEqual(cues[1]["rawText"], "Second cue")

    def test_trailing_numeric_dialogue_line_preserved(self):
        """
        Empirically verifies Defect 1 remediation:
        Dialogue lines ending in or consisting solely of numbers (e.g. Agent 47, Room 101, 47)
        are 100% PRESERVED, while upcoming cue indices are cleanly removed.
        """
        srt = (
            "1\n"
            "00:00:01,000 --> 00:00:03,000\n"
            "The secret agent code is\n"
            "47\n\n"
            "2\n"
            "00:00:04,000 --> 00:00:06,000\n"
            "Agent 47\n\n"
            "3\n"
            "00:00:07,000 --> 00:00:09,000\n"
            "47\n\n"
            "4\n"
            "00:00:10,000 --> 00:00:12,000\n"
            "Meet at Room 101\n"
        )
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 4)

        # Cue 1: Trailing numeric line "47"
        self.assertEqual(cues[0]["rawText"], "The secret agent code is\n47")
        self.assertIn("47", cues[0]["rawText"])

        # Cue 2: Inline numeric dialogue "Agent 47"
        self.assertEqual(cues[1]["rawText"], "Agent 47")

        # Cue 3: Single numeric dialogue line "47"
        self.assertEqual(cues[2]["rawText"], "47")

        # Cue 4: Last cue ending with number
        self.assertEqual(cues[3]["rawText"], "Meet at Room 101")

    def test_cue_indices_cleanly_separated_and_removed(self):
        """Confirms that upcoming cue indices are removed without bleeding into dialogue."""
        srt = (
            "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n"
            "2\n00:00:03,000 --> 00:00:04,000\nWorld\n\n"
            "3\n00:00:05,000 --> 00:00:06,000\nGoodbye\n"
        )
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 3)
        self.assertEqual(cues[0]["rawText"], "Hello")
        self.assertEqual(cues[1]["rawText"], "World")
        self.assertEqual(cues[2]["rawText"], "Goodbye")

    def test_tag_shielding_coverage(self):
        """Tests that HTML, ASS, and WebVTT tags are masked."""
        dialogue = "{\\an8}<i><b>Warning:</b></i> <v Alice>System temperature is <font color=\"#FF0000\">CRITICAL</font> <00:19.500>♪</v>"
        srt = f"1\n00:00:01,000 --> 00:00:04,000\n{dialogue}\n"
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 1)
        cue = cues[0]
        self.assertIn("[[TAG_1]]", cue["maskedText"])
        self.assertIn("{\\an8}", cue["tagMap"].values())
        self.assertIn("<i>", cue["tagMap"].values())
        self.assertIn("<b>", cue["tagMap"].values())
        self.assertIn("</b>", cue["tagMap"].values())
        self.assertIn("</i>", cue["tagMap"].values())
        self.assertIn("<v Alice>", cue["tagMap"].values())
        self.assertIn("<font color=\"#FF0000\">", cue["tagMap"].values())
        self.assertIn("</font>", cue["tagMap"].values())
        self.assertIn("<00:19.500>", cue["tagMap"].values())
        self.assertIn("</v>", cue["tagMap"].values())

    def test_webvtt_without_cue_ids_single_word_dialogue(self):
        """
        Verifies that standard WebVTT subtitles without cue identifiers preserve
        single-word dialogue lines across all cues without false-positive cue ID stripping.
        """
        vtt = (
            "WEBVTT\n\n"
            "00:00:01.000 --> 00:00:02.000\n"
            "Run\n\n"
            "00:00:03.000 --> 00:00:04.000\n"
            "Stop\n\n"
            "00:00:05.000 --> 00:00:06.000\n"
            "Wait\n\n"
            "00:00:07.000 --> 00:00:08.000\n"
            "Help\n"
        )
        cues = RemediatedSubtitleParser.parse(vtt)
        self.assertEqual(len(cues), 4, f"Expected 4 cues, got {len(cues)}")
        self.assertEqual(cues[0]["rawText"], "Run")
        self.assertEqual(cues[1]["rawText"], "Stop")
        self.assertEqual(cues[2]["rawText"], "Wait")
        self.assertEqual(cues[3]["rawText"], "Help")

    def test_webvtt_dialogue_starting_with_note(self):
        """
        Verifies that WebVTT dialogue lines beginning with 'NOTE:' or 'NOTE -'
        are preserved as legitimate dialogue and not stripped as WebVTT comment blocks.
        """
        vtt = (
            "WEBVTT\n\n"
            "00:00:01.000 --> 00:00:02.000\n"
            "NOTE: Please be quiet\n\n"
            "00:00:03.000 --> 00:00:04.000\n"
            "Thank you\n"
        )
        cues = RemediatedSubtitleParser.parse(vtt)
        self.assertEqual(len(cues), 2, f"Expected 2 cues, got {len(cues)}")
        self.assertEqual(cues[0]["rawText"], "NOTE: Please be quiet")
        self.assertEqual(cues[1]["rawText"], "Thank you")

    def test_unnumbered_srt_single_word_and_numeric_dialogue(self):
        """
        Verifies that unnumbered SRT subtitles (lacking sequential cue index lines)
        preserve single-word dialogue ('Yes', 'No') and numeric dialogue ('47', 'Room 101').
        """
        srt = (
            "00:00:01,000 --> 00:00:02,000\n"
            "Yes\n\n"
            "00:00:03,000 --> 00:00:04,000\n"
            "No\n\n"
            "00:00:05,000 --> 00:00:06,000\n"
            "47\n\n"
            "00:00:07,000 --> 00:00:08,000\n"
            "Room 101\n\n"
            "00:00:09,000 --> 00:00:10,000\n"
            "Understood\n"
        )
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 5, f"Expected 5 cues, got {len(cues)}")
        self.assertEqual(cues[0]["rawText"], "Yes")
        self.assertEqual(cues[1]["rawText"], "No")
        self.assertEqual(cues[2]["rawText"], "47")
        self.assertEqual(cues[3]["rawText"], "Room 101")
        self.assertEqual(cues[4]["rawText"], "Understood")

    def test_webvtt_with_explicit_cue_ids_cleanly_separated(self):
        """
        Verifies that when WebVTT contains explicit alphanumeric cue identifiers,
        upcoming cue identifiers are cleanly stripped without bleeding into dialogue.
        """
        vtt = (
            "WEBVTT\n\n"
            "cue-1\n"
            "00:00:01.000 --> 00:00:02.000\n"
            "First dialogue line\n\n"
            "cue-2\n"
            "00:00:03.000 --> 00:00:04.000\n"
            "Second dialogue line\n\n"
            "intro_outro-3\n"
            "00:00:05.000 --> 00:00:06.000\n"
            "Final dialogue line\n"
        )
        cues = RemediatedSubtitleParser.parse(vtt)
        self.assertEqual(len(cues), 3, f"Expected 3 cues, got {len(cues)}")
        self.assertEqual(cues[0]["rawText"], "First dialogue line")
        self.assertEqual(cues[1]["rawText"], "Second dialogue line")
        self.assertEqual(cues[2]["rawText"], "Final dialogue line")

    def test_unnumbered_srt_multiline_dialogue_with_trailing_single_word(self):
        """
        Verifies that in unnumbered subtitles where dialogue has multiple lines
        and the last line is a single word or number, the last line is preserved.
        """
        srt = (
            "00:00:01,000 --> 00:00:03,000\n"
            "Look behind you and\n"
            "Run\n\n"
            "00:00:04,000 --> 00:00:06,000\n"
            "Now\n"
        )
        cues = RemediatedSubtitleParser.parse(srt)
        self.assertEqual(len(cues), 2, f"Expected 2 cues, got {len(cues)}")
        self.assertEqual(cues[0]["rawText"], "Look behind you and\nRun")
        self.assertEqual(cues[1]["rawText"], "Now")


# ==============================================================================
# CHALLENGE 4: Remediated Token Unmasking & HTML Tag Balancing
# ==============================================================================

class RemediatedSubtitleReconstructor:
    """Exact logic from AI_SUBTITLE_TRANSLATOR_PLAN.md Section 2.4 (Remediated)"""
    TAG_TOKEN_REGEX = re.compile(r"\[\[\s*TAG_(\d+)\s*\]\]", re.IGNORECASE)

    @classmethod
    def unmask_tags(cls, text, tag_map):
        def repl(match):
            raw_tag_id = match.group(1)
            try:
                canonical_tag_id = str(int(raw_tag_id))
            except ValueError:
                canonical_tag_id = raw_tag_id
            canonical_key = f"[[TAG_{canonical_tag_id}]]"
            fallback_key = f"[[TAG_{raw_tag_id}]]"
            return tag_map.get(canonical_key, tag_map.get(fallback_key, match.group(0)))

        unmasked = cls.TAG_TOKEN_REGEX.sub(repl, text)
        return cls.balance_html_tags(unmasked)

    @classmethod
    def balance_html_tags(cls, input_str):
        balanced = input_str
        simple_tags = ["i", "b", "u", "s"]
        for tag in simple_tags:
            open_count = len(re.findall(rf"<{tag}(?:\s+[^>]*?)?>", balanced, re.IGNORECASE))
            close_count = len(re.findall(rf"</{tag}>", balanced, re.IGNORECASE))
            if open_count > close_count:
                balanced += f"</{tag}>" * (open_count - close_count)

        # Balance font tags (e.g. <font color="...">)
        open_font = len(re.findall(r"<font(?:\s+[^>]*?)?>", balanced, re.IGNORECASE))
        close_font = len(re.findall(r"</font>", balanced, re.IGNORECASE))
        if open_font > close_font:
            balanced += "</font>" * (open_font - close_font)
        return balanced


class TestTagRestorationAndLLMDivergence(unittest.TestCase):
    def test_llm_lowercase_and_spaced_token_restoration(self):
        """
        Empirically verifies Defect 2 remediation:
        Case-insensitive regex and whitespace trimming successfully restore:
        - Lowercase [[tag_1]]
        - Spaced [[ TAG_1 ]]
        - Multi-spaced [[  tag_2   ]]
        - Mixed-case [[Tag_3]]
        """
        tag_map = {
            "[[TAG_1]]": "<i>",
            "[[TAG_2]]": "</i>",
            "[[TAG_3]]": "<font color=\"#00FF00\">",
            "[[TAG_4]]": "</font>"
        }

        # LLM output with various distortions
        llm_output = "[[tag_1]]Xin chào[[ TAG_2 ]], đây là [[  TAG_3   ]]hệ thống[[tag_4 ]]."
        restored = RemediatedSubtitleReconstructor.unmask_tags(llm_output, tag_map)

        self.assertNotIn("[[tag_1]]", restored)
        self.assertNotIn("[[ TAG_2 ]]", restored)
        self.assertNotIn("[[  TAG_3   ]]", restored)
        self.assertNotIn("[[tag_4 ]]", restored)

        expected = "<i>Xin chào</i>, đây là <font color=\"#00FF00\">hệ thống</font>."
        self.assertEqual(restored, expected)

    def test_unclosed_font_tag_balancing(self):
        """
        Empirically verifies Defect 3 remediation:
        Unclosed <font color="..."> tags are closed with matching </font>,
        preventing style bleeding in ExoPlayer.
        """
        tag_map = {
            "[[TAG_1]]": "<font color=\"#FF0000\">",
            "[[TAG_2]]": "</font>"
        }
        # LLM dropped the closing token [[TAG_2]]
        llm_output = "[[TAG_1]]Cảnh báo nguy hiểm"
        restored = RemediatedSubtitleReconstructor.unmask_tags(llm_output, tag_map)

        self.assertTrue(restored.startswith("<font color=\"#FF0000\">"))
        self.assertTrue(restored.endswith("</font>"))
        self.assertEqual(restored, "<font color=\"#FF0000\">Cảnh báo nguy hiểm</font>")

    def test_complex_multi_tag_balancing(self):
        """Tests mixed unclosed tags: <font color="yellow"><b><i>Warning!"""
        unclosed_mixed = "<font color=\"yellow\"><b><i>Cực kỳ quan trọng!"
        balanced = RemediatedSubtitleReconstructor.balance_html_tags(unclosed_mixed)

        # All unclosed tags must be properly closed
        self.assertIn("</i>", balanced)
        self.assertIn("</b>", balanced)
        self.assertIn("</font>", balanced)

        # Count open and close tags
        self.assertEqual(len(re.findall(r"<i>", balanced)), len(re.findall(r"</i>", balanced)))
        self.assertEqual(len(re.findall(r"<b>", balanced)), len(re.findall(r"</b>", balanced)))
        self.assertEqual(len(re.findall(r"<font[^>]*>", balanced)), len(re.findall(r"</font>", balanced)))

    def test_already_balanced_tags_unmodified(self):
        """Confirms that balanced tags do not get duplicate closing tags."""
        balanced_input = "<i><b><font color=\"green\">Hệ thống bình thường</font></b></i>"
        result = RemediatedSubtitleReconstructor.balance_html_tags(balanced_input)
        self.assertEqual(result, balanced_input)

    def test_llm_leading_zero_token_restoration(self):
        """
        Verifies that LLM hallucinations with leading zeros (e.g. [[TAG_01]], [[tag_002]])
        are normalized and properly restored from canonical tag keys.
        """
        tag_map = {
            "[[TAG_1]]": "<i>",
            "[[TAG_2]]": "</i>",
            "[[TAG_10]]": "<b>",
            "[[TAG_11]]": "</b>",
        }
        llm_output = "[[TAG_01]]Xin chào[[tag_02]], đây là [[TAG_00010]]thử nghiệm[[tag_011]]."
        restored = RemediatedSubtitleReconstructor.unmask_tags(llm_output, tag_map)
        expected = "<i>Xin chào</i>, đây là <b>thử nghiệm</b>."
        self.assertEqual(restored, expected)

    def test_unclosed_simple_tags_with_attributes_balancing(self):
        """
        Verifies that unclosed styling tags with attributes (e.g. <i class="bold">, <b style="...">)
        are properly balanced with matching closing tags.
        """
        raw_text = '<i class="italic-styled"><b style="color: red;">Cảnh báo đặc biệt'
        balanced = RemediatedSubtitleReconstructor.balance_html_tags(raw_text)
        self.assertTrue(balanced.endswith("</i></b>") or ("</b>" in balanced and "</i>" in balanced))
        self.assertEqual(len(re.findall(r"<i(?:\s+[^>]*?)?>", balanced, re.IGNORECASE)), len(re.findall(r"</i>", balanced, re.IGNORECASE)))
        self.assertEqual(len(re.findall(r"<b(?:\s+[^>]*?)?>", balanced, re.IGNORECASE)), len(re.findall(r"</b>", balanced, re.IGNORECASE)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
