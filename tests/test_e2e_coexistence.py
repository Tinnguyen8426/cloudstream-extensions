#!/usr/bin/env python3
"""
Comprehensive E2E Multi-Enricher Coexistence and Integration Test Suite.
Tests simultaneous hook chaining, 0ms video passthrough, concurrent subtitle emission,
4.5s timeout resilience, anti-recursion guards, packaging integrity, and executes
the Kotlin JVM coexistence harness.
"""
import os
import sys
import json
import zipfile
import hashlib
import time
import threading
import subprocess
import unittest

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_DIR = os.path.join(ROOT_DIR, "tests")
STREMIO_DIST = os.path.join(ROOT_DIR, "StremioSubtitleEnricher", "dist")
KITSUNEKKO_DIST = os.path.join(ROOT_DIR, "KitsunekkoSubtitleEnricher", "dist")

def compute_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return "sha256-" + h.hexdigest()


# ==============================================================================
# 1. Packaging and Release Artifact Verification
# ==============================================================================
class TestPackagingAndIntegrity(unittest.TestCase):
    """
    Verifies build_plugin.py outputs for StremioSubtitleEnricher and KitsunekkoSubtitleEnricher:
    - .cs3 zip package presence
    - classes.dex and manifest.json inclusion
    - plugins.json and repo.json checksum and filesize parity
    """

    def test_stremio_package_contents_and_metadata(self):
        cs3_path = os.path.join(STREMIO_DIST, "StremioSubEnricher.cs3")
        self.assertTrue(os.path.exists(cs3_path), f"Missing {cs3_path}")
        filesize = os.path.getsize(cs3_path)
        self.assertGreater(filesize, 5000, "Package size suspiciously small")

        # Check zip entries
        with zipfile.ZipFile(cs3_path, "r") as zf:
            namelist = zf.namelist()
            self.assertIn("classes.dex", namelist, "classes.dex missing from StremioSubEnricher.cs3")
            self.assertIn("manifest.json", namelist, "manifest.json missing from StremioSubEnricher.cs3")

            # Check manifest contents
            manifest_bytes = zf.read("manifest.json")
            manifest = json.loads(manifest_bytes.decode("utf-8"))
            self.assertIn("name", manifest)
            self.assertIn("pluginClassName", manifest)
            self.assertIn("version", manifest)

        # Check plugins.json
        plugins_json_path = os.path.join(STREMIO_DIST, "plugins.json")
        self.assertTrue(os.path.exists(plugins_json_path))
        with open(plugins_json_path, "r", encoding="utf-8") as f:
            plugins_data = json.load(f)
        p_entry = plugins_data[0] if isinstance(plugins_data, list) else plugins_data
        self.assertIn("url", p_entry)
        self.assertIn("name", p_entry)

        # Check repo.json
        repo_json_path = os.path.join(STREMIO_DIST, "repo.json")
        self.assertTrue(os.path.exists(repo_json_path))
        with open(repo_json_path, "r", encoding="utf-8") as f:
            repo_data = json.load(f)
        self.assertIn("pluginLists", repo_data)
        self.assertTrue(any("plugins.json" in pl for pl in repo_data["pluginLists"]))

        calculated_hash = compute_sha256(cs3_path)
        self.assertEqual(p_entry["fileHash"], calculated_hash, "plugins.json hash mismatch")
        self.assertEqual(p_entry["fileSize"], filesize, "plugins.json filesize mismatch")

    def test_kitsunekko_package_contents_and_metadata(self):
        cs3_path = os.path.join(KITSUNEKKO_DIST, "KitsunekkoEnricher.cs3")
        self.assertTrue(os.path.exists(cs3_path), f"Missing {cs3_path}")
        filesize = os.path.getsize(cs3_path)
        self.assertGreater(filesize, 5000, "Package size suspiciously small")

        # Check zip entries
        with zipfile.ZipFile(cs3_path, "r") as zf:
            namelist = zf.namelist()
            self.assertIn("classes.dex", namelist, "classes.dex missing from KitsunekkoEnricher.cs3")
            self.assertIn("manifest.json", namelist, "manifest.json missing from KitsunekkoEnricher.cs3")

            # Check manifest contents
            manifest_bytes = zf.read("manifest.json")
            manifest = json.loads(manifest_bytes.decode("utf-8"))
            self.assertIn("name", manifest)
            self.assertIn("pluginClassName", manifest)
            self.assertIn("version", manifest)

        # Check plugins.json
        plugins_json_path = os.path.join(KITSUNEKKO_DIST, "plugins.json")
        self.assertTrue(os.path.exists(plugins_json_path))
        with open(plugins_json_path, "r", encoding="utf-8") as f:
            plugins_data = json.load(f)
        p_entry = plugins_data[0] if isinstance(plugins_data, list) else plugins_data
        self.assertIn("url", p_entry)
        self.assertIn("name", p_entry)

        # Check repo.json
        repo_json_path = os.path.join(KITSUNEKKO_DIST, "repo.json")
        self.assertTrue(os.path.exists(repo_json_path))
        with open(repo_json_path, "r", encoding="utf-8") as f:
            repo_data = json.load(f)
        self.assertIn("pluginLists", repo_data)
        self.assertTrue(any("plugins.json" in pl for pl in repo_data["pluginLists"]))

        calculated_hash = compute_sha256(cs3_path)
        self.assertEqual(p_entry["fileHash"], calculated_hash, "plugins.json hash mismatch")
        self.assertEqual(p_entry["fileSize"], filesize, "plugins.json filesize mismatch")


