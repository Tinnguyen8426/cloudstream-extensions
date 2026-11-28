#!/usr/bin/env python3
"""Dev-only local repository host for AiSubtitleTranslator dist/ files.

Usage:
    python AiSubtitleTranslator/host_local_repo.py
    adb -s <device> reverse tcp:8080 tcp:8080
Then add http://127.0.0.1:8080/repo.json as a repository in CloudStream.
URLs in plugins.json follow the request Host header, so LAN access works too.
"""
import os
import sys
import json
import socket
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
DIST_DIR = os.path.join(PROJECT_DIR, "dist")
MANIFEST_PATH = os.path.join(PROJECT_DIR, "manifest.json")
CS3_PATH = os.path.join(DIST_DIR, "AiSubtitleTranslator.cs3")
PORT = 8080

if not os.path.exists(CS3_PATH):
    print("[!] Error: dist/AiSubtitleTranslator.cs3 not found. Run build_plugin.py first.")
    sys.exit(1)

with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
    PLUGIN_VERSION = int(json.load(f)["version"])


class DynamicRepoHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIST_DIR, **kwargs)

    def log_message(self, format, *args):
        req = args[0] if len(args) > 0 else ""
        code = args[1] if len(args) > 1 else ""
        print(f"[HTTP SERVER] {code} - {req}")

    def get_base_url(self):
        host = self.headers.get("Host", f"127.0.0.1:{PORT}")
        proto = self.headers.get("X-Forwarded-Proto", "http")
        if "trycloudflare.com" in host or proto == "https":
            proto = "https"
        return f"{proto}://{host}"

    def do_GET(self):
        clean_path = self.path.split("?")[0]
        base_url = self.get_base_url()

        if clean_path == "/repo.json":
            repo_data = {
                "name": "AI Subtitle Translator Repo (Local)",
                "iconUrl": "https://translate.google.com/favicon.ico",
                "description": "Kho tiện ích mở rộng dịch phụ đề tự động bằng AI trực tiếp trên CloudStream (Local Host)",
                "manifestVersion": 1,
                "pluginLists": [f"{base_url}/plugins.json"]
            }
            self._send_json(json.dumps(repo_data, indent=4, ensure_ascii=False).encode("utf-8"))
            print(f"[+] Served /repo.json -> {base_url}/plugins.json")
            return

        if clean_path == "/plugins.json":
            cur_size = os.path.getsize(CS3_PATH)
            with open(CS3_PATH, "rb") as f:
                cur_hash = "sha256-" + hashlib.sha256(f.read()).hexdigest()
            plugins_data = [{
                "url": f"{base_url}/AiSubtitleTranslator.cs3",
                "status": 1,
                "version": PLUGIN_VERSION,
                "name": "AI Subtitle Translator",
                "internalName": "AiSubtitleTranslator",
                "authors": ["AI Subtitle Translator"],
                "description": "Dịch phụ đề phim tự động bằng AI (Gemini, OpenAI, Claude) trực tiếp trên CloudStream với độ trễ 0ms và hỗ trợ xử lý ngữ cảnh đa ngôn ngữ",
                "fileSize": cur_size,
                "repositoryUrl": base_url,
                "language": "vi",
                "tvTypes": ["Anime", "AnimeMovie", "OVA", "Movie", "TvSeries", "AsianDrama", "Live"],
                "iconUrl": "https://translate.google.com/favicon.ico",
                "apiVersion": 1,
                "fileHash": cur_hash
            }]
            self._send_json(json.dumps(plugins_data, indent=4, ensure_ascii=False).encode("utf-8"))
            print(f"[+] Served /plugins.json -> v{PLUGIN_VERSION} ({cur_size:,} bytes)")
            return

        if clean_path == "/AiSubtitleTranslator.cs3":
            print(f"[+] Device downloading AiSubtitleTranslator.cs3 v{PLUGIN_VERSION}...")

        return super().do_GET()

    def _send_json(self, content: bytes):
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(content)


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def main():
    print("=" * 65)
    print(f" CloudStream Local Repo Host - AiSubtitleTranslator v{PLUGIN_VERSION}")
    print("=" * 65)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), DynamicRepoHandler)
    print(f"[*] Listening on 0.0.0.0:{PORT}")
    print(f"[*] ADB reverse: adb reverse tcp:{PORT} tcp:{PORT}")
    print(f"[*] Device repo URL: http://127.0.0.1:{PORT}/repo.json")
    print(f"[*] LAN repo URL:    http://{get_local_ip()}:{PORT}/repo.json")
    print("[*] Press Ctrl+C to stop.")
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Stopping server...")
        server.shutdown()


if __name__ == "__main__":
    main()