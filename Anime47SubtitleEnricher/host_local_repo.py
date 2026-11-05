#!/usr/bin/env python3
import os
import sys
import re
import time
import json
import socket
import hashlib
import threading
import subprocess
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
DIST_DIR = os.path.join(PROJECT_DIR, "dist")
PORT = 8080

cs3_path = os.path.join(DIST_DIR, "Anime47Enricher.cs3")
if not os.path.exists(cs3_path):
    print("[!] Error: Anime47Enricher.cs3 not found in dist/")
    sys.exit(1)

file_size = os.path.getsize(cs3_path)
with open(cs3_path, "rb") as f:
    file_hash = "sha256-" + hashlib.sha256(f.read()).hexdigest()

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
                "name": "Anime47 Subtitle Enricher (Local)",
                "iconUrl": "https://anime47.best/favicon.ico",
                "description": "Kho tiện ích tự động cào và nhúng phụ đề Vietsub từ Anime47 (Local Host)",
                "manifestVersion": 1,
                "pluginLists": [
                    f"{base_url}/plugins.json"
                ]
            }
            content = json.dumps(repo_data, indent=4, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(content)
            print(f"[+] CloudStream requested /repo.json -> Returning pluginLists: {base_url}/plugins.json")
            return

        elif clean_path == "/plugins.json":
            cur_size = os.path.getsize(cs3_path)
            with open(cs3_path, "rb") as f:
                cur_hash = "sha256-" + hashlib.sha256(f.read()).hexdigest()

            plugins_data = [
                {
                    "url": f"{base_url}/Anime47Enricher.cs3",
                    "status": 1,
                    "version": 3,
                    "name": "Anime47 Subtitle Enricher",
                    "internalName": "Anime47Enricher",
                    "authors": [
                        "Anime47 Enricher"
                    ],
                    "description": "Tự động trích xuất và nhúng phụ đề Tiếng Việt (softsub) từ Anime47 vào CloudStream (tương thích Torrentio, TorraStream, AnimePahe...)",
                    "fileSize": cur_size,
                    "repositoryUrl": base_url,
                    "language": "vi",
                    "tvTypes": [
                        "Anime",
                        "AnimeMovie",
                        "OVA"
                    ],
                    "iconUrl": "https://anime47.best/favicon.ico",
                    "apiVersion": 1,
                    "fileHash": cur_hash
                }
            ]
            content = json.dumps(plugins_data, indent=4, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(content)
            print(f"[+] CloudStream requested /plugins.json -> Returning plugin url: {base_url}/Anime47Enricher.cs3")
            return

        elif clean_path == "/Anime47Enricher.cs3":
            print(f"[+] CloudStream đang tải file Anime47Enricher.cs3 ({file_size:,} bytes)...")

        return super().do_GET()

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
    print(" CloudStream Dynamic Local Repository Host")
    print("=" * 65)

    local_ip = get_local_ip()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), DynamicRepoHandler)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    print(f"[*] Local HTTP Server listening on 0.0.0.0:{PORT}")

    # Start cloudflared
    print("[*] Launching Cloudflare Tunnel for public HTTPS access...")
    cf_cmd = ["cloudflared", "tunnel", "--url", f"http://127.0.0.1:{PORT}"]
    cf_proc = subprocess.Popen(
        cf_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )

    tunnel_url = None
    url_regex = re.compile(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com")

    # Background pipe drainer
    def drain_pipe():
        nonlocal tunnel_url
        for line in cf_proc.stdout:
            if not tunnel_url:
                match = url_regex.search(line)
                if match:
                    tunnel_url = match.group(0)

    drain_thread = threading.Thread(target=drain_pipe, daemon=True)
    drain_thread.start()

    # Wait for tunnel URL
    for _ in range(30):
        if tunnel_url:
            break
        time.sleep(0.5)

    print("\n" + "=" * 65)
    print("🎉 KHO LƯU TRỮ REPOSITORY ĐANG CHẠY CỰC KỲ KHẢ THI!")
    print("=" * 65)

    if tunnel_url:
        print(f"\n[LỰA CHỌN 1] HTTPS CLOUDFLARE LINK (Khuyên dùng - 100% không bị Android chặn):")
        print(f"   👉 {tunnel_url}/repo.json\n")
    
    print(f"[LỰA CHỌN 2] HTTP LOCALHOST (Nếu CloudStream chạy cùng trên máy này):")
    print(f"   👉 http://127.0.0.1:{PORT}/repo.json")
    print(f"   👉 http://localhost:{PORT}/repo.json\n")

    print(f"[LỰA CHỌN 3] HTTP MẠNG LAN / WI-FI (Nếu CloudStream chạy trên Android TV cùng Wi-Fi):")
    print(f"   👉 http://{local_ip}:{PORT}/repo.json\n")

    print("=" * 65)
    print("HƯỚNG DẪN THỰC HIỆN TRÊN CLOUDSTREAM:")
    print("1. Mở CloudStream -> Cài đặt (Settings) -> Tiện ích mở rộng (Extensions)")
    print("2. Bấm 'Thêm kho lưu trữ' (Add repository)")
    print("3. Nhập 1 trong các link ở trên (ưu tiên link HTTPS)")
    print("4. Vào danh sách plugin của repo, bấm 'Cài đặt' (Install) Anime47 Subtitle Enricher")
    print("=" * 65)
    print("[*] Server đang hoạt động và sẵn sàng phục vụ CloudStream...")
    sys.stdout.flush()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[*] Stopping server...")
        cf_proc.terminate()
        server.shutdown()
        print("[+] Stopped.")

if __name__ == "__main__":
    main()