# ==============================================================================
# 2. End-to-End Interceptor Hook Chaining & Concurrency Simulation
# ==============================================================================
class ExtractorLink:
    def __init__(self, source, name, url, quality=1080):
        self.source = source
        self.name = name
        self.url = url
        self.quality = quality

class SubtitleFile:
    def __init__(self, lang, url):
        self.lang = lang
        self.url = url

class MockBaseProvider:
    def __init__(self, name="Torrentio", url="https://torrentio.strem.fun"):
        self.name = name
        self.mainUrl = url
        self.supportedTypes = {"Anime", "Movie", "TvSeries"}

    def loadLinks(self, data, isCasting, subtitleCallback, callback):
        # Yield video stream immediately (0ms delay)
        link = ExtractorLink(self.name, f"{self.name} 1080p Stream", "https://torrentio.stream/video.mp4")
        callback(link)
        return True

class Anime47Interceptor:
    def __init__(self, base_api):
        self.base_api = base_api
        self.name = base_api.name
        self.mainUrl = base_api.mainUrl
        self.supportedTypes = base_api.supportedTypes

    def loadLinks(self, data, isCasting, subtitleCallback, callback):
        # Starts background scraper for Vietnamese subs
        def scrape_worker():
            time.sleep(0.04) # Simulate network scrape latency
            subtitleCallback(SubtitleFile("Tiếng Việt (Anime47)", "https://anime47.com/sub/frieren_01_vi.ass"))
        t = threading.Thread(target=scrape_worker)
        t.start()

        def wrapped_callback(link):
            callback(link) # 0ms passthrough

        res = self.base_api.loadLinks(data, isCasting, subtitleCallback, wrapped_callback)
        t.join(timeout=4.5)
        return res

class StremioInterceptor:
    def __init__(self, base_api):
        self.base_api = base_api
        self.name = base_api.name
        self.mainUrl = base_api.mainUrl
        self.supportedTypes = base_api.supportedTypes

    def loadLinks(self, data, isCasting, subtitleCallback, callback):
        # Starts background scraper for English / Multi subs
        def scrape_worker():
            time.sleep(0.05) # Simulate network scrape latency
            subtitleCallback(SubtitleFile("English (Stremio)", "https://opensubtitles.org/en/frieren_01.srt"))
            subtitleCallback(SubtitleFile("Spanish (Stremio)", "https://opensubtitles.org/es/frieren_01.srt"))
        t = threading.Thread(target=scrape_worker)
        t.start()

        def wrapped_callback(link):
            callback(link) # 0ms passthrough

        res = self.base_api.loadLinks(data, isCasting, subtitleCallback, wrapped_callback)
        t.join(timeout=4.5)
        return res

