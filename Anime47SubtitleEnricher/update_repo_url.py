#!/usr/bin/env python3
import os
import sys
import json
import hashlib

DIST_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dist")
cs3_file = os.path.join(DIST_DIR, "Anime47Enricher.cs3")
plugins_json_file = os.path.join(DIST_DIR, "plugins.json")
repo_json_file = os.path.join(DIST_DIR, "repo.json")

def compute_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return "sha256-" + h.hexdigest()

def update_urls(base_url):
    base_url = base_url.rstrip("/")
    if not os.path.exists(cs3_file):
        print("[!] Anime47Enricher.cs3 not found in dist/!")
        sys.exit(1)

    file_size = os.path.getsize(cs3_file)
    file_hash = compute_sha256(cs3_file)

    cs3_url = f"{base_url}/Anime47Enricher.cs3"
    plugins_url = f"{base_url}/plugins.json"

    plugins_entry = [
        {
            "url": cs3_url,
            "status": 1,
            "version": 1,
            "name": "Anime47 Subtitle Enricher",
            "internalName": "Anime47Enricher",
            "authors": [
                "Anime47 Enricher"
            ],
            "description": "Tự động trích xuất và nhúng phụ đề Tiếng Việt (softsub) từ Anime47 vào CloudStream (tương thích hoàn hảo với Torrentio, TorraStream, StremioX, AnimePahe...)",
            "fileSize": file_size,
            "repositoryUrl": base_url,
            "language": "vi",
            "tvTypes": [
                "Anime",
                "AnimeMovie",
                "OVA"
            ],
            "iconUrl": "https://anime47.best/favicon.ico",
            "apiVersion": 1,
            "fileHash": file_hash
        }
    ]

    with open(plugins_json_file, "w", encoding="utf-8") as f:
        json.dump(plugins_entry, f, indent=4, ensure_ascii=False)

    repo_data = {
        "name": "Anime47 Subtitle Enricher Repo",
        "iconUrl": "https://anime47.best/favicon.ico",
        "description": "Kho tiện ích mở rộng tự động cào và nhúng phụ đề Vietsub từ Anime47 vào CloudStream",
        "manifestVersion": 1,
        "pluginLists": [
            plugins_url
        ]
    }

    with open(repo_json_file, "w", encoding="utf-8") as f:
        json.dump(repo_data, f, indent=4, ensure_ascii=False)

    print(f"[+] Updated dist/repo.json and dist/plugins.json with base URL: {base_url}")
    print(f"    - Repo link to add to CloudStream: {base_url}/repo.json")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 update_repo_url.py <BASE_URL>")
        print("Example GitHub: python3 update_repo_url.py https://raw.githubusercontent.com/<username>/<repo>/builds")
        print("Example Local:  python3 update_repo_url.py https://xxxx.trycloudflare.com")
        sys.exit(1)
    update_urls(sys.argv[1])