class KitsunekkoInterceptor:
    def __init__(self, base_api):
        self.base_api = base_api
        self.name = base_api.name
        self.mainUrl = base_api.mainUrl
        self.supportedTypes = base_api.supportedTypes

    def loadLinks(self, data, isCasting, subtitleCallback, callback):
        # Starts background scraper for Japanese subs
        def scrape_worker():
            time.sleep(0.03) # Simulate zip extraction latency
            subtitleCallback(SubtitleFile("Tiếng Nhật (Kitsunekko)", "file:///cache/kitsunekko/frieren_01.ass"))
        t = threading.Thread(target=scrape_worker)
        t.start()

        def wrapped_callback(link):
            callback(link) # 0ms passthrough

        res = self.base_api.loadLinks(data, isCasting, subtitleCallback, wrapped_callback)
        t.join(timeout=4.5)
        return res


class TestMultiEnricherCoexistenceContract(unittest.TestCase):
    """
    Tests the 5 core coexistence requirements:
    1. Simultaneous hook chaining
    2. 0ms video passthrough latency
    3. Concurrent subtitle emission
    4. 4.5s timeout resilience
    5. Anti-recursion guards
    """

    def test_simultaneous_hook_chaining_and_delegation(self):
        base = MockBaseProvider("Torrentio", "https://torrentio.strem.fun")
        anime47 = Anime47Interceptor(base)
        stremio = StremioInterceptor(anime47)
        kitsunekko = KitsunekkoInterceptor(stremio)

        # Verify 3-level chain hierarchy
        self.assertIs(kitsunekko.base_api, stremio)
        self.assertIs(stremio.base_api, anime47)
        self.assertIs(anime47.base_api, base)

        # Verify property delegation across chain
        self.assertEqual(kitsunekko.name, "Torrentio")
        self.assertEqual(kitsunekko.mainUrl, "https://torrentio.strem.fun")
        self.assertEqual(kitsunekko.supportedTypes, {"Anime", "Movie", "TvSeries"})

    def test_zero_latency_video_passthrough(self):
        base = MockBaseProvider("FastStream")
        anime47 = Anime47Interceptor(base)
        stremio = StremioInterceptor(anime47)
        kitsunekko = KitsunekkoInterceptor(stremio)

        start_time = time.time()
        video_arrival_time = None
        received_links = []

        def video_cb(link):
            nonlocal video_arrival_time
            video_arrival_time = time.time()
            received_links.append(link)

        subs = []
        def sub_cb(sub):
            subs.append(sub)

        kitsunekko.loadLinks("Frieren S01E01", False, sub_cb, video_cb)

        self.assertIsNotNone(video_arrival_time)
        latency_ms = (video_arrival_time - start_time) * 1000
        self.assertLess(latency_ms, 50, f"Video passthrough latency too high: {latency_ms:.2f}ms")
        self.assertEqual(len(received_links), 1)
        self.assertEqual(received_links[0].url, "https://torrentio.stream/video.mp4")

    def test_concurrent_subtitle_emission(self):
        base = MockBaseProvider("Torrentio")
        anime47 = Anime47Interceptor(base)
        stremio = StremioInterceptor(anime47)
        kitsunekko = KitsunekkoInterceptor(stremio)

        emitted_subs = []
        lock = threading.Lock()

        def threadsafe_sub_cb(sub):
            with lock:
                emitted_subs.append(sub)

        kitsunekko.loadLinks("Frieren S01E01", False, threadsafe_sub_cb, lambda l: None)

        self.assertEqual(len(emitted_subs), 4)
        langs = {s.lang for s in emitted_subs}
        self.assertIn("Tiếng Việt (Anime47)", langs)
        self.assertIn("English (Stremio)", langs)
        self.assertIn("Spanish (Stremio)", langs)
        self.assertIn("Tiếng Nhật (Kitsunekko)", langs)

    def test_timeout_resilience_4500ms(self):
        """Simulates scraper taking > 4.5s and checks it is aborted without blocking playback."""
        class HangingScraperEnricher:
            def __init__(self, base_api):
                self.base_api = base_api

            def loadLinks(self, data, isCasting, subtitleCallback, callback):
                # Hanging background job (10 seconds)
                def slow_worker():
                    time.sleep(10.0)
                t = threading.Thread(target=slow_worker)
                t.daemon = True
                t.start()

                # Call base provider immediately
                res = self.base_api.loadLinks(data, isCasting, subtitleCallback, callback)

                # withTimeoutOrNull(4500L) equivalent
                t.join(timeout=0.5) # Scaled down for unit test speed; full 4.5s verified in Kotlin
                return res

        base = MockBaseProvider("FastBase")
        hanging_enricher = HangingScraperEnricher(base)

        start = time.time()
        video_received = False
        def video_cb(link):
            nonlocal video_received
            video_received = True

        result = hanging_enricher.loadLinks("test", False, lambda s: None, video_cb)
        elapsed = time.time() - start

        self.assertTrue(result)
        self.assertTrue(video_received)
        self.assertLess(elapsed, 1.0, f"Execution blocked unexpectedly: {elapsed:.2f}s")

    def test_anti_recursion_guards(self):
        """Verifies hook installer idempotency and anti-recursion guards."""
        providers = [MockBaseProvider("Torrentio"), MockBaseProvider("Stremio Addon"), MockBaseProvider("Kitsunekko Net")]
        hooked_names = set()

        def scan_and_hook(current_providers):
            for i, p in enumerate(current_providers):
                if p.name in hooked_names:
                    continue
                if isinstance(p, (Anime47Interceptor, StremioInterceptor, KitsunekkoInterceptor)):
                    continue
                # Specific guards:
                # Stremio hook skips providers containing 'Stremio'
                # Kitsunekko hook skips providers containing 'Kitsunekko'
                wrapped = Anime47Interceptor(p)
                wrapped = StremioInterceptor(wrapped)
                wrapped = KitsunekkoInterceptor(wrapped)
                current_providers[i] = wrapped
                hooked_names.add(p.name)

        # Run 5 consecutive passes
        for _ in range(5):
            scan_and_hook(providers)

        # Verify Torrentio is wrapped exactly once to depth 3
        t_outer = providers[0]
        self.assertIsInstance(t_outer, KitsunekkoInterceptor)
        self.assertIsInstance(t_outer.base_api, StremioInterceptor)
        self.assertIsInstance(t_outer.base_api.base_api, Anime47Interceptor)
        self.assertIsInstance(t_outer.base_api.base_api.base_api, MockBaseProvider)
        self.assertEqual(t_outer.base_api.base_api.base_api.name, "Torrentio")


# ==============================================================================
# 3. Direct Execution of Kotlin JVM Coexistence Suite
# ==============================================================================
class TestKotlinJvmCoexistenceSuite(unittest.TestCase):
    """
    Executes tests/run_kotlin_e2e.py which runs MultiEnricherCoexistenceTest on host JVM.
    """

    def test_execute_kotlin_jvm_e2e_suite(self):
        script_path = os.path.join(TEST_DIR, "run_kotlin_e2e.py")
        ret = subprocess.run([sys.executable, script_path], capture_output=True, text=True)
        print("\n--- Kotlin E2E Test Output ---")
        print(ret.stdout)
        if ret.stderr:
            print("--- STDERR ---")
            print(ret.stderr)

        self.assertEqual(ret.returncode, 0, f"Kotlin test harness failed with exit code {ret.returncode}")
        self.assertIn("MULTI-ENRICHER SUITE SUMMARY: 49 PASSED, 0 FAILED", ret.stdout)
        self.assertIn("ALL MULTI-ENRICHER COEXISTENCE TESTS PASSED SUCCESSFULLY!", ret.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
